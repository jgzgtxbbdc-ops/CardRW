package com.cardrw.desfire.status

/**
 * Status byte SW2 of native DESFire wrapped responses (`91 xx`).
 * Annex B — pedagogical messages for the UI.
 */
enum class DesfireStatus(
    val code: Int,
    val shortName: String,
    val pedagogicalFr: String,
) {
    SUCCESS(
        0x00,
        "Success",
        "Commande exécutée avec succès.",
    ),
    NO_CHANGES(
        0x0C,
        "No changes",
        "Aucune modification effectuée (déjà dans l’état demandé).",
    ),
    OUT_OF_EEPROM(
        0x0E,
        "Out of EEPROM",
        "Mémoire EEPROM insuffisante pour cette opération.",
    ),
    ILLEGAL_COMMAND(
        0x1C,
        "Illegal command",
        "Commande illégale dans le contexte actuel (app non sélectionnée, SM, etc.).",
    ),
    INTEGRITY_ERROR(
        0x1E,
        "Integrity error",
        "Erreur d’intégrité (CRC / MAC) — données ou secure messaging incorrects.",
    ),
    NO_SUCH_KEY(
        0x40,
        "No such key",
        "Numéro de clé inexistant pour cette application.",
    ),
    LENGTH_ERROR(
        0x7E,
        "Length error",
        "Longueur de commande incorrecte.",
    ),
    PERMISSION_DENIED(
        0x9D,
        "Permission denied",
        "Permission refusée — authentification ou droits d’accès insuffisants.",
    ),
    PARAMETER_ERROR(
        0x9E,
        "Parameter error",
        "Paramètre invalide (AID, FileNo, taille, etc.).",
    ),
    APPLICATION_NOT_FOUND(
        0xA0,
        "Application not found",
        "Application (AID) introuvable sur la carte.",
    ),
    APPL_INTEGRITY_ERROR(
        0xA1,
        "App integrity error",
        "Erreur d’intégrité au niveau application.",
    ),
    AUTHENTICATION_ERROR(
        0xAE,
        "Authentication error",
        "Échec d’authentification — clé incorrecte ou session invalide.",
    ),
    ADDITIONAL_FRAME(
        0xAF,
        "Additional frame",
        "Trame suivante attendue (chaining) — continuer avec AdditionalFrame (0xAF).",
    ),
    BOUNDARY_ERROR(
        0xBE,
        "Boundary error",
        "Dépassement de limite (offset / taille fichier).",
    ),
    PICC_INTEGRITY_ERROR(
        0xC1,
        "PICC integrity error",
        "Erreur d’intégrité au niveau PICC.",
    ),
    COMMAND_ABORTED(
        0xCA,
        "Command aborted",
        "Commande abandonnée (ex. interruption de session).",
    ),
    PICC_DISABLED(
        0xCD,
        "PICC disabled",
        "PICC désactivé.",
    ),
    COUNT_ERROR(
        0xCE,
        "Count error",
        "Limite de nombre atteinte (applications / fichiers).",
    ),
    DUPLICATE_ERROR(
        0xDE,
        "Duplicate error",
        "Élément déjà existant (AID ou FileNo déjà utilisé).",
    ),
    EEPROM_ERROR(
        0xEE,
        "EEPROM error",
        "Erreur d’écriture EEPROM.",
    ),
    FILE_NOT_FOUND(
        0xF0,
        "File not found",
        "Fichier introuvable dans l’application sélectionnée.",
    ),
    FILE_INTEGRITY_ERROR(
        0xF1,
        "File integrity error",
        "Erreur d’intégrité au niveau fichier.",
    ),
    UNKNOWN(
        -1,
        "Unknown",
        "Code de statut non répertorié — consulter la doc NXP et le journal APDU.",
    );

    val isSuccess: Boolean get() = this == SUCCESS
    val isAdditionalFrame: Boolean get() = this == ADDITIONAL_FRAME
    val isError: Boolean get() = !isSuccess && !isAdditionalFrame

    companion object {
        fun fromCode(sw2: Int): DesfireStatus {
            val code = sw2 and 0xFF
            return entries.firstOrNull { it.code == code } ?: UNKNOWN
        }

        fun fromCode(sw2: Byte): DesfireStatus = fromCode(sw2.toInt())
    }
}
