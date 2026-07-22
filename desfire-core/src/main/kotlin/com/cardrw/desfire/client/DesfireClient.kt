package com.cardrw.desfire.client

import com.cardrw.desfire.command.DesfireCommand
import com.cardrw.desfire.crypto.AesCbc
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.framing.DesfireResponse
import com.cardrw.desfire.framing.NativeFraming
import com.cardrw.desfire.log.ApduDirection
import com.cardrw.desfire.log.ApduJournal
import com.cardrw.desfire.log.ApduLogEntry
import com.cardrw.desfire.model.Aid
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.FileSettings
import com.cardrw.desfire.model.FileType
import com.cardrw.desfire.model.KeySettingsInfo
import com.cardrw.desfire.model.UidKind
import com.cardrw.desfire.model.VersionInfo
import com.cardrw.desfire.session.AuthSession
import com.cardrw.desfire.session.DesfireSecureSession
import com.cardrw.desfire.session.Ev1Session
import com.cardrw.desfire.session.Ev2Session
import com.cardrw.desfire.session.SecureMessagingException
import com.cardrw.desfire.status.DesfireStatus
import com.cardrw.desfire.util.Hex
import java.security.SecureRandom

/**
 * Client protocole DESFire.
 *
 * - v0 : identité + apps
 * - v0.5 : SelectApplication, AuthenticateAES, SM EV1, explorateur lecture Standard
 * - EV2 : AuthenticateEV2First + SM EV2 ; [authenticateAesPreferEv1] fallback auto
 */
class DesfireClient(
    private val transceiver: DesfireTransceiver,
    val journal: ApduJournal = ApduJournal(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sessionId: String? = null,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Session AES EV1 ou EV2 courante — null si non authentifié. */
    var session: DesfireSecureSession? = null
        private set

    /** Dernière app sélectionnée (SelectApplication) — null avant tout select. */
    var selectedAid: Aid? = null
        private set

    val authSession: AuthSession?
        get() = session?.toAuthSession()

    val isAuthenticated: Boolean get() = session != null

    /** Select seulement si l’AID courant diffère (évite les doubles 5A dans le journal). */
    fun ensureApplicationSelected(aid: Aid) {
        val current = selectedAid
        if (current != null && current.hex.equals(aid.hex, ignoreCase = true)) return
        selectApplication(aid)
    }

    // -------------------------------------------------------------------------
    // Transport
    // -------------------------------------------------------------------------

    /**
     * Exchange bas niveau avec journalisation systématique des trames brutes
     * même si le parsing échoue (CDC §5.3 mode capture).
     */
    fun exchange(command: DesfireCommand, data: ByteArray = ByteArray(0)): DesfireResponse {
        val apdu = NativeFraming.wrap(command, data)
        journal.append(
            ApduLogEntry(
                timestampEpochMs = clockMs(),
                direction = ApduDirection.OUT,
                raw = apdu,
                command = command,
                annotation = buildOutAnnotation(command, data),
                sessionId = sessionId,
            ),
        )
        val raw = try {
            transceiver.transceive(apdu)
        } catch (e: DesfireTransportException) {
            journal.append(
                ApduLogEntry(
                    timestampEpochMs = clockMs(),
                    direction = ApduDirection.IN,
                    raw = ByteArray(0),
                    command = command,
                    status = null,
                    annotation = "Transport error: ${e.message}",
                    sessionId = sessionId,
                ),
            )
            throw e
        }
        val response = NativeFraming.parseResponse(raw)
        journal.append(
            ApduLogEntry(
                timestampEpochMs = clockMs(),
                direction = ApduDirection.IN,
                raw = raw,
                command = command,
                status = response.status,
                annotation = buildInAnnotation(command, response),
                sessionId = sessionId,
            ),
        )
        return response
    }

    /**
     * Exchange avec secure messaging EV1 si [session] active.
     * @param fileCommMode mode du fichier pour ReadData ; null = meta (GetFileIDs…) plain+CMAC
     */
    private fun exchangeSecure(
        command: DesfireCommand,
        data: ByteArray = ByteArray(0),
        fileCommMode: CommMode? = null,
    ): DesfireResponse {
        val sess = session
        if (sess == null) {
            return exchange(command, data)
        }
        val modeForTx = when {
            fileCommMode == null -> CommMode.PLAIN // meta : CMAC IV only
            // ReadData TX : plain+CMAC (params clairs) ; FULL ne s’applique qu’à la RX.
            // WriteData/ChangeKey FULL : prepareCommand(..., clearHeaderLength=…) côté appelant v1.
            command == DesfireCommand.READ_DATA -> CommMode.PLAIN
            else -> fileCommMode
        }
        val securedData = try {
            sess.prepareCommand(command.code, data, modeForTx)
        } catch (e: Exception) {
            throw DesfireProtocolException("SM prepare failed: ${e.message}")
        }
        val response = exchange(command, securedData)
        // Pas de postprocess sur AF intermediate — géré par les méthodes high-level
        return response
    }

    // -------------------------------------------------------------------------
    // v0 — identité
    // -------------------------------------------------------------------------

    fun getVersion(): VersionInfo {
        val frames = mutableListOf<ByteArray>()
        var response = exchange(DesfireCommand.GET_VERSION)
        frames += response.data
        var guard = 0
        while (response.isAdditionalFrame && guard < 8) {
            response = exchange(DesfireCommand.ADDITIONAL_FRAME)
            frames += response.data
            guard++
        }
        if (!response.isSuccess && !response.isAdditionalFrame) {
            throw DesfireProtocolException(
                "GetVersion failed: ${response.status.shortName} (0x${response.sw2.toString(16)})",
                response,
            )
        }
        val concat = frames.fold(ByteArray(0)) { acc, b -> acc + b }
        return VersionInfo.parse(concat)
    }

    fun getApplicationIds(): List<Aid> {
        val frames = collectChained(DesfireCommand.GET_APPLICATION_IDS)
        if (frames.isEmpty()) return emptyList()
        return Aid.parseList(frames)
    }

    fun selectApplication(aid: Aid): DesfireResponse {
        // Select invalide toujours la session auth (CDC §3.2)
        session = null
        val response = exchange(DesfireCommand.SELECT_APPLICATION, aid.bytes)
        if (!response.isSuccess) {
            selectedAid = null
            throw DesfireProtocolException(
                "SelectApplication(${aid.hex}) failed: ${response.status.shortName} — ${response.status.pedagogicalFr}",
                response,
            )
        }
        selectedAid = aid
        return response
    }

    fun getFreeMemory(): Int {
        val response = exchange(DesfireCommand.GET_FREE_MEMORY)
        if (!response.isSuccess) {
            throw DesfireProtocolException(
                "GetFreeMemory failed: ${response.status.shortName}",
                response,
            )
        }
        require(response.data.size >= 3) {
            "GetFreeMemory data too short: ${response.data.size}"
        }
        val d = response.data
        return (d[0].toInt() and 0xFF) or
            ((d[1].toInt() and 0xFF) shl 8) or
            ((d[2].toInt() and 0xFF) shl 16)
    }

    fun readIdentity(tagUid: ByteArray? = null): CardIdentity {
        val notes = mutableListOf<String>()
        val version = try {
            getVersion()
        } catch (e: Exception) {
            notes += "GetVersion: ${e.message}"
            null
        }

        try {
            selectApplication(Aid.PICC)
        } catch (e: Exception) {
            notes += "SelectApplication(PICC): ${e.message}"
        }

        val freeMem = try {
            getFreeMemory()
        } catch (e: Exception) {
            notes += "GetFreeMemory: ${e.message}"
            null
        }

        val apps = try {
            getApplicationIds()
        } catch (e: Exception) {
            notes += "GetApplicationIDs: ${e.message}"
            emptyList()
        }

        val typeLabel = version?.typeLabel ?: "Carte IsoDep (type inconnu)"
        val uidKind = when {
            tagUid == null -> UidKind.UNKNOWN
            tagUid.size == 4 -> UidKind.RANDOM
            else -> UidKind.FIXED
        }

        return CardIdentity(
            uidFromTag = tagUid,
            uidKind = uidKind,
            version = version,
            freeMemoryBytes = freeMem,
            applications = apps,
            typeLabel = typeLabel,
            rawNotes = notes,
        )
    }

    // -------------------------------------------------------------------------
    // v0.5 — AuthenticateAES + SM EV1
    // -------------------------------------------------------------------------

    /**
     * AuthenticateAES (0xAA) — flux EV1.
     * @param keyNo numéro de clé (0 = master app / PICC)
     * @param key AES-128 (16 o) — usine labo = 00…00
     * @param rndA optional fixed RndA for tests ; sinon SecureRandom
     */
    fun authenticateAes(
        keyNo: Int,
        key: ByteArray = AesConstants.FACTORY_KEY,
        aidHex: String = session?.aidHex ?: "000000",
        rndA: ByteArray? = null,
    ): Ev1Session {
        require(key.size == AesConstants.KEY_SIZE_BYTES) {
            "Clé AES doit faire 16 octets, got ${key.size}"
        }
        require(keyNo in 0..13) { "keyNo hors plage 0–13: $keyNo" }

        session = null
        val iv = AesCbc.zeroIv()

        // 1) AA + keyNo → ek(RndB) + 91 AF
        val step1 = exchange(DesfireCommand.AUTHENTICATE_AES, byteArrayOf(keyNo.toByte()))
        if (!step1.isAdditionalFrame) {
            throw DesfireProtocolException(
                "AuthenticateAES failed: ${step1.status.shortName} — ${step1.status.pedagogicalFr}",
                step1,
            )
        }
        if (step1.data.size != 16) {
            throw DesfireProtocolException(
                "AuthenticateAES: ek(RndB) attendu 16 o, got ${step1.data.size}",
                step1,
            )
        }

        val rndB = step1.data.copyOf()
        AesCbc.cbcReceive(key, iv, rndB)

        val hostRndA = rndA?.also {
            require(it.size == 16) { "RndA doit faire 16 octets" }
        } ?: ByteArray(16).also { random.nextBytes(it) }

        val rndBRot = AesCbc.rotateLeft(rndB)
        val token = hostRndA + rndBRot
        AesCbc.cbcSend(key, iv, token)

        // 2) AF + ek(RndA || RndB') → ek(RndA') + 91 00
        val step2 = exchange(DesfireCommand.ADDITIONAL_FRAME, token)
        if (!step2.isSuccess) {
            throw DesfireProtocolException(
                "AuthenticateAES (challenge) failed: ${step2.status.shortName} — ${step2.status.pedagogicalFr}",
                step2,
            )
        }
        if (step2.data.size != 16) {
            throw DesfireProtocolException(
                "AuthenticateAES: ek(RndA') attendu 16 o, got ${step2.data.size}",
                step2,
            )
        }

        val rndAPrime = step2.data.copyOf()
        AesCbc.cbcReceive(key, iv, rndAPrime)
        val expected = AesCbc.rotateLeft(hostRndA)
        if (!rndAPrime.contentEquals(expected)) {
            throw DesfireProtocolException(
                "AuthenticateAES: RndA' ne correspond pas — clé incorrecte ou carte non AES.",
            )
        }

        val newSession = Ev1Session.create(aidHex, keyNo, hostRndA, rndB)
        session = newSession
        return newSession
    }

    /**
     * AuthenticateEV2First (0x71) — flux EV2 → SM EV2.
     *
     * @param lenCap 0 = pas de PCDCap2 (défaut labo)
     * @param pcdCap2 6 octets si [lenCap] = 6
     */
    fun authenticateEv2First(
        keyNo: Int,
        key: ByteArray = AesConstants.FACTORY_KEY,
        aidHex: String = session?.aidHex ?: "000000",
        rndA: ByteArray? = null,
        lenCap: Int = 0,
        pcdCap2: ByteArray = ByteArray(0),
    ): Ev2Session {
        require(key.size == AesConstants.KEY_SIZE_BYTES) {
            "Clé AES doit faire 16 octets, got ${key.size}"
        }
        require(keyNo in 0..13) { "keyNo hors plage 0–13: $keyNo" }
        require(lenCap == 0 || (lenCap == 6 && pcdCap2.size == 6)) {
            "LenCap=0 sans PCDCap2, ou LenCap=6 avec 6 o"
        }

        session = null
        val iv = AesCbc.zeroIv()

        // 1) 71 KeyNo LenCap [PCDCap2] → ek(RndB) + AF
        val step1Data = byteArrayOf(keyNo.toByte(), lenCap.toByte()) +
            if (lenCap == 6) pcdCap2 else ByteArray(0)
        val step1 = exchange(DesfireCommand.AUTHENTICATE_EV2_FIRST, step1Data)
        if (!step1.isAdditionalFrame) {
            throw DesfireProtocolException(
                "AuthenticateEV2First failed: ${step1.status.shortName} — ${step1.status.pedagogicalFr}",
                step1,
            )
        }
        if (step1.data.size != 16) {
            throw DesfireProtocolException(
                "AuthenticateEV2First: ek(RndB) attendu 16 o, got ${step1.data.size}",
                step1,
            )
        }
        val rndB = step1.data.copyOf()
        AesCbc.cbcReceive(key, iv, rndB)

        val hostRndA = rndA?.also {
            require(it.size == 16) { "RndA doit faire 16 octets" }
        } ?: ByteArray(16).also { random.nextBytes(it) }

        val rndBRot = AesCbc.rotateLeft(rndB)
        val token = hostRndA + rndBRot
        AesCbc.cbcSend(key, iv, token)

        // 2) AF + ek(RndA||RndB') → ek(RndA'||TI||PDCap2||PCDCap2) + 00
        val step2 = exchange(DesfireCommand.ADDITIONAL_FRAME, token)
        if (!step2.isSuccess) {
            throw DesfireProtocolException(
                "AuthenticateEV2First (challenge) failed: ${step2.status.shortName} — ${step2.status.pedagogicalFr}",
                step2,
            )
        }
        if (step2.data.size != 32) {
            throw DesfireProtocolException(
                "AuthenticateEV2First: réponse finale attendue 32 o, got ${step2.data.size}",
                step2,
            )
        }
        val plain = step2.data.copyOf()
        AesCbc.cbcReceive(key, iv, plain)
        // RndA'(16) || TI(4) || PDCap2(6) || PCDCap2(6)
        val rndAPrime = plain.copyOfRange(0, 16)
        val ti = plain.copyOfRange(16, 20)
        val expected = AesCbc.rotateLeft(hostRndA)
        if (!rndAPrime.contentEquals(expected)) {
            throw DesfireProtocolException(
                "AuthenticateEV2First: RndA' ne correspond pas — clé incorrecte ou pas EV2.",
            )
        }

        val newSession = Ev2Session.create(
            aidHex = aidHex,
            keyNumber = keyNo,
            authKey = key,
            rndA = hostRndA,
            rndB = rndB,
            ti = ti,
        )
        session = newSession
        return newSession
    }

    /**
     * Auth AES : tente **EV1 (0xAA)** puis **EV2 First (0x71)** si EV1 est refusé
     * par la carte (parc EV2-only / EV3 « propre »).
     *
     * @return session EV1 ou EV2
     */
    fun authenticateAesPreferEv1(
        keyNo: Int,
        key: ByteArray = AesConstants.FACTORY_KEY,
        aidHex: String = session?.aidHex ?: "000000",
        rndA: ByteArray? = null,
    ): DesfireSecureSession {
        return try {
            authenticateAes(keyNo, key, aidHex, rndA)
        } catch (e: DesfireProtocolException) {
            if (!shouldFallbackToEv2(e)) throw e
            authenticateEv2First(keyNo, key, aidHex, rndA)
        }
    }

    /**
     * Auth AES : tente **EV2 First** d’abord (cible EV3 propre), puis EV1.
     */
    fun authenticateAesPreferEv2(
        keyNo: Int,
        key: ByteArray = AesConstants.FACTORY_KEY,
        aidHex: String = session?.aidHex ?: "000000",
        rndA: ByteArray? = null,
    ): DesfireSecureSession {
        return try {
            authenticateEv2First(keyNo, key, aidHex, rndA)
        } catch (e: DesfireProtocolException) {
            if (!shouldFallbackToEv1(e)) throw e
            authenticateAes(keyNo, key, aidHex, rndA)
        }
    }

    private fun shouldFallbackToEv2(e: DesfireProtocolException): Boolean {
        val st = e.response?.status
        val msg = e.message.orEmpty()
        // Ne pas basculer sur simple 0xAE (souvent mauvaise clé EV1 encore supportée).
        // EV2-only : 0xAA souvent « illegal command » / permission.
        return st == DesfireStatus.ILLEGAL_COMMAND ||
            st == DesfireStatus.PERMISSION_DENIED ||
            msg.contains("Illegal", ignoreCase = true) ||
            msg.contains("0x1C", ignoreCase = true) ||
            msg.contains("Permission denied", ignoreCase = true) ||
            msg.contains("not supported", ignoreCase = true)
    }

    private fun shouldFallbackToEv1(e: DesfireProtocolException): Boolean {
        val st = e.response?.status
        val msg = e.message.orEmpty()
        return st == DesfireStatus.ILLEGAL_COMMAND ||
            st == DesfireStatus.PERMISSION_DENIED ||
            msg.contains("Illegal", ignoreCase = true) ||
            msg.contains("0x1C", ignoreCase = true)
    }

    fun clearSession() {
        session = null
    }

    // -------------------------------------------------------------------------
    // v0.5 — exploration
    // -------------------------------------------------------------------------

    fun getKeySettings(): KeySettingsInfo {
        val raw = exchangeMeta(DesfireCommand.GET_KEY_SETTINGS)
        return KeySettingsInfo.parse(raw)
    }

    fun getFileIds(): List<Int> {
        val data = exchangeMeta(DesfireCommand.GET_FILE_IDS)
        return data.map { it.toInt() and 0xFF }
    }

    fun getFileSettings(fileNo: Int): FileSettings {
        val data = exchangeMeta(
            DesfireCommand.GET_FILE_SETTINGS,
            byteArrayOf(fileNo.toByte()),
        )
        return FileSettings.parse(fileNo, data)
    }

    /**
     * GetCardUID (0x51) — nécessite auth ; réponse FULL enciphered.
     */
    fun getCardUid(): ByteArray {
        val sess = session
            ?: throw DesfireProtocolException("GetCardUID requiert une session authentifiée.")
        // Commande plain+CMAC ; réponse FULL
        val response = exchangeSecure(DesfireCommand.GET_CARD_UID)
        if (!response.isSuccess) {
            throw DesfireProtocolException(
                "GetCardUID failed: ${response.status.shortName}",
                response,
            )
        }
        return try {
            sess.postprocessResponse(response.data, response.sw2, CommMode.FULL)
        } catch (e: SecureMessagingException) {
            throw DesfireProtocolException("GetCardUID SM: ${e.message}", response)
        }
    }

    /**
     * ReadData Standard — respecte [commMode] du fichier.
     * @param length 0 = tout le fichier (si taille connue côté carte)
     */
    fun readData(
        fileNo: Int,
        offset: Int = 0,
        length: Int = 0,
        commMode: CommMode = CommMode.PLAIN,
    ): ByteArray {
        val payload = ByteArray(7)
        payload[0] = fileNo.toByte()
        writeLe24(payload, 1, offset)
        writeLe24(payload, 4, length)

        val sess = session
        // Première commande
        val firstData = if (sess != null) {
            sess.prepareCommand(DesfireCommand.READ_DATA.code, payload, CommMode.PLAIN)
        } else {
            payload
        }

        val frames = mutableListOf<ByteArray>()
        var response = exchange(DesfireCommand.READ_DATA, firstData)
        frames += response.data
        var guard = 0
        while (response.isAdditionalFrame && guard < 64) {
            // AF chaining : plain, sans re-CMAC sur TX (freefare)
            response = exchange(DesfireCommand.ADDITIONAL_FRAME)
            frames += response.data
            guard++
        }
        if (!response.isSuccess && !response.isAdditionalFrame) {
            throw DesfireProtocolException(
                "ReadData($fileNo) failed: ${response.status.shortName} — ${response.status.pedagogicalFr}",
                response,
            )
        }
        val concat = frames.fold(ByteArray(0)) { acc, b -> acc + b }
        if (sess == null) return concat

        return try {
            sess.postprocessResponse(concat, response.sw2, commMode)
        } catch (e: SecureMessagingException) {
            throw DesfireProtocolException("ReadData SM: ${e.message}", response)
        }
    }

    /**
     * Explore une application déjà sélectionnée.
     *
     * **PICC** : GetKeySettings seulement.
     *
     * **App — piège key settings bit1 = 0** (free directory list désactivé) :
     * GetKeySettings / GetFileIDs / GetFileSettings exigent la **clé maître (0)**.
     * Une clé de lecture (ex. 2) peut faire ReadData mais **pas** lister la structure ;
     * la carte renvoie souvent `91 AE` (pas seulement 9D).
     *
     * Si [cachedFileSettings] est fourni et que la session n’est pas la clé 0,
     * on saute la structure et on ne fait que la lecture (workflow 2 phases labo).
     */
    fun exploreSelectedApplication(
        aid: Aid,
        readStandardFiles: Boolean = true,
        cachedFileSettings: List<FileSettings>? = null,
    ): ApplicationExploreResult {
        val notes = mutableListOf<String>()
        val authKey = session?.keyNumber

        // --- Mode lecture seule : structure déjà connue, clé non-maître ---
        if (!aid.isPicc &&
            cachedFileSettings != null &&
            cachedFileSettings.isNotEmpty() &&
            authKey != null &&
            authKey != 0
        ) {
            notes += "Mode lecture (structure en cache) — clé n°$authKey. " +
                "GetKeySettings/GetFileIDs omis : souvent réservés à la clé maître (0) si free-list désactivé."
            val nodes = readPhase(cachedFileSettings, readStandardFiles, notes, authKey)
            return ApplicationExploreResult(
                aid = aid,
                keySettings = null,
                files = nodes,
                notes = notes,
                structureFromCache = true,
            )
        }

        val keySettings = try {
            getKeySettings()
        } catch (e: Exception) {
            if (isAuthOrPermissionBarrier(e)) {
                clearSession()
                val hint = if (authKey != null && authKey != 0) {
                    "GetKeySettings refusé en clé n°$authKey (souvent AE si free-list désactivé). " +
                        "1) Auth clé maître 0 → Explorer (structure)  " +
                        "2) Auth clé de lecture → Explorer (données, structure en cache)."
                } else {
                    "Session invalidée (AE/9D) pendant GetKeySettings — ré-authentifie."
                }
                notes += hint
                // Tentative lecture seule si cache dispo (session morte → lecture impossible sans re-auth)
                if (cachedFileSettings != null && cachedFileSettings.isNotEmpty()) {
                    notes += "Structure en cache disponible : ré-authentifie avec la clé de lecture puis Explorer."
                    return ApplicationExploreResult(
                        aid = aid,
                        keySettings = null,
                        files = cachedFileSettings.map { FileNode(it, null, "Ré-auth puis Explorer pour lire.") },
                        notes = notes,
                        structureFromCache = true,
                    )
                }
                return ApplicationExploreResult(aid, null, emptyList(), notes)
            }
            notes += "GetKeySettings: ${e.message}"
            null
        }

        if (keySettings != null && !keySettings.bits.freeDirectoryListWithoutMaster) {
            notes += "Key settings : free-list désactivé → structure (IDs/settings) réservée à la clé maître (0)."
        }

        if (aid.isPicc) {
            notes += "PICC : pas de fichiers Standard ici — sélectionne une application (AID) pour lister les fichiers."
            return ApplicationExploreResult(aid, keySettings, emptyList(), notes)
        }

        val fileIds = try {
            getFileIds()
        } catch (e: Exception) {
            if (isAuthOrPermissionBarrier(e)) {
                clearSession()
                notes += directoryBarrierNote("GetFileIDs", authKey)
                return ApplicationExploreResult(aid, keySettings, emptyList(), notes)
            }
            notes += "GetFileIDs: ${humanizeAccessError(e, authKey)}"
            emptyList()
        }

        val settingsList = mutableListOf<FileSettings>()
        for (fileNo in fileIds) {
            try {
                settingsList += getFileSettings(fileNo)
            } catch (e: Exception) {
                if (isAuthOrPermissionBarrier(e)) {
                    clearSession()
                    notes += directoryBarrierNote("GetFileSettings($fileNo)", authKey)
                    break
                }
                notes += "GetFileSettings($fileNo): ${humanizeAccessError(e, authKey)}"
            }
        }

        val nodes = readPhase(settingsList, readStandardFiles, notes, authKey)
        return ApplicationExploreResult(aid, keySettings, nodes, notes)
    }

    /** Phase lecture uniquement (session déjà authentifiée avec la bonne clé). */
    fun readStandardFiles(
        aid: Aid,
        settingsList: List<FileSettings>,
    ): ApplicationExploreResult {
        val notes = mutableListOf<String>()
        val authKey = session?.keyNumber
        notes += "Lecture seule · clé session ${authKey?.toString() ?: "aucune"}"
        val nodes = readPhase(settingsList, readStandardFiles = true, notes = notes, authKey = authKey)
        return ApplicationExploreResult(aid, null, nodes, notes, structureFromCache = true)
    }

    private fun readPhase(
        settingsList: List<FileSettings>,
        readStandardFiles: Boolean,
        notes: MutableList<String>,
        authKey: Int?,
    ): List<FileNode> {
        val nodes = mutableListOf<FileNode>()
        for (settings in settingsList) {
            var dataHex: String? = null
            var dataError: String? = null

            when {
                settings.fileType != FileType.STANDARD -> {
                    dataError = "Type ${settings.fileType.labelFr} — lecture v1.1"
                }
                !readStandardFiles -> { /* skip */ }
                session == null && !settings.accessRights.isReadFree -> {
                    dataError = "Lecture : authentifie d’abord (R=${settings.accessRights.readLabel})."
                }
                !settings.accessRights.canReadWith(session?.keyNumber) -> {
                    val need = settings.accessRights.readLabel
                    val have = session?.keyNumber?.let { "clé $it" } ?: "sans auth"
                    dataError = "Lecture réservée à $need (session : $have). " +
                        "Auth avec cette clé puis Explorer (lecture)."
                }
                else -> {
                    try {
                        val len = settings.sizeBytes ?: 0
                        val data = readData(settings.fileNo, 0, len, settings.commMode)
                        dataHex = Hex.encode(data)
                    } catch (e: Exception) {
                        if (isAuthOrPermissionBarrier(e)) {
                            clearSession()
                            dataError = "Échec ReadData (AE/droits) — ${humanizeAccessError(e, authKey)}"
                            nodes += FileNode(settings, null, dataError)
                            notes += "Arrêt des lectures après fichier ${settings.fileNo}."
                            for (s in settingsList.dropWhile { it.fileNo != settings.fileNo }.drop(1)) {
                                nodes += FileNode(s, null, "Non lu (session invalidée plus tôt).")
                            }
                            return nodes
                        }
                        dataError = humanizeAccessError(e, authKey)
                    }
                }
            }
            nodes += FileNode(settings, dataHex, dataError)
        }
        return nodes
    }

    private fun directoryBarrierNote(where: String, authKey: Int?): String {
        return if (authKey != null && authKey != 0) {
            "$where refusé en clé n°$authKey — free-list probablement off : " +
                "utilise la clé maître (0) pour la structure, puis la clé de lecture pour les données."
        } else {
            "Session invalidée pendant $where — ré-authentifie."
        }
    }

    /** AE / 9D / integrity : barrière auth ou droits (session souvent morte côté carte). */
    private fun isAuthOrPermissionBarrier(e: Exception): Boolean {
        if (e is DesfireProtocolException) {
            val st = e.response?.status
            if (st == DesfireStatus.AUTHENTICATION_ERROR ||
                st == DesfireStatus.INTEGRITY_ERROR ||
                st == DesfireStatus.PERMISSION_DENIED
            ) {
                return true
            }
        }
        val msg = e.message.orEmpty()
        return msg.contains("Authentication error", ignoreCase = true) ||
            msg.contains("0xAE", ignoreCase = true) ||
            msg.contains("Permission", ignoreCase = true) ||
            msg.contains("0x9D", ignoreCase = true) ||
            msg.contains("Integrity error", ignoreCase = true)
    }

    private fun humanizeAccessError(e: Exception, authKey: Int?): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Permission", ignoreCase = true) || msg.contains("9D") ->
                "Permission refusée" + (authKey?.let { " (clé session n°$it insuffisante)" } ?: "") +
                    " — ${e.message}"
            else -> e.message ?: e::class.java.simpleName
        }
    }

    /**
     * Select + optionnellement auth + explore en une passe (tag présent).
     */
    fun selectAndExplore(
        aid: Aid,
        keyNo: Int? = null,
        key: ByteArray = AesConstants.FACTORY_KEY,
        readFiles: Boolean = true,
    ): ApplicationExploreResult {
        selectApplication(aid)
        if (keyNo != null) {
            authenticateAes(keyNo, key, aid.hex)
        }
        return exploreSelectedApplication(aid, readFiles)
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Commande meta (GetFileIDs, GetFileSettings, GetKeySettings) avec SM plain+CMAC si auth.
     * Gère le chaining AF des réponses.
     */
    private fun exchangeMeta(command: DesfireCommand, data: ByteArray = ByteArray(0)): ByteArray {
        val sess = session
        val txData = if (sess != null) {
            sess.prepareMetaCommand(command.code, data)
        } else {
            data
        }
        val frames = mutableListOf<ByteArray>()
        var response = exchange(command, txData)
        frames += response.data
        var guard = 0
        while (response.isAdditionalFrame && guard < 32) {
            response = exchange(DesfireCommand.ADDITIONAL_FRAME)
            frames += response.data
            guard++
        }
        if (!response.isSuccess) {
            throw DesfireProtocolException(
                "${command.displayName} failed: ${response.status.shortName} — ${response.status.pedagogicalFr}",
                response,
            )
        }
        val concat = frames.fold(ByteArray(0)) { acc, b -> acc + b }
        if (sess == null) return concat
        return try {
            sess.postprocessMetaResponse(concat, response.sw2)
        } catch (e: SecureMessagingException) {
            // Fallback : certaines cartes / Free access renvoient sans CMAC malgré auth
            // si la vérif échoue sur data courte, tenter sans MAC
            if (concat.size < 8) {
                concat
            } else {
                throw DesfireProtocolException("${command.displayName} SM: ${e.message}", response)
            }
        }
    }

    private fun collectChained(command: DesfireCommand, data: ByteArray = ByteArray(0)): ByteArray {
        val frames = mutableListOf<ByteArray>()
        var response = exchange(command, data)
        frames += response.data
        var guard = 0
        while (response.isAdditionalFrame && guard < 16) {
            response = exchange(DesfireCommand.ADDITIONAL_FRAME)
            frames += response.data
            guard++
        }
        if (!response.isSuccess) {
            throw DesfireProtocolException(
                "${command.displayName} failed: ${response.status.shortName}",
                response,
            )
        }
        return frames.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    private fun writeLe24(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value and 0xFF).toByte()
        dest[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dest[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun buildOutAnnotation(command: DesfireCommand, data: ByteArray): String {
        return when (command) {
            DesfireCommand.SELECT_APPLICATION ->
                if (data.contentEquals(byteArrayOf(0, 0, 0))) {
                    "SelectApplication (PICC)"
                } else {
                    "SelectApplication (${Hex.encode(data.copyOf(minOf(3, data.size)))})"
                }
            DesfireCommand.AUTHENTICATE_AES ->
                if (data.isNotEmpty()) "AuthenticateAES (clé n°${data[0].toInt() and 0xFF})"
                else "AuthenticateAES"
            DesfireCommand.AUTHENTICATE_EV2_FIRST ->
                if (data.isNotEmpty()) "AuthenticateEV2First (clé n°${data[0].toInt() and 0xFF})"
                else "AuthenticateEV2First"
            DesfireCommand.READ_DATA ->
                if (data.size >= 1) "ReadData (fichier ${data[0].toInt() and 0xFF})"
                else "ReadData"
            DesfireCommand.GET_FILE_SETTINGS ->
                if (data.isNotEmpty()) "GetFileSettings (fichier ${data[0].toInt() and 0xFF})"
                else "GetFileSettings"
            else -> {
                if (data.isEmpty()) command.displayName
                else "${command.displayName} ${Hex.encode(data)}"
            }
        }
    }

    private fun buildInAnnotation(command: DesfireCommand, response: DesfireResponse): String {
        if (!response.parseOk) {
            return response.parseNote ?: "parse error"
        }
        val size = response.data.size
        val sizePart = if (size > 0) " · ${size} o" else ""
        val authPart = when (session?.smLevel) {
            com.cardrw.desfire.crypto.SecureMessagingLevel.EV1 -> " · SM EV1"
            com.cardrw.desfire.crypto.SecureMessagingLevel.EV2 -> " · SM EV2"
            else -> ""
        }
        return when (response.status) {
            DesfireStatus.SUCCESS -> "OK (0x00)$sizePart$authPart"
            DesfireStatus.ADDITIONAL_FRAME -> "suite (0xAF)$sizePart"
            DesfireStatus.UNKNOWN -> {
                val sw = (response.sw2 and 0xFF).toString(16).uppercase().padStart(2, '0')
                "statut 0x$sw$sizePart — ${response.status.pedagogicalFr}"
            }
            else -> {
                val sw = (response.sw2 and 0xFF).toString(16).uppercase().padStart(2, '0')
                "${response.status.shortName} (0x$sw)$sizePart — ${response.status.pedagogicalFr}"
            }
        }
    }
}

class DesfireProtocolException(
    message: String,
    val response: DesfireResponse? = null,
) : Exception(message)
