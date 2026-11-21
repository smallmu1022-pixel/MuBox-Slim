package com.mubox.app.utils.parser

import com.mubox.app.model.MultiplexConfig
import com.mubox.app.model.Outbound
import com.mubox.app.model.SingBoxConfig
import com.mubox.app.model.TlsConfig
import com.mubox.app.model.TransportConfig
import com.mubox.app.model.UtlsConfig

@Suppress("TooManyFunctions")
abstract class ClashYamlParserBase : SubscriptionParser {
    // Virtual declarations keep split class logic callable across files.
    abstract override fun canParse(content: String): Boolean

    abstract override fun parse(content: String): SingBoxConfig?

    protected abstract fun sanitizeUrlTestUrl(rawUrl: String?): String

    protected abstract fun normalizeProxyGroupRefs(rawProxies: Any?, knownOutboundTags: Set<String>): List<String>

    protected abstract fun normalizeProxyGroupRef(ref: String, knownOutboundTags: Set<String>): String?

    protected abstract fun parseProxy(proxyMap: Map<*, *>, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): List<Outbound>?

    protected abstract fun parseVLess(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseTrojan(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseTrojanTransport(
        network: String?,
        map: Map<*, *>,
        sni: String,
        fingerprint: String?
    ): TransportConfig?

    protected abstract fun parseHysteria2(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseAnyTLS(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseHttp(map: Map<*, *>, name: String, server: String?, port: Int?, globalFingerprint: String? = null, globalTlsMinVersion: String? = null): Outbound?

    protected abstract fun parseSocks(map: Map<*, *>, name: String, server: String?, port: Int?): Outbound?

    protected abstract fun parseSmux(map: Map<*, *>): MultiplexConfig?

    protected abstract fun buildTlsConfig(
        map: Map<*, *>,
        enabled: Boolean = true,
        serverName: String? = null,
        insecure: Boolean? = null,
        alpn: List<String>? = null,
        minVersion: String? = null,
        utls: UtlsConfig? = null,
        reality: com.mubox.app.model.RealityConfig? = null
    ): TlsConfig

    protected abstract fun firstNonBlankString(map: Map<*, *>, vararg keys: String): String?

    protected abstract fun asNestedMap(v: Any?): Map<*, *>?

    protected abstract fun getUserAgent(fingerprint: String?): String

    protected abstract fun asString(v: Any?): String?

    protected abstract fun asInt(v: Any?): Int?

    protected abstract fun asBool(v: Any?): Boolean?

    protected abstract fun asStringList(v: Any?): List<String>?

    protected abstract fun buildWsOrHttpUpgradeTransport(
        wsOpts: Map<*, *>?,
        path: String,
        headers: Map<String, String>,
        host: String?,
        forceHttpUpgrade: Boolean = false
    ): TransportConfig?

    protected abstract fun Map<String, String>.withoutHostHeader(): Map<String, String>
}
