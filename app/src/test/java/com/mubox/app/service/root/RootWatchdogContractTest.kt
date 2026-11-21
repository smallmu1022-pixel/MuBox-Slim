package com.mubox.app.service.root

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootWatchdogContractTest {
    @Test
    fun staleInstalledScriptNeverMatchesBundledScript() {
        assertFalse(rootScriptContentMatches("old-script", "new-script"))
        assertFalse(rootScriptContentMatches(null, "new-script"))
        assertTrue(rootScriptContentMatches("new-script", "new-script"))
    }

    @Test
    fun cleanupTouchesOnlyKnownMuBoxStateAndReportsRealDiagnostics() {
        val watchdog = File("src/main/assets/root/mubox-root-watchdog.sh").readText()
        val cleanup = File("src/main/assets/root/mubox-root-cleanup-owned.sh").readText()

        listOf(
            "MBX_OUT4", "MBX_PRE4", "MBX_IN4", "MBX_RED4",
            "MBX_OUT6", "MBX_PRE6", "MBX_IN6", "MBX_RED6",
            "MBX_BLOCK4", "MBX_BLOCK6", "MBX_QUIC4", "MBX_QUIC6",
            "MBX_PRIV6", "MBX_GUARD4", "MBX_GUARD6"
        ).forEach { assertTrue(cleanup.contains(it)) }
        assertTrue(cleanup.contains("delete_known_chains"))
        assertTrue(cleanup.contains("remaining_owned_rules"))
        assertTrue(cleanup.contains("remaining_owned_chains"))
        assertTrue(cleanup.contains("NETFILTER_VERIFICATION_FAILED:"))
        assertTrue(cleanup.contains("backend="))
        assertTrue(cleanup.contains("cleanup_command="))
        assertTrue(cleanup.contains("mkdir \"\$LOCK_DIR\""))
        assertFalse(cleanup.contains("iptables -F"))
        assertFalse(cleanup.contains("ip6tables -F"))
        assertFalse(cleanup.contains("nft flush ruleset"))
        assertFalse(cleanup.contains("rm -rf"))
        assertFalse(watchdog.contains("rm -rf"))
        assertFalse(watchdog.contains("setenforce"))
        assertFalse(cleanup.contains("setenforce"))
        assertFalse(watchdog.contains("NOW - LEASE"))
        assertTrue(watchdog.contains("CURRENT_ROOT_START_TIME"))
    }

    @Test
    fun staleAckDoesNotKillAStillRunningWatchdog() {
        assertFalse(shouldReportWatchdogLost(wasHealthy = true, ackHealthy = false, watchdogProcessAlive = true))
        assertTrue(shouldReportWatchdogLost(wasHealthy = true, ackHealthy = false, watchdogProcessAlive = false))
    }
}
