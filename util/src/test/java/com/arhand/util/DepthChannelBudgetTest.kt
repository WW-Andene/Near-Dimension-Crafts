package com.arhand.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthChannelBudgetTest {

    private val ids = listOf("da2", "slam")

    @Test
    fun `every channel runs every frame when under budget`() {
        val budget = DepthChannelBudget(budgetMs = 25f)
        repeat(10) {
            val decision = budget.tick(ids)
            assertEquals(true, decision["da2"])
            assertEquals(true, decision["slam"])
            budget.report("da2", 2f)
            budget.report("slam", 2f)
        }
    }

    @Test
    fun `lowest priority channel is shed first when over budget`() {
        val budget = DepthChannelBudget(budgetMs = 25f)
        budget.tick(ids)                // warm-up: ema starts at 0, nothing over budget yet
        budget.report("da2", 5f)
        budget.report("slam", 1000f)    // one EMA update already pushes slam far over budget
        budget.tick(ids)                // now sees the elevated ema -> sheds exactly one step
        // "slam" is last in the priority list, so it is throttled before "da2".
        assertEquals(1, budget.everyN("da2"))
        assertTrue(budget.everyN("slam") > 1)
    }

    @Test
    fun `no channel is ever fully disabled`() {
        val budget = DepthChannelBudget(budgetMs = 1f, maxEvery = 4)
        repeat(200) {
            budget.tick(ids)
            budget.report("da2", 50f)
            budget.report("slam", 50f)
        }
        assertTrue(budget.everyN("da2") <= 4)
        assertTrue(budget.everyN("slam") <= 4)
        // Every channel must still run at least once every maxEvery frames.
        var da2Ran = false
        var slamRan = false
        repeat(4) {
            val decision = budget.tick(ids)
            if (decision["da2"] == true) da2Ran = true
            if (decision["slam"] == true) slamRan = true
            budget.report("da2", 50f)
            budget.report("slam", 50f)
        }
        assertTrue(da2Ran)
        assertTrue(slamRan)
    }

    @Test
    fun `recovers after load drops`() {
        val budget = DepthChannelBudget(budgetMs = 25f, recoveryRatio = 0.70f)

        // Sustained heavy load — shed at least "slam", possibly "da2" too depending on
        // exactly how many frames it takes the EMA to decay back down while this loop
        // keeps reporting high cost; either way, some throttling must have kicked in.
        repeat(5) {
            budget.tick(ids)
            budget.report("da2", 5f)
            budget.report("slam", 1000f)
        }
        assertTrue(budget.everyN("slam") > 1)

        // Load drops to near-zero and stays there. Recovery only restores one step per
        // 30 frames (per channel, highest priority first), so this must run long enough
        // to fully drain even the worst case — both channels shed all the way to
        // maxEvery — well within 500 frames.
        repeat(500) {
            budget.tick(ids)
            budget.report("da2", 0f)
            budget.report("slam", 0f)
        }
        assertEquals(1, budget.everyN("da2"))
        assertEquals(1, budget.everyN("slam"))
    }

    @Test
    fun `reset clears all state`() {
        val budget = DepthChannelBudget(budgetMs = 1f)
        repeat(10) {
            budget.tick(ids)
            budget.report("da2", 50f)
            budget.report("slam", 50f)
        }
        assertTrue(budget.everyN("slam") > 1)
        budget.reset()
        assertEquals(1, budget.everyN("slam"))
        assertEquals(1, budget.everyN("da2"))
        assertEquals(0f, budget.totalEma)
    }
}
