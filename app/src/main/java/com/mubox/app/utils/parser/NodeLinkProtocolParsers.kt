@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.mubox.app.utils.parser

import android.util.Log
import com.google.gson.JsonPrimitive
import com.mubox.app.model.ObfsConfig
import com.mubox.app.model.Outbound
import com.mubox.app.model.RealityConfig
import com.mubox.app.model.TlsConfig
import com.mubox.app.model.TransportConfig
import com.mubox.app.model.UtlsConfig
import com.mubox.app.model.V2RAY_TRANSPORT_TYPES
import com.mubox.app.model.asHttpHeaderMap

@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun NodeLinkParser.parseVLessLink(link: String): Outbound? {
    try {
        val uri = java.net.URI(sanitizeUri(link))
        val name = java.net.URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
        val uuid = uri.userInfo
        val server = uri.host
        val port = if (uri.port > 0) uri.port else 443
        if (!hasRequiredLinkFields("VLESS", server, uuid, port)) return null

        val params = mutableMapOf<String, String>()
        uri.query?.split("&")?.forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
            }
        }

        val hostParam = firstParam(params, "host")
        val explicitSni = firstParam(params, "sni")?.takeIf { it.isNotBlank() }
        val transportType = firstParam(params, "type")
            ?.trim()
            ?.lowercase()
            ?.ifEmpty { "tcp" }
            ?: "tcp"
        if (isUnsupportedV2RayTransport(transportType)) {
            Log.w("NodeLinkParser", "Ignoring VLESS node with unsupported transport: $transportType")
            return null
        }
        val securityRaw = (firstParam(params, "security") ?: "none").lowercase()
        val tlsLikeTransport = transportType != "tcp" && transportType in V2RAY_TRANSPORT_TYPES
        val shouldInferTls = explicitSni != null || (port == 443 && hostParam != null && tlsLikeTransport)
        // 很多机场生成的 VLESS 分享链接会省略 security=tls。
        // 出现 sni，或 443 端口上的常见 HTTP 类传输带 Host 时，按 TLS 处理。
        val security = if (securityRaw == "none" && shouldInferTls) {
            "tls"
        } else {
            securityRaw
        }
        val sni = defaultTlsServerName(
            explicitServerName = explicitSni,
            primaryFallback = hostParam,
            server = server
        )
        val insecure = parseBooleanFlag(firstParam(params, "allowInsecure", "insecure")) == true
        val fingerprint = firstParam(params, "fp")?.takeIf { it.isNotBlank() }
        val alpnList = firstParam(params, "alpn")?.split(",")?.filter { it.isNotBlank() }
        val flow = firstParam(params, "flow")?.takeIf { it.isNotBlank() }
        val packetEncoding = firstParam(params, "packetEncoding", "packet-encoding")
            ?.takeIf { it.isNotBlank() }
        val encryption = firstParam(params, "encryption")?.trim()
        if (!encryption.isNullOrEmpty() && !encryption.equals("none", ignoreCase = true)) {
            Log.w("NodeLinkParser", "Ignoring VLESS node with unsupported private encryption")
            return null
        }

        val echConfig = parseEchConfig(params)

        val tlsConfig = when (security) {
            "tls" -> TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpnList,
                utls = (fingerprint ?: "chrome").let { UtlsConfig(enabled = true, fingerprint = it) },
                ech = echConfig
            )
            "reality" -> TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpnList,
                reality = RealityConfig(
                    enabled = true,
                    publicKey = firstParam(params, "pbk"),
                    shortId = firstParam(params, "sid")
                ),
                utls = (fingerprint ?: "chrome").let { UtlsConfig(enabled = true, fingerprint = it) },
                ech = echConfig
            )
            else -> null
        }

        val transport = when (transportType) {
            "ws" -> {
                val webSocketPathConfig = parseWebSocketPathConfig(firstParam(params, "path")) ?: return null
                TransportConfig(
                    type = "ws",
                    path = webSocketPathConfig.path,
                    headers = hostParam?.let { mapOf("Host" to it) },
                    maxEarlyData = webSocketPathConfig.maxEarlyData,
                    earlyDataHeaderName = webSocketPathConfig.earlyDataHeaderName
                )
            }
            "grpc" -> TransportConfig(
                type = "grpc",
                serviceName = firstParam(params, "serviceName", "sn") ?: ""
            )
            "h2", "http" -> TransportConfig(
                type = "http",
                path = firstParam(params, "path"),
                method = firstParam(params, "method"),
                host = parseHostList(hostParam)
            )
            "quic" -> TransportConfig(type = "quic")
            "httpupgrade" -> TransportConfig(
                type = "httpupgrade",
                path = firstParam(params, "path") ?: "/",
                host = parseSingleHost(hostParam)
            )
            "xhttp", "splithttp" -> buildXhttpTransport(hostParam, firstParam(params, "path")) { key ->
                if (key == "xPaddingBytes") {
                    firstParam(params, "xPaddingBytes", "x-padding-bytes")
                } else {
                    firstParam(params, key)
                }
            }
            else -> null
        }
        if (transportType == "quic" && tlsConfig?.enabled != true) {
            Log.w("NodeLinkParser", "Ignoring VLESS QUIC node without TLS: $name")
            return null
        }

        return Outbound(
            type = "vless",
            tag = name,
            server = server,
            serverPort = port,
            uuid = uuid,
            flow = flow,
            tls = tlsConfig,
            transport = transport,
            packetEncoding = packetEncoding
        )
    } catch (e: Exception) {
        Log.e("NodeLinkParser", "Failed to parse VLESS link", e)
    }
    return null
}

@Suppress("ReturnCount")
internal fun NodeLinkParser.parseTrojanLink(link: String): Outbound? {
    try {
        val uri = java.net.URI(sanitizeUri(link))
        val name = java.net.URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
        val password = uri.userInfo
        val server = uri.host
        val port = if (uri.port > 0) uri.port else 443
        if (!hasRequiredLinkFields("Trojan", server, password, port)) return null

        val params = parseQueryParams(uri.query)
        val transportType = firstParam(params, "type")
            ?.trim()
            ?.lowercase()
            ?.ifEmpty { "tcp" }
            ?: "tcp"
        if (isUnsupportedV2RayTransport(transportType)) {
            Log.w("NodeLinkParser", "Ignoring Trojan node with unsupported transport: $transportType")
            return null
        }

        val hostParam = firstParam(params, "host")
        val sni = defaultTlsServerName(
            explicitServerName = firstParam(params, "sni"),
            primaryFallback = hostParam,
            server = server
        )
        val insecure = parseBooleanFlag(firstParam(params, "allowInsecure", "insecure")) == true
        val fingerprint = firstParam(params, "fp")?.takeIf { it.isNotBlank() }
        val alpnList = firstParam(params, "alpn")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

        val echConfig = parseEchConfig(params)

        val tlsConfig = TlsConfig(
            enabled = true,
            serverName = sni,
            insecure = insecure,
            alpn = alpnList,
            utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) },
            ech = echConfig
        )
        val transport = buildTrojanTransport(params, hostParam)
        if (transportType != "tcp" && transport == null) return null

        return Outbound(
            type = "trojan",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            tls = tlsConfig,
            transport = transport
        )
    } catch (e: Exception) {
        Log.e("NodeLinkParser", "Failed to parse Trojan link", e)
    }
    return null
}

internal fun NodeLinkParser.buildTrojanTransport(
    params: Map<String, String>,
    hostParam: String?
): TransportConfig? {
    val transportType = firstParam(params, "type")
        ?.trim()
        ?.lowercase()
        ?.ifEmpty { "tcp" }
        ?: "tcp"
    return when (transportType) {
        "ws" -> {
            val webSocketPathConfig = parseWebSocketPathConfig(firstParam(params, "path")) ?: return null
            TransportConfig(
                type = "ws",
                path = webSocketPathConfig.path,
                headers = hostParam?.let { mapOf("Host" to it) },
                maxEarlyData = webSocketPathConfig.maxEarlyData,
                earlyDataHeaderName = webSocketPathConfig.earlyDataHeaderName
            )
        }

        "grpc" -> TransportConfig(
            type = "grpc",
            serviceName = firstParam(params, "serviceName", "sn") ?: ""
        )

        "h2", "http" -> TransportConfig(
            type = "http",
            path = firstParam(params, "path"),
            method = firstParam(params, "method"),
            host = parseHostList(hostParam)
        )

        "quic" -> TransportConfig(type = "quic")

        "httpupgrade" -> TransportConfig(
            type = "httpupgrade",
            path = firstParam(params, "path") ?: "/",
            host = parseSingleHost(hostParam)
        )

        "xhttp", "splithttp" -> buildXhttpTransport(hostParam, firstParam(params, "path")) { key ->
            if (key == "xPaddingBytes") {
                firstParam(params, "xPaddingBytes", "x-padding-bytes")
            } else {
                firstParam(params, key)
            }
        }

        else -> null
    }
}

internal fun NodeLinkParser.parseHysteria2Link(link: String): Outbound? {
    try {
        val uri = java.net.URI(sanitizeUri(link.replace("hy2://", "hysteria2://")))
        val name = java.net.URLDecoder.decode(uri.fragment ?: "Hysteria2 Node", "UTF-8")
        val password = uri.userInfo
        val server = uri.host
        val port = if (uri.port == -1) 443 else uri.port

        val params = mutableMapOf<String, String>()
        uri.query?.split("&")?.forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
            }
        }

        return Outbound(
            type = "hysteria2",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            upMbps = firstParam(params, "up_mbps", "upmbps", "up")?.toIntOrNull(),
            downMbps = firstParam(params, "down_mbps", "downmbps", "down")?.toIntOrNull(),
            tls = TlsConfig(
                enabled = true,
                serverName = defaultTlsServerName(
                    explicitServerName = params["sni"],
                    server = server
                ),
                insecure = parseBooleanQueryParam(
                    params["insecure"] ?: params["allowInsecure"] ?: params["skip-cert-verify"]
                ),
                alpn = parseCsvQueryParam(params["alpn"]),
                certificatePublicKeySha256 = params["pinSHA256"]?.let { listOf(it) }
            ),
            obfs = params["obfs"]?.let { ObfsConfig(type = it, password = params["obfs-password"]) },
            serverPorts = parseServerPorts(firstParam(params, "mport", "server_ports")),
            hopInterval = firstParam(params, "hop_interval", "hop-interval"),
            network = firstParam(params, "network")?.let(::listOf),
            disableMtuDiscovery = parseBooleanFlag(
                firstParam(params, "disable_mtu_discovery", "disable-mtu-discovery")
            )
        )
    } catch (e: Exception) {
        Log.e("NodeLinkParser", "Failed to parse Hy2 link", e)
    }
    return null
}

internal fun NodeLinkParser.parseBooleanQueryParam(value: String?): Boolean? {
    return when (value?.trim()?.lowercase()) {
        null, "" -> null
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }
}

internal fun NodeLinkParser.parseCsvQueryParam(value: String?): List<String>? {
    return value
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
}

internal fun NodeLinkParser.parseServerPorts(value: String?): List<String>? {
    return value
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
}

internal fun NodeLinkParser.parseAnyTLSLink(link: String): Outbound? {
    try {
        val uri = java.net.URI(sanitizeUri(link))
        val name = java.net.URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
        val password = uri.userInfo
        val server = uri.host
        val port = if (uri.port > 0) uri.port else 443

        val params = mutableMapOf<String, String>()
        uri.query?.split("&")?.forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                try {
                    params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                } catch (e: Exception) {
                    params[parts[0]] = parts[1]
                }
            }
        }

        val sni = defaultTlsServerName(
            explicitServerName = params["sni"],
            server = server
        )
        val insecure = params["insecure"] == "1" || params["allowInsecure"] == "1"
        val alpnList = params["alpn"]?.split(",")?.filter { it.isNotBlank() }
        val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }

        val idleSessionCheckInterval = params["idle_session_check_interval"]
        val idleSessionTimeout = params["idle_session_timeout"]
        val minIdleSession = params["min_idle_session"]?.toIntOrNull()

        return Outbound(
            type = "anytls",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            idleSessionCheckInterval = idleSessionCheckInterval,
            idleSessionTimeout = idleSessionTimeout,
            minIdleSession = minIdleSession,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = insecure,
                alpn = alpnList,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            )
        )
    } catch (e: Exception) {
        Log.e("NodeLinkParser", "Failed to parse AnyTLS link", e)
    }
    return null
}

internal fun NodeLinkParser.parseExtraHeaders(params: Map<String, String>): Map<String, String>? {
    val normalized = linkedMapOf<String, MutableList<String>>()
    params.forEach { (key, rawValue) ->
        if (!key.equals("extra_headers", ignoreCase = true)) return@forEach
        rawValue
            .replace("\r\n", "\n")
            .split("\n", ";")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separatorIndex = line.indexOf(':')
                if (separatorIndex <= 0) return@forEach

                val headerName = line.substring(0, separatorIndex).trim()
                val headerValue = line.substring(separatorIndex + 1).trim()
                if (headerName.isNotEmpty() && headerValue.isNotEmpty()) {
                    normalized.getOrPut(headerName) { mutableListOf() }.add(headerValue)
                }
            }
    }

    return normalized
        .mapValues { (_, values) -> values.toList() }
        .takeIf { it.isNotEmpty() }
        ?.asHttpHeaderMap()
}

/**
 *       https://[username:password@]host:port[#name]
 */

internal fun NodeLinkParser.parseHttpLink(link: String, useTls: Boolean): Outbound? {
    return try {
        val uri = java.net.URI(sanitizeUri(link))
        val server = uri.host
        if (!looksLikeHttpProxyUri(uri) || server.isNullOrBlank()) {
            null
        } else {
            val name = java.net.URLDecoder.decode(
                uri.fragment ?: if (useTls) "HTTPS Proxy" else "HTTP Proxy",
                "UTF-8"
            )
            val (username, password) = parseHttpCredentials(uri)
            val port = if (uri.port > 0) uri.port else if (useTls) 443 else 8080
            val params = parseQueryParams(uri.rawQuery)

            Outbound(
                type = "http",
                tag = name,
                server = server,
                serverPort = port,
                username = username,
                password = password,
                tls = buildHttpTlsConfig(useTls, server, params),
                path = firstParam(params, "proxy_path", "path"),
                headers = parseExtraHeaders(
                    mapOf("extra_headers" to firstParam(params, "headers", "extra_headers").orEmpty())
                )
            )
        }
    } catch (e: Exception) {
        Log.e("NodeLinkParser", "Failed to parse HTTP/HTTPS link", e)
        null
    }
}

internal fun NodeLinkParser.parseHttpCredentials(uri: java.net.URI): Pair<String?, String?> {
    val userInfo = uri.userInfo ?: return null to null
    val parts = userInfo.split(":", limit = 2)
    val username = java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")
        .takeIf { it.isNotBlank() }
    val password = java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
        .takeIf { it.isNotBlank() }
    return username to password
}

internal fun NodeLinkParser.buildHttpTlsConfig(
    useTls: Boolean,
    server: String,
    params: Map<String, String> = emptyMap()
): TlsConfig? {
    if (!useTls) return null
    return TlsConfig(
        enabled = true,
        serverName = defaultTlsServerName(
            explicitServerName = firstParam(params, "sni", "server_name"),
            server = server
        ),
        insecure = parseBooleanFlag(firstParam(params, "insecure", "allowInsecure")),
        alpn = parseCsvQueryParam(firstParam(params, "alpn")),
        ca = firstParam(params, "certificate", "ca")?.let(::listOf),
        caPath = firstParam(params, "certificate_path", "ca_path"),
        certificate = firstParam(params, "client_certificate")?.let(::listOf),
        certificatePath = firstParam(params, "client_certificate_path"),
        key = firstParam(params, "client_key")?.let(::listOf),
        keyPath = firstParam(params, "client_key_path"),
        certificatePublicKeySha256 = firstParam(params, "pinSHA256")?.let { listOf(it) }
    )
}

internal fun NodeLinkParser.looksLikeHttpProxyUri(uri: java.net.URI): Boolean {
    val hasUserInfo = !uri.userInfo.isNullOrBlank()
    val hasExplicitPort = uri.port > 0
    val hasName = !uri.fragment.isNullOrBlank()
    if (!hasUserInfo && !hasExplicitPort && !hasName) {
        return false
    }

    val path = uri.rawPath.orEmpty()
    val hasNonRootPath = path.isNotBlank() && path != "/"
    val hasQuery = !uri.rawQuery.isNullOrBlank()
    val hasContentPath = hasNonRootPath || hasQuery
    val lacksExplicitProxyIdentity = !hasUserInfo && !hasName
    if (hasContentPath && lacksExplicitProxyIdentity) {
        return false
    }

    return true
}

/**
 *       socks://[username:password@]host:port[#name]
 */

internal fun NodeLinkParser.parseSocks5Link(link: String): Outbound? {
    try {

        val schemeVersion = when {
            link.startsWith("socks4a://") -> "4a"
            link.startsWith("socks4://") -> "4"
            else -> "5"
        }
        val normalizedLink = link
            .replaceFirst("socks5://", "socks://")
            .replaceFirst("socks4a://", "socks://")
            .replaceFirst("socks4://", "socks://")
        val uri = java.net.URI(sanitizeUri(normalizedLink))
        val name = java.net.URLDecoder.decode(uri.fragment ?: "SOCKS5 Proxy", "UTF-8")
        val server = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 1080
        val params = parseQueryParams(uri.rawQuery)

        var username: String? = null
        var password: String? = null
        if (uri.userInfo != null) {
            val parts = uri.userInfo.split(":", limit = 2)
            username = java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")
                .takeIf { it.isNotBlank() }
            password = java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
                .takeIf { it.isNotBlank() }
        }

        return Outbound(
            type = "socks",
            tag = name,
            server = server,
            serverPort = port,
            version = JsonPrimitive(firstParam(params, "version") ?: schemeVersion),
            username = username,
            password = password,
            network = firstParam(params, "network")?.let(::listOf),
            udpOverTcp = if (parseBooleanFlag(firstParam(params, "uot", "udp_over_tcp")) == true) {
                com.mubox.app.model.UdpOverTcpConfig(enabled = true)
            } else {
                null
            }
        )
    } catch (e: Exception) {
        Log.e("NodeLinkParser", "Failed to parse SOCKS5 link", e)
    }
    return null
}
