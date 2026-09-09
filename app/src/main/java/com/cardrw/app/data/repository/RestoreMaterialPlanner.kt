package com.cardrw.app.data.repository

import com.cardrw.app.data.model.KeyProfile
import com.cardrw.desfire.dump.DumpRestorePlanner
import com.cardrw.desfire.model.AccessRights
import com.cardrw.desfire.model.writeKeyCandidates

/**
 * Dry-run **matériaux** d’un plan restore (P5) — sans NFC, sans secrets.
 *
 * PICC k0 → Format / CreateApp ;
 * app k0 → Select / CreateFile ;
 * slots W/RW → WriteData (pas master 0 par défaut).
 */
object RestoreMaterialPlanner {

    data class StepCheck(
        val label: String,
        val ready: Boolean,
        val detail: String,
        /** true = restore ne peut pas démarrer / s’arrêtera forcément ici. */
        val blocking: Boolean,
    ) {
        val line: String
            get() {
                val mark = when {
                    !ready && blocking -> "✗"
                    !ready -> "⚠"
                    else -> "✓"
                }
                return "$mark $label · $detail"
            }
    }

    data class Report(
        val profileName: String?,
        val checks: List<StepCheck>,
    ) {
        val blockingCount: Int get() = checks.count { it.blocking && !it.ready }
        val warningCount: Int get() = checks.count { !it.ready && !it.blocking }
        val readyCount: Int get() = checks.count { it.ready }

        fun summaryLine(): String {
            val profile = profileName?.let { "Profil « $it »" } ?: "Sans profil (labo usine)"
            return "$profile · ${readyCount} matériau(x) OK" +
                if (blockingCount > 0) " · $blockingCount bloquant(s)" else "" +
                if (warningCount > 0) " · $warningCount Write/étape sans matériau" else ""
        }
    }

    fun check(
        plan: DumpRestorePlanner.Plan,
        profile: KeyProfile?,
        rememberedByAid: Map<String, Set<Int>> = emptyMap(),
        knownVaultIds: Set<String> = emptySet(),
    ): Report {
        val checks = mutableListOf<StepCheck>()
        val needsPicc = plan.steps.any {
            it is DumpRestorePlanner.Step.FormatPicc ||
                it is DumpRestorePlanner.Step.CreateApplication
        }
        if (needsPicc) {
            checks += slotCheck(
                aidHex = "000000",
                keyNo = 0,
                label = "PICC master (Format / CreateApplication)",
                blocking = true,
                profile = profile,
                rememberedByAid = rememberedByAid,
                knownVaultIds = knownVaultIds,
                role = "PICC k0",
            )
        }
        val appMasterAids = linkedSetOf<String>()
        for (step in plan.steps) {
            when (step) {
                is DumpRestorePlanner.Step.SelectApplication -> appMasterAids += step.aidHex.uppercase()
                is DumpRestorePlanner.Step.CreateStdDataFile -> appMasterAids += step.aidHex.uppercase()
                else -> {}
            }
        }
        for (aid in appMasterAids) {
            val blocking = plan.steps.any {
                it is DumpRestorePlanner.Step.CreateStdDataFile &&
                    it.aidHex.equals(aid, ignoreCase = true)
            }
            checks += slotCheck(
                aidHex = aid,
                keyNo = 0,
                label = "App $aid master (CreateFile / Select)",
                blocking = blocking,
                profile = profile,
                rememberedByAid = rememberedByAid,
                knownVaultIds = knownVaultIds,
                role = "app k0",
            )
        }
        for (step in plan.steps.filterIsInstance<DumpRestorePlanner.Step.WriteData>()) {
            checks += writeCheck(
                step = step,
                profile = profile,
                rememberedByAid = rememberedByAid,
                knownVaultIds = knownVaultIds,
            )
        }
        return Report(profileName = profile?.displayName, checks = checks)
    }

    private fun writeCheck(
        step: DumpRestorePlanner.Step.WriteData,
        profile: KeyProfile?,
        rememberedByAid: Map<String, Set<Int>>,
        knownVaultIds: Set<String>,
    ): StepCheck {
        val rights = AccessRights.parse(step.accessRights)
        val slots = if (rights.isWriteFree) {
            listOf(0)
        } else {
            rights.writeKeyCandidates().map { it.keyNo }
        }
        if (slots.isEmpty()) {
            return StepCheck(
                label = step.label,
                ready = false,
                detail = "écriture Never — pas de slot W/RW",
                blocking = true,
            )
        }
        var anyRequiredMissing = false
        for (keyNo in slots) {
            val preview = KeyMaterialResolver.preview(
                scope = KeyMaterialResolver.scopeForAid(step.aidHex),
                keyNo = keyNo,
                profile = profile,
                remembered = rememberedByAid[step.aidHex.uppercase()]?.contains(keyNo) == true,
                knownVaultIds = knownVaultIds,
            )
            if (preview.ready) {
                return StepCheck(
                    label = step.label,
                    ready = true,
                    detail = "k$keyNo · ${preview.detail}",
                    blocking = false,
                )
            }
            if (preview.required) anyRequiredMissing = true
        }
        val need = slots.joinToString(",") { "k$it" }
        return StepCheck(
            label = step.label,
            ready = false,
            detail = "besoin $need — Write s’arrêtera",
            blocking = anyRequiredMissing,
        )
    }

    private fun slotCheck(
        aidHex: String,
        keyNo: Int,
        label: String,
        blocking: Boolean,
        profile: KeyProfile?,
        rememberedByAid: Map<String, Set<Int>>,
        knownVaultIds: Set<String>,
        role: String,
    ): StepCheck {
        val preview = KeyMaterialResolver.preview(
            scope = KeyMaterialResolver.scopeForAid(aidHex),
            keyNo = keyNo,
            profile = profile,
            remembered = rememberedByAid[aidHex.uppercase()]?.contains(keyNo) == true,
            knownVaultIds = knownVaultIds,
        )
        return StepCheck(
            label = label,
            ready = preview.ready,
            detail = "$role · ${preview.detail}",
            blocking = blocking && !preview.ready || preview.required && !preview.ready,
        )
    }
}
