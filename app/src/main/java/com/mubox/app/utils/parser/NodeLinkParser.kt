@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.mubox.app.utils.parser

import android.util.Log
import com.google.gson.Gson
import com.mubox.app.model.EchConfig
import com.mubox.app.model.Outbound
import com.mubox.app.model.TransportConfig
import com.mubox.app.model.UInt32JsonAdapter
import com.mubox.app.model.V2RAY_TRANSPORT_TYPES

@Suppress("TooManyFunctions", "LargeClass")
class NodeLinkParser(internal val gson: Gson) {
    companion object {
        internal val SUPPORTED_LINK_PREFIXES = listOf(
            "vless://", "trojan://",
            "hysteria2://", "hy2://", "anytls://",
            "socks4://", "socks4a://", "socks5://", "socks://", "http://", "https://"
        )

        // 瘦身版已移除的协议（内核未编入）：仅用于识别与提示，永不解析。
        // 订阅/导入遇到这些类型时静默跳过；手动粘贴时给出明确提示。
        internal val REMOVED_OUTBOUND_TYPES = setOf(
            "vmess", "shadowsocks", "hysteria", "tuic", "wireguard",
            "ssh", "naive", "snell", "tor", "shadowtls", "ssmapi"
        )

        internal val REMOVED_LINK_SCHEMES = linkedMapOf(
            "vmess://" to "VMess",
            "ss://" to "Shadowsocks",
            "hysteria://" to "Hysteria",
            "tuic://" to "TUIC",
            "naive+https://" to "Naive",
            "naive://" to "Naive",
            "wireguard://" to "WireGuard",
            "wg://" to "WireGuard",
            "ssh://" to "SSH"
        )

        // 订阅文本中仍需能定位到已移除协议的链接（定位后跳过并计数），故抽取面 = 支持面 + 已移除面。
        internal val EXTRACTABLE_LINK_PREFIXES = SUPPORTED_LINK_PREFIXES + REMOVED_LINK_SCHEMES.keys

        internal fun isRemovedOutboundType(type: String?): Boolean {
            return type?.trim()?.lowercase() in REMOVED_OUTBOUND_TYPES
        }

        internal fun removedProtocolDisplayName(link: String): String? {
            val value = link.trim()
            return REMOVED_LINK_SCHEMES.entries.firstOrNull { (scheme, _) ->
                value.startsWith(scheme, ignoreCase = true)
            }?.value
        }
        internal fun isSupportedLink(link: String): Boolean {
            val value = link.trim()
            val prefix = SUPPORTED_LINK_PREFIXES.firstOrNull { value.startsWith(it, ignoreCase = true) }
                ?: return false
            if (prefix != "http://" && prefix != "https://") return true
            return runCatching {
                val uri = java.net.URI(value)
                !uri.userInfo.isNullOrBlank() || uri.port > 0 || !uri.fragment.isNullOrBlank()
            }.getOrDefault(false)
        }
    }

    internal fun firstParam(params: Map<String, String>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            params.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        }
    }

    internal fun hasRequiredLinkFields(protocol: String, server: String?, credential: String?, port: Int): Boolean {
        if (server.isNullOrBlank()) {
            Log.w("NodeLinkParser", "$protocol link missing server")
            return false
        }
        if (credential.isNullOrBlank()) {
            Log.w("NodeLinkParser", "$protocol link missing credential")
            return false
        }
        if (port !in 1..65535) {
            Log.w("NodeLinkParser", "$protocol link has invalid port: $port")
            return false
        }
        return true
    }

    internal fun parseBooleanFlag(value: String?): Boolean? {
        val normalized = value?.trim()?.lowercase() ?: return null
        return when (normalized) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    internal fun buildXhttpTransport(
        host: String?,
        path: String?,
        value: (String) -> Any?
    ): TransportConfig {
        fun longValue(name: String): Long? = when (val raw = value(name)) {
            is Number -> raw.toLong()
            else -> raw?.toString()?.toLongOrNull()
        }

        return TransportConfig(
            type = "xhttp",
            path = path.orEmpty().ifBlank { "/" },
            host = parseHostList(host),
            mode = value("mode")?.toString(),
            xPaddingBytes = value("xPaddingBytes")?.toString(),
            scMaxEachPostBytes = longValue("scMaxEachPostBytes"),
            scMinPostsIntervalMs = longValue("scMinPostsIntervalMs"),
            scMaxBufferedPosts = longValue("scMaxBufferedPosts"),
            noGRPCHeader = parseBooleanFlag(value("noGRPCHeader")?.toString()),
            noSSEHeader = parseBooleanFlag(value("noSSEHeader")?.toString())
        )
    }

    internal fun parseHostList(value: String?): List<String>? {
        if (value.isNullOrBlank()) return null
        val hosts = value.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return hosts.takeIf { it.isNotEmpty() }
    }

    internal fun parseSingleHost(value: String?): List<String>? {
        return parseHostList(value)?.firstOrNull()?.let { listOf(it) }
    }

    internal fun isUnsupportedV2RayTransport(type: String): Boolean =
        type.isNotEmpty() && type !in V2RAY_TRANSPORT_TYPES

    internal fun parseEchConfig(params: Map<String, String>): EchConfig? {
        val echParam = firstParam(params, "ech") ?: return null
        val disabled = echParam.isBlank() ||
            echParam.equals("0", ignoreCase = true) ||
            echParam.equals("false", ignoreCase = true)
        val queryServerName = echParam
            .substringBefore('+')
            .substringBefore(' ')
            .takeIf { it.contains(".") }
        val dnsServerSource = if (echParam.contains("+")) {
            echParam.substringAfter('+')
        } else {
            echParam.substringAfter(' ', missingDelimiterValue = "")
        }
        val dnsServer = dnsServerSource
            .substringBefore(' ')
            .takeIf { it.isNotBlank() && it.contains(".") }
        return if (disabled) {
            null
        } else {
            EchConfig(enabled = true, queryServerName = queryServerName, dnsServer = dnsServer)
        }
    }

    internal fun parseWebSocketPathConfig(rawPath: String?): NodeLinkParserWebSocketPathConfig? {
        if (rawPath.isNullOrBlank()) {
            return NodeLinkParserWebSocketPathConfig(path = "/", maxEarlyData = null, earlyDataHeaderName = null)
        }

        val trimmed = rawPath.trim().ifEmpty { "/" }
        val normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val questionIndex = normalized.indexOf('?')
        if (questionIndex == -1) {
            return NodeLinkParserWebSocketPathConfig(path = normalized, maxEarlyData = null, earlyDataHeaderName = null)
        }

        val basePath = normalized.substring(0, questionIndex).ifEmpty { "/" }
        val queryParams = parseQueryParams(normalized.substring(questionIndex + 1))
        val rawMaxEarlyData = firstParam(queryParams, "ed")
        val maxEarlyData = rawMaxEarlyData?.toLongOrNull()?.let { value ->
            runCatching { UInt32JsonAdapter.requireValue(value) }.getOrNull()
        }
        if (rawMaxEarlyData != null && maxEarlyData == null) return null
        val earlyDataHeaderName = if (maxEarlyData != null) "Sec-WebSocket-Protocol" else null
        return NodeLinkParserWebSocketPathConfig(
            path = basePath,
            maxEarlyData = maxEarlyData,
            earlyDataHeaderName = earlyDataHeaderName
        )
    }

    internal fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val params = mutableMapOf<String, String>()
        query.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                params[parts[0]] = java.net.URLDecoder.decode(parts[1], "UTF-8")
            }
        }
        return params
    }

    internal fun isIpLiteral(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val candidate = value.trim().removeSurrounding("[", "]")
        val ipv4Pattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        return ipv4Pattern.matches(candidate) || candidate.contains(":")
    }

    internal fun defaultTlsServerName(
        explicitServerName: String?,
        primaryFallback: String? = null,
        server: String?
    ): String? {
        val explicit = explicitServerName?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit

        val fallback = primaryFallback?.takeIf { it.isNotBlank() }
        if (fallback != null) return fallback

        return server?.takeIf { it.isNotBlank() && !isIpLiteral(it) }
    }

    internal fun sanitizeUri(link: String): String {
        var result = link

        val hashIndex = result.indexOf('#')
        var fragment = ""
        if (hashIndex != -1) {
            fragment = result.substring(hashIndex + 1)
            result = result.substring(0, hashIndex)
        }

        val questionIndex = result.indexOf('?')
        if (questionIndex != -1) {
            val base = result.substring(0, questionIndex)
            val query = result.substring(questionIndex + 1)

            val cleanedQuery = query
                .replace(Regex("\\s*=\\s*"), "=")
                .replace(Regex("\\s*&\\s*"), "&")
                .replace(" ", "%20")
            result = "$base?$cleanedQuery"
        }

        if (fragment.isNotEmpty()) {
            result = "$result#${fragment.replace(" ", "%20")}"
        }

        return result
    }

    internal fun normalizeInputLink(link: String): String {
        val trimmed = link.trim().trim('`', '"', '\'')
        val prefixMatch = Regex("^[A-Za-z][A-Za-z0-9+.-]*://\\S+")
            .find(trimmed)
            ?.value
            ?: trimmed
        return prefixMatch.trimEnd(',', '，', ';', '；', '。')
    }

    fun parse(link: String): Outbound? {
        val normalizedLink = normalizeInputLink(link)
        removedProtocolDisplayName(normalizedLink)?.let { removedName ->
            Log.w("NodeLinkParser", "Skipping node of removed protocol: $removedName")
            return null
        }
        return when {
            normalizedLink.startsWith("vless://") -> parseVLessLink(normalizedLink)
            normalizedLink.startsWith("trojan://") -> parseTrojanLink(normalizedLink)
            normalizedLink.startsWith("hysteria2://") ||
                normalizedLink.startsWith("hy2://") -> parseHysteria2Link(normalizedLink)
            normalizedLink.startsWith("anytls://") -> parseAnyTLSLink(normalizedLink)
            normalizedLink.startsWith("https://") -> parseHttpLink(normalizedLink, useTls = true)
            normalizedLink.startsWith("http://") -> parseHttpLink(normalizedLink, useTls = false)
            normalizedLink.startsWith("socks5://") ||
                normalizedLink.startsWith("socks4a://") ||
                normalizedLink.startsWith("socks4://") ||
                normalizedLink.startsWith("socks://") -> parseSocks5Link(normalizedLink)
            else -> null
        }
    }

    internal fun tryDecodeBase64(content: String): String? {

        val cleaned = content.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        val padded = padBase64(cleaned)

        decodeWithJvmBase64(padded, urlSafe = true)?.let { decoded ->
            if (decoded.isNotEmpty()) return String(decoded, Charsets.UTF_8)
        }
        decodeWithJvmBase64(padded, urlSafe = false)?.let { decoded ->
            if (decoded.isNotEmpty()) return String(decoded, Charsets.UTF_8)
        }

        val candidates = arrayOf(
            android.util.Base64.DEFAULT,
            android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = android.util.Base64.decode(padded, flags)
                // Basic validation: string should not contain excessive control chars
                if (decoded.isNotEmpty()) {
                    return String(decoded, Charsets.UTF_8)
                }
            } catch (_: Exception) {
                // Continue
            }
        }
        return null
    }

    internal fun padBase64(content: String): String {
        return when (content.length % 4) {
            2 -> content + "=="
            3 -> content + "="
            else -> content
        }
    }

    internal fun decodeWithJvmBase64(content: String, urlSafe: Boolean): ByteArray? {
        return try {
            val base64Class = Class.forName("java.util.Base64")
            val methodName = if (urlSafe) "getUrlDecoder" else "getDecoder"
            val decoder = base64Class.getMethod(methodName).invoke(null)
            decoder.javaClass.getMethod("decode", String::class.java).invoke(decoder, content) as? ByteArray
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    internal fun parseHostPort(hostPort: String): Pair<String, Int> {
        val lastColon = hostPort.lastIndexOf(':')
        val lastBracket = hostPort.lastIndexOf(']')

        var server: String
        var port: Int = 8388

        if (lastColon > lastBracket) {
            server = hostPort.substring(0, lastColon)
            port = hostPort.substring(lastColon + 1).toIntOrNull() ?: 8388
        } else {
            server = hostPort
        }

        if (server.startsWith("[") && server.endsWith("]")) {
            server = server.substring(1, server.length - 1)
        }
        return server to port
    }
}
