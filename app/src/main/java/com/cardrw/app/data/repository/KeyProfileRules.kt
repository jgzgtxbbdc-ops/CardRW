package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.MaterialRef

/**
 * Validation pure des profils / bindings — testable sans Android.
 * Spec : [docs/UX_PROFIL_CLES.md] §4 (unicité, noms, vaultId cassé).
 */
object KeyProfileRules {

    const val MAX_NAME_LENGTH = 40
    const val KEY_NO_MIN = 0
    const val KEY_NO_MAX = 13
    private val AID_HEX = Regex("^[0-9A-F]{6}$")

    fun isValidDisplayName(name: String): Boolean {
        val t = name.trim()
        return t.isNotEmpty() && t.length <= MAX_NAME_LENGTH
    }

    fun normalizeForCompare(name: String): String = name.trim().lowercase()

    fun nextDefaultName(existingDisplayNames: Collection<String>): String {
        val taken = existingDisplayNames.map { it.trim().lowercase() }.toSet()
        var n = 1
        while ("profil $n" in taken || "profile $n" in taken) n++
        return "Profil $n"
    }

    fun isValidKeyNo(keyNo: Int): Boolean = keyNo in KEY_NO_MIN..KEY_NO_MAX

    /**
     * Normalise un AID saisi (espaces / casse) → 6 hex majuscules.
     * @throws IllegalArgumentException si invalide
     */
    fun normalizeAidHex(raw: String): String {
        val clean = raw.replace(Regex("[^0-9a-fA-F]"), "").uppercase()
        require(clean.length == 6) { "AID = 6 hex, got ${clean.length}" }
        require(AID_HEX.matches(clean)) { "AID hex invalide" }
        return clean
    }

    /** Clé d’unicité (scope, keyNo) dans un profil. */
    fun bindingSlotKey(scope: BindingScope, keyNo: Int): String =
        when (scope) {
            is BindingScope.Picc -> "PICC|$keyNo"
            is BindingScope.Application -> "APP|${scope.aidHex.uppercase()}|$keyNo"
        }

    fun bindingSlotKey(binding: KeyBinding): String =
        bindingSlotKey(binding.scope, binding.keyNo)

    /**
     * Valide une liste de bindings : keyNo, AID, unicité (scope,keyNo).
     * @return bindings normalisés (AID uppercase)
     */
    fun validateAndNormalizeBindings(bindings: List<KeyBinding>): List<KeyBinding> {
        val seen = mutableSetOf<String>()
        return bindings.map { b ->
            require(isValidKeyNo(b.keyNo)) {
                "slot carte 0–13, got ${b.keyNo}"
            }
            val scope = when (val s = b.scope) {
                is BindingScope.Picc -> BindingScope.Picc
                is BindingScope.Application ->
                    BindingScope.Application(normalizeAidHex(s.aidHex))
            }
            val material = when (val m = b.materialRef) {
                is MaterialRef.FactoryZero -> MaterialRef.FactoryZero
                is MaterialRef.VaultEntry -> {
                    require(m.vaultId.isNotBlank()) { "vaultId manquant" }
                    MaterialRef.VaultEntry(m.vaultId.trim())
                }
            }
            val role = b.roleHint?.trim()?.takeIf { it.isNotEmpty() }?.take(24)
            val normalized = KeyBinding(
                scope = scope,
                keyNo = b.keyNo,
                materialRef = material,
                roleHint = role,
                required = b.required,
            )
            val key = bindingSlotKey(normalized)
            require(key !in seen) {
                "binding déjà défini pour ${formatScope(normalized.scope)} k${normalized.keyNo}"
            }
            seen += key
            normalized
        }
    }

    /**
     * Bindings dont le [MaterialRef.VaultEntry] n’existe plus dans le coffre.
     * [FactoryZero] n’est jamais cassé.
     */
    fun findBrokenVaultBindings(
        bindings: List<KeyBinding>,
        knownVaultIds: Set<String>,
    ): List<KeyBinding> =
        bindings.filter { b ->
            when (val m = b.materialRef) {
                is MaterialRef.FactoryZero -> false
                is MaterialRef.VaultEntry -> m.vaultId !in knownVaultIds
            }
        }

    fun isVaultBindingBroken(binding: KeyBinding, knownVaultIds: Set<String>): Boolean =
        findBrokenVaultBindings(listOf(binding), knownVaultIds).isNotEmpty()

    fun formatScope(scope: BindingScope): String =
        when (scope) {
            is BindingScope.Picc -> "PICC"
            is BindingScope.Application -> scope.aidHex.uppercase()
        }

    /** Affichage court d’un binding (UI / tests). */
    fun formatBindingLabel(binding: KeyBinding): String {
        val scope = formatScope(binding.scope)
        val role = binding.roleHint?.let { " · $it" }.orEmpty()
        return "$scope k${binding.keyNo}$role"
    }
}
