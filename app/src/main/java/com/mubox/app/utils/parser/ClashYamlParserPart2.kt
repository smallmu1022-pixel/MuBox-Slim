package com.mubox.app.utils.parser

import com.google.gson.JsonPrimitive
import com.mubox.app.model.MultiplexConfig
import com.mubox.app.model.Outbound
import com.mubox.app.model.TlsConfig
import com.mubox.app.model.TransportConfig
import com.mubox.app.model.UInt32JsonAdapter
import com.mubox.app.model.UtlsConfig

@Suppress("TooManyFunctions")
open class ClashYamlParserPart2 : ClashYamlParserPart1() {
    protected override fun parseAnyTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null

        val password = asString(map["password"])
            ?: asString(map["uuid"])
            ?: asString(map["token"])
            ?: return null

        val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true || asBool(map["allow-insecure"]) == true || asBool(map["insecure"]) == true
        val alpn = asStringList(map["alpn"])

        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"]) ?: globalFingerprint

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val idleSessionCheckInterval = asString(map["idle-session-check-interval"])
        val idleSessionTimeout = asString(map["idle-session-timeout"])
        val minIdleSession = asInt(map["min-idle-session"])

        return Outbound(
            type = "anytls",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            idleSessionCheckInterval = idleSessionCheckInterval,
            idleSessionTimeout = idleSessionTimeout,
            minIdleSession = minIdleSession,
            tls = buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    }

    protected override fun parseHttp(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null

        val username = asString(map["username"])
        val password = asString(map["password"])

        val tlsEnabled = asBool(map["tls"]) == true

        val fingerprint = asString(map["client-fingerprint"]) ?: asString(map["fingerprint"]) ?: globalFingerprint
        val tlsConfig = if (tlsEnabled) {
            val sni = asString(map["sni"]) ?: asString(map["servername"]) ?: server

            val skipCertVerify = map["skip-cert-verify"]
            val insecure = asBool(skipCertVerify) == true
            val alpn = asStringList(map["alpn"])
            val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion
            buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        } else null

        val path = asString(map["path"])
        val headersRaw = map["headers"] as? Map<*, *>
        val headers = if (headersRaw != null) {
            val headerMap = mutableMapOf<String, String>()
            headersRaw.forEach { (k, v) ->
                val ks = asString(k) ?: return@forEach
                val vs = asString(v) ?: return@forEach
                headerMap[ks] = vs
            }
            headerMap.takeIf { it.isNotEmpty() }
        } else null

        return Outbound(
            type = "http",
            tag = name,
            server = server,
            serverPort = port,
            username = username,
            password = password,
            tls = tlsConfig,
            path = path,
            headers = headers
        )
    }

    protected override fun parseSocks(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound? {
        if (server == null || port == null) return null

        val tlsEnabled = asBool(map["tls"]) == true
        if (tlsEnabled) {
            android.util.Log.w("ClashYamlParser", "SOCKS proxy '$name' has TLS enabled but sing-box does not support it, importing without TLS")
        }

        val username = asString(map["username"])
        val password = asString(map["password"])
        val version = asString(map["version"]) ?: "5"
        val network = asString(map["network"])
        val udpOverTcp = asBool(map["udp-over-tcp"]) == true || asBool(map["udp_over_tcp"]) == true

        return Outbound(
            type = "socks",
            tag = name,
            server = server,
            serverPort = port,
            version = JsonPrimitive(version),
            username = username,
            password = password,
            network = network?.let(::listOf),
            udpOverTcp = if (udpOverTcp) com.mubox.app.model.UdpOverTcpConfig(enabled = true) else null
        )
    }

    // --- Helpers ---

    /**
     * smux:
     *   enabled: true
     *   protocol: smux  # smux/yamux/h2mux
     *   max-connections: 4
     *   min-streams: 4
     *   max-streams: 0
     *   padding: false
     */

    protected override fun parseSmux(map: Map<*, *>): MultiplexConfig? {
        val smuxOpts = map["smux"] as? Map<*, *> ?: return null
        val enabled = asBool(smuxOpts["enabled"]) == true
        if (!enabled) return null

        return MultiplexConfig(
            enabled = true,
            protocol = asString(smuxOpts["protocol"]) ?: "smux",
            maxConnections = asInt(smuxOpts["max-connections"]),
            minStreams = asInt(smuxOpts["min-streams"]),
            maxStreams = asInt(smuxOpts["max-streams"]),
            padding = asBool(smuxOpts["padding"])
        )
    }

    protected override fun buildTlsConfig(
        map: Map<*, *>,
        enabled: Boolean,
        serverName: String?,
        insecure: Boolean?,
        alpn: List<String>?,
        minVersion: String?,
        utls: UtlsConfig?,
        reality: com.mubox.app.model.RealityConfig?): TlsConfig {
        return TlsConfig(
            enabled = enabled,
            serverName = serverName,
            insecure = insecure,
            alpn = alpn,
            minVersion = minVersion,
            utls = utls,
            reality = reality,
            ca = firstNonEmptyStringList(map, "ca", "ca-cert", "ca_cert", "caPem", "ca_pem"),
            caPath = firstNonBlankString(map, "ca_path", "ca-path"),
            certificate = firstNonEmptyStringList(
                map,
                "certificate",
                "cert",
                "client-cert",
                "client_cert"
            ),
            certificatePath = firstNonBlankString(
                map,
                "certificate_path",
                "certificate-path",
                "cert_path",
                "cert-path",
                "client-cert-path",
                "client_cert_path"
            ),
            key = firstNonEmptyStringList(map, "key", "client-key", "client_key"),
            keyPath = firstNonBlankString(map, "key_path", "key-path", "client-key-path", "client_key_path")
        )
    }

    protected override fun firstNonBlankString(map: Map<*, *>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            asString(map[key])?.takeIf { it.isNotBlank() }
        }
    }

    private fun firstNonEmptyStringList(map: Map<*, *>, vararg keys: String): List<String>? {
        return keys.firstNotNullOfOrNull { key ->
            when (val value = map[key]) {
                is List<*> -> value.mapNotNull { item ->
                    asString(item)?.takeIf { it.isNotBlank() }
                }.takeIf { it.isNotEmpty() }
                else -> asString(value)?.takeIf { it.isNotBlank() }?.let(::listOf)
            }
        }
    }

    protected override fun asNestedMap(v: Any?): Map<*, *>? = v as? Map<*, *>

    protected override fun getUserAgent(fingerprint: String?): String {
        return if (fingerprint?.contains("chrome", ignoreCase = true) == true) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        }
    }

    protected override fun asString(v: Any?): String? = when (v) {
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        is List<*> -> v.firstOrNull()?.toString()
        else -> null
    }

    protected override fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    protected override fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.lowercase()) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> null
        }
        else -> null
    }

    protected override fun asStringList(v: Any?): List<String>? {
        return when (v) {
            is List<*> -> v.mapNotNull { asString(it) }.takeIf { it.isNotEmpty() }
            is String -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    @Suppress("CognitiveComplexMethod")
    protected override fun buildWsOrHttpUpgradeTransport(
        wsOpts: Map<*, *>?,
        path: String,
        headers: Map<String, String>,
        host: String?,
        forceHttpUpgrade: Boolean
    ): TransportConfig? {
        val isHttpUpgrade = forceHttpUpgrade || asBool(wsOpts?.get("v2ray-http-upgrade")) == true
        val rawMaxEarlyData = asString(wsOpts?.get("max-early-data")).takeUnless { isHttpUpgrade }
        val maxEarlyData = rawMaxEarlyData?.toLongOrNull()?.let { value ->
            runCatching { UInt32JsonAdapter.requireValue(value) }.getOrNull()
        }
        if (rawMaxEarlyData != null && maxEarlyData == null) return null
        val earlyDataHeaderName = if (maxEarlyData != null) {
            asString(wsOpts?.get("early-data-header-name")) ?: "Sec-WebSocket-Protocol"
        } else {
            null
        }
        val httpUpgradeHost = host
            ?.split(',')
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()

        return TransportConfig(
            type = if (isHttpUpgrade) "httpupgrade" else "ws",
            path = path,
            headers = if (isHttpUpgrade) headers.withoutHostHeader().ifEmpty { null } else headers,
            host = if (isHttpUpgrade) httpUpgradeHost?.let { listOf(it) } else null,
            maxEarlyData = if (isHttpUpgrade) null else maxEarlyData,
            earlyDataHeaderName = if (isHttpUpgrade) null else earlyDataHeaderName
        )
    }

    protected override fun Map<String, String>.withoutHostHeader(): Map<String, String> {
        return filterKeys { !it.equals("Host", ignoreCase = true) }
    }
}
