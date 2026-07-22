package com.cardrw.desfire.command

/**
 * Inventaire des commandes DESFire (Annexe A — vivant).
 *
 * @property code opcode natif (octet INS dans le wrap 90 CMD … 00)
 * @property smRequired niveau de secure messaging requis côté produit
 * @property releaseTarget première release CardRW où la commande est supportée
 */
enum class DesfireCommand(
    val code: Int,
    val displayName: String,
    val smRequired: SmRequirement,
    val releaseTarget: String,
    val notes: String = "",
) {
    // --- v0 lecture / identité ---
    GET_VERSION(
        0x60,
        "GetVersion",
        SmRequirement.NONE,
        "v0",
        "Réponse chaînée (3 frames via AdditionalFrame). Heuristique EV1/EV2/EV3.",
    ),
    ADDITIONAL_FRAME(
        0xAF,
        "AdditionalFrame",
        SmRequirement.NONE,
        "v0",
        "Continue un chaînage de commande ou de réponse.",
    ),
    GET_APPLICATION_IDS(
        0x6A,
        "GetApplicationIDs",
        SmRequirement.NONE,
        "v0",
        "Liste des AID (3 octets chacun). Peut chaîner si nombreuses apps.",
    ),
    SELECT_APPLICATION(
        0x5A,
        "SelectApplication",
        SmRequirement.NONE,
        "v0",
        "Sélectionne une app (AID 3 octets) ou PICC (00 00 00). Invalide la session auth.",
    ),
    GET_FREE_MEMORY(
        0x6E,
        "GetFreeMemory",
        SmRequirement.NONE,
        "v0",
        "Mémoire libre en octets (3 octets LE). EV1+ selon config.",
    ),
    GET_CARD_UID(
        0x51,
        "GetCardUID",
        SmRequirement.AUTHENTICATED,
        "v0.5",
        "UID réel après auth — utile si Random ID.",
    ),
    GET_FILE_IDS(
        0x6F,
        "GetFileIDs",
        SmRequirement.NONE,
        "v0.5",
        "Liste FileNo de l’app sélectionnée.",
    ),
    GET_FILE_SETTINGS(
        0xF5,
        "GetFileSettings",
        SmRequirement.NONE,
        "v0.5",
        "Type, comm mode, droits, taille selon type de fichier.",
    ),
    GET_KEY_SETTINGS(
        0x45,
        "GetKeySettings",
        SmRequirement.NONE,
        "v0.5",
        "Key settings PICC ou application + nombre max de clés.",
    ),
    GET_KEY_VERSION(
        0x64,
        "GetKeyVersion",
        SmRequirement.NONE,
        "v1",
        "Version d’une clé (n°).",
    ),

    // --- Auth / SM ---
    AUTHENTICATE_DES(
        0x0A,
        "AuthenticateDES",
        SmRequirement.NONE,
        "v0.6",
        "Auth DES/2KTDEA legacy (PICC master usine carte vierge).",
    ),
    AUTHENTICATE_AES(
        0xAA,
        "AuthenticateAES",
        SmRequirement.NONE,
        "v0.5",
        "Auth AES legacy (flux EV1) → SM EV1. Échoue si PICC encore en DES usine.",
    ),
    AUTHENTICATE_EV2_FIRST(
        0x71,
        "AuthenticateEV2First",
        SmRequirement.NONE,
        "v1",
        "Auth EV2 first → SM EV2 (CMAC).",
    ),
    AUTHENTICATE_EV2_NON_FIRST(
        0x77,
        "AuthenticateEV2NonFirst",
        SmRequirement.AUTHENTICATED,
        "v1",
        "Auth EV2 non-first dans la même session.",
    ),

    // --- Lecture / écriture Standard ---
    READ_DATA(
        0xBD,
        "ReadData",
        SmRequirement.DEPENDS_ON_FILE,
        "v0.5",
        "Lecture fichier Standard — plain / MAC / full selon settings.",
    ),
    WRITE_DATA(
        0x3D,
        "WriteData",
        SmRequirement.DEPENDS_ON_FILE,
        "v1",
        "Écriture fichier Standard.",
    ),

    // --- Structure ---
    CREATE_APPLICATION(
        0xCA,
        "CreateApplication",
        SmRequirement.AUTHENTICATED,
        "v1",
        "Création app : AID, key settings, nb clés, crypto AES.",
    ),
    DELETE_APPLICATION(
        0xDA,
        "DeleteApplication",
        SmRequirement.AUTHENTICATED,
        "v1",
    ),
    CREATE_STD_DATA_FILE(
        0xCD,
        "CreateStdDataFile",
        SmRequirement.AUTHENTICATED,
        "v1",
    ),
    DELETE_FILE(
        0xDF,
        "DeleteFile",
        SmRequirement.AUTHENTICATED,
        "v1",
    ),
    CHANGE_KEY(
        0xC4,
        "ChangeKey",
        SmRequirement.AUTHENTICATED,
        "v1",
        "Changement de clé AES — garde-fous UI obligatoires.",
    ),
    CHANGE_KEY_SETTINGS(
        0x54,
        "ChangeKeySettings",
        SmRequirement.AUTHENTICATED,
        "v1",
        "Attention gel irréversible des settings.",
    ),
    FORMAT_PICC(
        0xFC,
        "FormatPICC",
        SmRequirement.AUTHENTICATED,
        "v1",
        "Efface toutes les apps — double confirmation + UID.",
    ),

    // --- Value / Records (v1.1) ---
    GET_VALUE(
        0x6C,
        "GetValue",
        SmRequirement.DEPENDS_ON_FILE,
        "v1.1",
    ),
    CREDIT(
        0x0C,
        "Credit",
        SmRequirement.DEPENDS_ON_FILE,
        "v1.1",
    ),
    DEBIT(
        0xDC,
        "Debit",
        SmRequirement.DEPENDS_ON_FILE,
        "v1.1",
    ),
    READ_RECORDS(
        0xBB,
        "ReadRecords",
        SmRequirement.DEPENDS_ON_FILE,
        "v1.1",
    ),
    WRITE_RECORD(
        0x3B,
        "WriteRecord",
        SmRequirement.DEPENDS_ON_FILE,
        "v1.1",
    ),
    ;

    val codeByte: Byte get() = code.toByte()

    companion object {
        fun fromCode(code: Int): DesfireCommand? =
            entries.firstOrNull { it.code == (code and 0xFF) }

        fun fromCode(code: Byte): DesfireCommand? = fromCode(code.toInt())

        /** Commandes prioritaires freeze scope v0. */
        fun v0Commands(): List<DesfireCommand> =
            entries.filter { it.releaseTarget == "v0" }
    }
}

enum class SmRequirement {
    /** Pas d’auth requise pour émettre la commande. */
    NONE,
    /** Session authentifiée requise. */
    AUTHENTICATED,
    /** Selon comm mode / access rights du fichier. */
    DEPENDS_ON_FILE,
}
