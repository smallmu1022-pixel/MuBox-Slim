package com.mubox.app.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BackgroundPowerManagerTest {

    @Test
    fun remainingDelayUsesOriginalAwayTimestamp() {
        val minute = 60_000L

        val remaining = BackgroundPowerManager.remainingPowerSavingDelayMs(
            thresholdMs = 30 * minute,
            userAwayAtMs = minute,
            nowMs = 21 * minute
        )

        assertEquals(10 * minute, remaining)
    }

    @Test
    fun forcePowerSavingSuspendsAndResumesProcessesOnce() {
        val callbacks = RecordingCallbacks()
        val manager = BackgroundPowerManager(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        manager.init(callbacks, thresholdMs = Long.MAX_VALUE)

        manager.forceEnterPowerSaving()
        manager.forceEnterPowerSaving()

        assertTrue(manager.isPowerSaving)
        assertEquals(1, callbacks.suspendCalls)

        manager.forceExitPowerSaving()
        manager.forceExitPowerSaving()

        assertFalse(manager.isPowerSaving)
        assertEquals(1, callbacks.resumeCalls)
    }

    @Test
    fun initializeDeviceStateImmediatelyEvaluatesCurrentScreenAndAppState() {
        val manager = BackgroundPowerManager(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        manager.init(RecordingCallbacks(), thresholdMs = Long.MAX_VALUE)

        manager.initializeDeviceState(screenOn = false, appInForeground = false)

        val awayStats = manager.getStats()
        assertEquals(true, awayStats["isScreenOff"])
        assertEquals(true, awayStats["isAppInBackground"])
        assertEquals(true, awayStats["isUserAway"])

        manager.initializeDeviceState(screenOn = true, appInForeground = true)

        val presentStats = manager.getStats()
        assertEquals(false, presentStats["isScreenOff"])
        assertEquals(false, presentStats["isAppInBackground"])
        assertEquals(false, presentStats["isUserAway"])
        manager.cleanup()
    }

    private class RecordingCallbacks : BackgroundPowerManager.Callbacks {
        var suspendCalls: Int = 0
        var resumeCalls: Int = 0

        override fun suspendNonEssentialProcesses() {
            suspendCalls++
        }

        override fun resumeNonEssentialProcesses() {
            resumeCalls++
        }
    }
}
