package com.cardrw.app.data.repository

/**
 * Noms par défaut `key1`, `key2`, … — plus petit N libre (réutilise les trous).
 */
object KeyVaultNaming {
    private val KEY_N = Regex("^key(\\d+)$", RegexOption.IGNORE_CASE)

    fun nextDefaultName(existingDisplayNames: Collection<String>): String {
        val taken = existingDisplayNames.map { it.trim().lowercase() }.toSet()
        var n = 1
        while ("key$n" in taken) n++
        return "key$n"
    }

    fun isValidDisplayName(name: String): Boolean {
        val t = name.trim()
        return t.isNotEmpty() && t.length <= 40
    }

    /** Heuristique : ressemble à 32 hex (confusion nom / matériau). */
    fun looksLikeKeyHex(name: String): Boolean {
        val clean = name.replace(Regex("[^0-9a-fA-F]"), "")
        return clean.length == 32
    }

    fun normalizeForCompare(name: String): String = name.trim().lowercase()
}
