package com.mubox.app.core

import com.mubox.app.model.LatencyTestMethod
import com.mubox.app.model.Outbound
import com.mubox.app.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyRoutePolicyTest {

    @Test
    fun tcpTransportProtocolsUseTcpPing() {
        listOf("vless", "trojan", "anytls", "socks", "http").forEach { protocol ->
            assertEquals(
                "$protocol over TCP should use TCP Ping",
                LatencyTestMethod.TCP_PING,
                LatencyRoutePolicy.resolve(LatencyTestMethod.TCP_PING, outbound(protocol, "tcp"))
            )
        }
    }

    @Test
    fun hysteria2AlwaysUsesUrlTestWhenTcpPingWasRequested() {
        assertEquals(
            LatencyTestMethod.URL_TEST,
            LatencyRoutePolicy.resolve(LatencyTestMethod.TCP_PING, outbound("hysteria2"))
        )
    }

    @Test
    fun vlessAndTrojanQuicUseUrlTestWhenTcpPingWasRequested() {
        listOf("vless", "trojan").forEach { protocol ->
            assertEquals(
                "$protocol over QUIC should use URL Test",
                LatencyTestMethod.URL_TEST,
                LatencyRoutePolicy.resolve(LatencyTestMethod.TCP_PING, outbound(protocol, "quic"))
            )
        }
    }

    @Test
    fun explicitUrlTestRemainsUrlTestForEveryProtocol() {
        listOf("vless", "trojan", "hysteria2", "anytls", "socks", "http").forEach { protocol ->
            assertEquals(
                "$protocol should retain an explicit URL Test request",
                LatencyTestMethod.URL_TEST,
                LatencyRoutePolicy.resolve(LatencyTestMethod.URL_TEST, outbound(protocol, "tcp"))
            )
        }
    }

    private fun outbound(protocol: String, transportType: String? = null) = Outbound(
        type = protocol,
        transport = transportType?.let { TransportConfig(type = it) }
    )
}
