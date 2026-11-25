package com.mubox.app.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyEntryPointRoutingTest {

    @Test
    fun dashboardSingleAndBatchTestsRequestAutomaticProtocolRouting() {
        val dashboardRuntime = File(
            "src/main/java/com/mubox/app/viewmodel/DashboardConnectionRuntime.kt"
        ).readText(Charsets.UTF_8)
        val singleTest = dashboardRuntime
            .substringAfter("internal fun DashboardViewModel.startPingTestRuntime()")
            .substringBefore("internal fun DashboardViewModel.stopPingTestRuntime()")
        val batchTest = dashboardRuntime
            .substringAfter("internal fun DashboardViewModel.testAllNodesLatencyRuntime(")
            .substringBefore("internal fun DashboardViewModel.getActiveProfileNameRuntime()")

        assertTrue(singleTest.contains("methodOverride = LatencyTestMethod.TCP_PING"))
        assertTrue(batchTest.contains("LatencyTestMethod.TCP_PING"))
        assertTrue(batchTest.contains("testAllNodesLatency("))
    }

    @Test
    fun nodeListSingleAndBatchTestsUseAutomaticRoutingForSubscriptionNodesToo() {
        val source = File("src/main/java/com/mubox/app/viewmodel/NodesViewModel.kt")
            .readText(Charsets.UTF_8)
        val singleTest = source
            .substringAfter("fun testLatency(nodeId: String)")
            .substringBefore("fun testAllLatency()")
        val batchTest = source
            .substringAfter("fun testAllLatency()")
            .substringBefore("fun clearAllLatencies()")

        assertTrue(singleTest.contains("methodOverride = LatencyTestMethod.TCP_PING"))
        assertTrue(batchTest.contains("targetNodeIds = targetIds"))
        assertTrue(batchTest.contains("methodOverride = LatencyTestMethod.TCP_PING"))
    }

    @Test
    fun batchCoreRoutesQuicAndHysteriaThroughUrlTestInOneSharedImplementation() {
        val source = File("src/main/java/com/mubox/app/core/SingBoxLatencyRuntime.kt")
            .readText(Charsets.UTF_8)
        assertTrue(source.contains("LatencyRoutePolicy.requiresUrlTestForTcpPing(it)"))
        assertTrue(source.contains("runTcpingBatch(tcpTargets"))
        assertTrue(source.contains("serviceTargets = urlTestTargets"))
    }
}
