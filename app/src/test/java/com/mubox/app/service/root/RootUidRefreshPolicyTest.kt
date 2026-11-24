package com.mubox.app.service.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootUidRefreshPolicyTest {
    @Test
    fun uidInventoryUsesFiveMinuteFallbackInsteadOfFrequentPolling() {
        val rootRuntime = File("src/main/java/com/mubox/app/service/root/runtime/MuBoxRootServiceRuntime.kt")
            .readText(Charsets.UTF_8)
        val monitor = rootRuntime.substringAfter("internal fun MuBoxRootService.startUidMonitor(")
            .substringBefore("internal fun MuBoxRootService.failUidMonitorSafely")

        assertEquals(5 * 60_000L, MuBoxRootService.UID_REFRESH_FALLBACK_INTERVAL_MS)
        assertTrue(monitor.contains("delay(MuBoxRootService.UID_REFRESH_FALLBACK_INTERVAL_MS)"))
        assertTrue(monitor.contains("resolver.resolveRouting("))
        assertTrue(monitor.contains("installUidRefreshGuard(request, current, artifacts.plan, currentResolved)"))
    }

    @Test
    fun packageAndUserChangesStillRequestImmediateUidRefresh() {
        val service = File("src/main/java/com/mubox/app/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val runtime = File("src/main/java/com/mubox/app/service/root/runtime/RootTransparentForegroundRuntime.kt")
            .readText(Charsets.UTF_8)
        val packageReceiver = service.substringAfter("internal val packageChangeReceiver")
            .substringBefore("internal fun acquireUidRefreshWakeLock")
        val userReceiver = service.substringAfter("internal val userChangeReceiver")
            .substringBefore("override fun onCreate()")
        val refresh = runtime.substringAfter("internal fun RootTransparentForegroundService.scheduleUidRefresh(")
            .substringBefore("@Suppress(\"LongMethod\")")
        val refreshRuntime = runtime.substringAfter("internal suspend fun RootTransparentForegroundService.refreshUidRoutingLocked(")
            .substringBefore("internal fun RootTransparentForegroundService.publishUidRefreshBlocked")

        assertTrue(packageReceiver.contains("scheduleUidRefresh(intent?.action.orEmpty())"))
        assertTrue(userReceiver.contains("scheduleUidRefresh(intent?.action.orEmpty())"))
        assertTrue(runtime.contains("Intent.ACTION_PACKAGE_ADDED"))
        assertTrue(runtime.contains("Intent.ACTION_PACKAGE_REMOVED"))
        assertTrue(runtime.contains("Intent.ACTION_PACKAGE_REPLACED"))
        assertTrue(runtime.contains("Intent.ACTION_PACKAGE_CHANGED"))
        assertTrue(refresh.contains("refreshUidRoutingLocked(reason, token)"))
        assertTrue(refreshRuntime.contains("rootService.blockForUidRefresh(runtimeSessionId)"))
    }
}
