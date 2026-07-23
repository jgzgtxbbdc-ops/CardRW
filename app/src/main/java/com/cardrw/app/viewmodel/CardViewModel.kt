package com.cardrw.app.viewmodel

import android.nfc.Tag
import android.nfc.tech.IsoDep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardrw.app.BuildConfig
import com.cardrw.app.data.model.KeyVaultEntryMeta
import com.cardrw.app.data.repository.AidNameRepository
import com.cardrw.app.data.repository.ApduJournalRepository
import com.cardrw.app.data.repository.DumpRepository
import com.cardrw.app.data.repository.KeyVaultRepository
import com.cardrw.app.nfc.IsoDepTransceiver
import com.cardrw.app.nfc.NfcReaderController
import com.cardrw.app.nfc.NfcTagBus
import com.cardrw.desfire.client.DesfireClient
import com.cardrw.desfire.client.DesfireProtocolException
import com.cardrw.desfire.client.DesfireTransportException
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.crypto.DesConstants
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.dump.CardDumpBuilder
import com.cardrw.desfire.dump.DumpRestorePlanner
import com.cardrw.desfire.model.AccessRights
import com.cardrw.desfire.model.Aid
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.AuthBarrier
import com.cardrw.desfire.model.AuthIntent
import com.cardrw.desfire.model.AuthKeyPlan
import com.cardrw.desfire.model.AuthKeyPlanner
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.model.FileNode
import com.cardrw.desfire.model.UidKind
import com.cardrw.desfire.model.canWriteWith
import com.cardrw.desfire.model.readKeyCandidates
import com.cardrw.desfire.model.writeKeyCandidates
import com.cardrw.desfire.session.AuthSession
import com.cardrw.desfire.session.DesLegacySession
import com.cardrw.desfire.util.Hex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /**
     * Cache multi-AID pour l’arbre moniteur (U4) : clés = AID hex uppercase.
     * Les apps déjà visitées restent visibles repliées avec leur dernier directory.
     */
    val exploreByAid: Map<String, ApplicationExploreResult> = emptyMap(),
    /** UID réel via GetCardUID (après auth), si Random ID. */
    val realUidHex: String? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    /** Message d’opération court (pas de doublon avec le profil). */
    val statusLine: String? = null,
    val tagPresent: Boolean = false,
    /**
     * Flash sobre d’auth réussie — texte via [authSuccessMessage] ; auto-clear après délai.
     */
    val authSuccessFlash: Boolean = false,
    /** Message du flash (défaut vs manuel). */
    val authSuccessMessage: String? = null,
    /**
     * Incrémenté pour demander l’ouverture de la sheet auth (échec auto-auth silencieux).
     */
    val openAuthSheetNonce: Long = 0L,
    /**
     * Plan à présenter dans la sheet (lecture / écriture / générique) quand l’auto-auth échoue.
     */
    val pendingAuthPlan: AuthKeyPlan? = null,
    /**
     * Ouvrir la sheet Write une fois la session d’écriture prête (auth auto intention Write).
     */
    val pendingWriteFileNo: Int? = null,
    /**
     * Dernier dump exporté (JSON) — UI peut copier / partager.
     */
    val lastDumpJson: String? = null,
    val lastDumpFileName: String? = null,
    /**
     * Dry-run restore (CDC §8.5) — lignes + warnings pour sheet UI.
     */
    val restorePreviewLines: List<String> = emptyList(),
    val restorePreviewWarnings: List<String> = emptyList(),
    val restorePreviewFileName: String? = null,
    /**
     * Proposition d’enregistrer le matériau hex malgré un échec d’auth
     * (ex. bon secret, mauvais slot carte). Non null → dialog UI.
     */
    val pendingVaultSave: PendingVaultSaveOffer? = null,
)

/**
 * Offre d’enregistrement coffre après auth refusée (matériau en mémoire VM uniquement).
 */
data class PendingVaultSaveOffer(
    val displayName: String,
    /** Hex 32 pour affichage masqué / debug UI si besoin. */
    val keyHexMasked: String = "••••••••",
)

enum class CardPhase {
    Waiting,
    Reading,
    Ready,
    Error,
}

/**
 * Auth AES réussie mémorisée pour un slot carte (session VM uniquement).
 * Plusieurs slots peuvent coexister par AID (ex. clé 2 lecture F0/F1, clé 3 lecture F2).
 */
data class RememberedAppAuth(
    val keyNo: Int,
    /** Si non null, on reprend le matériau du coffre en priorité. */
    val vaultEntryId: String? = null,
    /** Copie session du secret (wipe à la pose d’une nouvelle carte / reset). */
    val keyBytes: ByteArray,
) {
    fun copyMaterial(): ByteArray = keyBytes.copyOf()
}

@HiltViewModel
class CardViewModel @Inject constructor(
    private val journalRepository: ApduJournalRepository,
    private val aidNames: AidNameRepository,
    private val keyVault: KeyVaultRepository,
    private val dumpRepository: DumpRepository,
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

    /**
     * Mémoire session multi-clés : AID uppercase → (keyNo → matériau).
     * SelectApplication invalide la SM carte ; on rejoue les clés déjà validées
     * et on **conserve** les données fichier déjà lues sous une autre session.
     */
    private val rememberedKeysByAid = mutableMapOf<String, MutableMap<Int, RememberedAppAuth>>()

    /** Dernier keyNo OK par AID (préférence re-select). */
    private val lastKeyNoByAid = mutableMapOf<String, Int>()

    /**
     * Slots déjà tentés avec la **clé standard usine** (00…00) et refusés (0xAE),
     * pour ne pas reboucler sur une carte re-encodée. Cleared avec la mémoire session.
     */
    private val factoryFailedSlotsByAid = mutableMapOf<String, MutableSet<Int>>()

    /** Matériau en attente d’enregistrement coffre après auth KO (wipe sur dismiss / create). */
    private var pendingVaultKeyBytes: ByteArray? = null

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

    fun clearLastDumpExport() {
        _ui.update { it.copy(lastDumpJson = null, lastDumpFileName = null) }
    }

    /**
     * Export dump moniteur (structure + données lues, **sans secrets**) →
     * fichier local `filesDir/dumps` + JSON en mémoire pour copie presse-papiers.
     */
    fun exportMonitorDump() {
        val identity = _ui.value.identity
        if (identity == null) {
            _ui.update { it.copy(errorMessage = "Aucune carte lue — pose une carte d’abord.") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "Export dump…")
            }
            try {
                val doc = CardDumpBuilder.build(
                    identity = identity,
                    exploreByAid = _ui.value.exploreByAid,
                    realUidHex = _ui.value.realUidHex,
                    appVersion = BuildConfig.VERSION_NAME,
                    friendlyName = { aidNames.nameFor(it) },
                )
                val json = CardDumpBuilder.toPrettyJson(doc)
                val item = withContext(Dispatchers.IO) {
                    dumpRepository.save(doc, uidHint = identity.displayUid)
                }
                val unread = doc.structure.unreadFiles.size
                val dataCount = doc.data.files.size
                _ui.update {
                    it.copy(
                        busy = false,
                        lastDumpJson = json,
                        lastDumpFileName = item.fileName,
                        statusLine = "Dump OK — ${item.fileName} · " +
                            "${doc.structure.applications.size} app(s) · $dataCount fichier(s) lu(s)" +
                            if (unread > 0) " · $unread non lu(s)" else "",
                        errorMessage = null,
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        busy = false,
                        errorMessage = "Export dump : ${e.message}",
                        statusLine = null,
                    )
                }
            }
        }
    }

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

    fun authPlanForWrite(node: FileNode): AuthKeyPlan {
        val sessionKey = _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber
        return AuthKeyPlanner.plan(
            AuthIntent.WriteFile(node.fileNo, node.settings.accessRights),
            currentSessionKey = sessionKey,
        )
    }

    fun consumePendingWriteFile() {
        _ui.update { it.copy(pendingWriteFileNo = null) }
    }

    fun consumePendingAuthPlan() {
        _ui.update { it.copy(pendingAuthPlan = null) }
    }

    /**
     * CTA **lecture** fichier : auth auto selon intention Read
     * (candidats R/RW, mémorisée puis usine). True = auto lancée / déjà OK (pas de sheet).
     */
    fun tryAuthFileWithRemembered(node: FileNode): Boolean {
        val aidHex = _ui.value.selectedAidHex ?: return false
        val plan = authPlanForFile(node)
        return when (plan.barrier) {
            AuthBarrier.NONE -> true // session déjà suffisante
            AuthBarrier.NEVER -> false
            AuthBarrier.NEEDS_KEY -> {
                viewModelScope.launch {
                    val ok = autoAuthForPlan(
                        aidHex = aidHex,
                        plan = plan,
                        intentLabel = "lire F${node.fileNo}",
                    )
                    if (!ok) {
                        _ui.update {
                            it.copy(
                                statusLine = "Auth lecture F${node.fileNo} — saisie manuelle.",
                                openAuthSheetNonce = it.openAuthSheetNonce + 1,
                                pendingAuthPlan = plan,
                            )
                        }
                    }
                }
                true
            }
        }
    }

    /**
     * CTA **écriture** fichier : si session OK → [CardUiState.pendingWriteFileNo] ;
     * sinon auth auto sur candidats W/RW puis ouvrir le sheet.
     * @return true si l’UI ne doit pas ouvrir la sheet auth manuelle tout de suite
     */
    fun requestWriteFile(node: FileNode): Boolean {
        val aidHex = _ui.value.selectedAidHex ?: return false
        val plan = authPlanForWrite(node)
        return when (plan.barrier) {
            AuthBarrier.NEVER -> {
                _ui.update {
                    it.copy(errorMessage = plan.detailMessage ?: "Écriture impossible sur ce fichier.")
                }
                true
            }
            AuthBarrier.NONE -> {
                _ui.update { it.copy(pendingWriteFileNo = node.fileNo, errorMessage = null) }
                true
            }
            AuthBarrier.NEEDS_KEY -> {
                viewModelScope.launch {
                    val ok = autoAuthForPlan(
                        aidHex = aidHex,
                        plan = plan,
                        intentLabel = "écrire F${node.fileNo}",
                    )
                    if (ok) {
                        _ui.update {
                            it.copy(pendingWriteFileNo = node.fileNo, errorMessage = null)
                        }
                    } else {
                        _ui.update {
                            it.copy(
                                statusLine = "Auth écriture F${node.fileNo} — saisie manuelle.",
                                openAuthSheetNonce = it.openAuthSheetNonce + 1,
                                pendingAuthPlan = plan,
                            )
                        }
                    }
                }
                true
            }
        }
    }

    /**
     * Auth auto pour un [AuthKeyPlan] d’intention (Read / Write / Structure) :
     * mémorisée sur slots candidats (prefer d’abord), puis usine sur ces slots.
     */
    private suspend fun autoAuthForPlan(
        aidHex: String,
        plan: AuthKeyPlan,
        intentLabel: String,
    ): Boolean {
        val aidKey = aidHex.uppercase()
        val candidateNos = buildList {
            plan.preferKeyNo?.let { add(it) }
            plan.candidates.forEach { add(it.keyNo) }
        }.distinct()
        if (candidateNos.isEmpty()) return false

        val remembered = rememberedKeysByAid[aidKey].orEmpty()
        for (keyNo in candidateNos) {
            val entry = remembered[keyNo] ?: continue
            val role = plan.candidates.find { it.keyNo == keyNo }?.roleLabel
            val ok = silentAuthThenExplore(
                aidHex = aidHex,
                remembered = entry,
                flashMessage = authFlashMessage(
                    aidHex = aidHex,
                    keyNo = keyNo,
                    source = "mémorisée",
                    roleLabel = role,
                    intentLabel = intentLabel,
                ),
                fillRemembered = true,
            )
            if (ok) return true
        }

        val failedFactory = factoryFailedSlotsByAid[aidKey].orEmpty()
        for (keyNo in candidateNos) {
            if (keyNo in failedFactory) continue
            val role = plan.candidates.find { it.keyNo == keyNo }?.roleLabel
            val ok = tryFactoryAuthThenExplore(
                aidHex = aidHex,
                keyNo = keyNo,
                flashMessage = authFlashMessage(
                    aidHex = aidHex,
                    keyNo = keyNo,
                    source = "usine",
                    roleLabel = role,
                    intentLabel = intentLabel,
                ),
                fillAfter = true,
            )
            if (ok) return true
        }
        return false
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
     * SelectApplication puis **auth auto** + pull directory (philosophie moniteur).
     *
     * Select invalide toujours la SM carte : on rejoue sans saisie
     * 1) clé mémorisée pour l’AID
     * 2) clé usine slot 0
     * 3) explore sans auth si (1)(2) échouent
     *
     * @param tryDefaultAuth conservé (double-tap = forcer usine d’abord, même logique)
     */
    fun selectApplication(aidHex: String, tryDefaultAuth: Boolean = false) {
        viewModelScope.launch {
            val cacheKey = aidHex.uppercase()
            _ui.update {
                it.copy(
                    selectedAidHex = aidHex,
                    // U4 : garder le cache de l’AID si déjà visité (pas de flash vide)
                    explore = it.exploreByAid[cacheKey],
                    authSession = null,
                    errorMessage = null,
                    busy = true,
                    statusLine = "SelectApplication…",
                    authSuccessFlash = false,
                    authSuccessMessage = null,
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
                        )
                    }
                    syncJournal()
                    // Toujours tenter une auth auto (mémorisée → usine) — pas de double-tap obligatoire
                    autoAuthThenExplore(
                        aidHex = aidHex,
                        preferFactoryFirst = tryDefaultAuth,
                    )
                },
                onFailure = { e -> handleOpFailure(e, selectedAidHex = aidHex) },
            )
        }
    }

    /**
     * Prochain AID labo libre (hex 6) à partir des apps déjà sur la carte.
     * Plages : F00101…F001FF, puis F00201…, etc.
     */
    fun suggestNextAidHex(): String {
        val used = _ui.value.identity?.applications
            ?.map { it.hex.uppercase() }
            ?.toSet()
            .orEmpty()
        for (base in LAB_AID_BASES) {
            for (n in 1..0xFF) {
                val candidate = "%06X".format(base + n)
                if (candidate !in used) return candidate
            }
        }
        return "F00101"
    }

    /**
     * Auth auto post-select : mémorisée ↔ usine (ordre selon [preferFactoryFirst]).
     * Flash pédagogique avec scope + clé + droits.
     */
    private suspend fun autoAuthThenExplore(
        aidHex: String,
        preferFactoryFirst: Boolean = false,
    ) {
        if (preferFactoryFirst) {
            if (tryFactoryAuthThenExplore(
                    aidHex = aidHex,
                    keyNo = DEFAULT_AUTH_KEY_NO,
                    flashMessage = authFlashMessage(aidHex, DEFAULT_AUTH_KEY_NO, "usine"),
                    fillAfter = true,
                )
            ) {
                return
            }
            if (tryRememberedAuthThenExplore(aidHex)) return
            openAuthSheetAfterAutoFail(aidHex)
            runExplore(aidHex, fillRemembered = false)
            return
        }
        if (tryRememberedAuthThenExplore(aidHex)) return
        if (tryFactoryAuthThenExplore(
                aidHex = aidHex,
                keyNo = DEFAULT_AUTH_KEY_NO,
                flashMessage = authFlashMessage(aidHex, DEFAULT_AUTH_KEY_NO, "usine"),
                fillAfter = true,
            )
        ) {
            return
        }
        // Free-list / explore sans auth si possible ; sheet si free-list bloquée
        runExplore(aidHex, fillRemembered = false)
        val explore = _ui.value.explore
        val freeListBlocked = explore?.notes?.any {
            it.contains("free-list", ignoreCase = true) ||
                it.contains("Authentication", ignoreCase = true) ||
                it.contains("0xAE", ignoreCase = true)
        } == true
        if (freeListBlocked && !aidHex.equals("000000", ignoreCase = true)) {
            openAuthSheetAfterAutoFail(aidHex)
        }
    }

    private fun openAuthSheetAfterAutoFail(aidHex: String) {
        _ui.update {
            it.copy(
                authSession = null,
                errorMessage = null,
                statusLine = null,
                openAuthSheetNonce = it.openAuthSheetNonce + 1,
            )
        }
    }

    /**
     * Rejoue la dernière clé OK pour [aidHex], puis fill multi-clés.
     * @return true si au moins une auth mémorisée a réussi
     */
    private suspend fun tryRememberedAuthThenExplore(aidHex: String): Boolean {
        val preferred = preferredRemembered(aidHex) ?: return false
        val ok = silentAuthThenExplore(
            aidHex = aidHex,
            remembered = preferred,
            flashMessage = authFlashMessage(aidHex, preferred.keyNo, source = "mémorisée"),
            fillRemembered = true,
        )
        if (ok) return true
        forgetKey(aidHex, preferred.keyNo)
        val fallback = preferredRemembered(aidHex) ?: return false
        return silentAuthThenExplore(
            aidHex = aidHex,
            remembered = fallback,
            flashMessage = authFlashMessage(aidHex, fallback.keyNo, source = "mémorisée"),
            fillRemembered = true,
        )
    }

    /**
     * Message flash auth : scope + n° clé + rôle intention (Read/Write/…) + source.
     */
    private fun authFlashMessage(
        aidHex: String,
        keyNo: Int,
        source: String,
        roleLabel: String? = null,
        intentLabel: String? = null,
    ): String {
        val isPicc = aidHex.equals("000000", ignoreCase = true)
        val scope = if (isPicc) "PICC" else "app ${aidHex.uppercase()}"
        val rights = roleLabel ?: when {
            keyNo == 0 && isPicc -> "master · structure / Format / Create"
            keyNo == 0 -> "master app · structure"
            else -> "droits selon fichiers"
        }
        val intent = intentLabel?.let { " · $it" }.orEmpty()
        return "Auth auto · $scope · clé n°$keyNo ($source)$intent · $rights"
    }

    /**
     * True si le **client live** a une session AES master PICC (clé 0).
     * Ne se fie pas seul à l’UI : SelectApplication tue la SM côté carte.
     */
    private suspend fun liveClientHasPiccMasterAes(): Boolean {
        return withContext(Dispatchers.IO) {
            withLiveClient { client ->
                val s = client.session ?: return@withLiveClient false
                s.keyNumber == 0 &&
                    s.smLevel != SecureMessagingLevel.DES_LEGACY &&
                    s.smLevel != SecureMessagingLevel.NONE &&
                    (client.selectedAid?.isPicc == true ||
                        s.aidHex.equals("000000", ignoreCase = true))
            }.getOrDefault(false)
        }
    }

    /**
     * Garantit une session **AES** master PICC (clé 0) pour Create / Format / Delete / restore.
     *
     * 1) Session live AES déjà OK  
     * 2) Auth AES mémorisée / usine  
     * 3) Si master encore **DES usine** (carte vierge NXP) → **bascule auto DES→AES**
     *    (ChangeKey 0xC4, nouvelle clé AES 00…00) puis re-auth AES
     *
     * Important : vérifier le [DesfireClient.session] live — SelectApplication invalide l’auth.
     */
    private suspend fun ensurePiccMasterAesSession(): Boolean {
        if (liveClientHasPiccMasterAes()) {
            val live = withContext(Dispatchers.IO) {
                withLiveClient { it.authSession }.getOrNull()
            }
            if (live != null) {
                _ui.update {
                    it.copy(
                        authSession = live,
                        selectedAidHex = "000000",
                        errorMessage = null,
                    )
                }
            }
            return true
        }

        // Déjà en DES live/UI → bascule auto tout de suite
        val uiDes = _ui.value.authSession?.let {
            it.authenticated &&
                it.aidHex.equals("000000", ignoreCase = true) &&
                it.smLevel == SecureMessagingLevel.DES_LEGACY
        } == true
        val liveDes = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                client.session is DesLegacySession && client.session?.keyNumber == 0
            }.getOrDefault(false)
        }
        if (uiDes || liveDes) {
            return upgradeDesPiccMasterToAesFactory()
        }

        _ui.update {
            it.copy(
                busy = true,
                errorMessage = null,
                statusLine = "Auth auto PICC master AES (structure)…",
                selectedAidHex = "000000",
            )
        }
        // Mémorisée PICC clé 0 (AES only)
        val remembered = rememberedKeysByAid["000000"]?.get(0)
        if (remembered != null) {
            val ok = silentAuthThenExplore(
                aidHex = "000000",
                remembered = remembered,
                flashMessage = authFlashMessage("000000", 0, "mémorisée"),
                fillRemembered = false,
                allowDesFactoryFallback = false,
            )
            if (ok && liveClientHasPiccMasterAes()) return true
        }
        // Usine AES (sans fallback DES — on gère la bascule explicitement)
        val keyBytes = AesConstants.FACTORY_KEY.copyOf()
        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                client.ensureApplicationSelected(Aid.PICC)
                client.authenticateAesPreferEv1(
                    keyNo = 0,
                    key = keyBytes,
                    aidHex = "000000",
                    allowDesFactoryFallback = false,
                )
                client.authSession
            }
        }
        return result.fold(
            onSuccess = { session ->
                if (session != null &&
                    session.smLevel != SecureMessagingLevel.DES_LEGACY &&
                    session.smLevel != SecureMessagingLevel.NONE
                ) {
                    rememberAuth("000000", 0, keyBytes, vaultEntryId = null)
                    _ui.update {
                        it.copy(
                            authSession = session,
                            selectedAidHex = "000000",
                            errorMessage = null,
                            statusLine = null,
                        )
                    }
                    syncJournal()
                    showAuthSuccessFlash(authFlashMessage("000000", 0, "usine"))
                    true
                } else {
                    // AES a répondu mais session DES ? bascule
                    upgradeDesPiccMasterToAesFactory()
                }
            },
            onFailure = { e ->
                syncJournal()
                // AE / auth fail sur blank → tenter DES→AES auto
                val tryDesUpgrade = e is DesfireProtocolException ||
                    e.message?.contains("auth", ignoreCase = true) == true
                if (tryDesUpgrade && upgradeDesPiccMasterToAesFactory()) {
                    true
                } else {
                    _ui.update {
                        it.copy(
                            busy = false,
                            authSession = null,
                            errorMessage = "Auth PICC master AES requise : ${e.message}. " +
                                "Bascule DES→AES auto a aussi échoué (master non usine ?).",
                        )
                    }
                    false
                }
            },
        )
    }

    /**
     * Carte vierge NXP : master PICC encore DES 00…00 → ChangeKey DES→AES usine 00…00
     * + re-auth AES. Labo only (ne touche pas une master DES personnalisée inconnue).
     */
    private suspend fun upgradeDesPiccMasterToAesFactory(): Boolean {
        val newAes = AesConstants.FACTORY_KEY.copyOf()
        _ui.update {
            it.copy(
                busy = true,
                errorMessage = null,
                statusLine = "PICC DES usine — bascule auto DES→AES…",
                selectedAidHex = "000000",
            )
        }
        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                client.ensureApplicationSelected(Aid.PICC)
                // Auth DES si pas déjà en session DES slot 0
                if (client.session !is DesLegacySession || client.session?.keyNumber != 0) {
                    client.authenticateDes(
                        keyNo = 0,
                        key = DesConstants.FACTORY_2KTDEA_KEY,
                        aidHex = "000000",
                    )
                }
                client.changeKeyDesToAes(keyNo = 0, newAesKey = newAes, keyVersion = 0)
                // Session DES morte → AES
                client.authenticateAesPreferEv1(
                    keyNo = 0,
                    key = newAes,
                    aidHex = "000000",
                    allowDesFactoryFallback = false,
                )
                client.authSession
            }
        }
        return result.fold(
            onSuccess = { session ->
                if (session == null ||
                    session.smLevel == SecureMessagingLevel.DES_LEGACY ||
                    session.smLevel == SecureMessagingLevel.NONE
                ) {
                    syncJournal()
                    _ui.update {
                        it.copy(
                            busy = false,
                            authSession = session,
                            errorMessage = "Bascule DES→AES OK mais session AES absente — réessaie.",
                            statusLine = null,
                        )
                    }
                    false
                } else {
                    rememberAuth("000000", 0, newAes, vaultEntryId = null)
                    factoryFailedSlotsByAid.remove("000000")
                    _ui.update {
                        it.copy(
                            authSession = session,
                            selectedAidHex = "000000",
                            keyHex = Hex.encode(newAes),
                            keyNo = 0,
                            errorMessage = null,
                            statusLine = "Master PICC basculée DES→AES (usine) — session AES OK",
                            authSuccessFlash = true,
                            authSuccessMessage = "DES→AES auto (usine 00…00)",
                        )
                    }
                    syncJournal()
                    true
                }
            },
            onFailure = { e ->
                syncJournal()
                _ui.update {
                    it.copy(
                        busy = false,
                        authSession = null,
                        errorMessage = "Bascule DES→AES auto échouée : ${e.message}. " +
                            "Master non usine ? Utilise le sheet « Basculer master PICC en AES » avec la bonne clé.",
                        statusLine = null,
                    )
                }
                false
            },
        )
    }

    /**
     * Auth silencieuse avec matériau mémorisé → explore (+ option fill multi-clés).
     * @param allowDesFactoryFallback false pour ops structure (Create/restore) — AES only.
     * @return true si auth OK
     */
    private suspend fun silentAuthThenExplore(
        aidHex: String,
        remembered: RememberedAppAuth,
        flashMessage: String?,
        fillRemembered: Boolean = true,
        allowDesFactoryFallback: Boolean = true,
    ): Boolean {
        val keyNo = remembered.keyNo
        val keyBytes: ByteArray = try {
            resolveRememberedMaterial(remembered)
        } catch (e: Exception) {
            forgetKey(aidHex, keyNo)
            _ui.update {
                it.copy(
                    busy = false,
                    errorMessage = "Clé mémorisée indisponible : ${e.message}",
                    statusLine = null,
                )
            }
            return false
        }

        _ui.update {
            it.copy(
                keyNo = keyNo,
                keyHex = Hex.encode(keyBytes),
                busy = true,
                errorMessage = null,
                statusLine = "Ré-auth auto (clé n°$keyNo)…",
            )
        }
        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                client.ensureApplicationSelected(Aid.fromHex(aidHex))
                client.authenticateAesPreferEv1(
                    keyNo = keyNo,
                    key = keyBytes,
                    aidHex = aidHex,
                    allowDesFactoryFallback = allowDesFactoryFallback,
                )
                client.authSession
            }
        }
        return result.fold(
            onSuccess = { session ->
                // Structure AES-only : refuser de compter une session DES comme succès
                if (!allowDesFactoryFallback &&
                    (session == null ||
                        session.smLevel == SecureMessagingLevel.DES_LEGACY ||
                        session.smLevel == SecureMessagingLevel.NONE)
                ) {
                    syncJournal()
                    _ui.update { it.copy(busy = false, authSession = session) }
                    return@fold false
                }
                rememberAuth(aidHex, keyNo, keyBytes, remembered.vaultEntryId)
                _ui.update {
                    it.copy(
                        authSession = session,
                        errorMessage = null,
                        statusLine = null,
                    )
                }
                syncJournal()
                runExplore(aidHex, fillRemembered = fillRemembered)
                if (flashMessage != null) showAuthSuccessFlash(flashMessage)
                true
            },
            onFailure = {
                forgetKey(aidHex, keyNo)
                syncJournal()
                _ui.update { it.copy(busy = false, authSession = null) }
                false
            },
        )
    }

    private suspend fun resolveRememberedMaterial(remembered: RememberedAppAuth): ByteArray {
        val vaultId = remembered.vaultEntryId
        if (vaultId != null) {
            return try {
                keyVault.material(vaultId)
            } catch (_: Exception) {
                remembered.copyMaterial()
            }
        }
        return remembered.copyMaterial()
    }

    private fun rememberAuth(
        aidHex: String,
        keyNo: Int,
        keyBytes: ByteArray,
        vaultEntryId: String?,
    ) {
        val aidKey = aidHex.uppercase()
        val slot = keyNo.coerceIn(0, 13)
        val map = rememberedKeysByAid.getOrPut(aidKey) { mutableMapOf() }
        map[slot]?.keyBytes?.fill(0)
        map[slot] = RememberedAppAuth(
            keyNo = slot,
            vaultEntryId = vaultEntryId,
            keyBytes = keyBytes.copyOf(),
        )
        lastKeyNoByAid[aidKey] = slot
        _ui.update {
            it.copy(
                keyNo = slot,
                keyHex = Hex.encode(keyBytes),
            )
        }
    }

    private fun hasRememberedKeys(aidHex: String): Boolean =
        rememberedKeysByAid[aidHex.uppercase()]?.isNotEmpty() == true

    private fun preferredRemembered(aidHex: String): RememberedAppAuth? {
        val aidKey = aidHex.uppercase()
        val map = rememberedKeysByAid[aidKey] ?: return null
        if (map.isEmpty()) return null
        val last = lastKeyNoByAid[aidKey]
        if (last != null) map[last]?.let { return it }
        // Préférer une clé non-maître de lecture si seule 0 est structure
        return map.values.maxByOrNull { it.keyNo } // n° plus élevé souvent lecture ; fallback any
            ?: map.values.firstOrNull()
    }

    private fun forgetKey(aidHex: String, keyNo: Int) {
        val aidKey = aidHex.uppercase()
        val map = rememberedKeysByAid[aidKey] ?: return
        map.remove(keyNo)?.keyBytes?.fill(0)
        if (map.isEmpty()) {
            rememberedKeysByAid.remove(aidKey)
            lastKeyNoByAid.remove(aidKey)
        } else if (lastKeyNoByAid[aidKey] == keyNo) {
            lastKeyNoByAid[aidKey] = map.keys.first()
        }
    }

    private fun clearRememberedAuth() {
        rememberedKeysByAid.values.forEach { map ->
            map.values.forEach { it.keyBytes.fill(0) }
            map.clear()
        }
        rememberedKeysByAid.clear()
        lastKeyNoByAid.clear()
        factoryFailedSlotsByAid.clear()
        clearPendingVaultSave(wipeMaterial = true)
    }

    /**
     * Après explore : complète les lectures manquantes avec
     * 1) clés **mémorisées** (déjà validées sur cette session)
     * 2) **clé standard usine** 00…00 sur les slots candidats (R/RW + maître 0 structure)
     */
    private suspend fun fillUnreadWithRememberedKeys(aidHex: String) {
        fillWithRememberedMaterials(aidHex)
        fillWithFactoryKey(aidHex)
    }

    /** Phase 1 : rejouer les matériaux déjà OK (multi-slots). */
    private suspend fun fillWithRememberedMaterials(aidHex: String) {
        val aidKey = aidHex.uppercase()
        val keys = rememberedKeysByAid[aidKey] ?: return
        if (keys.isEmpty()) return

        var guard = 0
        while (guard++ < 8) {
            val nextKeyNo = nextSlotNeedingAuth(aidHex, preferKnown = keys.keys) ?: break
            val entry = keys[nextKeyNo] ?: break
            _ui.update { it.copy(statusLine = "Lecture auto (clé n°$nextKeyNo)…") }
            val ok = silentAuthThenExplore(
                aidHex = aidHex,
                remembered = entry,
                flashMessage = null,
                fillRemembered = false,
            )
            if (!ok) continue
        }
    }

    /**
     * Phase 2 : clé standard 00…00 **seulement** si la carte ressemble au labo usine.
     *
     * Important : chaque tentative d’auth **détruit** la session client (AES start).
     * On snapshot la session courante et on la **restaure** en fin de fill pour
     * ne pas laisser l’UI « clé 0 » alors que le client n’a plus de session
     * (WriteData → 0xAE).
     *
     * Slots R/W (1, 2…) : uniquement si master 0 a déjà été validé avec 00…00
     * sur cet AID (sinon spam 0xAE sur cartes à clés non nulles).
     */
    private suspend fun fillWithFactoryKey(aidHex: String) {
        val aidKey = aidHex.uppercase()
        val failed = factoryFailedSlotsByAid.getOrPut(aidKey) { mutableSetOf() }
        val sessionBefore = _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber
        val restore = sessionBefore?.let { rememberedKeysByAid[aidKey]?.get(it) }
            ?: preferredRemembered(aidHex)

        var masterIsFactoryZero = rememberedKeysByAid[aidKey]?.get(0)?.keyBytes
            ?.all { it == 0.toByte() } == true

        var guard = 0
        while (guard++ < 8) {
            val known = rememberedKeysByAid[aidKey]?.keys.orEmpty()
            val nextKeyNo = nextSlotNeedingAuth(aidHex, preferKnown = null)
                ?.takeUnless { it in known || it in failed }
                ?: break

            if (nextKeyNo != 0 && !masterIsFactoryZero) {
                // Carte non-usine : ne pas probe les slots fichier avec 00…00
                failed += nextKeyNo
                continue
            }

            _ui.update {
                it.copy(statusLine = "Auth auto clé standard (n°$nextKeyNo)…")
            }
            val ok = tryFactoryAuthThenExplore(
                aidHex = aidHex,
                keyNo = nextKeyNo,
                flashMessage = null,
                fillAfter = false,
            )
            if (!ok) {
                failed += nextKeyNo
                // Auth a clear la session client — UI doit le refléter jusqu’à restore
                _ui.update { it.copy(authSession = null) }
                continue
            }
            if (nextKeyNo == 0) {
                masterIsFactoryZero = true
            }
            if (guard == 1) {
                showAuthSuccessFlash(authFlashMessage(aidHex, nextKeyNo, "usine"))
            }
        }

        // Restaurer la session d’avant le fill (souvent master 0 pour Write)
        val current = _ui.value.authSession?.keyNumber
        if (restore != null && current != restore.keyNo) {
            silentAuthThenExplore(
                aidHex = aidHex,
                remembered = restore,
                flashMessage = null,
                fillRemembered = false,
            )
        } else if (restore != null && current == null) {
            silentAuthThenExplore(
                aidHex = aidHex,
                remembered = restore,
                flashMessage = null,
                fillRemembered = false,
            )
        }
    }

    /**
     * Prochain slot carte utile pour progresser :
     * - 0 si structure absente / free-list OFF sans session maître
     * - sinon un n° R/RW d’un fichier Standard encore non lu
     *
     * @param preferKnown si non null, ne proposer que ces slots (phase mémorisée)
     */
    private fun nextSlotNeedingAuth(aidHex: String, preferKnown: Set<Int>?): Int? {
        val explore = _ui.value.explore
            ?.takeIf { it.aidHex.equals(aidHex, ignoreCase = true) }
            ?: return null
        val sessionKey = _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber

        fun accept(slot: Int): Boolean {
            if (slot == sessionKey) return false
            if (preferKnown != null && slot !in preferKnown) return false
            return true
        }

        // Structure : free-list OFF ou absente + pas de fichiers → maître 0
        val freeList = explore.keySettings?.bits?.freeDirectoryListWithoutMaster
        val needsStructureAuth =
            explore.files.isEmpty() &&
                (freeList == false || explore.keySettings == null) &&
                sessionKey != 0
        if (needsStructureAuth && accept(0)) return 0

        val unread = explore.files.filter { node ->
            node.dataHex == null &&
                node.settings.fileType == com.cardrw.desfire.model.FileType.STANDARD &&
                !node.settings.accessRights.isReadNever &&
                !node.settings.accessRights.isReadFree
        }
        if (unread.isEmpty() && !needsStructureAuth) return null

        // Ordre : Read spécifique avant RW, puis plus petit n°
        val slots = linkedSetOf<Int>()
        for (node in unread) {
            for (c in node.settings.accessRights.readKeyCandidates()) {
                if (accept(c.keyNo)) slots += c.keyNo
            }
        }
        if (slots.isEmpty() && needsStructureAuth && accept(0)) return 0
        return slots.minOrNull()
    }

    /**
     * AuthenticateAES avec matériau usine 00…00 sur [keyNo], puis explore.
     * Succès → mémorise le slot ; échec → false (caller gère failed set).
     */
    private suspend fun tryFactoryAuthThenExplore(
        aidHex: String,
        keyNo: Int,
        flashMessage: String?,
        fillAfter: Boolean,
    ): Boolean {
        val keyBytes = AesConstants.FACTORY_KEY.copyOf()
        _ui.update {
            it.copy(
                keyNo = keyNo,
                keyHex = Hex.encode(keyBytes),
                busy = true,
                errorMessage = null,
                statusLine = "Auth auto clé standard (n°$keyNo)…",
            )
        }
        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                client.ensureApplicationSelected(Aid.fromHex(aidHex))
                client.authenticateAesPreferEv1(keyNo, keyBytes, aidHex)
                client.authSession
            }
        }
        return result.fold(
            onSuccess = { session ->
                rememberAuth(aidHex, keyNo, keyBytes, vaultEntryId = null)
                // Succès usine : retirer d’éventuels échecs antérieurs sur ce slot
                factoryFailedSlotsByAid[aidHex.uppercase()]?.remove(keyNo)
                _ui.update {
                    it.copy(
                        authSession = session,
                        errorMessage = null,
                        statusLine = null,
                    )
                }
                syncJournal()
                runExplore(aidHex, fillRemembered = fillAfter)
                if (flashMessage != null) showAuthSuccessFlash(flashMessage)
                true
            },
            onFailure = {
                // authenticate* clear la session client dès le début
                syncJournal()
                _ui.update { it.copy(busy = false, authSession = null) }
                false
            },
        )
    }

    /**
     * Conserve le hex déjà lu sous une session précédente quand la session courante
     * n’a pas le droit de re-lire (ex. clé 3 après lecture clé 2).
     */
    private fun mergeExplorePreserveData(
        previous: ApplicationExploreResult?,
        fresh: ApplicationExploreResult,
    ): ApplicationExploreResult {
        if (previous == null || !previous.aidHex.equals(fresh.aidHex, ignoreCase = true)) {
            return fresh
        }
        val prevByNo = previous.files.associateBy { it.fileNo }
        val mergedFiles = fresh.files.map { node ->
            val prev = prevByNo[node.fileNo]
            when {
                node.dataHex != null -> node
                prev?.dataHex != null ->
                    node.copy(dataHex = prev.dataHex, dataError = null)
                else -> node
            }
        }
        return fresh.copy(
            keySettings = fresh.keySettings ?: previous.keySettings,
            files = mergedFiles,
            structureFromCache = fresh.structureFromCache || previous.structureFromCache,
        )
    }

    private fun showAuthSuccessFlash(message: String) {
        _ui.update {
            it.copy(authSuccessFlash = true, authSuccessMessage = message)
        }
        viewModelScope.launch {
            delay(AUTH_SUCCESS_FLASH_MS)
            _ui.update {
                if (it.authSuccessMessage == message) {
                    it.copy(authSuccessFlash = false, authSuccessMessage = null)
                } else {
                    it
                }
            }
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
                    client.authenticateAesPreferEv1(resolvedKeyNo, keyBytes, aidHex)
                    client.authSession
                }
            }
            result.fold(
                onSuccess = { session ->
                    clearPendingVaultSave(wipeMaterial = true)
                    var resolvedVaultId = vaultEntryId
                    var vaultNote: String? = null
                    if (saveAsVaultName != null && vaultEntryId == null) {
                        try {
                            resolvedVaultId = keyVault.create(saveAsVaultName, keyBytes)
                        } catch (e: Exception) {
                            vaultNote = "Auth OK — coffre : ${e.message}"
                        }
                    }
                    // Mémoriser ce slot (multi-clés par AID) pour re-select / fill auto
                    rememberAuth(aidHex, resolvedKeyNo, keyBytes, resolvedVaultId)
                    _ui.update {
                        it.copy(
                            authSession = session,
                            errorMessage = null,
                            statusLine = vaultNote,
                            pendingVaultSave = null,
                        )
                    }
                    syncJournal()
                    runExplore(aidHex, fillRemembered = true)
                    showAuthSuccessFlash(MANUAL_AUTH_OK_MESSAGE)
                },
                onFailure = { e ->
                    syncJournal()
                    // Hex + option « enregistrer » : proposer le coffre malgré l’échec
                    // (souvent bon matériau, mauvais n° de slot carte).
                    if (saveAsVaultName != null && vaultEntryId == null) {
                        offerVaultSaveDespiteAuthFailure(
                            displayName = saveAsVaultName,
                            keyBytes = keyBytes,
                            authError = humanize(e),
                        )
                    } else {
                        handleOpFailure(e)
                    }
                },
            )
        }
    }

    /**
     * Auth KO alors que l’utilisateur voulait sauver le matériau → dialog
     * « Enregistrer quand même ? » (slot peut être faux, secret correct).
     */
    private fun offerVaultSaveDespiteAuthFailure(
        displayName: String,
        keyBytes: ByteArray,
        authError: String,
    ) {
        pendingVaultKeyBytes?.fill(0)
        pendingVaultKeyBytes = keyBytes.copyOf()
        _ui.update {
            it.copy(
                busy = false,
                errorMessage = authError,
                statusLine = null,
                pendingVaultSave = PendingVaultSaveOffer(
                    displayName = displayName.trim().ifEmpty { keyVault.nextDefaultName() },
                ),
            )
        }
    }

    /** Confirme l’enregistrement coffre après auth refusée. */
    fun confirmPendingVaultSave() {
        val offer = _ui.value.pendingVaultSave ?: return
        val bytes = pendingVaultKeyBytes
        if (bytes == null || bytes.size != 16) {
            dismissPendingVaultSave()
            _ui.update { it.copy(errorMessage = "Matériau indisponible — resaisis la clé.") }
            return
        }
        viewModelScope.launch {
            try {
                keyVault.create(offer.displayName, bytes)
                clearPendingVaultSave(wipeMaterial = true)
                reloadVault()
                _ui.update {
                    it.copy(
                        pendingVaultSave = null,
                        statusLine = "Clé « ${offer.displayName} » enregistrée dans le coffre.",
                        errorMessage = it.errorMessage, // garde le message d’auth KO
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(errorMessage = "Coffre : ${e.message}")
                }
            }
        }
    }

    /** Refuse d’enregistrer après auth KO. */
    fun dismissPendingVaultSave() {
        clearPendingVaultSave(wipeMaterial = true)
        _ui.update { it.copy(pendingVaultSave = null) }
    }

    private fun clearPendingVaultSave(wipeMaterial: Boolean) {
        if (wipeMaterial) {
            pendingVaultKeyBytes?.fill(0)
            pendingVaultKeyBytes = null
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
            runExplore(aidHex, fillRemembered = true)
        }
    }

    /**
     * GetKeySettings / FileIDs / FileSettings / ReadData selon droits de session.
     * Fusionne avec le cache pour **ne pas effacer** les données déjà lues sous une autre clé.
     *
     * @param fillRemembered si true, enchaîne les clés mémorisées pour les fichiers encore vides.
     */
    private suspend fun runExplore(aidHex: String, fillRemembered: Boolean = true) {
        val cacheKey = aidHex.uppercase()
        // Préférer l’état UI courant, sinon cache multi-AID
        val prev = _ui.value.explore
            ?.takeIf { it.aidHex.equals(aidHex, ignoreCase = true) }
            ?: _ui.value.exploreByAid[cacheKey]
        val cachedSettings =
            if (prev != null && prev.files.isNotEmpty()) {
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
                val withKs = if (exploreResult.structureFromCache &&
                    exploreResult.keySettings == null &&
                    prev?.keySettings != null
                ) {
                    exploreResult.copy(keySettings = prev.keySettings)
                } else {
                    exploreResult
                }
                // Conserves dataHex déjà obtenus sous une autre session (clé 2 puis clé 3…)
                val merged = mergeExplorePreserveData(prev, withKs)
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
                        exploreByAid = it.exploreByAid + (cacheKey to exploreResult),
                        authSession = session,
                        statusLine = summary,
                        errorMessage = null,
                    )
                }
                syncJournal()
                if (fillRemembered) {
                    fillUnreadWithRememberedKeys(aidHex)
                }
                // U5 : GetCardUID auto si Random ID + session auth
                maybeFetchRealUidSilent()
            },
            onFailure = { e -> handleOpFailure(e) },
        )
    }

    // -------------------------------------------------------------------------
    // v1 labo — écriture (session AES EV1/EV2)
    // -------------------------------------------------------------------------

    /**
     * WriteData sur le fichier [fileNo] (hex compact).
     *
     * DESFire n’efface **pas** le reste du fichier : seuls offset…offset+len sont
     * écrasés. Si [padToFileSize] et taille connue, on étend le buffer avec des
     * `0x00` jusqu’à la taille du fichier (écriture « remplacer tout » labo).
     *
     * Mode SM déduit des FileSettings + session (Free → PLAIN, même si fichier FULL).
     */
    fun writeFileData(fileNo: Int, dataHex: String, padToFileSize: Boolean = false) {
        val clean = dataHex.replace(Regex("[^0-9a-fA-F]"), "")
        viewModelScope.launch {
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "WriteData fichier $fileNo…")
            }
            var bytes = try {
                require(clean.length % 2 == 0) { "hex impair (${clean.length})" }
                Hex.decode(clean)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(busy = false, errorMessage = "Données hex invalides : ${e.message}")
                }
                return@launch
            }
            val aidHex = _ui.value.selectedAidHex
            if (aidHex == null) {
                _ui.update {
                    it.copy(busy = false, errorMessage = "Aucune application sélectionnée.")
                }
                return@launch
            }
            val settings = _ui.value.explore?.files?.find { it.fileNo == fileNo }?.settings
            // Session peut avoir basculé (fill lecture clé R) : re-auth intention Write si besoin
            if (settings != null) {
                val plan = AuthKeyPlanner.plan(
                    AuthIntent.WriteFile(fileNo, settings.accessRights),
                    _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber,
                )
                if (plan.barrier == AuthBarrier.NEVER) {
                    _ui.update {
                        it.copy(
                            busy = false,
                            errorMessage = plan.detailMessage ?: "Écriture interdite sur ce fichier.",
                        )
                    }
                    return@launch
                }
                if (plan.barrier == AuthBarrier.NEEDS_KEY) {
                    val ok = autoAuthForPlan(
                        aidHex = aidHex,
                        plan = plan,
                        intentLabel = "écrire F$fileNo",
                    )
                    if (!ok) {
                        _ui.update {
                            it.copy(
                                busy = false,
                                errorMessage = "Auth écriture requise (W/RW) avant WriteData.",
                                openAuthSheetNonce = it.openAuthSheetNonce + 1,
                                pendingAuthPlan = plan,
                            )
                        }
                        return@launch
                    }
                }
            }
            val sessionKey = _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber
            val fileSize = settings?.sizeBytes
            var padNote = ""
            if (padToFileSize && fileSize != null) {
                when {
                    bytes.size > fileSize -> {
                        _ui.update {
                            it.copy(
                                busy = false,
                                errorMessage = "Données (${bytes.size} o) > taille fichier ($fileSize o).",
                            )
                        }
                        return@launch
                    }
                    bytes.size < fileSize -> {
                        bytes = bytes + ByteArray(fileSize - bytes.size)
                        padNote = " · pad 00 → $fileSize o"
                    }
                }
            }
            val mode = settings?.effectiveCommModeForWrite(sessionKey)
                ?: com.cardrw.desfire.model.CommMode.PLAIN
            val modeHint = when (mode) {
                com.cardrw.desfire.model.CommMode.PLAIN -> "PLAIN"
                com.cardrw.desfire.model.CommMode.MACED -> "MAC"
                com.cardrw.desfire.model.CommMode.FULL -> "FULL"
            }
            _ui.update {
                it.copy(statusLine = "WriteData fichier $fileNo ($modeHint$padNote)…")
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    // ensure = no-op si déjà sur l’AID (ne pas re-Select : invaliderait la SM)
                    client.ensureApplicationSelected(Aid.fromHex(aidHex))
                    if (client.session == null) {
                        throw DesfireProtocolException(
                            "WriteData : session perdue (re-auth auto a échoué ou probe usine). " +
                                "Ré-authentifie la clé d’écriture (W/RW) puis réessaie.",
                        )
                    }
                    client.writeData(fileNo, bytes, offset = 0, commMode = mode)
                }
            }
            result.fold(
                onSuccess = { n ->
                    _ui.update {
                        it.copy(
                            busy = false,
                            statusLine = "WriteData OK — $n o → fichier $fileNo" +
                                if (padNote.isNotEmpty()) " (fichier entier)" else " (partiel dès offset 0)",
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                    _ui.value.selectedAidHex?.let { runExplore(it, fillRemembered = true) }
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /** CreateApplication labo (AES, settings ouverts 0x0F). */
    fun createApplicationLab(aidHex: String, maxKeys: Int = 3) {
        viewModelScope.launch {
            if (!ensurePiccMasterAesSession()) return@launch
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "CreateApplication…")
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.createApplication(
                        aid = Aid.fromHex(aidHex),
                        keySettings = 0x0F,
                        maxKeys = maxKeys.coerceIn(1, 14),
                        aesCrypto = true,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    _ui.update {
                        it.copy(
                            statusLine = "CreateApplication OK — AID ${aidHex.uppercase()}",
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                    // readIdentity → Select PICC invalide la session : re-auth auto
                    refreshIdentityAfterStructureChange(restorePiccMaster = true)
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /**
     * ChangeKey AES (0xC4) — session AES EV1/EV2 (PICC ou app sélectionnée).
     * Si on change le slot authentifié : session morte, re-auth auto avec la nouvelle clé.
     */
    fun changeKeyAesLab(keyNo: Int, newAesKeyHex: String) {
        viewModelScope.launch {
            val clean = newAesKeyHex.replace(Regex("[^0-9a-fA-F]"), "")
            val newKey = try {
                require(clean.length == 32) { "clé AES = 32 hex, got ${clean.length}" }
                Hex.decode(clean)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(errorMessage = "Nouvelle clé AES invalide : ${e.message}")
                }
                return@launch
            }
            val aid = _ui.value.selectedAidHex ?: run {
                _ui.update { it.copy(errorMessage = "Sélectionne PICC ou une app d’abord.") }
                return@launch
            }
            _ui.update {
                it.copy(
                    busy = true,
                    errorMessage = null,
                    statusLine = "ChangeKey AES slot $keyNo…",
                )
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.ensureApplicationSelected(Aid.fromHex(aid))
                    client.changeKeyAes(keyNo, newKey, keyVersion = 0)
                }
            }
            result.fold(
                onSuccess = {
                    val sessionKey = _ui.value.authSession?.keyNumber
                    rememberAuth(aid, keyNo, newKey, vaultEntryId = null)
                    factoryFailedSlotsByAid[aid.uppercase()]?.remove(keyNo)
                    syncJournal()
                    if (sessionKey == keyNo) {
                        // Session morte — re-auth avec la nouvelle clé
                        _ui.update {
                            it.copy(
                                authSession = null,
                                statusLine = "ChangeKey AES OK — re-auth slot $keyNo…",
                                keyHex = clean.uppercase(),
                                keyNo = keyNo,
                            )
                        }
                        val reauth = withContext(Dispatchers.IO) {
                            withLiveClient { client ->
                                client.ensureApplicationSelected(Aid.fromHex(aid))
                                client.authenticateAesPreferEv1(keyNo, newKey, aid)
                            }
                        }
                        reauth.fold(
                            onSuccess = { sess ->
                                _ui.update {
                                    it.copy(
                                        busy = false,
                                        authSession = sess.toAuthSession(),
                                        selectedAidHex = aid,
                                        statusLine = "ChangeKey AES OK — session clé $keyNo",
                                        authSuccessFlash = true,
                                        authSuccessMessage = "ChangeKey AES k$keyNo",
                                        errorMessage = null,
                                    )
                                }
                                syncJournal()
                                runExplore(aid, fillRemembered = true)
                            },
                            onFailure = { e ->
                                _ui.update {
                                    it.copy(
                                        busy = false,
                                        statusLine = "ChangeKey AES OK — re-auth à faire",
                                        errorMessage = "Clé changée mais auth a échoué : ${e.message}",
                                    )
                                }
                                syncJournal()
                            },
                        )
                    } else {
                        _ui.update {
                            it.copy(
                                busy = false,
                                statusLine = "ChangeKey AES OK — slot $keyNo (session k$sessionKey intacte)",
                                errorMessage = null,
                            )
                        }
                    }
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /** Liste des dumps locaux (noms) pour sheet restore. */
    fun listDumpFileNames(): List<String> = dumpRepository.list().map { it.fileName }

    /**
     * Dry-run restore (CDC §8.5) — remplit [CardUiState.restorePreviewLines].
     */
    fun previewRestoreDump(
        fileName: String,
        mode: DumpRestorePlanner.Mode = DumpRestorePlanner.Mode.STRUCTURE_AND_DATA,
        formatFirst: Boolean = false,
    ) {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { dumpRepository.readJson(fileName) }
                    ?: error("Dump introuvable : $fileName")
                val doc = CardDumpBuilder.parseJson(json)
                val plan = DumpRestorePlanner.plan(doc, mode, formatFirst)
                _ui.update {
                    it.copy(
                        restorePreviewFileName = fileName,
                        restorePreviewLines = plan.steps.map { s -> s.label },
                        restorePreviewWarnings = plan.warnings,
                        errorMessage = null,
                        statusLine = "Dry-run restore : ${plan.actionableCount} op(s) · $fileName",
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        errorMessage = "Dry-run restore : ${e.message}",
                        restorePreviewLines = emptyList(),
                        restorePreviewWarnings = emptyList(),
                        restorePreviewFileName = null,
                    )
                }
            }
        }
    }

    fun clearRestorePreview() {
        _ui.update {
            it.copy(
                restorePreviewLines = emptyList(),
                restorePreviewWarnings = emptyList(),
                restorePreviewFileName = null,
            )
        }
    }

    /**
     * Exécute le plan de restore sur la carte live (labo AES usine).
     *
     * **SelectApplication tue la session auth** : ne jamais `select` avant Create/Write
     * sans re-auth ; utiliser [ensureApplicationSelected] + [ensurePiccMasterAesSession] /
     * [ensureAppMasterAesSession] qui re-auth si le client live n’a plus de SM.
     *
     * Erreur auth AES / DES → arrêt immédiat (pas 16× le même message).
     */
    fun executeRestoreDump(
        fileName: String,
        mode: DumpRestorePlanner.Mode = DumpRestorePlanner.Mode.STRUCTURE_AND_DATA,
        formatFirst: Boolean = false,
    ) {
        viewModelScope.launch {
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "Restore dump…")
            }
            try {
                val json = withContext(Dispatchers.IO) { dumpRepository.readJson(fileName) }
                    ?: error("Dump introuvable : $fileName")
                val doc = CardDumpBuilder.parseJson(json)
                val plan = DumpRestorePlanner.plan(doc, mode, formatFirst)
                val errors = mutableListOf<String>()
                var done = 0

                if (!ensurePiccMasterAesSession()) {
                    // errorMessage déjà posé par ensure (DES / AE / …)
                    val msg = _ui.value.errorMessage
                        ?: "Restore : auth master PICC AES requise (usine 00…00 ou mémorisée). " +
                        "Si carte vierge DES → « Basculer master PICC en AES »."
                    _ui.update {
                        it.copy(busy = false, errorMessage = msg)
                    }
                    return@launch
                }

                fun isFatalAuthError(msg: String?): Boolean {
                    if (msg == null) return false
                    val m = msg.lowercase()
                    return m.contains("session aes") ||
                        m.contains("authentifie") ||
                        m.contains("des usine") ||
                        m.contains("authentication") ||
                        m.contains("0xae")
                }

                for (step in plan.steps) {
                    when (step) {
                        is DumpRestorePlanner.Step.Skip -> continue
                        is DumpRestorePlanner.Step.FormatPicc -> {
                            _ui.update { it.copy(statusLine = step.label) }
                            // Auth d’abord, puis format **sans** re-Select (Select tue la SM)
                            if (!ensurePiccMasterAesSession()) {
                                _ui.update {
                                    it.copy(
                                        busy = false,
                                        errorMessage = _ui.value.errorMessage
                                            ?: "FormatPICC : session AES PICC requise.",
                                    )
                                }
                                return@launch
                            }
                            val r = withContext(Dispatchers.IO) {
                                withLiveClient { client ->
                                    client.ensureApplicationSelected(Aid.PICC)
                                    if (client.session == null ||
                                        client.session!!.smLevel == SecureMessagingLevel.DES_LEGACY
                                    ) {
                                        val key = AesConstants.FACTORY_KEY.copyOf()
                                        client.authenticateAesPreferEv1(
                                            keyNo = 0,
                                            key = key,
                                            aidHex = "000000",
                                            allowDesFactoryFallback = false,
                                        )
                                    }
                                    client.formatPicc()
                                }
                            }
                            if (r.isFailure) {
                                _ui.update {
                                    it.copy(
                                        busy = false,
                                        errorMessage = "FormatPICC KO : ${r.exceptionOrNull()?.message}",
                                    )
                                }
                                return@launch
                            }
                            if (!ensurePiccMasterAesSession()) {
                                _ui.update {
                                    it.copy(
                                        busy = false,
                                        errorMessage = "Format OK mais re-auth PICC AES a échoué.",
                                    )
                                }
                                return@launch
                            }
                            done++
                        }
                        is DumpRestorePlanner.Step.CreateApplication -> {
                            _ui.update { it.copy(statusLine = step.label) }
                            if (!ensurePiccMasterAesSession()) {
                                val msg = _ui.value.errorMessage
                                    ?: "CreateApp ${step.aidHex} : pas de session PICC AES"
                                _ui.update {
                                    it.copy(busy = false, errorMessage = msg)
                                }
                                return@launch
                            }
                            val r = withContext(Dispatchers.IO) {
                                withLiveClient { client ->
                                    // ensure (pas select forcé). Si Select a eu lieu → SM morte → re-auth.
                                    client.ensureApplicationSelected(Aid.PICC)
                                    if (client.session == null ||
                                        client.session!!.smLevel == SecureMessagingLevel.DES_LEGACY
                                    ) {
                                        val key = AesConstants.FACTORY_KEY.copyOf()
                                        client.authenticateAesPreferEv1(
                                            keyNo = 0,
                                            key = key,
                                            aidHex = "000000",
                                            allowDesFactoryFallback = false,
                                        )
                                    }
                                    client.createApplication(
                                        aid = Aid.fromHex(step.aidHex),
                                        keySettings = step.keySettings,
                                        maxKeys = step.maxKeys,
                                        aesCrypto = true,
                                    )
                                }
                            }
                            if (r.isFailure) {
                                val em = r.exceptionOrNull()?.message
                                if (isFatalAuthError(em)) {
                                    _ui.update {
                                        it.copy(
                                            busy = false,
                                            errorMessage = "Restore stoppé (auth) : $em",
                                            statusLine = "Restore — $done étape(s) avant échec auth",
                                        )
                                    }
                                    return@launch
                                }
                                errors += "CreateApp ${step.aidHex}: $em"
                            } else {
                                done++
                            }
                        }
                        is DumpRestorePlanner.Step.SelectApplication -> {
                            _ui.update { it.copy(statusLine = step.label) }
                            val ok = ensureAppMasterAesSession(step.aidHex)
                            if (!ok) {
                                // Après CreateApp, auth app usine 00…00 ; si KO on note et on continue
                                // (CreateFile retentera). Pas d’arrêt global.
                                errors += "Select/auth ${step.aidHex} KO"
                            } else {
                                done++
                            }
                        }
                        is DumpRestorePlanner.Step.CreateStdDataFile -> {
                            _ui.update { it.copy(statusLine = step.label) }
                            if (!ensureAppMasterAesSession(step.aidHex)) {
                                errors += "CreateFile ${step.aidHex}/F${step.fileNo} : auth KO"
                                continue
                            }
                            val r = withContext(Dispatchers.IO) {
                                withLiveClient { client ->
                                    // ensureApp a déjà Select+auth ; re-select tuerait la SM
                                    if (client.selectedAid?.hex?.equals(step.aidHex, true) != true ||
                                        client.session == null
                                    ) {
                                        error("Session app ${step.aidHex} absente avant CreateFile")
                                    }
                                    client.createStdDataFile(
                                        fileNo = step.fileNo,
                                        fileSize = step.sizeBytes,
                                        commSettings = step.commSettings,
                                        accessRights = step.accessRights,
                                    )
                                }
                            }
                            if (r.isFailure) {
                                val em = r.exceptionOrNull()?.message
                                if (isFatalAuthError(em)) {
                                    _ui.update {
                                        it.copy(
                                            busy = false,
                                            errorMessage = "Restore stoppé (auth) : $em",
                                        )
                                    }
                                    return@launch
                                }
                                errors += "CreateFile ${step.aidHex}/F${step.fileNo}: $em"
                            } else {
                                done++
                            }
                        }
                        is DumpRestorePlanner.Step.WriteData -> {
                            _ui.update { it.copy(statusLine = step.label) }
                            // Auth avec clé W/RW (ex. W=2), pas master 0 si hors droits
                            if (!ensureAppWriteAesSession(step.aidHex, step.accessRights)) {
                                errors += "Write ${step.aidHex}/F${step.fileNo} : " +
                                    "auth W/RW KO (droits 0x${step.accessRights.toString(16)} — " +
                                    "clé non usine ?)"
                                continue
                            }
                            val rights = AccessRights.parse(step.accessRights)
                            val sessionKey = _ui.value.authSession?.keyNumber
                            // freefare : sans clé W → PLAIN même si fichier FULL
                            val mode = if (rights.sessionHasWriteKey(sessionKey)) {
                                CommMode.fromWire(step.commSettings)
                            } else {
                                CommMode.PLAIN
                            }
                            val bytes = Hex.decode(step.dataHex)
                            val r = withContext(Dispatchers.IO) {
                                withLiveClient { client ->
                                    if (client.selectedAid?.hex?.equals(step.aidHex, true) != true ||
                                        client.session == null
                                    ) {
                                        error("Session app ${step.aidHex} absente avant Write")
                                    }
                                    client.writeData(
                                        step.fileNo,
                                        bytes,
                                        offset = 0,
                                        commMode = mode,
                                    )
                                }
                            }
                            if (r.isFailure) {
                                // 0xAE tue la session — ne pas enchaîner sans re-auth
                                val em = r.exceptionOrNull()?.message
                                errors += "Write ${step.aidHex}/F${step.fileNo}: $em"
                                _ui.update { it.copy(authSession = null) }
                            } else {
                                done++
                            }
                        }
                    }
                    syncJournal()
                }

                // Refresh identity + moniteur
                refreshIdentityAfterStructureChange(restorePiccMaster = true)
                val summary = if (errors.isEmpty()) {
                    "Restore OK — $done étape(s)"
                } else {
                    "Restore terminé — $done OK · ${errors.size} erreur(s)"
                }
                _ui.update {
                    it.copy(
                        busy = false,
                        statusLine = summary,
                        errorMessage = errors.takeIf { e -> e.isNotEmpty() }
                            ?.joinToString("\n")
                            ?.take(500),
                        restorePreviewFileName = fileName,
                        restorePreviewLines = plan.steps.map { s -> s.label },
                        restorePreviewWarnings = plan.warnings + errors.map { "ERR: $it" },
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        busy = false,
                        errorMessage = "Restore : ${e.message}",
                    )
                }
            }
        }
    }

    /** Auth master app (clé 0) usine ou mémorisée — CreateFile / structure. */
    private suspend fun ensureAppMasterAesSession(aidHex: String): Boolean =
        ensureAppSlotAesSession(aidHex, preferredKeyNos = listOf(0))

    /**
     * Auth pour **WriteData** restore : slots W puis RW (pas master 0 si hors droits).
     * Ex. wire rights `20 12` → R=1 W=2 RW=2 Ch=0 → auth **k2**.
     * Matériau : mémorisé puis usine 00…00 (labo).
     */
    private suspend fun ensureAppWriteAesSession(aidHex: String, accessRightsLogical: Int): Boolean {
        val rights = AccessRights.parse(accessRightsLogical)
        if (rights.isWriteFree) {
            // Free write : n’importe quelle session AES app (master 0 labo) + PLAIN côté write
            return ensureAppSlotAesSession(aidHex, preferredKeyNos = listOf(0))
        }
        val candidates = rights.writeKeyCandidates().map { it.keyNo }
        if (candidates.isEmpty()) return false
        // Session courante déjà W/RW ?
        val sess = _ui.value.authSession
        if (sess?.authenticated == true &&
            sess.aidHex.equals(aidHex, ignoreCase = true) &&
            rights.canWriteWith(sess.keyNumber) &&
            sess.smLevel != SecureMessagingLevel.DES_LEGACY
        ) {
            val liveOk = withContext(Dispatchers.IO) {
                withLiveClient { c ->
                    c.session != null &&
                        c.selectedAid?.hex?.equals(aidHex, true) == true &&
                        rights.canWriteWith(c.session?.keyNumber)
                }.getOrDefault(false)
            }
            if (liveOk) return true
        }
        return ensureAppSlotAesSession(aidHex, preferredKeyNos = candidates)
    }

    /**
     * Auth AES sur [aidHex] en essayant les slots [preferredKeyNos] (mémorisé puis usine).
     * Vérifie la session **live** (Select tue la SM).
     */
    private suspend fun ensureAppSlotAesSession(
        aidHex: String,
        preferredKeyNos: List<Int>,
    ): Boolean {
        val aid = aidHex.uppercase()
        val slots = preferredKeyNos.map { it and 0x0F }.distinct().filter { it in 0..13 }
        if (slots.isEmpty()) return false

        // Déjà live OK sur un des slots
        val liveKey = withContext(Dispatchers.IO) {
            withLiveClient { c ->
                val s = c.session
                if (s != null &&
                    c.selectedAid?.hex?.equals(aid, true) == true &&
                    s.smLevel != SecureMessagingLevel.DES_LEGACY &&
                    (s.keyNumber and 0x0F) in slots
                ) {
                    s.keyNumber and 0x0F
                } else {
                    null
                }
            }.getOrNull()
        }
        if (liveKey != null) {
            val live = withContext(Dispatchers.IO) {
                withLiveClient { it.authSession }.getOrNull()
            }
            if (live != null) {
                _ui.update {
                    it.copy(authSession = live, selectedAidHex = aid, errorMessage = null)
                }
            }
            return true
        }

        for (keyNo in slots) {
            val remembered = rememberedKeysByAid[aid]?.get(keyNo)
            if (remembered != null) {
                val ok = silentAuthThenExplore(
                    aidHex = aid,
                    remembered = remembered,
                    flashMessage = null,
                    fillRemembered = false,
                    allowDesFactoryFallback = false,
                )
                if (ok) {
                    val after = _ui.value.authSession
                    if (after?.keyNumber == keyNo &&
                        after.smLevel != SecureMessagingLevel.DES_LEGACY
                    ) {
                        return true
                    }
                }
            }
            // Usine 00…00 sur ce slot
            val keyBytes = AesConstants.FACTORY_KEY.copyOf()
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.ensureApplicationSelected(Aid.fromHex(aid))
                    // Select a pu tuer une ancienne session
                    client.authenticateAesPreferEv1(
                        keyNo = keyNo,
                        key = keyBytes,
                        aidHex = aid,
                        allowDesFactoryFallback = false,
                    )
                    client.authSession
                }
            }
            val ok = result.fold(
                onSuccess = { session ->
                    if (session == null ||
                        session.smLevel == SecureMessagingLevel.DES_LEGACY
                    ) {
                        false
                    } else {
                        rememberAuth(aid, keyNo, keyBytes, vaultEntryId = null)
                        _ui.update {
                            it.copy(
                                authSession = session,
                                selectedAidHex = aid,
                                errorMessage = null,
                            )
                        }
                        syncJournal()
                        true
                    }
                },
                onFailure = {
                    syncJournal()
                    false
                },
            )
            if (ok) return true
        }
        return false
    }

    /** CreateStdDataFile labo (FULL, droits Free 0xEEEE par défaut). */
    fun createStdFileLab(
        fileNo: Int,
        sizeBytes: Int = 16,
        commSettings: Int = 0x03,
        accessRights: Int = 0xEEEE,
    ) {
        viewModelScope.launch {
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "CreateStdDataFile $fileNo…")
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.createStdDataFile(
                        fileNo = fileNo,
                        fileSize = sizeBytes,
                        commSettings = commSettings,
                        accessRights = accessRights,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    // Message stable : runExplore écraserait sinon avec « Exploration : n fichier(s) ».
                    val okMsg = "CreateStdDataFile OK — F$fileNo ($sizeBytes B)"
                    _ui.update {
                        it.copy(
                            busy = false,
                            statusLine = okMsg,
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                    _ui.value.selectedAidHex?.let { runExplore(it, fillRemembered = true) }
                    _ui.update { it.copy(statusLine = okMsg, busy = false, errorMessage = null) }
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /**
     * ChangeKey DES→AES sur master PICC (carte vierge).
     * Après succès : session DES invalidée, re-auth AES auto avec [newAesKeyHex].
     */
    fun changeKeyDesToAesLab(newAesKeyHex: String, keyNo: Int = 0) {
        viewModelScope.launch {
            val clean = newAesKeyHex.replace(Regex("[^0-9a-fA-F]"), "")
            val newKey = try {
                require(clean.length == 32) { "clé AES = 32 hex, got ${clean.length}" }
                Hex.decode(clean)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(errorMessage = "Nouvelle clé AES invalide : ${e.message}")
                }
                return@launch
            }
            _ui.update {
                it.copy(
                    busy = true,
                    errorMessage = null,
                    statusLine = "ChangeKey DES→AES (clé $keyNo)…",
                )
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.changeKeyDesToAes(keyNo, newKey, keyVersion = 0)
                }
            }
            result.fold(
                onSuccess = {
                    // Session DES morte ; mémoriser la nouvelle clé AES pour re-auth
                    val aid = "000000"
                    rememberAuth(aid, keyNo, newKey, vaultEntryId = null)
                    factoryFailedSlotsByAid.remove(aid.uppercase())
                    _ui.update {
                        it.copy(
                            authSession = null,
                            statusLine = "ChangeKey DES→AES OK — re-auth AES…",
                            errorMessage = null,
                            keyHex = clean.uppercase(),
                            keyNo = keyNo,
                        )
                    }
                    syncJournal()
                    // Re-auth AES immédiat (même slot) pour débloquer Create/Write
                    val reauth = withContext(Dispatchers.IO) {
                        withLiveClient { client ->
                            client.selectApplication(Aid.PICC)
                            client.authenticateAesPreferEv1(
                                keyNo = keyNo,
                                key = newKey,
                                aidHex = aid,
                            )
                        }
                    }
                    reauth.fold(
                        onSuccess = { sess ->
                            _ui.update {
                                it.copy(
                                    busy = false,
                                    authSession = sess.toAuthSession(),
                                    selectedAidHex = aid,
                                    statusLine = "Master PICC basculée en AES — session AES OK",
                                    authSuccessFlash = true,
                                    authSuccessMessage = "PICC master AES (après DES→AES)",
                                    errorMessage = null,
                                )
                            }
                            syncJournal()
                            runExplore(aid, fillRemembered = true)
                        },
                        onFailure = { e ->
                            _ui.update {
                                it.copy(
                                    busy = false,
                                    statusLine = "ChangeKey DES→AES OK — re-auth AES à faire",
                                    errorMessage = "Bascule OK mais auth AES a échoué : ${e.message}",
                                )
                            }
                            syncJournal()
                        },
                    )
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /**
     * FormatPICC (0xFC) — efface toutes les apps.
     * Session master PICC AES (clé 0) requise. Master key PICC conservée.
     */
    fun formatPiccLab() {
        viewModelScope.launch {
            if (!ensurePiccMasterAesSession()) return@launch
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "FormatPICC…")
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.formatPicc()
                }
            }
            result.fold(
                onSuccess = {
                    // Apps disparues : wipe mémoire session (sauf PICC master)
                    rememberedKeysByAid.keys
                        .filter { !it.equals("000000", ignoreCase = true) }
                        .toList()
                        .forEach { aid ->
                            rememberedKeysByAid.remove(aid)?.values?.forEach { it.keyBytes.fill(0) }
                            lastKeyNoByAid.remove(aid)
                            factoryFailedSlotsByAid.remove(aid)
                        }
                    _ui.update {
                        it.copy(
                            authSession = null,
                            selectedAidHex = "000000",
                            explore = null,
                            exploreByAid = emptyMap(),
                            statusLine = "FormatPICC OK — toutes les apps effacées (master PICC inchangée)",
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                    refreshIdentityAfterStructureChange(restorePiccMaster = true)
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /** DeleteApplication (PICC master AES). */
    fun deleteApplicationLab(aidHex: String) {
        viewModelScope.launch {
            if (!ensurePiccMasterAesSession()) return@launch
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "DeleteApplication…")
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.deleteApplication(Aid.fromHex(aidHex))
                }
            }
            result.fold(
                onSuccess = {
                    rememberedKeysByAid.remove(aidHex.uppercase())?.values?.forEach {
                        it.keyBytes.fill(0)
                    }
                    lastKeyNoByAid.remove(aidHex.uppercase())
                    _ui.update {
                        it.copy(
                            statusLine = "DeleteApplication OK — $aidHex",
                            errorMessage = null,
                            selectedAidHex = if (it.selectedAidHex.equals(aidHex, true)) {
                                "000000"
                            } else {
                                it.selectedAidHex
                            },
                            explore = null,
                        )
                    }
                    syncJournal()
                    refreshIdentityAfterStructureChange(restorePiccMaster = true)
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /** DeleteFile. */
    fun deleteFileLab(fileNo: Int) {
        viewModelScope.launch {
            _ui.update {
                it.copy(busy = true, errorMessage = null, statusLine = "DeleteFile $fileNo…")
            }
            val result = withContext(Dispatchers.IO) {
                withLiveClient { client ->
                    client.deleteFile(fileNo)
                }
            }
            result.fold(
                onSuccess = {
                    _ui.update {
                        it.copy(
                            busy = false,
                            statusLine = "DeleteFile OK — fichier $fileNo",
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                    _ui.value.selectedAidHex?.let { runExplore(it, fillRemembered = true) }
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
    }

    /**
     * Relit GetVersion / apps. [readIdentity] fait Select PICC → session morte.
     * Si [restorePiccMaster], re-auth auto pour enchaîner Create/Format sans saisie.
     */
    private suspend fun refreshIdentityAfterStructureChange(restorePiccMaster: Boolean = false) {
        val statusKeep = _ui.value.statusLine
        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                val uid = liveUid
                client.readIdentity(uid)
            }
        }
        result.fold(
            onSuccess = { identity ->
                _ui.update {
                    it.copy(
                        identity = identity,
                        exploreByAid = emptyMap(),
                        explore = null,
                        authSession = null,
                        statusLine = statusKeep,
                    )
                }
                syncJournal()
                if (restorePiccMaster) {
                    ensurePiccMasterAesSession()
                    _ui.update {
                        it.copy(
                            busy = false,
                            statusLine = statusKeep,
                        )
                    }
                } else {
                    _ui.update { it.copy(busy = false) }
                }
            },
            onFailure = {
                _ui.update { it.copy(busy = false) }
            },
        )
    }

    /**
     * GetCardUID après auth — utile si Random ID.
     * U5 : aussi déclenché auto via [maybeFetchRealUidSilent] ; ce bouton reste en secours.
     */
    fun fetchRealUid() {
        if (_ui.value.authSession?.authenticated != true) {
            _ui.update { it.copy(errorMessage = "Authentifie d’abord (GetCardUID nécessite une session).") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, errorMessage = null, statusLine = "GetCardUID…") }
            val ok = maybeFetchRealUidSilent(force = true)
            if (!ok && _ui.value.realUidHex == null) {
                // maybeFetchRealUidSilent a déjà posé error si force
            } else {
                _ui.update { it.copy(busy = false, statusLine = null) }
            }
        }
    }

    /**
     * U5 — GetCardUID silencieux si Random ID et UID réel encore inconnu.
     * @param force true = bouton manuel (rapporte l’erreur)
     * @return true si UID obtenu ou déjà connu / non applicable
     */
    private suspend fun maybeFetchRealUidSilent(force: Boolean = false): Boolean {
        val state = _ui.value
        if (state.realUidHex != null) return true
        if (state.authSession?.authenticated != true) {
            if (force) {
                _ui.update {
                    it.copy(
                        busy = false,
                        errorMessage = "Authentifie d’abord (GetCardUID nécessite une session).",
                    )
                }
            }
            return false
        }
        if (!force && state.identity?.uidKind != UidKind.RANDOM) return true

        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                Hex.encode(client.getCardUid())
            }
        }
        return result.fold(
            onSuccess = { uid ->
                _ui.update {
                    it.copy(
                        realUidHex = uid,
                        busy = if (force) false else it.busy,
                        errorMessage = if (force) null else it.errorMessage,
                        statusLine = if (force) null else it.statusLine,
                    )
                }
                syncJournal()
                true
            },
            onFailure = { e ->
                if (force) {
                    handleOpFailure(e)
                }
                // Auto : silencieux (carte / SM peut refuser)
                false
            },
        )
    }

    fun resetToWaiting() {
        viewModelScope.launch {
            nfcMutex.withLock { closeLive() }
            clearRememberedAuth()
            _ui.value = CardUiState()
        }
    }

    // -------------------------------------------------------------------------

    private suspend fun connectAndRead(tag: Tag) {
        closeLive()
        clearRememberedAuth()
        _ui.update {
            it.copy(
                phase = CardPhase.Reading,
                errorMessage = null,
                statusLine = null,
                identity = null,
                selectedAidHex = null,
                authSession = null,
                explore = null,
                exploreByAid = emptyMap(),
                realUidHex = null,
                pendingVaultSave = null,
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

    companion object {
        /** Slot carte pour auto-auth (clé maître app / PICC). */
        const val DEFAULT_AUTH_KEY_NO = 0
        /** Durée d’affichage du bandeau d’auth réussie. */
        const val AUTH_SUCCESS_FLASH_MS = 3_200L
        const val MANUAL_AUTH_OK_MESSAGE = "Authentification réussie"
        /** Bases AID labo pour [suggestNextAidHex] (F001xx, F002xx, A000xx). */
        private val LAB_AID_BASES = intArrayOf(0xF00100, 0xF00200, 0xA00000)
    }
}
