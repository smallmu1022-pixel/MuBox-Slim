package com.mubox.app.repository.config

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.mubox.app.model.ObfsConfig
import com.mubox.app.model.Outbound
import com.mubox.app.model.TlsConfig
import com.mubox.app.model.TransportConfig
import com.mubox.app.model.UdpOverTcpConfig
import com.mubox.app.utils.parser.NodeLinkParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLinkExporterTest {

    private val gson = Gson()

    @Test
    fun exportVlessShouldPreservePrivateEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "VLESS",
            server = "vless.example.com",
            serverPort = 443,
            uuid = "uuid",
            flow = "xtls-rprx-vision",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            tls = TlsConfig(enabled = true, serverName = "apple.com"),
            transport = TransportConfig(type = "ws", path = "/ws")
        )

        val link = NodeLinkExporter.export(outbound, gson)

        assertNotNull(link)
        assertTrue(link!!.contains("encryption=mlkem768x25519plus.native.0rtt.sample"))
        assertTrue(link.contains("type=ws"))
        assertTrue(link.contains("flow=xtls-rprx-vision"))
    }

    @Test
    fun exportVlessShouldPreserveXhttpTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "XHTTP",
            server = "vless.example.com",
            serverPort = 443,
            uuid = "uuid",
            transport = TransportConfig(
                type = "xhttp",
                path = "/xhttp",
                host = listOf("cdn.example.com"),
                mode = "auto",
                xPaddingBytes = "100-200",
                scMaxEachPostBytes = 1_048_576,
                scMinPostsIntervalMs = 30,
                scMaxBufferedPosts = 64,
                noGRPCHeader = true,
                noSSEHeader = true
            )
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertNotNull(link)
        assertEquals("xhttp", parsed?.transport?.type)
        assertEquals("/xhttp", parsed?.transport?.path)
        assertEquals(listOf("cdn.example.com"), parsed?.transport?.host)
        assertEquals("auto", parsed?.transport?.mode)
        assertEquals("100-200", parsed?.transport?.xPaddingBytes)
        assertEquals(1_048_576L, parsed?.transport?.scMaxEachPostBytes)
        assertEquals(true, parsed?.transport?.noGRPCHeader)
        assertEquals(true, parsed?.transport?.noSSEHeader)
    }

    @Test
    fun exportVlessShouldRejectPrivateXhttpEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "Private Encryption",
            server = "vless.example.com",
            serverPort = 443,
            uuid = "uuid",
            encryption = "mlkem768x25519plus.native.0rtt.sample",
            transport = TransportConfig(type = "xhttp", path = "/private")
        )

        assertNull(NodeLinkExporter.export(outbound, gson))
    }

    @Test
    fun exportV2RayProtocolsShouldRejectUnknownTransport() {
        listOf("vmess", "vless", "trojan").forEach { protocol ->
            val outbound = Outbound(
                type = protocol,
                tag = "Unknown Transport",
                server = "$protocol.example.com",
                serverPort = 443,
                uuid = "uuid",
                password = "secret",
                transport = TransportConfig(type = "h3")
            )

            assertNull(NodeLinkExporter.export(outbound, gson))
        }
    }

    @Test
    fun exportWebSocketShouldValidateMaxEarlyDataUInt32Range() {
        fun export(maxEarlyData: Long): String? {
            return NodeLinkExporter.export(
                Outbound(
                    type = "vless",
                    tag = "VLESS WS",
                    server = "vless.example.com",
                    serverPort = 443,
                    uuid = "uuid",
                    transport = TransportConfig(type = "ws", path = "/ws", maxEarlyData = maxEarlyData)
                ),
                gson
            )
        }

        assertNotNull(export(4_294_967_295L))
        assertNull(export(4_294_967_296L))
        assertNull(export(-1L))
    }

    @Test
    fun exportVlessShouldPreserveHttpUpgradeTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "HTTPUpgrade",
            server = "edge.example.com",
            serverPort = 443,
            uuid = "uuid",
            tls = TlsConfig(enabled = true, serverName = "cdn.example.com"),
            transport = TransportConfig(type = "httpupgrade", path = "/up", host = listOf("cdn.example.com"))
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertNotNull(link)
        assertTrue(link!!.contains("type=httpupgrade"))
        assertTrue(link.contains("path=%2Fup"))
        assertTrue(link.contains("host=cdn.example.com"))
        assertEquals("httpupgrade", parsed?.transport?.type)
        assertEquals("/up", parsed?.transport?.path)
        assertEquals(listOf("cdn.example.com"), parsed?.transport?.host)
    }

    @Test
    fun exportTrojanShouldPreserveTransport() {
        val outbound = Outbound(
            type = "trojan",
            tag = "Trojan WS",
            server = "trojan.example.com",
            serverPort = 443,
            password = "secret",
            tls = TlsConfig(enabled = true, serverName = "edge.example.com"),
            transport = TransportConfig(
                type = "ws",
                path = "/ws",
                headers = mapOf("Host" to "edge.example.com")
            )
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("ws", parsed?.transport?.type)
        assertEquals("/ws", parsed?.transport?.path)
        assertEquals("edge.example.com", parsed?.transport?.headers?.get("Host"))
    }

    @Test
    fun exportHysteria2ShouldPreserveRuntimeFields() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "HY2",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret",
            upMbps = 80,
            downMbps = 160,
            obfs = ObfsConfig(type = "salamander", password = "obfs-secret"),
            serverPorts = listOf("20000-21000"),
            hopInterval = "30s",
            network = listOf("udp"),
            disableMtuDiscovery = true,
            tls = TlsConfig(enabled = true, serverName = "edge.example.com", alpn = listOf("h3"))
        )

        val link = NodeLinkExporter.export(outbound, gson)
        val parsed = link?.let { NodeLinkParser(gson).parse(it) }

        assertEquals(80, parsed?.upMbps)
        assertEquals(160, parsed?.downMbps)
        assertEquals("salamander", parsed?.obfs?.type)
        assertEquals("obfs-secret", parsed?.obfs?.password)
        assertEquals(listOf("20000-21000"), parsed?.serverPorts)
        assertEquals("30s", parsed?.hopInterval)
        assertEquals(listOf("udp"), parsed?.network)
        assertFalse(link.orEmpty().contains("disable_mtu_discovery"))
        assertNull(parsed?.disableMtuDiscovery)
        assertEquals(listOf("h3"), parsed?.tls?.alpn)
    }

    @Test
    fun exportHttpShouldRoundTrip() {
        val outbound = Outbound(
            type = "http",
            tag = "HTTP Proxy",
            server = "proxy.example.com",
            serverPort = 8080,
            username = "user",
            password = "pass",
            path = "/proxy",
            headers = mapOf("User-Agent" to "MuBox")
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("http", parsed?.type)
        assertEquals("proxy.example.com", parsed?.server)
        assertEquals(8080, parsed?.serverPort)
        assertEquals("user", parsed?.username)
        assertEquals("pass", parsed?.password)
        assertEquals("/proxy", parsed?.path)
        assertEquals("MuBox", parsed?.headers?.get("User-Agent"))
    }

    @Test
    fun exportSocksShouldRoundTrip() {
        val outbound = Outbound(
            type = "socks",
            tag = "SOCKS Proxy",
            server = "socks.example.com",
            serverPort = 1080,
            version = JsonPrimitive("4a"),
            username = "user",
            password = "pass",
            network = listOf("tcp"),
            udpOverTcp = UdpOverTcpConfig(enabled = true)
        )

        val parsed = NodeLinkExporter.export(outbound, gson)?.let { NodeLinkParser(gson).parse(it) }

        assertEquals("socks", parsed?.type)
        assertEquals("socks.example.com", parsed?.server)
        assertEquals(1080, parsed?.serverPort)
        assertEquals("user", parsed?.username)
        assertEquals("pass", parsed?.password)
        assertEquals("4a", parsed?.version?.asString)
        assertEquals(listOf("tcp"), parsed?.network)
        assertEquals(true, parsed?.udpOverTcp?.enabled)
    }

    @Test
    fun removedProtocolsAreNotExported() {
        listOf("vmess", "shadowsocks", "naive", "hysteria", "tuic", "wireguard", "ssh").forEach { type ->
            assertNull("Removed protocol should not be exported: $type", NodeLinkExporter.export(Outbound(type = type, tag = type), gson))
        }
    }

}
