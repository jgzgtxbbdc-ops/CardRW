package com.cardrw.app.viewmodel

import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.model.ApplicationExploreResult
import com.cardrw.desfire.model.AuthKeyPlan
import com.cardrw.desfire.model.CardIdentity
import com.cardrw.desfire.session.AuthSession
import com.cardrw.desfire.util.Hex

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
    /** P5 : dry-run matériaux (profil / usine / manque). */
    val restoreMaterialLines: List<String> = emptyList(),
    val restoreMaterialSummary: String? = null,
    val restoreMaterialBlocking: Boolean = false,
    /**
     * Proposition d’enregistrer le matériau hex malgré un échec d’auth
     * (ex. bon secret, mauvais slot carte). Non null → dialog UI.
     */
    val pendingVaultSave: PendingVaultSaveOffer? = null,
    /** P2 : profil actif (null = moniteur labo sans profil). */
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    /** Liste légère pour sheet sélection moniteur. */
    val profileSummaries: List<ProfileSummary> = emptyList(),
    /** P3 : slots mémorisés session (pour activer « Enregistrer ce jeu »). */
    val rememberedSlotCount: Int = 0,
    /** P3 : preview capture ouverte (null = fermé). */
    val capturePreview: CapturePreviewUi? = null,
    /** P4 : dry-run couverture dump (sheet). */
    val dumpCoverage: DumpCoverageUi? = null,
)

enum class DumpMode {
    QUICK,
    COMPLETE,
}

/** P4 : aperçu couverture dump (sans secrets). */
data class DumpCoverageUi(
    val profileName: String?,
    val summary: String,
    val lines: List<String>,
    val missingCount: Int,
    val expectedReadable: Int,
)

/** P3 : ligne de la sheet capture (sans secret). */
data class CaptureSlotUi(
    val selectionKey: String,
    val label: String,
    val sourceLabel: String,
    val selected: Boolean,
)

/** P3 : état sheet « Enregistrer ce jeu ». */
data class CapturePreviewUi(
    val suggestedName: String,
    val slots: List<CaptureSlotUi>,
    val vaultCreatesNeeded: Int,
)

/** Entrée liste sélection profil moniteur (P2). */
data class ProfileSummary(
    val id: String,
    val displayName: String,
    val bindingCount: Int,
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
