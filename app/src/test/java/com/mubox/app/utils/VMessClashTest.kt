package com.mubox.app.utils

import com.mubox.app.utils.parser.ClashYamlParser
import org.junit.Assert.assertFalse
import org.junit.Test

class VMessClashTest {
    @Test
    fun removedVmessClashProxiesAreSkipped() {
        val fixtures = listOf(
            """
                proxies:
                  - {name: TLS-VMess, server: test.example.com, port: 21585, type: vmess, uuid: uuid, alterId: 1, cipher: auto, tls: true, skip-cert-verify: false, udp: true}
            """.trimIndent(),
            """
                proxies:
                  - name: Secure-VMess
                    type: vmess
                    server: edge.example.com
                    port: 443
                    uuid: uuid
                    cipher: auto
                    tls: true
            """.trimIndent()
        )

        fixtures.forEach { yaml ->
            val outbounds = ClashYamlParser().parse(yaml)?.outbounds.orEmpty()
            assertFalse(outbounds.any { it.type == "vmess" })
        }
    }
}
