package com.cardrw.desfire.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthKeyPlannerTest {

    private fun rights(r: Int, w: Int, rw: Int, ch: Int = 0): AccessRights {
        val raw = ((r and 0xF) shl 12) or ((w and 0xF) shl 8) or ((rw and 0xF) shl 4) or (ch and 0xF)
        return AccessRights.parse(raw)
    }

    @Test
    fun read_R1_RW2_prefersRead() {
        val plan = AuthKeyPlanner.plan(
            AuthIntent.ReadFile(0, rights(r = 1, w = 1, rw = 2)),
        )
        assertEquals(AuthBarrier.NEEDS_KEY, plan.barrier)
        assertEquals(listOf(1, 2), plan.candidates.map { it.keyNo })
        assertEquals(1, plan.preferKeyNo)
        assertFalse(plan.allowAnyKey)
        assertTrue(plan.needsSheet)
    }

    @Test
    fun read_free_noSheet() {
        val plan = AuthKeyPlanner.plan(
            AuthIntent.ReadFile(0, rights(r = 0xE, w = 0xE, rw = 0xE)),
        )
        assertEquals(AuthBarrier.NONE, plan.barrier)
        assertFalse(plan.needsSheet)
    }

    @Test
    fun read_never_noKey() {
        val plan = AuthKeyPlanner.plan(
            AuthIntent.ReadFile(0, rights(r = 0xF, w = 0xF, rw = 0xF)),
        )
        assertEquals(AuthBarrier.NEVER, plan.barrier)
        assertTrue(plan.candidates.isEmpty())
    }

    @Test
    fun read_sessionAlreadyOk() {
        val plan = AuthKeyPlanner.plan(
            AuthIntent.ReadFile(0, rights(r = 2, w = 2, rw = 0xE)),
            currentSessionKey = 2,
        )
        assertEquals(AuthBarrier.NONE, plan.barrier)
    }

    @Test
    fun structure_freeListOff_master0() {
        val plan = AuthKeyPlanner.plan(
            AuthIntent.ExploreStructure(freeDirectoryListWithoutMaster = false),
        )
        assertEquals(AuthBarrier.NEEDS_KEY, plan.barrier)
        assertEquals(listOf(0), plan.candidates.map { it.keyNo })
        assertEquals(0, plan.preferKeyNo)
        assertFalse(plan.allowAnyKey)
    }

    @Test
    fun structure_unknown_orients0_allowAny() {
        val plan = AuthKeyPlanner.plan(AuthIntent.ExploreStructure(null))
        assertEquals(0, plan.preferKeyNo)
        assertTrue(plan.allowAnyKey)
    }

    @Test
    fun generic_allowAny() {
        val plan = AuthKeyPlanner.plan(AuthIntent.Generic, currentSessionKey = 3)
        assertTrue(plan.allowAnyKey)
        assertEquals(3, plan.preferKeyNo)
    }

    @Test
    fun write_candidates() {
        val ar = rights(r = 1, w = 2, rw = 3)
        assertEquals(listOf(2, 3), ar.writeKeyCandidates().map { it.keyNo })
    }
}
