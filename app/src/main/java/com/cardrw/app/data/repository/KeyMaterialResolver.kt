package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.KeyProfile
import com.cardrw.app.data.model.MaterialRef
import com.cardrw.desfire.crypto.AesConstants

/**
 * Résout le **matériau** pour un couple (scope, keyNo).
 * Ne remplace pas [AuthKeyPlanner] (slots) — uniquement *quel secret* coller.
 *
 * Spec : [docs/UX_PROFIL_CLES.md] §5 — ordre moniteur :
 * 1) mémorisée session  2) binding profil  3) usine si autorisée  4) NeedsUser
 */
object KeyMaterialResolver {

    enum class MaterialSource {
        /** Déjà validé sur cette pose carte (RAM VM). */
        REMEMBERED,
        /** Binding du profil actif (coffre ou usine déclarée). */
        PROFILE,
        /** 00…00 labo / fallback. */
        FACTORY,
    }

    sealed class ResolveResult {
        data class Ready(
            val keyBytes: ByteArray,
            val source: MaterialSource,
            val vaultEntryId: String? = null,
        ) : ResolveResult() {
            fun copyKey(): ByteArray = keyBytes.copyOf()
        }

        data class NeedsUser(val reason: String) : ResolveResult()
        data class Impossible(val reason: String) : ResolveResult()
    }

    fun scopeForAid(aidHex: String): BindingScope {
        val clean = aidHex.replace(" ", "").uppercase()
        return if (clean == "000000" || clean.isEmpty()) {
            BindingScope.Picc
        } else {
            BindingScope.Application(KeyProfileRules.normalizeAidHex(clean))
        }
    }

    fun sameScope(a: BindingScope, b: BindingScope): Boolean =
        KeyProfileRules.bindingSlotKey(a, 0) == KeyProfileRules.bindingSlotKey(b, 0)

    fun findBinding(profile: KeyProfile, scope: BindingScope, keyNo: Int): KeyBinding? {
        val want = KeyProfileRules.bindingSlotKey(scope, keyNo)
        return profile.bindings.find { KeyProfileRules.bindingSlotKey(it) == want }
    }

    fun bindingsForScope(profile: KeyProfile, scope: BindingScope): List<KeyBinding> =
        profile.bindings
            .filter { sameScope(it.scope, scope) }
            .sortedWith(compareBy({ it.keyNo != 0 }, { it.keyNo }))

    /**
     * Usine autorisée pour un slot **sans** binding (ou hors binding) ?
     * - Pas de profil → labo moniteur actuel (oui)
     * - Profil + allowFactoryFallback → oui
     * - Profil sans fallback → non (sauf binding explicite FactoryZero, traité à part)
     */
    fun allowsFactoryFallback(profile: KeyProfile?): Boolean =
        profile == null || profile.allowFactoryFallback

    /**
     * Résolution moniteur pour un slot précis.
     *
     * @param rememberedMaterial octets déjà validés en session (null = pas mémorisé)
     * @param rememberedVaultId si la mémorisée vient du coffre
     * @param loadVault charge le secret coffre (peut throw VaultLocked / missing)
     */
    suspend fun resolve(
        scope: BindingScope,
        keyNo: Int,
        profile: KeyProfile?,
        rememberedMaterial: ByteArray?,
        rememberedVaultId: String? = null,
        knownVaultIds: Set<String> = emptySet(),
        loadVault: suspend (vaultId: String) -> ByteArray,
    ): ResolveResult {
        require(KeyProfileRules.isValidKeyNo(keyNo)) { "keyNo 0–13" }

        // 1) Session mémorisée
        if (rememberedMaterial != null && rememberedMaterial.size == 16) {
            return ResolveResult.Ready(
                keyBytes = rememberedMaterial.copyOf(),
                source = MaterialSource.REMEMBERED,
                vaultEntryId = rememberedVaultId,
            )
        }

        // 2) Binding profil exact
        if (profile != null) {
            val binding = findBinding(profile, scope, keyNo)
            if (binding != null) {
                return resolveBinding(
                    binding = binding,
                    knownVaultIds = knownVaultIds,
                    loadVault = loadVault,
                )
            }
            // 3) Pas de binding : fallback usine selon profil
            if (profile.allowFactoryFallback) {
                return factoryReady()
            }
            return ResolveResult.NeedsUser(
                "Profil « ${profile.displayName} » : pas de binding pour " +
                    "${KeyProfileRules.formatScope(scope)} k$keyNo",
            )
        }

        // 4) Sans profil : labo moniteur (usine proposée — le caller décide du probe)
        return factoryReady()
    }

    /**
     * Matériau d’un binding seul (sans mémorisée) — pour pré-remplir auto depuis profil.
     */
    suspend fun resolveBinding(
        binding: KeyBinding,
        knownVaultIds: Set<String>,
        loadVault: suspend (vaultId: String) -> ByteArray,
    ): ResolveResult {
        return when (val m = binding.materialRef) {
            is MaterialRef.FactoryZero -> factoryReady(source = MaterialSource.PROFILE)
            is MaterialRef.VaultEntry -> {
                val id = m.vaultId
                if (id !in knownVaultIds && knownVaultIds.isNotEmpty()) {
                    val msg = "Entrée coffre manquante pour " +
                        KeyProfileRules.formatBindingLabel(binding)
                    return if (binding.required) {
                        ResolveResult.Impossible(msg)
                    } else {
                        ResolveResult.NeedsUser(msg)
                    }
                }
                try {
                    val bytes = loadVault(id)
                    require(bytes.size == 16) { "secret coffre ${bytes.size} o" }
                    ResolveResult.Ready(
                        keyBytes = bytes.copyOf(),
                        source = MaterialSource.PROFILE,
                        vaultEntryId = id,
                    )
                } catch (e: Exception) {
                    val msg = e.message ?: "coffre indisponible"
                    if (binding.required) {
                        ResolveResult.Impossible(msg)
                    } else {
                        ResolveResult.NeedsUser(msg)
                    }
                }
            }
        }
    }

    fun sourceLabel(source: MaterialSource): String =
        when (source) {
            MaterialSource.REMEMBERED -> "mémorisée"
            MaterialSource.PROFILE -> "profil"
            MaterialSource.FACTORY -> "usine"
        }

    private fun factoryReady(
        source: MaterialSource = MaterialSource.FACTORY,
    ): ResolveResult.Ready =
        ResolveResult.Ready(
            keyBytes = AesConstants.FACTORY_KEY.copyOf(),
            source = source,
            vaultEntryId = null,
        )
}
