package com.mubox.app.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyEntryPointRoutingTest {

    @Test
    fun dashboardSingleAndBatchTestsFollowSavedMethodWithoutPopup() {
        val dashboardRuntime = File(
            "src/main/java/com/mubox/app/viewmodel/DashboardConnectionRuntime.kt"
        ).readText(Charsets.UTF_8)
        val singleTest = dashboardRuntime
            .substringAfter("internal fun DashboardViewModel.startPingTestRuntime()")
            .substringBefore("internal fun DashboardViewModel.stopPingTestRuntime()")
        val batchTest = dashboardRuntime
            .substringAfter("internal fun DashboardViewModel.testAllNodesLatencyRuntime(")
            .substringBefore("internal fun DashboardViewModel.getActiveProfileNameRuntime()")

        assertTrue(singleTest.contains("configRepository.testNodeLatency(targetNodeId)"))
        assertTrue(batchTest.contains("methodOverride = methodOverride"))
        assertTrue(batchTest.contains("methodOverride: com.mubox.app.model.LatencyTestMethod? = null"))
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

        assertTrue(singleTest.contains("configRepository.testNodeLatency(nodeId)"))
        assertTrue(!singleTest.contains("methodOverride"))
        assertTrue(batchTest.contains("targetNodeIds = targetIds"))
        assertTrue(!batchTest.contains("methodOverride"))
    }

    @Test
    fun batchCoreRoutesQuicAndHysteriaThroughUrlTestInOneSharedImplementation() {
        val source = File("src/main/java/com/mubox/app/core/SingBoxLatencyRuntime.kt")
            .readText(Charsets.UTF_8)
        assertTrue(source.contains("settings.latencyTestMethod.normalized() != LatencyTestMethod.URL_TEST"))
        assertTrue(source.contains("LatencyRoutePolicy.requiresUrlTestForTcpPing(it)"))
        assertTrue(source.contains("runTcpingBatch(tcpTargets"))
        assertTrue(source.contains("serviceTargets = urlTestTargets"))
    }
}
