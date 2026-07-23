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
import com.cardrw.desfire.model.readKeyCandidates
import com.cardrw.desfire.session.AuthSession
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

    /**
     * CTA fichier : re-auth auto si possible —
     * 1) clé mémorisée pour un slot candidat
     * 2) sinon **clé standard usine** (00…00) sur le slot préféré / candidat
     * Retourne true si l’auto-auth a démarré (pas de sheet).
     */
    fun tryAuthFileWithRemembered(node: FileNode): Boolean {
        val aidHex = _ui.value.selectedAidHex ?: return false
        val plan = authPlanForFile(node)
        if (plan.barrier != AuthBarrier.NEEDS_KEY) return false
        val aidKey = aidHex.uppercase()
        val remembered = rememberedKeysByAid[aidKey].orEmpty()
        val prefer = plan.preferKeyNo
        val candidateNos = buildList {
            prefer?.let { add(it) }
            plan.candidates.forEach { add(it.keyNo) }
        }.distinct()

        // 1) Matériau déjà validé pour ce slot
        val memKeyNo = candidateNos.firstOrNull { remembered.containsKey(it) }
        if (memKeyNo != null) {
            val entry = remembered[memKeyNo] ?: return false
            viewModelScope.launch {
                silentAuthThenExplore(aidHex, entry, flashMessage = REMEMBERED_AUTH_OK_MESSAGE)
            }
            return true
        }

        // 2) Clé standard usine sur un slot candidat pas encore raté
        val failedFactory = factoryFailedSlotsByAid[aidKey].orEmpty()
        val factoryKeyNo = candidateNos.firstOrNull { it !in failedFactory } ?: return false
        viewModelScope.launch {
            val ok = tryFactoryAuthThenExplore(
                aidHex = aidHex,
                keyNo = factoryKeyNo,
                flashMessage = DEFAULT_AUTH_OK_MESSAGE,
                fillAfter = true,
            )
            if (!ok) {
                // Laisser l’UI proposer la sheet (nonce) pour saisie manuelle
                _ui.update {
                    it.copy(
                        statusLine = "Clé standard refusée (n°$factoryKeyNo) — saisie manuelle.",
                        openAuthSheetNonce = it.openAuthSheetNonce + 1,
                    )
                }
            }
        }
        return true
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
     *
     * @param tryDefaultAuth double-tap : tente clé par défaut (slot 0 + usine 00…00) ;
     *   succès → flash UI ; échec silencieux → explore puis sheet auth standard.
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
                    when {
                        tryDefaultAuth -> tryDefaultAuthThenExplore(aidHex)
                        hasRememberedKeys(cacheKey) -> tryRememberedAuthThenExplore(aidHex)
                        else -> runExplore(aidHex, fillRemembered = true)
                    }
                },
                onFailure = { e -> handleOpFailure(e, selectedAidHex = aidHex) },
            )
        }
    }

    /**
     * Authentifie avec la **clé par défaut labo** : slot carte n°0 + matériau usine `00…00`.
     * Succès → flash UI + mémorisation pour re-select.
     * Échec → si des clés étaient déjà mémorisées pour l’AID, les tenter ; sinon sheet auth.
     */
    private suspend fun tryDefaultAuthThenExplore(aidHex: String) {
        val keyNo = DEFAULT_AUTH_KEY_NO
        val keyBytes = AesConstants.FACTORY_KEY.copyOf()
        val keyHex = Hex.encode(keyBytes)
        _ui.update {
            it.copy(
                keyNo = keyNo,
                keyHex = keyHex,
                busy = true,
                errorMessage = null,
                statusLine = "Auth clé par défaut (n°$keyNo, usine)…",
            )
        }
        val result = withContext(Dispatchers.IO) {
            withLiveClient { client ->
                client.ensureApplicationSelected(Aid.fromHex(aidHex))
                client.authenticateAesPreferEv1(keyNo, keyBytes, aidHex)
                client.authSession
            }
        }
        result.fold(
            onSuccess = { session ->
                rememberAuth(aidHex, keyNo, keyBytes, vaultEntryId = null)
                _ui.update {
                    it.copy(
                        authSession = session,
                        errorMessage = null,
                        statusLine = null,
                    )
                }
                syncJournal()
                runExplore(aidHex, fillRemembered = true)
                showAuthSuccessFlash(DEFAULT_AUTH_OK_MESSAGE)
            },
            onFailure = {
                syncJournal()
                if (hasRememberedKeys(aidHex)) {
                    tryRememberedAuthThenExplore(aidHex)
                } else {
                    _ui.update {
                        it.copy(
                            authSession = null,
                            errorMessage = null,
                            statusLine = null,
                            openAuthSheetNonce = it.openAuthSheetNonce + 1,
                        )
                    }
                    runExplore(aidHex, fillRemembered = false)
                }
            },
        )
    }

    /**
     * Rejoue la dernière clé OK pour [aidHex], puis complète les fichiers encore vides
     * avec les **autres** clés mémorisées (multi-droits R/W).
     */
    private suspend fun tryRememberedAuthThenExplore(aidHex: String) {
        val preferred = preferredRemembered(aidHex)
        if (preferred == null) {
            runExplore(aidHex, fillRemembered = true)
            return
        }
        val ok = silentAuthThenExplore(
            aidHex = aidHex,
            remembered = preferred,
            flashMessage = REMEMBERED_AUTH_OK_MESSAGE,
            fillRemembered = true,
        )
        if (!ok) {
            forgetKey(aidHex, preferred.keyNo)
            // Essayer une autre clé mémorisée
            val fallback = preferredRemembered(aidHex)
            if (fallback != null) {
                silentAuthThenExplore(
                    aidHex = aidHex,
                    remembered = fallback,
                    flashMessage = REMEMBERED_AUTH_OK_MESSAGE,
                    fillRemembered = true,
                )
            } else {
                _ui.update {
                    it.copy(
                        authSession = null,
                        errorMessage = null,
                        statusLine = "Clé mémorisée refusée — authentifie à nouveau.",
                    )
                }
                runExplore(aidHex, fillRemembered = false)
            }
        }
    }

    /**
     * Auth silencieuse avec matériau mémorisé → explore (+ option fill multi-clés).
     * @return true si auth OK
     */
    private suspend fun silentAuthThenExplore(
        aidHex: String,
        remembered: RememberedAppAuth,
        flashMessage: String?,
        fillRemembered: Boolean = true,
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
                client.authenticateAesPreferEv1(keyNo, keyBytes, aidHex)
                client.authSession
            }
        }
        return result.fold(
            onSuccess = { session ->
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
                _ui.update { it.copy(busy = false) }
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
     * Phase 2 : tenter la **clé standard** (usine 00…00) sur les slots encore utiles.
     * Silencieux si refus (carte re-encodée) — pas de sheet spam.
     */
    private suspend fun fillWithFactoryKey(aidHex: String) {
        val aidKey = aidHex.uppercase()
        val failed = factoryFailedSlotsByAid.getOrPut(aidKey) { mutableSetOf() }
        var guard = 0
        while (guard++ < 8) {
            val known = rememberedKeysByAid[aidKey]?.keys.orEmpty()
            val nextKeyNo = nextSlotNeedingAuth(aidHex, preferKnown = null)
                ?.takeUnless { it in known || it in failed }
                ?: break

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
                continue
            }
            // Succès : flash sobre une seule fois si lecture avancée
            if (guard == 1) {
                showAuthSuccessFlash(DEFAULT_AUTH_OK_MESSAGE)
            }
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
                syncJournal()
                _ui.update { it.copy(busy = false) }
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
            val sessionKey = _ui.value.authSession?.takeIf { it.authenticated }?.keyNumber
            val settings = _ui.value.explore?.files?.find { it.fileNo == fileNo }?.settings
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
                            busy = false,
                            statusLine = "CreateApplication OK — AID ${aidHex.uppercase()}",
                            errorMessage = null,
                        )
                    }
                    syncJournal()
                    // Re-lire identité pour rafraîchir la liste d’apps
                    refreshIdentityAfterStructureChange()
                },
                onFailure = { e -> handleOpFailure(e) },
            )
        }
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
                    _ui.update {
                        it.copy(
                            busy = false,
                            statusLine = "CreateStdDataFile OK — fichier $fileNo ($sizeBytes o)",
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

    /** DeleteApplication (PICC master AES). */
    fun deleteApplicationLab(aidHex: String) {
        viewModelScope.launch {
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
                    _ui.update {
                        it.copy(
                            busy = false,
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
                    refreshIdentityAfterStructureChange()
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

    private suspend fun refreshIdentityAfterStructureChange() {
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
                    )
                }
                syncJournal()
            },
            onFailure = { /* status déjà posé par l’op structure */ },
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
        /** Slot carte pour auto-auth double-tap (clé maître app / PICC). */
        const val DEFAULT_AUTH_KEY_NO = 0
        /** Durée d’affichage du bandeau d’auth réussie. */
        const val AUTH_SUCCESS_FLASH_MS = 2_500L
        const val DEFAULT_AUTH_OK_MESSAGE =
            "Authentification réussie avec la clé par défaut"
        const val MANUAL_AUTH_OK_MESSAGE = "Authentification réussie"
        /** Re-select app : session restaurée sans re-saisie. */
        const val REMEMBERED_AUTH_OK_MESSAGE = "Session restaurée (clé mémorisée)"
    }
}
