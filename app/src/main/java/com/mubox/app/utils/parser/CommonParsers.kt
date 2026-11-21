package com.mubox.app.utils.parser

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.mubox.app.model.Outbound
import com.mubox.app.model.SingBoxConfig
import com.mubox.app.model.TlsConfig

class SingBoxParser(private val gson: Gson) : SubscriptionParser {
    companion object {
        private const val TAG = "SingBoxParser"
        private val OUTBOUND_LIST_TYPE = object : TypeToken<List<Outbound>>() {}.type
    }

    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    override fun parse(content: String): SingBoxConfig? {
        val trimmed = content.trim()

        if (trimmed.startsWith("[")) {
            return parseAsOutboundArray(trimmed)
        }

        return parseAsConfigObject(trimmed)
    }

    private fun normalizeOutbounds(outbounds: List<Outbound>): List<Outbound> {
        return filterRemovedOutbounds(outbounds.map(::normalizeOutbound))
    }

    private fun normalizeOutbounds(outbounds: List<Outbound>, rawElements: JsonArray): List<Outbound> {
        val normalized = outbounds.mapIndexed { index, outbound ->
            val rawElement = if (index < rawElements.size()) rawElements[index] else null
            val raw = rawElement?.takeIf { it.isJsonObject }?.asJsonObject
            normalizeOutbound(outbound, raw)
        }
        return filterRemovedOutbounds(normalized)
    }

    // 瘦身版静默过滤：已移除协议的 outbound/endpoint 直接丢弃，组引用同步清理，不让整份配置导入失败。
    private fun filterRemovedOutbounds(outbounds: List<Outbound>): List<Outbound> {
        val removedTags = outbounds
            .filter { NodeLinkParser.isRemovedOutboundType(it.type) }
            .mapTo(mutableSetOf()) { it.tag }
        if (removedTags.isEmpty()) return outbounds
        Log.i(TAG, "Skipped ${removedTags.size} node(s) of removed protocols: $removedTags")
        return outbounds.mapNotNull { outbound ->
            when {
                outbound.tag in removedTags -> null
                outbound.type == "selector" || outbound.type == "urltest" ->
                    outbound.copy(outbounds = outbound.outbounds?.filterNot { it in removedTags })
                else -> outbound
            }
        }
    }

    private fun normalizeOutbound(outbound: Outbound, rawObject: JsonObject? = null): Outbound {
        val normalizedTls = normalizeTlsAliases(outbound.tls, rawObject?.getAsJsonObject("tls"))
        return if (normalizedTls == outbound.tls) {
            outbound
        } else {
            outbound.copy(tls = normalizedTls)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun normalizeTlsAliases(tls: TlsConfig?, rawTls: JsonObject? = null): TlsConfig? {
        if (tls == null && rawTls == null) return null
        val baseTls = tls ?: TlsConfig()
        val legacyCa = readJsonListAlias(rawTls, "ca", "ca-cert", "ca_cert", "caPem", "ca_pem")
        val legacyCaPath = readJsonStringAlias(rawTls, "ca_path", "ca-path")
        return baseTls.copy(
            ca = legacyCa ?: baseTls.ca ?: readJsonListAlias(rawTls, "certificate"),
            caPath = legacyCaPath ?: baseTls.caPath ?: readJsonStringAlias(rawTls, "certificate_path"),
            certificate = baseTls.certificate ?: readJsonListAlias(
                rawTls,
                "client_certificate",
                "client-cert",
                "client_cert"
            ) ?: legacyCa?.let { readJsonListAlias(rawTls, "certificate") },
            certificatePath = baseTls.certificatePath ?: readJsonStringAlias(
                rawTls,
                "client_certificate_path",
                "client-cert-path",
                "client_cert_path"
            ) ?: legacyCaPath?.let { readJsonStringAlias(rawTls, "certificate_path") },
            key = baseTls.key ?: readJsonListAlias(rawTls, "client_key", "client-key", "key"),
            keyPath = baseTls.keyPath ?: readJsonStringAlias(
                rawTls,
                "client_key_path",
                "client-key-path",
                "key_path"
            )
        )
    }

    private fun readJsonStringAlias(rawObject: JsonObject?, vararg aliases: String): String? {
        if (rawObject == null) return null
        return aliases.firstNotNullOfOrNull { alias ->
            rawObject.get(alias)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }
    }

    private fun readJsonListAlias(rawObject: JsonObject?, vararg aliases: String): List<String>? {
        if (rawObject == null) return null
        return aliases.firstNotNullOfOrNull { alias ->
            when (val value = rawObject.get(alias)) {
                null -> null
                is JsonArray -> value.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                }.takeIf { it.isNotEmpty() }
                else -> value.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::listOf)
            }
        }
    }

    private fun parseAsOutboundArray(content: String): SingBoxConfig? {
        return try {
            val rawElements = JsonParser.parseString(content).asJsonArray
            val outbounds: List<Outbound> = gson.fromJson(content, OUTBOUND_LIST_TYPE)
            if (outbounds.isNotEmpty()) {
                SingBoxConfig(outbounds = normalizeOutbounds(outbounds, rawElements))
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse as outbound array: ${e.message}")
            null
        }
    }

    @Suppress("NestedBlockDepth")
    private fun parseAsConfigObject(content: String): SingBoxConfig? {
        return try {
            val jsonObject = JsonParser.parseString(content).asJsonObject
            val parsed = gson.fromJson(jsonObject, SingBoxConfig::class.java)
            val outboundsElement = jsonObject.get("outbounds") ?: jsonObject.get("proxies")
            if (outboundsElement != null && outboundsElement.isJsonArray) {
                val rawElements = outboundsElement.asJsonArray
                val outbounds: List<Outbound> = gson.fromJson(rawElements, OUTBOUND_LIST_TYPE)
                if (outbounds.isNotEmpty()) {
                    val normalizedOutbounds = normalizeOutbounds(outbounds, rawElements)
                    val keptEndpoints = parsed.endpoints?.filterNot {
                        NodeLinkParser.isRemovedOutboundType(it.type)
                    }
                    return parsed.copy(outbounds = normalizedOutbounds, endpoints = keptEndpoints)
                }
            }
            val sanitized = parsed.copy(
                outbounds = parsed.outbounds?.let { filterRemovedOutbounds(it.map(::normalizeOutbound)) },
                endpoints = parsed.endpoints?.filterNot {
                    NodeLinkParser.isRemovedOutboundType(it.type)
                }
            )
            sanitized.takeIf {
                !it.endpoints.isNullOrEmpty() ||
                    !it.inbounds.isNullOrEmpty() ||
                    it.dns != null ||
                    it.route != null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract outbounds from JSON: ${e.message}")
            null
        }
    }
}

class Base64Parser(private val nodeParser: (String) -> Outbound?) : SubscriptionParser {
    // 抽取面包含已移除协议的链接前缀：先定位、再跳过计数，保证订阅里的被砍节点是静默过滤而不是解析失败。
    private val linkPrefixes = NodeLinkParser.EXTRACTABLE_LINK_PREFIXES

    override fun canParse(content: String): Boolean {
        val trimmed = content.trim()
        return !trimmed.startsWith("{") && !trimmed.startsWith("proxies:") && !trimmed.startsWith("proxy-groups:")
    }

    override fun parse(content: String): SingBoxConfig? {
        android.util.Log.d("Base64Parser", "Parsing content, length: ${content.length}, starts with: ${content.take(20)}")
        val trimmed = content.trim()

        val isAlreadyLink = linkPrefixes.any { trimmed.startsWith(it) }
        val decoded = if (isAlreadyLink) trimmed else (tryDecodeBase64(trimmed) ?: trimmed)
        val normalized = decoded
            .replace("\u2028", "\n")
            .replace("\u2029", "\n")
        val candidates = normalized.lines().flatMap { extractLinksFromLine(it) }
            .ifEmpty { normalized.split(Regex("\\s+")).flatMap { extractLinksFromLine(it) } }
        android.util.Log.d("Base64Parser", "Found ${candidates.size} link candidates")
        val outbounds = mutableListOf<Outbound>()

        var skippedRemovedCount = 0
        for (candidate in candidates) {
            android.util.Log.d("Base64Parser", "Trying to parse candidate: ${candidate.take(30)}...")
            val outbound = nodeParser(candidate)
            if (outbound != null) {
                android.util.Log.d("Base64Parser", "Successfully parsed: ${outbound.tag}")
                outbounds.add(outbound)
            } else if (NodeLinkParser.removedProtocolDisplayName(candidate) != null) {
                skippedRemovedCount++
            } else {
                android.util.Log.w("Base64Parser", "Failed to parse candidate")
            }
        }
        if (skippedRemovedCount > 0) {
            android.util.Log.i("Base64Parser", "Skipped $skippedRemovedCount node(s) of removed protocols")
        }

        android.util.Log.d("Base64Parser", "Total outbounds parsed: ${outbounds.size}")
        if (outbounds.isEmpty()) return null

        return SingBoxConfig(outbounds = outbounds)
    }

    private fun extractLinksFromLine(line: String): List<String> {
        val normalized = line.trim()
            .trimStart('\uFEFF', '\u200B', '\u200C', '\u200D')
            .removePrefix("- ")
            .removePrefix("·")
            .trim()
            .trim('`', '"', '\'')

        if (normalized.isBlank()) return emptyList()

        val sortedPrefixes = linkPrefixes.sortedByDescending { it.length }

        val linkPositions = mutableListOf<Pair<Int, String>>()
        val usedPositions = mutableSetOf<Int>()

        for (prefix in sortedPrefixes) {
            var searchFrom = 0
            while (searchFrom < normalized.length) {
                val index = normalized.indexOf(prefix, searchFrom)
                if (index < 0) break

                val isOverlapped = usedPositions.any { usedPos ->
                    val matchedPrefixLength = sortedPrefixes.firstOrNull {
                        normalized.substring(usedPos).startsWith(it)
                    }?.length ?: return@any false
                    index >= usedPos && index < usedPos + matchedPrefixLength
                }

                if (!isOverlapped) {
                    linkPositions.add(index to prefix)
                    usedPositions.add(index)
                }
                searchFrom = index + 1
            }
        }

        if (linkPositions.isEmpty()) return emptyList()

        val sortedPositions = linkPositions.sortedBy { it.first }

        val results = mutableListOf<String>()
        for (i in sortedPositions.indices) {
            val start = sortedPositions[i].first
            val end = if (i + 1 < sortedPositions.size) sortedPositions[i + 1].first else normalized.length
            var candidate = normalized.substring(start, end).trim()
            candidate = candidate.trimEnd(',', '，', ';', '；', '.', '。', ')', '）', ']', '】', '}', '`', '"', '\'')
            if (candidate.isNotBlank()) {
                results.add(candidate)
            }
        }
        return results
    }

    private fun tryDecodeBase64(content: String): String? {
        val cleaned = content.replace(Regex("\\s+"), "")
        val padded = padBase64(cleaned)

        val jvmDecoded = decodeWithJvmBase64(padded, urlSafe = false)
            ?: decodeWithJvmBase64(padded, urlSafe = true)
        if (jvmDecoded != null) return jvmDecoded

        val candidates = arrayOf(
            Base64.DEFAULT,
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        for (flags in candidates) {
            try {
                val decoded = Base64.decode(padded, flags)
                decodeSubscriptionText(decoded)?.let { return it }
            } catch (e: Exception) {
                Log.d("Base64Parser", "Base64 decode failed for flags=$flags: ${e.message}")
            }
        }
        return null
    }

    private fun padBase64(content: String): String {
        return when (content.length % 4) {
            2 -> content + "=="
            3 -> content + "="
            else -> content
        }
    }

    private fun decodeWithJvmBase64(content: String, urlSafe: Boolean): String? {
        return try {
            val base64Class = Class.forName("java.util.Base64")
            val methodName = if (urlSafe) "getUrlDecoder" else "getDecoder"
            val decoder = base64Class.getMethod(methodName).invoke(null)
            val decoded = decoder.javaClass.getMethod("decode", String::class.java)
                .invoke(decoder, content) as? ByteArray
            decodeSubscriptionText(decoded)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    private fun decodeSubscriptionText(decoded: ByteArray?): String? {
        if (decoded == null) return null
        val text = String(decoded, Charsets.UTF_8)
        return if (text.isNotBlank() && looksLikeSubscriptionText(text)) text else null
    }

    private fun looksLikeSubscriptionText(text: String): Boolean {
        return text.contains("://") ||
            text.contains("\n") ||
            text.contains("\r") ||
            text.all { it.isLetterOrDigit() || it.isWhitespace() || "=/-_:.".contains(it) }
    }
}
