package com.cardrw.app.data.repository

import com.cardrw.app.data.model.BindingScope
import com.cardrw.app.data.model.KeyBinding
import com.cardrw.app.data.model.MaterialRef

/**
 * Capture « Enregistrer ce jeu » — plan pur depuis slots mémorisés session.
 * Spec : [docs/UX_PROFIL_CLES.md] §6.3 / P3.
 *
 * Ne touche ni coffre ni disque : produit un plan (bindings + créations vault éventuelles).
 */
object KeyProfileCapture {

    /** Snapshot d’un slot déjà auth OK (session VM). */
    data class SessionSlot(
        val aidHex: String,
        val keyNo: Int,
        val vaultEntryId: String?,
        /** 16 o — utilisé seulement pour factory detect + dedup création coffre. */
        val keyBytes: ByteArray,
    ) {
        val aidKey: String get() = aidHex.replace(" ", "").uppercase()
        val isPicc: Boolean get() = aidKey == "000000" || aidKey.isEmpty()
        val isFactoryZero: Boolean get() = keyBytes.size == 16 && keyBytes.all { it == 0.toByte() }
        /** Empreinte matériau pour dédup des créations coffre (hex compact). */
        val materialFingerprint: String
            get() = keyBytes.joinToString("") { b -> "%02X".format(b) }
    }

    sealed class MaterialPlan {
        data class UseVault(val vaultId: String) : MaterialPlan()
        data object FactoryZero : MaterialPlan()
        /**
         * @param suggestedName nom coffre proposé (K4)
         * @param fingerprint hex 32 du matériau (dédup multi-slots)
         */
        data class CreateVault(
            val suggestedName: String,
            val fingerprint: String,
        ) : MaterialPlan()
    }

    data class PlannedSlot(
        val aidHex: String,
        val keyNo: Int,
        val scope: BindingScope,
        val plan: MaterialPlan,
        /** Libellé UI : « PICC k0 · usine », « F40101 k2 · coffre », … */
        val label: String,
        val sourceLabel: String,
        val selectedByDefault: Boolean = true,
    ) {
        val selectionKey: String
            get() = KeyProfileRules.bindingSlotKey(scope, keyNo)
    }

    /**
     * Construit le plan de capture.
     *
     * @param knownVaultIds entrées coffre encore présentes
     * @param existingVaultNames pour suggestions K4 sans collision
     */
    fun plan(
        slots: List<SessionSlot>,
        knownVaultIds: Set<String>,
        existingVaultNames: List<String>,
    ): List<PlannedSlot> {
        val vaultNames = existingVaultNames.toMutableList()
        // fingerprint → nom déjà réservé dans ce plan (dédup CreateVault)
        val fingerprintNames = mutableMapOf<String, String>()

        return slots
            .sortedWith(
                compareBy<SessionSlot> { if (it.isPicc) 0 else 1 }
                    .thenBy { it.aidKey }
                    .thenBy { it.keyNo },
            )
            .map { slot ->
                val scope = if (slot.isPicc) {
                    BindingScope.Picc
                } else {
                    BindingScope.Application(KeyProfileRules.normalizeAidHex(slot.aidKey))
                }
                val plan = planMaterial(slot, knownVaultIds, vaultNames, fingerprintNames)
                val sourceLabel = when (plan) {
                    is MaterialPlan.FactoryZero -> "usine"
                    is MaterialPlan.UseVault -> "coffre"
                    is MaterialPlan.CreateVault -> "session → coffre « ${plan.suggestedName} »"
                }
                val scopeLabel = KeyProfileRules.formatScope(scope)
                PlannedSlot(
                    aidHex = if (slot.isPicc) "000000" else slot.aidKey,
                    keyNo = slot.keyNo,
                    scope = scope,
                    plan = plan,
                    label = "$scopeLabel k${slot.keyNo}",
                    sourceLabel = sourceLabel,
                )
            }
    }

    private fun planMaterial(
        slot: SessionSlot,
        knownVaultIds: Set<String>,
        vaultNames: MutableList<String>,
        fingerprintNames: MutableMap<String, String>,
    ): MaterialPlan {
        if (slot.isFactoryZero) return MaterialPlan.FactoryZero
        val vaultId = slot.vaultEntryId?.takeIf { it in knownVaultIds }
        if (vaultId != null) return MaterialPlan.UseVault(vaultId)

        val fp = slot.materialFingerprint
        val existingName = fingerprintNames[fp]
        if (existingName != null) {
            return MaterialPlan.CreateVault(existingName, fp)
        }
        val name = KeyVaultNaming.suggestContextName(
            existingDisplayNames = vaultNames,
            aidHex = if (slot.isPicc) "000000" else slot.aidKey,
            keyNo = slot.keyNo,
            roleHint = null,
        )
        vaultNames += name
        fingerprintNames[fp] = name
        return MaterialPlan.CreateVault(name, fp)
    }

    /**
     * Nom de profil suggéré à partir des AID de la session.
     */
    fun suggestProfileName(
        slots: List<SessionSlot>,
        existingProfileNames: Collection<String>,
    ): String {
        val apps = slots
            .map { it.aidKey }
            .filter { it.isNotEmpty() && it != "000000" }
            .distinct()
        val base = when {
            apps.size == 1 -> "Site ${apps[0]}"
            apps.size > 1 -> "Site ${apps[0]}+"
            slots.any { it.isPicc } -> "PICC labo"
            else -> KeyProfileRules.nextDefaultName(existingProfileNames)
        }.take(KeyProfileRules.MAX_NAME_LENGTH)

        val taken = existingProfileNames.map { KeyProfileRules.normalizeForCompare(it) }.toSet()
        if (KeyProfileRules.normalizeForCompare(base) !in taken &&
            KeyProfileRules.isValidDisplayName(base)
        ) {
            return base
        }
        // Collision : Profil N
        return KeyProfileRules.nextDefaultName(existingProfileNames)
    }

    /**
     * Transforme un plan exécuté (vaultIds résolus) en bindings profil.
     *
     * @param vaultIdByFingerprint map fingerprint → vaultId pour les CreateVault
     * @param selectedKeys selectionKey des slots cochés
     */
    fun toBindings(
        planned: List<PlannedSlot>,
        selectedKeys: Set<String>,
        vaultIdByFingerprint: Map<String, String>,
    ): List<KeyBinding> {
        return planned
            .filter { it.selectionKey in selectedKeys }
            .map { slot ->
                val material = when (val p = slot.plan) {
                    is MaterialPlan.FactoryZero -> MaterialRef.FactoryZero
                    is MaterialPlan.UseVault -> MaterialRef.VaultEntry(p.vaultId)
                    is MaterialPlan.CreateVault -> {
                        val id = vaultIdByFingerprint[p.fingerprint]
                            ?: error("vault non créé pour ${p.suggestedName}")
                        MaterialRef.VaultEntry(id)
                    }
                }
                KeyBinding(
                    scope = slot.scope,
                    keyNo = slot.keyNo,
                    materialRef = material,
                )
            }
            .let { KeyProfileRules.validateAndNormalizeBindings(it) }
    }

    fun countNeedingVaultCreate(planned: List<PlannedSlot>, selectedKeys: Set<String>): Int {
        val fps = linkedSetOf<String>()
        for (s in planned) {
            if (s.selectionKey !in selectedKeys) continue
            val p = s.plan as? MaterialPlan.CreateVault ?: continue
            fps += p.fingerprint
        }
        return fps.size
    }
}
