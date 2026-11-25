package com.mubox.app.core

import com.mubox.app.model.LatencyTestMethod
import com.mubox.app.model.Outbound

/**
 * Resolves the requested lightweight probe to the route supported by an outbound.
 * TCP Ping measures a physical TCP connect; URL Test measures an HTTP request through
 * the configured proxy. These are distinct metrics and must remain separately labelled.
 */
internal object LatencyRoutePolicy {

    fun resolve(
        requestedMethod: LatencyTestMethod,
        outbound: Outbound
    ): LatencyTestMethod {
        val normalized = requestedMethod.normalized()
        if (normalized == LatencyTestMethod.URL_TEST) return LatencyTestMethod.URL_TEST
        return if (requiresUrlTestForTcpPing(outbound)) {
            LatencyTestMethod.URL_TEST
        } else {
            LatencyTestMethod.TCP_PING
        }
    }

    fun requiresUrlTestForTcpPing(outbound: Outbound): Boolean {
        val protocol = outbound.type.trim()
        if (protocol.equals("hysteria2", ignoreCase = true)) return true

        val isVlessOrTrojan = protocol.equals("vless", ignoreCase = true) ||
            protocol.equals("trojan", ignoreCase = true)
        val transportType = outbound.transport?.type?.trim()
        return isVlessOrTrojan && transportType.equals("quic", ignoreCase = true)
    }
}
