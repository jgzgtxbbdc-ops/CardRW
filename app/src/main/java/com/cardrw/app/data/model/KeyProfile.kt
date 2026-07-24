package com.cardrw.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Profil de clés — bindings slot×contexte → référence coffre (pas de secret).
 * Spec : [docs/UX_PROFIL_CLES.md] §4.
 */
@Serializable
data class KeyProfile(
    val id: String,
    val displayName: String,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    /** Labo : tenter usine 00…00 si pas de binding pour le slot. */
    val allowFactoryFallback: Boolean = true,
    /** null = auto (comportement client actuel). */
    val preferEv2: Boolean? = null,
    val bindings: List<KeyBinding> = emptyList(),
)

@Serializable
data class KeyBinding(
    val scope: BindingScope,
    /** Slot carte DESFire 0–13. */
    val keyNo: Int,
    val materialRef: MaterialRef,
    /** Libellé UI only (« master », « read », …). */
    val roleHint: String? = null,
    /** true = échec auth = stop run dump/encode (P4–P5). */
    val required: Boolean = false,
)

/**
 * Contexte carte du binding.
 * PICC = master carte (AID logique 00 00 00) ; Application = AID 3 o.
 */
@Serializable
sealed class BindingScope {
    @Serializable
    @SerialName("picc")
    data object Picc : BindingScope()

    /**
     * @param aidHex 6 hex majuscules (sans espaces).
     */
    @Serializable
    @SerialName("app")
    data class Application(val aidHex: String) : BindingScope()
}

/**
 * Référence matériau — jamais les 16 octets dans le profil.
 */
@Serializable
sealed class MaterialRef {
    @Serializable
    @SerialName("vault")
    data class VaultEntry(val vaultId: String) : MaterialRef()

    /** Clé usine 00…00 (AES ou DES selon flux client). */
    @Serializable
    @SerialName("factory")
    data object FactoryZero : MaterialRef()
}

@Serializable
data class KeyProfilesFile(
    val profiles: List<KeyProfile> = emptyList(),
)
