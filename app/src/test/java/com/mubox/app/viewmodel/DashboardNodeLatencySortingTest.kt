package com.mubox.app.viewmodel

import com.mubox.app.model.LatencyTestMethod
import com.mubox.app.model.NodeFilter
import com.mubox.app.model.NodeSortType
import com.mubox.app.model.NodeUi
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardNodeLatencySortingTest {

    @Test
    fun latencySortGroupsMeasurementsByMethodAndLeavesUnknownLast() {
        val nodes = listOf(
            node("url-slow", 300L, LatencyTestMethod.URL_TEST),
            node("unknown-fast", 1L, null),
            node("tcp-slow", 100L, LatencyTestMethod.TCP_PING),
            node("url-fast", 1L, LatencyTestMethod.URL_TEST),
            node("tcp-fast", 10L, LatencyTestMethod.TCP_PING)
        )

        val sorted = buildDashboardNodes(
            nodes = nodes,
            filter = NodeFilter(),
            sortType = NodeSortType.LATENCY,
            customOrder = emptyList()
        )

        assertEquals(
            listOf("tcp-fast", "tcp-slow", "url-fast", "url-slow", "unknown-fast"),
            sorted.map { it.id }
        )
    }

    private fun node(id: String, latency: Long, method: LatencyTestMethod?) = NodeUi(
        id = id,
        name = id,
        protocol = "vless",
        group = "test",
        latencyMs = latency,
        latencyTestMethod = method,
        sourceProfileId = "profile"
    )
}
