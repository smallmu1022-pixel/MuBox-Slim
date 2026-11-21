package com.mubox.app.ui.screens

import com.mubox.app.model.Outbound
import com.mubox.app.model.TlsConfig
import com.mubox.app.model.allHeaderValues
import com.mubox.app.model.asHttpHeaderMap

internal fun createEmptyOutbound(protocol: String): Outbound {
    val defaultPort = when (protocol) {
        "socks" -> 1080
        "http" -> 8080
        else -> 443
    }

    val needsTls = protocol in listOf("vless", "trojan", "hysteria2", "anytls")

    return Outbound(
        type = protocol,
        tag = "New-${protocol.uppercase()}",
        server = "",
        serverPort = defaultPort,
        tls = if (needsTls) TlsConfig(enabled = true) else null
    )
}

internal fun formatHeaderLines(headers: Map<String, String>?): String {
    return headers
        ?.allHeaderValues()
        ?.entries
        ?.sortedBy { it.key.lowercase() }
        ?.flatMap { (key, values) -> values.map { value -> "$key: $value" } }
        ?.joinToString("\n")
        .orEmpty()
}

internal fun parseHeaderLines(text: String): Map<String, String>? {
    val parsed = linkedMapOf<String, MutableList<String>>()
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@forEach

            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                parsed.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

    return parsed
        .mapValues { (_, values) -> values.toList() }
        .takeIf { it.isNotEmpty() }
        ?.asHttpHeaderMap()
}
