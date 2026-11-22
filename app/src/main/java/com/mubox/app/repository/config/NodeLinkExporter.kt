package com.mubox.app.repository.config

import android.util.Base64
import com.google.gson.Gson
import com.mubox.app.model.Outbound
import com.mubox.app.model.UInt32JsonAdapter
import com.mubox.app.model.V2RAY_TRANSPORT_PROTOCOLS
import com.mubox.app.model.V2RAY_TRANSPORT_TYPES
import com.mubox.app.model.V2RAY_XHTTP_TRANSPORT_TYPES
import com.mubox.app.model.allHeaderValues

object NodeLinkExporter {

    fun export(outbound: Outbound, gson: Gson): String? {
        if (hasInvalidV2RayTransport(outbound)) return null
        return when (outbound.type) {
            "vless" -> generateVLessLink(outbound)
            "trojan" -> generateTrojanLink(outbound)
            "hysteria2" -> generateHysteria2Link(outbound)
            "anytls" -> generateAnyTLSLink(outbound)
            "http" -> generateHttpLink(outbound)
            "socks" -> generateSocksLink(outbound)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun hasInvalidV2RayTransport(outbound: Outbound): Boolean {
        if (outbound.type !in V2RAY_TRANSPORT_PROTOCOLS) return false
        val transport = outbound.transport ?: return false
        val type = transport.type?.trim()?.lowercase().orEmpty()
        if (type.isNotEmpty() && type !in V2RAY_TRANSPORT_TYPES) return true
        if (type in V2RAY_XHTTP_TRANSPORT_TYPES && outbound.encryption?.let {
                it.isNotBlank() && !it.equals("none", ignoreCase = true)
            } == true) return true
        return type == "ws" && transport.maxEarlyData?.let { value ->
            runCatching { UInt32JsonAdapter.requireValue(value) }.isFailure
        } == true
    }

    private fun encodeUrlComponent(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun formatServerHost(server: String): String {
        val s = server.trim()
        return if (s.contains(":") && !s.startsWith("[") && !s.endsWith("]")) "[$s]" else s
    }

    private fun encodeBase64NoWrap(bytes: ByteArray): String {
        return try {
            val base64Class = Class.forName("java.util.Base64")
            val encoder = base64Class.getMethod("getEncoder").invoke(null)
            encoder.javaClass.getMethod("encodeToString", ByteArray::class.java).invoke(encoder, bytes) as String
        } catch (_: Throwable) {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    private fun buildOptionalQuery(params: List<String>): String {
        val query = params.filter { it.isNotBlank() }.joinToString("&")
        return if (query.isNotEmpty()) "?$query" else ""
    }

    private fun buildUserInfo(username: String?, password: String?): String {
        val user = username?.takeIf { it.isNotBlank() } ?: return ""
        val encodedUser = encodeUrlComponent(user)
        val encodedPassword = password?.takeIf { it.isNotBlank() }?.let { ":${encodeUrlComponent(it)}" }.orEmpty()
        return "$encodedUser$encodedPassword@"
    }

    private fun joinTransportHosts(hosts: List<String>?): String? {
        return hosts.orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth", "LongMethod")
    private fun generateVLessLink(outbound: Outbound): String {
        val uuid = outbound.uuid ?: return ""
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val params = mutableListOf<String>()

        params.add("type=${outbound.transport?.type ?: "tcp"}")
        params.add(
            "encryption=" + (outbound.encryption?.takeIf { it.isNotBlank() }
                ?.let { encodeUrlComponent(it) } ?: "none")
        )

        outbound.flow?.let { params.add("flow=$it") }

        if (outbound.tls?.enabled == true) {
            if (outbound.tls.reality?.enabled == true) {
                params.add("security=reality")
                outbound.tls.reality.publicKey?.let { params.add("pbk=${encodeUrlComponent(it)}") }
                outbound.tls.reality.shortId?.let { params.add("sid=${encodeUrlComponent(it)}") }
                outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            } else {
                params.add("security=tls")
                outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
            if (outbound.tls.insecure == true) {
                params.add("allowInsecure=1")
            }
            outbound.tls.alpn?.let {
                if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
            }
        }

        outbound.packetEncoding?.let { params.add("packetEncoding=$it") }

        when (outbound.transport?.type) {
            "ws" -> {
                val host = outbound.transport.headers?.get("Host")
                    ?: outbound.transport.host?.firstOrNull()
                host?.let { params.add("host=${encodeUrlComponent(it)}") }

                var path = outbound.transport.path ?: "/"
                outbound.transport.maxEarlyData?.let { ed ->
                    if (ed != 0L) {
                        val separator = if (path.contains("?")) "&" else "?"
                        path = "$path${separator}ed=$ed"
                    }
                }
                params.add("path=${encodeUrlComponent(path)}")
            }
            "grpc" -> {
                outbound.transport.serviceName?.let {
                    params.add("serviceName=${encodeUrlComponent(it)}")
                }
                params.add("mode=gun")
            }
            "http", "h2" -> {
                outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                joinTransportHosts(outbound.transport.host)
                    ?.let { params.add("host=${encodeUrlComponent(it)}") }
            }
            "httpupgrade" -> {
                outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                val host = outbound.transport.host?.firstOrNull()
                    ?: outbound.transport.headers?.get("Host")
                    ?: outbound.transport.headers?.get("host")
                host?.let { params.add("host=${encodeUrlComponent(it)}") }
            }
            "xhttp", "splithttp" -> {
                outbound.transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                joinTransportHosts(outbound.transport.host)
                    ?.let { params.add("host=${encodeUrlComponent(it)}") }
                outbound.transport.mode?.let { params.add("mode=${encodeUrlComponent(it)}") }
                outbound.transport.xPaddingBytes?.let {
                    params.add("xPaddingBytes=${encodeUrlComponent(it)}")
                }
                outbound.transport.scMaxEachPostBytes?.let { params.add("scMaxEachPostBytes=$it") }
                outbound.transport.scMinPostsIntervalMs?.let { params.add("scMinPostsIntervalMs=$it") }
                outbound.transport.scMaxBufferedPosts?.let { params.add("scMaxBufferedPosts=$it") }
                if (outbound.transport.noGRPCHeader == true) params.add("noGRPCHeader=1")
                if (outbound.transport.noSSEHeader == true) params.add("noSSEHeader=1")
            }
        }

        val name = encodeUrlComponent(outbound.tag)
        val queryPart = buildOptionalQuery(params)
        return "vless://$uuid@$server:$port$queryPart#$name"
    }

    private fun generateTrojanLink(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()
        if (outbound.tls?.enabled == true) {
            params.add("security=tls")
            outbound.tls.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
            if (outbound.tls.insecure == true) params.add("allowInsecure=1")
            outbound.tls.alpn?.takeIf { it.isNotEmpty() }?.let {
                params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
            }
            outbound.tls.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }
        }

        val transport = outbound.transport
        if (transport != null) {
            params.add("type=${encodeUrlComponent(transport.type ?: "tcp")}")
            when (transport.type) {
                "ws" -> {
                    val host = transport.headers?.get("Host")
                        ?: transport.headers?.get("host")
                        ?: transport.host?.firstOrNull()
                    host?.let { params.add("host=${encodeUrlComponent(it)}") }
                    params.add("path=${encodeUrlComponent(transport.path ?: "/")}")
                }
                "grpc" -> transport.serviceName?.let {
                    params.add("serviceName=${encodeUrlComponent(it)}")
                }
                "http", "h2" -> {
                    joinTransportHosts(transport.host)?.let { params.add("host=${encodeUrlComponent(it)}") }
                    transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                }
                "httpupgrade" -> {
                    transport.host?.firstOrNull()?.let { params.add("host=${encodeUrlComponent(it)}") }
                    transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                }
                "xhttp", "splithttp" -> {
                    joinTransportHosts(transport.host)?.let { params.add("host=${encodeUrlComponent(it)}") }
                    transport.path?.let { params.add("path=${encodeUrlComponent(it)}") }
                    transport.mode?.let { params.add("mode=${encodeUrlComponent(it)}") }
                    transport.xPaddingBytes?.let {
                        params.add("xPaddingBytes=${encodeUrlComponent(it)}")
                    }
                    transport.scMaxEachPostBytes?.let { params.add("scMaxEachPostBytes=$it") }
                    transport.scMinPostsIntervalMs?.let { params.add("scMinPostsIntervalMs=$it") }
                    transport.scMaxBufferedPosts?.let { params.add("scMaxBufferedPosts=$it") }
                    if (transport.noGRPCHeader == true) params.add("noGRPCHeader=1")
                    if (transport.noSSEHeader == true) params.add("noSSEHeader=1")
                }
            }
        }

        val queryPart = buildOptionalQuery(params)
        return "trojan://$password@$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateHttpLink(outbound: Outbound): String {
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val useTls = outbound.tls?.enabled == true
        val scheme = if (useTls) "https" else "http"
        val port = outbound.serverPort ?: if (useTls) 443 else 8080
        val name = encodeUrlComponent(outbound.tag)
        val userInfo = buildUserInfo(outbound.username, outbound.password)
        val params = mutableListOf<String>()
        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.takeIf { it.isNotEmpty() }?.let {
            params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.certificatePublicKeySha256?.firstOrNull()?.let {
            params.add("pinSHA256=${encodeUrlComponent(it)}")
        }
        outbound.tls?.ca?.takeIf { it.isNotEmpty() }?.let {
            params.add("certificate=${encodeUrlComponent(it.joinToString("\n"))}")
        }
        outbound.tls?.caPath?.let { params.add("certificate_path=${encodeUrlComponent(it)}") }
        outbound.tls?.certificate?.takeIf { it.isNotEmpty() }?.let {
            params.add("client_certificate=${encodeUrlComponent(it.joinToString("\n"))}")
        }
        outbound.tls?.certificatePath?.let { params.add("client_certificate_path=${encodeUrlComponent(it)}") }
        outbound.tls?.key?.takeIf { it.isNotEmpty() }?.let {
            params.add("client_key=${encodeUrlComponent(it.joinToString("\n"))}")
        }
        outbound.tls?.keyPath?.let { params.add("client_key_path=${encodeUrlComponent(it)}") }
        outbound.path?.let { params.add("proxy_path=${encodeUrlComponent(it)}") }
        outbound.headers
            ?.takeIf { it.isNotEmpty() }
            ?.allHeaderValues()
            ?.flatMap { (key, values) -> values.map { value -> "$key: $value" } }
            ?.joinToString("\n")
            ?.let { params.add("headers=${encodeUrlComponent(it)}") }
        val queryPart = buildOptionalQuery(params)
        return "$scheme://$userInfo$server:$port$queryPart#$name"
    }

    private fun generateSocksLink(outbound: Outbound): String {
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 1080
        val name = encodeUrlComponent(outbound.tag)
        val userInfo = buildUserInfo(outbound.username, outbound.password)
        val version = outbound.version?.asString?.lowercase()?.takeIf { it in setOf("4", "4a", "5") } ?: "5"
        val params = mutableListOf<String>()
        outbound.network?.firstOrNull()?.let { params.add("network=${encodeUrlComponent(it)}") }
        if (outbound.udpOverTcp?.enabled == true) params.add("uot=1")
        val queryPart = buildOptionalQuery(params)
        return "socks$version://$userInfo$server:$port$queryPart#$name"
    }

    private fun generateHysteria2Link(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()

        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.takeIf { it.isNotEmpty() }?.let {
            params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.certificatePublicKeySha256?.firstOrNull()?.let {
            params.add("pinSHA256=${encodeUrlComponent(it)}")
        }

        outbound.obfs?.let { obfs ->
            obfs.type?.let { params.add("obfs=${encodeUrlComponent(it)}") }
            obfs.password?.let { params.add("obfs-password=${encodeUrlComponent(it)}") }
        }
        outbound.upMbps?.let { params.add("upmbps=$it") }
        outbound.downMbps?.let { params.add("downmbps=$it") }
        outbound.serverPorts?.takeIf { it.isNotEmpty() }?.let {
            params.add("mport=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.hopInterval?.let { params.add("hop_interval=${encodeUrlComponent(it)}") }
        outbound.network?.firstOrNull()?.let { params.add("network=${encodeUrlComponent(it)}") }

        val queryPart = buildOptionalQuery(params)
        return "hysteria2://$password@$server:$port$queryPart#$name"
    }

    @Suppress("CyclomaticComplexMethod")
    private fun generateAnyTLSLink(outbound: Outbound): String {
        val password = encodeUrlComponent(outbound.password ?: "")
        val server = outbound.server?.let { formatServerHost(it) } ?: return ""
        val port = outbound.serverPort ?: 443
        val name = encodeUrlComponent(outbound.tag)

        val params = mutableListOf<String>()

        outbound.tls?.serverName?.let { params.add("sni=${encodeUrlComponent(it)}") }
        if (outbound.tls?.insecure == true) params.add("insecure=1")
        outbound.tls?.alpn?.let {
            if (it.isNotEmpty()) params.add("alpn=${encodeUrlComponent(it.joinToString(","))}")
        }
        outbound.tls?.utls?.fingerprint?.let { params.add("fp=${encodeUrlComponent(it)}") }

        outbound.idleSessionCheckInterval?.let { params.add("idle_session_check_interval=$it") }
        outbound.idleSessionTimeout?.let { params.add("idle_session_timeout=$it") }
        outbound.minIdleSession?.let { params.add("min_idle_session=$it") }

        val queryPart = buildOptionalQuery(params)
        return "anytls://$password@$server:$port$queryPart#$name"
    }

}
