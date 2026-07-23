package com.cardrw.desfire.dump

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DumpRestorePlannerTest {

    @Test
    fun parse_rights_proxmark_like() {
        val raw = DumpRestorePlanner.parseRightsOrFree("r:1 w:2 rw:2 ch:0")
        assertEquals(0x1220, raw)
        assertEquals(0xEEEE, DumpRestorePlanner.parseRightsOrFree("r:free w:free rw:free ch:free"))
        assertEquals(0xEEEE, DumpRestorePlanner.parseRightsOrFree("legacy string"))
    }

    @Test
    fun plan_structure_and_data() {
        val doc = CardDumpDocument(
            createdAt = "2026-01-01T00:00:00Z",
            appVersion = "test",
            note = "t",
            card = DumpCardSection(
                typeLabel = "EV3",
                uidTag = "01020304",
                uidKind = "FIXED",
            ),
            structure = DumpStructureSection(
                applications = listOf(
                    DumpApplication(
                        aid = "F00102",
                        explored = true,
                        keySettings = DumpKeySettings(settingsRaw = 0x0F, maxKeys = 3),
                        files = listOf(
                            DumpFileMeta(
                                fileNo = 0,
                                type = "Std",
                                commMode = "FULL",
                                sizeBytes = 16,
                                accessRights = "r:free w:free rw:free ch:free",
                                accessRightsRaw = 0xEEEE,
                                commModeWire = 0x03,
                                dataStatus = "read",
                            ),
                        ),
                    ),
                ),
            ),
            data = DumpDataSection(
                files = mapOf("F00102/0" to "AABBCCDD"),
            ),
        )
        val plan = DumpRestorePlanner.plan(
            doc,
            mode = DumpRestorePlanner.Mode.STRUCTURE_AND_DATA,
            formatFirst = true,
        )
        assertTrue(plan.steps.any { it is DumpRestorePlanner.Step.FormatPicc })
        assertTrue(plan.steps.any { it is DumpRestorePlanner.Step.CreateApplication })
        assertTrue(plan.steps.any { it is DumpRestorePlanner.Step.CreateStdDataFile })
        assertTrue(plan.steps.any { it is DumpRestorePlanner.Step.WriteData })
        assertTrue(plan.actionableCount >= 4)
    }
}
