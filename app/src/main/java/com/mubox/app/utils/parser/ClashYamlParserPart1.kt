package com.mubox.app.utils.parser

import com.mubox.app.model.AppSettings
import com.mubox.app.model.Outbound
import com.mubox.app.model.SingBoxConfig
import com.mubox.app.model.TransportConfig
import com.mubox.app.model.UtlsConfig
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

private val CLASH_V2RAY_NETWORKS = setOf(
    "tcp", "http", "h2", "ws", "quic", "grpc", "httpupgrade", "xhttp", "splithttp"
)

@Suppress("TooManyFunctions")
abstract class ClashYamlParserPart1 : ClashYamlParserBase() {
    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()

        val nodeLinkPrefixes = listOf(
            "vmess://", "vless://", "ss://", "trojan://",
            "hysteria2://", "hy2://", "hysteria://",
            "tuic://", "anytls://", "wireguard://", "wg://", "ssh://"
        )
        if (nodeLinkPrefixes.any { trimmed.startsWith(it) }) {
            return false
        }

        return trimmed.contains("proxies:") || trimmed.contains("proxy-groups:")
    }

    override fun parse(content: String): SingBoxConfig? {
        val root = try {
            Yaml().load<Any>(content)
        } catch (_: YAMLException) {
            return null
        } catch (_: Exception) {
            return null
        }

        val rootMap = (root as? Map<*, *>) ?: return null
        val proxiesRaw = rootMap["proxies"] as? List<*> ?: return null

        val globalClientFingerprint = asString(rootMap["global-client-fingerprint"])

        val globalTlsMinVersion = asString(rootMap["tls-version"])
            ?: asString(rootMap["min-tls-version"])

        val outbounds = mutableListOf<Outbound>()
        var skippedCount = 0

        for (p in proxiesRaw) {
            val m = p as? Map<*, *> ?: continue

            val obs = parseProxy(m, globalClientFingerprint, globalTlsMinVersion)
            if (obs != null && obs.isNotEmpty()) {
                outbounds.addAll(obs)
            } else {
                skippedCount++
            }
        }

        if (skippedCount > 0) {
            android.util.Log.d("ClashYamlParser", "Parsed ${outbounds.size} proxies, skipped $skippedCount")
        }

        val proxyGroupsRaw = rootMap["proxy-groups"] as? List<*>
        if (proxyGroupsRaw != null) {
            val knownGroupNames = proxyGroupsRaw.mapNotNull { group ->
                (group as? Map<*, *>)?.let { asString(it["name"]) }
            }.toSet()
            val knownOutboundTags = outbounds.map { it.tag }.toSet() + knownGroupNames
            for (g in proxyGroupsRaw) {
                val gm = g as? Map<*, *> ?: continue
                val name = asString(gm["name"]) ?: continue
                val type = asString(gm["type"])?.lowercase() ?: continue
                val proxies = normalizeProxyGroupRefs(gm["proxies"], knownOutboundTags)
                if (proxies.isEmpty()) continue

                when (type) {
                    "select", "selector" -> {
                        outbounds.add(
                            Outbound(
                                type = "selector",
                                tag = name,
                                outbounds = proxies,
                                default = proxies.firstOrNull(),
                                interruptExistConnections = false
                            )
                        )
                    }
                    "url-test", "urltest" -> {
                        val url = sanitizeUrlTestUrl(asString(gm["url"]))
                        val interval = asString(gm["interval"]) ?: asInt(gm["interval"])?.toString() ?: "300s"
                        val tolerance = asInt(gm["tolerance"]) ?: 50
                        outbounds.add(
                            Outbound(
                                type = "urltest",
                                tag = name,
                                outbounds = proxies,
                                url = url,
                                interval = interval,
                                tolerance = tolerance,
                                interruptExistConnections = false
                            )
                        )
                    }
                }
            }
        }

        if (outbounds.isEmpty()) return null

        return SingBoxConfig(outbounds = outbounds)
    }

    protected override fun sanitizeUrlTestUrl(rawUrl: String?): String {
        return AppSettings.normalizeLatencyTestUrl(rawUrl)
    }

    protected override fun normalizeProxyGroupRefs(rawProxies: Any?, knownOutboundTags: Set<String>): List<String> {
        return (rawProxies as? List<*>)
            ?.mapNotNull { value -> asString(value)?.trim()?.takeIf { it.isNotBlank() } }
            ?.mapNotNull { normalizeProxyGroupRef(it, knownOutboundTags) }
            ?.distinct()
            .orEmpty()
    }

    protected override fun normalizeProxyGroupRef(ref: String, knownOutboundTags: Set<String>): String? {
        if (knownOutboundTags.contains(ref)) return ref
        return when {
            ref.equals("DIRECT", ignoreCase = true) -> "direct"
            ref.equals("REJECT", ignoreCase = true) -> null
            ref.equals("REJECT-DROP", ignoreCase = true) -> null
            ref.equals("PASS", ignoreCase = true) -> null
            ref.equals("GLOBAL", ignoreCase = true) -> null
            else -> ref
        }
    }

    protected override fun parseProxy(proxyMap: Map<*, *>, globalFingerprint: String?, globalTlsMinVersion: String?): List<Outbound>? {
        val name = asString(proxyMap["name"]) ?: run {
            android.util.Log.w("ClashYamlParser", "Proxy missing name field: ${proxyMap.keys}")
            return null
        }
        val type = asString(proxyMap["type"])?.lowercase() ?: run {
            android.util.Log.w("ClashYamlParser", "Proxy '$name' missing type field")
            return null
        }

        val server = asString(proxyMap["server"])
        val port = asInt(proxyMap["port"])

        // 瘦身版已移除的类型（ss/vmess/hysteria/tuic/naive/ssh/wireguard/shadowtls 等）不进 when，
        // 统一落到 else -> null，由 parse() 的 skippedCount 静默计数，不让整份订阅失败。
        val outbound = when (type) {
            "vless" -> parseVLess(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "trojan" -> parseTrojan(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "hysteria2", "hy2" -> parseHysteria2(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "anytls" -> parseAnyTLS(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "http" -> parseHttp(proxyMap, name, server, port, globalFingerprint, globalTlsMinVersion)
            "socks5" -> parseSocks(proxyMap, name, server, port)
            else -> null
        }

        if (outbound == null && type.contains("anytls")) {
            android.util.Log.w("ClashYamlParser", "Failed to parse $type node '$name'. Server: $server, Port: $port, Map: $proxyMap")
        }

        return outbound?.let { listOf(it) }
    }

    private fun buildDirectHttpUpgradeTransport(
        map: Map<*, *>,
        fallbackHost: String,
        fingerprint: String?
    ): TransportConfig? {
        val opts = map["http-upgrade-opts"] as? Map<*, *> ?: map["ws-opts"] as? Map<*, *>
        val path = asString(opts?.get("path")) ?: "/"
        val headers = mutableMapOf<String, String>()
        (opts?.get("headers") as? Map<*, *>)?.forEach { (key, value) ->
            val headerName = asString(key) ?: return@forEach
            val headerValue = asString(value) ?: return@forEach
            headers[headerName] = headerValue
        }
        val host = headers["Host"]
            ?: headers["host"]
            ?: asString(opts?.get("host"))
            ?: fallbackHost
        if (!headers.containsKey("User-Agent")) {
            headers["User-Agent"] = getUserAgent(fingerprint)
        }
        return buildWsOrHttpUpgradeTransport(
            wsOpts = opts,
            path = path,
            headers = headers,
            host = host,
            forceHttpUpgrade = true
        )
    }

    private fun buildXhttpTransport(map: Map<*, *>): TransportConfig? {
        val opts = map["xhttp-opts"] as? Map<*, *>
            ?: map["splithttp-opts"] as? Map<*, *>
            ?: return null
        val extra = asNestedMap(opts["extra"])
        val encryption = asString(extra?.get("encryption"))
        if (!encryption.isNullOrBlank() && !encryption.equals("none", ignoreCase = true)) {
            return null
        }
        return TransportConfig(
            type = "xhttp",
            path = asString(opts["path"]) ?: "/",
            host = asStringList(opts["host"]),
            mode = asString(opts["mode"]),
            xPaddingBytes = asString(opts["xPaddingBytes"]) ?: asString(opts["x-padding-bytes"])
                ?: asString(opts["x_padding_bytes"]),
            scMaxEachPostBytes = asLong(opts["scMaxEachPostBytes"] ?: opts["sc_max_each_post_bytes"]),
            scMinPostsIntervalMs = asLong(opts["scMinPostsIntervalMs"] ?: opts["sc_min_posts_interval_ms"]),
            scMaxBufferedPosts = asLong(opts["scMaxBufferedPosts"] ?: opts["sc_max_buffered_posts"]),
            noGRPCHeader = asBool(opts["noGRPCHeader"] ?: opts["no_grpc_header"]),
            noSSEHeader = asBool(opts["noSSEHeader"] ?: opts["no_sse_header"])
        )
    }

    private fun asLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    protected override fun parseVLess(
        map: Map<*, *>,
        name: String,
        server: String?,
        port: Int?,
        globalFingerprint: String?,
        globalTlsMinVersion: String?
    ): Outbound? {
        if (server == null || port == null) return null
        val uuid = asString(map["uuid"]) ?: return null
        val network = asString(map["network"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (network != null && network !in CLASH_V2RAY_NETWORKS) return null
        val tlsEnabled = asBool(map["tls"]) == true
        val serverName = asString(map["servername"]) ?: asString(map["sni"]) ?: server

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])
        val flow = asString(map["flow"])
        // VLESS Encryption（mlkem768x25519plus）内核已支持（vc37 起）：透传到出站。
        val encryption = asString(map["encryption"])?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
        val packetEncoding = asString(map["packet-encoding"])?.takeIf { it.isNotBlank() }

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        // Reality support
        val realityOpts = asNestedMap(map["reality-opts"])
        val realityPublicKey = asString(realityOpts?.get("public-key"))
        val realityShortId = asString(realityOpts?.get("short-id"))

        val finalAlpn = if (tlsEnabled && network == "ws" && (alpn == null || alpn.isEmpty())) listOf("http/1.1") else alpn

        val tlsConfig = if (tlsEnabled) {
            buildTlsConfig(
                map = map,
                serverName = serverName,
                insecure = insecure,
                alpn = finalAlpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) },
                reality = if (realityPublicKey != null) {
                    com.mubox.app.model.RealityConfig(
                        enabled = true,
                        publicKey = realityPublicKey,
                        shortId = realityShortId
                    )
                } else null
            )
        } else null

        val transport = when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) ->
                    val ks = asString(k) ?: return@forEach
                    val vs = asString(v) ?: return@forEach
                    headers[ks] = vs
                }

                val host = headers["Host"] ?: headers["host"] ?: serverName
                if (!host.isNullOrBlank()) headers["Host"] = host

                if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }

                buildWsOrHttpUpgradeTransport(
                    wsOpts = wsOpts,
                    path = path,
                    headers = headers,
                    host = host
                ) ?: return null
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name"))
                    ?: asString(grpcOpts?.get("service-name"))
                    ?: asString(map["grpc-service-name"])
                    ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            "h2", "http" -> {
                val h2Opts = map["h2-opts"] as? Map<*, *>
                val path = asString(map["path"]) ?: asString(h2Opts?.get("path"))
                val host = asString(map["host"])?.let { listOf(it) } ?: asStringList(h2Opts?.get("host"))
                TransportConfig(type = "http", path = path, host = host)
            }
            "xhttp", "splithttp" -> buildXhttpTransport(map) ?: return null
            "quic" -> TransportConfig(type = "quic")
            "httpupgrade" -> buildDirectHttpUpgradeTransport(map, serverName, fingerprint) ?: return null
            else -> null
        }
        if (network == "quic" && tlsConfig?.enabled != true) return null

        val multiplex = parseSmux(map)

        return Outbound(
            type = "vless",
            tag = name,
            server = server,
            serverPort = port,
            uuid = uuid,
            flow = if (encryption != null && flow == "xtls-rprx-vision") null else flow,
            encryption = encryption,
            tls = tlsConfig,
            transport = transport,
            packetEncoding = packetEncoding,
            multiplex = multiplex
        )
    }

    /**
     *
     *   - type: ss
     *     plugin: shadow-tls
     *     plugin-opts:
     *       host: example.com
     *       password: xxx
     *       version: 3
     *
     */

    protected override fun parseTrojan(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val network = asString(map["network"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (network != null && network !in CLASH_V2RAY_NETWORKS) return null
        val sni = asString(map["sni"]) ?: server

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val tlsConfig = buildTlsConfig(
            map = map,
            serverName = sni,
            insecure = insecure,
            alpn = alpn,
            minVersion = tlsMinVersion,
            utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
        )

        val transport = parseTrojanTransport(network, map, sni, fingerprint)
        if (network != null && network != "tcp" && transport == null) return null

        val multiplex = parseSmux(map)

        return Outbound(
            type = "trojan",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            tls = tlsConfig,
            transport = transport,
            multiplex = multiplex
        )
    }

    protected override fun parseTrojanTransport(
        network: String?,
        map: Map<*, *>,
        sni: String,
        fingerprint: String?
    ): TransportConfig? {
        return when (network) {
            "ws" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val path = asString(wsOpts?.get("path")) ?: "/"
                val headersRaw = wsOpts?.get("headers") as? Map<*, *>
                val headers = mutableMapOf<String, String>()
                headersRaw?.forEach { (k, v) -> headers[asString(k) ?: ""] = asString(v) ?: "" }
                if (!headers.containsKey("Host")) headers["Host"] = sni
                if (!headers.containsKey("User-Agent")) {
                    headers["User-Agent"] = getUserAgent(fingerprint)
                }
                val host = headers["Host"] ?: headers["host"] ?: sni

                buildWsOrHttpUpgradeTransport(
                    wsOpts = wsOpts,
                    path = path,
                    headers = headers,
                    host = host
                ) ?: return null
            }
            "grpc" -> {
                val grpcOpts = map["grpc-opts"] as? Map<*, *>
                val serviceName = asString(grpcOpts?.get("grpc-service-name")) ?: ""
                TransportConfig(type = "grpc", serviceName = serviceName)
            }
            "h2", "http" -> {
                val h2Opts = map["h2-opts"] as? Map<*, *>
                val path = asString(map["path"]) ?: asString(h2Opts?.get("path"))
                val host = asString(map["host"])?.let { listOf(it) } ?: asStringList(h2Opts?.get("host"))
                TransportConfig(type = "http", path = path, host = host)
            }
            "xhttp", "splithttp" -> buildXhttpTransport(map) ?: return null
            "quic" -> TransportConfig(type = "quic")
            "httpupgrade" -> buildDirectHttpUpgradeTransport(map, sni, fingerprint)
            else -> null
        }
    }

    protected override fun parseHysteria2(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String?, globalTlsMinVersion: String?): Outbound? {
        if (server == null || port == null) return null
        val password = asString(map["password"]) ?: return null
        val sni = asString(map["sni"]) ?: server
        val insecure = asBool(map["skip-cert-verify"]) == true
        val alpn = asStringList(map["alpn"])

        val fingerprint = asString(map["client-fingerprint"]) ?: globalFingerprint
        val obfs = asString(map["obfs"])
        val obfsPassword = asString(map["obfs-password"])

        val tlsMinVersion = asString(map["tls-version"]) ?: asString(map["min-tls-version"]) ?: globalTlsMinVersion

        val network = asString(map["network"])

        val upMbps = asInt(map["up"]) ?: asInt(map["up-mbps"])
        val downMbps = asInt(map["down"]) ?: asInt(map["down-mbps"])

        val portsStr = asString(map["ports"])?.takeIf { it.isNotBlank() }
        val serverPorts = portsStr?.let { listOf(it) }
        val hopInterval = asString(map["hop-interval"])?.takeIf { it.isNotBlank() }

        return Outbound(
            type = "hysteria2",
            tag = name,
            server = server,
            serverPort = port,
            password = password,
            network = network?.let(::listOf),
            upMbps = upMbps,
            downMbps = downMbps,
            serverPorts = serverPorts,
            hopInterval = hopInterval,
            tls = buildTlsConfig(
                map = map,
                serverName = sni,
                insecure = insecure,
                alpn = alpn,
                minVersion = tlsMinVersion,
                utls = fingerprint?.let { UtlsConfig(enabled = true, fingerprint = it) }
            ),
            obfs = if (obfs != null) com.mubox.app.model.ObfsConfig(type = obfs, password = obfsPassword) else null
        )
    }
}
