package com.cardrw.app.viewmodel

import android.nfc.Tag
import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.data.repository.AidNameRepository
import com.cardrw.app.data.repository.ApduJournalRepository
import com.cardrw.app.data.repository.KeyVaultRepository
import com.cardrw.app.nfc.IsoDepTransceiver
import com.cardrw.app.nfc.NfcReaderController
import com.cardrw.app.nfc.NfcTagBus
import com.cardrw.desfire.client.DesfireClient
import com.cardrw.desfire.client.DesfireProtocolException
import com.cardrw.desfire.client.DesfireTransportException
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.model.Aid
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.AuthBarrier
import com.cardrw.desfire.model.AuthIntent
import com.cardrw.desfire.model.AuthKeyPlan
import com.cardrw.desfire.model.AuthKeyPlanner
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.UidKind
import com.cardrw.desfire.session.AuthSession
import com.cardrw.desfire.util.Hex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CardUiState(
    val phase: CardPhase = CardPhase.Waiting,
    val identity: CardIdentity? = null,
    val selectedAidHex: String? = null,
    val authSession: AuthSession? = null,
    val keyNo: Int = 0,
    /** Hex compact 32 chars (sans espaces) — l’UI peut grouper à l’affichage. */
    val keyHex: String = Hex.encode(AesConstants.FACTORY_KEY),
    val explore: ApplicationExploreResult? = null,
    /** UID réel via GetCardUID (après auth), si Random ID. */
    val realUidHex: String? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    /** Message d’opération court (pas de doublon avec le profil). */
    val statusLine: String? = null,
    val tagPresent: Boolean = false,
)

enum class CardPhase {
    Waiting,
    Reading,
    Ready,
    Error,
}

@HiltViewModel
class CardViewModel @Inject constructor(
    private val journalRepository: ApduJournalRepository,
    private val aidNames: AidNameRepository,
    private val keyVault: KeyVaultRepository,
    private val tagBus: NfcTagBus,
) : ViewModel() {

    private val _ui = MutableStateFlow(CardUiState())
    val ui: StateFlow<CardUiState> = _ui.asStateFlow()

    /** Entrées coffre (noms seulement) pour la sheet auth. */
    val vaultEntries: StateFlow<List<KeyVaultEntryMeta>> = keyVault.entries

    private val nfcMutex = Mutex()
    private var liveIsoDep: IsoDep? = null
    private var liveClient: DesfireClient? = null
    private var liveUid: ByteArray? = null

    init {
        viewModelScope.launch { keyVault.load() }
        // Tags via MainActivity reader mode + NfcTagBus (pending si navigation depuis Accueil)
        viewModelScope.launch {
            tagBus.consumePending()?.let { handleIncomingTag(it) }
            tagBus.tags.collect { tag ->
                tagBus.clearPending(tag)
                handleIncomingTag(tag)
            }
        }
    }

    private fun handleIncomingTag(tag: Tag) {
        if (NfcReaderController.isIsoDep(tag)) {
            onTagDiscovered(tag)
        }
    }

    fun friendlyName(aidHex: String): String? = aidNames.nameFor(aidHex)

    fun nextVaultDefaultName(): String = keyVault.nextDefaultName()

    fun reloadVault() {
        viewModelScope.launch { keyVault.load() }
    }

    /**
     * Plan d’auth pour la sheet (U3) selon moniteur courant.
     * Priorité : premier fichier non lisible avec session → structure free-list → générique.
     */
    fun suggestAuthPlan(): AuthKeyPlan {
        val sessionKey = _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber
        val explore = _ui.value.explore
        if (explore != null && !explore.aid.isPicc) {
            val unread = explore.files.firstOrNull { node ->
                node.dataHex == null &&
                    !node.settings.accessRights.canReadWith(sessionKey) &&
                    !(node.settings.accessRights.isReadNever &&
                        (node.settings.accessRights.readWrite and 0x0F) == 0x0F)
            }
            if (unread != null) {
                return AuthKeyPlanner.plan(
                    AuthIntent.ReadFile(unread.fileNo, unread.settings.accessRights),
                    currentSessionKey = sessionKey,
                )
            }
            val freeList = explore.keySettings?.bits?.freeDirectoryListWithoutMaster
            if (explore.files.isEmpty() && freeList == false) {
                return AuthKeyPlanner.plan(
                    AuthIntent.ExploreStructure(false),
                    currentSessionKey = sessionKey,
                )
            }
            if (explore.files.isEmpty() && explore.keySettings == null) {
                return AuthKeyPlanner.plan(
                    AuthIntent.ExploreStructure(null),
                    currentSessionKey = sessionKey,
                )
            }
        } else if (explore == null && _ui.value.selectedAidHex != null) {
            return AuthKeyPlanner.plan(
                AuthIntent.ExploreStructure(null),
                currentSessionKey = sessionKey,
            )
        }
        return AuthKeyPlanner.plan(AuthIntent.Generic, currentSessionKey = sessionKey)
    }

    fun authPlanForFile(node: FileNode): AuthKeyPlan {
        val sessionKey = _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber
        return AuthKeyPlanner.plan(
            AuthIntent.ReadFile(node.fileNo, node.settings.accessRights),
            currentSessionKey = sessionKey,
        )
    }

    fun onTagDiscovered(tag: Tag) {
        viewModelScope.launch {
            nfcMutex.withLock {
                val uid = tag.id
                val existing = liveIsoDep
                if (existing != null &&
                    existing.isConnected &&
                    liveUid != null &&
                    uid.contentEquals(liveUid)
                ) {
                    _ui.update { it.copy(tagPresent = true) }
                    return@withLock
                }
                connectAndRead(tag)
            }
        }
    }

    fun updateKeyNo(value: Int) {
        _ui.update { it.copy(keyNo = value.coerceIn(0, 13)) }
    }

    fun updateKeyHex(value: String) {
        val clean = value.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
        when {
            clean.length <= 32 ->
                _ui.update { it.copy(keyHex = clean, errorMessage = null) }
            else ->
                // Colle souvent un journal APDU entier : on refuse plutôt que de tronquer en silence
                _ui.update {
                    it.copy(
                        errorMessage = "Colle uniquement la clé AES (32 caractères hex), pas un journal APDU.",
                    )
                }
        }
    }

    fun setFactoryKey() {
        _ui.update {
            it.copy(
                keyHex = Hex.encode(AesConstants.FACTORY_KEY),
                errorMessage = null,
            )
        }
    }

    fun pasteKeyHex(raw: String) {
        updateKeyHex(raw)
        val clean = raw.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
        if (clean.length == 32) {
            _ui.update { it.copy(keyHex = clean, errorMessage = null) }
        }
    }

    /**
     * SelectApplication puis **pull auto** du directory (U1 / moniteur diagnostic).
     * L’auth est invalidée par le select côté client.
     */
    fun selectApplication(aidHex: String) {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    selectedAidHex = aidHex,
                    explore = null,
                    authSession = null,
                    errorMessage = null,
                    busy = true,
                    statusLine = "SelectApplication…",
                )
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.ensureApplicationSelected(Aid.fromHex(aidHex))
                    // Select invalide l’auth côté client
                    client.authSession
                }
            }
            result.fold(
                onSuccess = {
                    _ui.update {
                        it.copy(
                            authSession = null,
                            errorMessage = null,
                            // busy reste true : enchaîne explore
                        )
                    }
                    syncJournal()
                    runExplore(aidHex)
                },
                onFailure = { e -> handleOpFailure(e, selectedAidHex = aidHex) },
            )
        }
    }

    /**
     * AuthenticateAES puis **re-pull auto** (structure / données selon session).
     *
     * Matériau : [keyHex] **ou** [vaultEntryId] (coffre K2).  
     * Si [saveAsVaultName] non null et auth OK → enregistre le matériau saisi dans le coffre.
     */
    fun authenticate(
        keyNo: Int? = null,
        keyHex: String? = null,
        vaultEntryId: String? = null,
        saveAsVaultName: String? = null,
    ) {
        if (keyNo != null) {
            _ui.update { it.copy(keyNo = keyNo.coerceIn(0, 13)) }
        }
        val aidHex = _ui.value.selectedAidHex
        if (aidHex == null) {
            _ui.update { it.copy(errorMessage = "Sélectionne d’abord une application (ou PICC).") }
            return
        }
        val resolvedKeyNo = _ui.value.keyNo

        viewModelScope.launch {
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "AuthenticateAES clé n°$resolvedKeyNo…")
            }
            val keyBytes: ByteArray = try {
                when {
                    vaultEntryId != null -> {
                        keyVault.material(vaultEntryId)
                    }
                    keyHex != null -> {
                        val clean = keyHex.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
                        if (clean.length != 32) {
                            throw IllegalArgumentException("attendu 32 hex, got ${clean.length}")
                        }
                        _ui.update { it.copy(keyHex = clean) }
                        parseKeyHex(clean)
                    }
                    else -> parseKeyHex(_ui.value.keyHex)
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        busy = false,
                        errorMessage = "Clé AES invalide : ${e.message}",
                    )
                }
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.ensureApplicationSelected(Aid.fromHex(aidHex))
                    client.authenticateAes(resolvedKeyNo, keyBytes, aidHex)
                    client.authSession
                }
            }
            result.fold(
                onSuccess = { session ->
                    if (saveAsVaultName != null && vaultEntryId == null) {
                        try {
                            keyVault.create(saveAsVaultName, keyBytes)
                        } catch (e: Exception) {
                            // Auth OK ; échec save non bloquant
                            _ui.update {
                                it.copy(
                                    authSession = session,
                                    errorMessage = null,
                                    statusLine = "Auth OK — coffre : ${e.message}",
                                )
                            }
                            syncJournal()
                            runExplore(aidHex)
                            return@fold
                        }
                    }
                    _ui.update {
                        it.copy(
                            authSession = session,
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                    runExplore(aidHex)
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /** Actualiser manuellement le directory de l’AID sélectionné (U1 : plus la porte d’entrée). */
    fun explore() {
        val aidHex = _ui.value.selectedAidHex
        if (aidHex == null) {
            _ui.update { it.copy(errorMessage = "Sélectionne d’abord une application (ou PICC).") }
            return
        }
        viewModelScope.launch {
            runExplore(aidHex)
        }
    }

    /**
     * GetKeySettings / FileIDs / FileSettings / ReadData selon droits de session.
     * Appelé après select, après auth, ou via [explore] (Actualiser).
     */
    private suspend fun runExplore(aidHex: String) {
        // Cache structure du même AID (workflow clé 0 structure → clé lecture données)
        val prev = _ui.value.explore
        val cachedSettings =
            if (prev != null && prev.aidHex.equals(aidHex, ignoreCase = true) && prev.files.isNotEmpty()) {
                prev.fileSettings
            } else {
                null
            }

        val keyNo = _ui.value.authSession?.keyNumber
        val modeHint = when {
            keyNo != null && keyNo != 0 && cachedSettings != null -> "Lecture (cache structure)…"
            keyNo != null && keyNo != 0 -> "Exploration (clé non-maître)…"
            else -> "Exploration…"
        }
        _ui.update {
            it.copy(busy = true, errorMessage = null, statusLine = modeHint)
        }
        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                client.ensureApplicationSelected(Aid.fromHex(aidHex))
                val exploreResult = client.exploreSelectedApplication(
                    aid = Aid.fromHex(aidHex),
                    readStandardFiles = true,
                    cachedFileSettings = cachedSettings,
                )
                // Fusionner key settings du cache si mode lecture seule
                val merged = if (exploreResult.structureFromCache &&
                    exploreResult.keySettings == null &&
                    prev?.keySettings != null &&
                    prev.aidHex.equals(aidHex, ignoreCase = true)
                ) {
                    exploreResult.copy(keySettings = prev.keySettings)
                } else {
                    exploreResult
                }
                merged to client.authSession
            }
        }
        result.fold(
            onSuccess = { (exploreResult, session) ->
                val fileCount = exploreResult.files.size
                val readable = exploreResult.files.count { it.dataHex != null }
                val summary = when {
                    Aid.fromHex(aidHex).isPicc -> "PICC : key settings (pas de fichiers ici)"
                    exploreResult.structureFromCache && readable > 0 ->
                        "Lecture : $readable fichier(s) lu(s) (structure en cache)"
                    exploreResult.structureFromCache ->
                        "Structure en cache — $fileCount fichier(s), 0 lu (droits / session)"
                    fileCount == 0 -> "Exploration : aucun fichier listé"
                    else -> "Exploration : $fileCount fichier(s) · $readable lu(s)"
                }
                _ui.update {
                    it.copy(
                        busy = false,
                        explore = exploreResult,
                        authSession = session,
                        statusLine = summary,
                        errorMessage = null,
                    )
                }
                syncJournal()
            },
            onFailure = { e -> handleOpFailure(e) },
        )
    }

    /** GetCardUID après auth — utile si Random ID. */
    fun fetchRealUid() {
        if (_ui.value.authSession?.authenticated != true) {
            _ui.update { it.copy(errorMessage = "Authentifie d’abord (GetCardUID nécessite une session).") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusLine = "GetCardUID…") }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    Hex.encode(client.getCardUid())
                }
            }
            result.fold(
                onSuccess = { uid ->
                    _ui.update {
                        it.copy(
                            busy = false,
                            realUidHex = uid,
                            statusLine = null,
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    fun resetToWaiting() {
        viewModelScope.launch {
            nfcMutex.withLock { closeLive() }
            _ui.value = CardUiState()
        }
    }

    // -------------------------------------------------------------------------

    private suspend fun connectAndRead(tag: Tag) {
        closeLive()
        _ui.update {
            it.copy(
                phase = CardPhase.Reading,
                errorMessage = null,
                statusLine = null,
                identity = null,
                selectedAidHex = null,
                authSession = null,
                explore = null,
                realUidHex = null,
                tagPresent = true,
            )
        }
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            _ui.update {
                it.copy(
                    phase = CardPhase.Error,
                    errorMessage = "Le tag ne supporte pas IsoDep (pas DESFire ?).",
                    tagPresent = false,
                )
            }
            return
        }
        try {
            isoDep.connect()
            isoDep.timeout = IsoDepTransceiver.DEFAULT_TIMEOUT_MS
            val transceiver = IsoDepTransceiver(isoDep)
            val client = DesfireClient(transceiver)
            val identity = withContext(Dispatchers.IO) {
                client.readIdentity(tag.id)
            }
            liveIsoDep = isoDep
            liveClient = client
            liveUid = tag.id
            journalRepository.replaceFrom(client.journal)
            _ui.update {
                it.copy(
                    phase = CardPhase.Ready,
                    identity = identity,
                    statusLine = null,
                    errorMessage = null,
                    tagPresent = true,
                )
            }
        } catch (e: Exception) {
            closeLive()
            _ui.update {
                it.copy(
                    phase = CardPhase.Error,
                    errorMessage = humanize(e),
                    statusLine = null,
                    tagPresent = false,
                )
            }
        }
    }

    private suspend fun <T> withLiveClient(block: (DesfireClient) -> T): Result<T> {
        val client = liveClient
        val iso = liveIsoDep
        if (client == null || iso == null) {
            return Result.failure(
                IllegalStateException("Aucune carte connectée — approche et maintiens la carte sur l’antenne."),
            )
        }
        return try {
            if (!iso.isConnected) {
                iso.connect()
            }
            nfcMutex.withLock {
                Result.success(block(client))
            }
        } catch (e: Exception) {
            if (e is DesfireTransportException || e.cause is java.io.IOException) {
                closeLive()
            }
            Result.failure(e)
        }
    }

    private fun handleOpFailure(e: Throwable, selectedAidHex: String? = _ui.value.selectedAidHex) {
        val tagLost = e is DesfireTransportException ||
            e.message?.contains("Tag lost", ignoreCase = true) == true ||
            e.message?.contains("connectée", ignoreCase = true) == true
        if (tagLost && e is DesfireTransportException) {
            viewModelScope.launch { nfcMutex.withLock { closeLive() } }
        }
        syncJournal()
        _ui.update {
            it.copy(
                busy = false,
                selectedAidHex = selectedAidHex ?: it.selectedAidHex,
                authSession = if (tagLost) null else it.authSession,
                errorMessage = humanize(e),
                statusLine = if (tagLost) "Carte perdue — ne pas retirer pendant une opération." else null,
                tagPresent = if (tagLost) false else it.tagPresent,
            )
        }
    }

    private fun syncJournal() {
        liveClient?.let { journalRepository.replaceFrom(it.journal) }
    }

    private fun closeLive() {
        try {
            liveIsoDep?.close()
        } catch (_: Exception) {
        }
        liveIsoDep = null
        liveClient = null
        liveUid = null
    }

    private fun parseKeyHex(hex: String): ByteArray {
        val clean = hex.replace(Regex("[\\s:_-]"), "")
        require(clean.length == 32) { "attendu 32 hex (16 octets), got ${clean.length}" }
        return Hex.decode(clean)
    }

    private fun humanize(e: Throwable): String = when (e) {
        is DesfireTransportException ->
            "Carte perdue ou erreur NFC — maintiens la carte pendant l’opération."
        is DesfireProtocolException ->
            e.message ?: "Erreur protocole DESFire"
        else -> e.message ?: e::class.java.simpleName
    }

    override fun onCleared() {
        closeLive()
        super.onCleared()
    }
}
