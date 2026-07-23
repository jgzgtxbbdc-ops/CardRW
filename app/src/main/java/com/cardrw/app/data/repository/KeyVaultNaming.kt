package com.cardrw.app.data.repository

/**
 * Noms par défaut `key1`, `key2`, … — plus petit N libre (réutilise les trous).
 * Suggestions contextuelles K4 (AID · slot) — éditables, jamais imposées.
 */
object KeyVaultNaming {
    private val KEY_N = Regex("^key(\\d+)$", RegexOption.IGNORE_CASE)

    fun nextDefaultName(existingDisplayNames: Collection<String>): String {
        val taken = existingDisplayNames.map { it.trim().lowercase() }.toSet()
        var n = 1
        while ("key$n" in taken) n++
        return "key$n"
    }

    /**
     * Suggestion mnémo labo : `PICC · k0`, `B613F5 · k2`, `B613F5 · k2 write`.
     * Si collision → suffixe `keyN` libre collé.
     */
    fun suggestContextName(
        existingDisplayNames: Collection<String>,
        aidHex: String?,
        keyNo: Int,
        roleHint: String? = null,
    ): String {
        val aid = aidHex?.replace(" ", "")?.uppercase().orEmpty()
        val scope = when {
            aid.isEmpty() || aid == "000000" -> "PICC"
            aid.length == 6 -> aid
            else -> "App"
        }
        val role = roleHint?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        val base = "$scope · k$keyNo$role".take(40)
        val taken = existingDisplayNames.map { normalizeForCompare(it) }.toSet()
        if (normalizeForCompare(base) !in taken) return base
        // Collision : coller un keyN libre
        val n = nextDefaultName(existingDisplayNames)
        return "$base · $n".take(40)
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
