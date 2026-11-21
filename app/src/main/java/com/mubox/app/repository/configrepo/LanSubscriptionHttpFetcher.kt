@file:Suppress("MaxLineLength", "ReturnCount", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "ComplexCondition")

package com.mubox.app.repository

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * 方向 2b 专用拉取器：仅服务「http + 私有/环回/链路本地/ULA 的 IP 字面量」订阅地址。
 *
 * 背景：network_security_config 不支持 CIDR，IP 字面量局域网订阅经 OkHttp 会被 NSC 拦下；
 * 而 AOSP 明确 java.net.Socket 不在 NSC 约束范围内。本拉取器用裸 Socket 实现极简 HTTP/1.1 GET，
 * 入口条件把放行集合严格限定在审计本就想放行的 LAN 例外内：
 * 其余一切 URL（主机名、https、公网 IP）一律不进此路径，仍走既有 OkHttp 链路。
 *
 * 本拉取器永不做 TLS、永不连接公网地址；重定向目标一旦离开上述集合，立即交回 OkHttp 处理。
 * 返回形状（状态码/状态消息/响应头/body 字符串）与 executeSubscriptionAttempt 的消费方式对齐，
 * 解析、UA 回退与健康记账全部复用既有管线。
 */
internal object LanSubscriptionHttpFetcher {

    data class Response(
        val code: Int,
        val message: String,
        val headers: List<Pair<String, String>>,
        val body: String
    ) {
        // 与 OkHttp Response.header 语义一致：不区分大小写、同名取最后一个
        fun header(name: String): String? =
            headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    sealed interface FetchOutcome {
        data class Completed(val response: Response) : FetchOutcome
        data class HandBackToOkHttp(val url: String) : FetchOutcome
    }

    private const val MAX_REDIRECT_FOLLOWS = 5
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_HEADER_LINE_BYTES = 8192
    private const val HTTP_DEFAULT_PORT = 80

    // 与 ConfigRepository.buildSubscriptionRequest 中的 Accept 取值逐字一致
    private const val SUBSCRIPTION_ACCEPT_HEADER = "application/yaml,text/yaml,text/plain,application/json,*/*"

    private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    private val HEADER_TERMINATOR = intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)

    /** 入口判定：scheme=http 且 host 为落在私有/环回/链路本地/ULA 段的 IP 字面量才返回 true。 */
    fun isEligible(url: String): Boolean = resolveTarget(url) != null

    fun fetch(
        url: String,
        userAgent: String,
        timeoutBudget: SubscriptionAttemptTimeoutBudget
    ): FetchOutcome {
        val deadlineMs = System.currentTimeMillis() + timeoutBudget.callTimeoutSeconds * 1000L
        var currentUrl = url
        var redirectsFollowed = 0
        while (true) {
            val target = resolveTarget(currentUrl)
                ?: return FetchOutcome.HandBackToOkHttp(currentUrl)
            val response = executeOnce(target, userAgent, timeoutBudget, deadlineMs)
            val location = response.header("Location")
            if (response.code !in REDIRECT_STATUS_CODES || location.isNullOrBlank()) {
                return FetchOutcome.Completed(response)
            }
            val nextUrl = resolveLocation(currentUrl, location)
            if (redirectsFollowed >= MAX_REDIRECT_FOLLOWS) {
                return FetchOutcome.HandBackToOkHttp(nextUrl)
            }
            redirectsFollowed++
            currentUrl = nextUrl
        }
    }

    private class LanTarget(
        val uri: URI,
        val address: InetAddress,
        val port: Int
    )

    private fun resolveTarget(url: String): LanTarget? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val address = lanAddressOrNull(host) ?: return null
        val port = if (uri.port >= 0) uri.port else HTTP_DEFAULT_PORT
        return LanTarget(uri = uri, address = address, port = port)
    }

    /**
     * 仅当 host 是 IP 字面量且落在目标网段时返回其地址，否则返回 null。
     * 主机名一律返回 null：本函数永不做 DNS 解析，杜绝「域名解析到私有 IP 被顺带放行」。
     */
    private fun lanAddressOrNull(host: String): InetAddress? {
        if (host.contains(':')) {
            // IPv6 字面量：含 ':' 的字符串不可能是主机名，getByName 只做字面量解析、不查 DNS
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
            val bytes = address.address ?: return null
            return if (isPrivateOrLocalAddress(bytes)) address else null
        }
        val octets = parseIpv4Literal(host) ?: return null
        if (!isPrivateOrLocalIpv4(octets)) return null
        return InetAddress.getByAddress(octets)
    }

    private fun parseIpv4Literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun isPrivateOrLocalAddress(bytes: ByteArray): Boolean {
        return when (bytes.size) {
            4 -> isPrivateOrLocalIpv4(bytes)
            16 -> isPrivateOrLocalIpv6(bytes)
            else -> false
        }
    }

    private fun isPrivateOrLocalIpv4(bytes: ByteArray): Boolean {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 10 -> true // 10.0.0.0/8
            b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12
            b0 == 192 && b1 == 168 -> true // 192.168.0.0/16
            b0 == 127 -> true // 127.0.0.0/8 环回
            b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 链路本地
            else -> false
        }
    }

    private fun isPrivateOrLocalIpv6(bytes: ByteArray): Boolean {
        // ::1 环回
        if (bytes.copyOfRange(0, 15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return true
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 ULA
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true // fe80::/10 链路本地
        // IPv4 映射地址 ::ffff:a.b.c.d：按内嵌 IPv4 的口径判定
        val isV4Mapped = bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        if (isV4Mapped) return isPrivateOrLocalIpv4(bytes.copyOfRange(12, 16))
        return false
    }

    private fun resolveLocation(currentUrl: String, location: String): String {
        val trimmed = location.trim()
        return runCatching { URI(currentUrl).resolve(trimmed).toString() }.getOrDefault(trimmed)
    }

    private fun executeOnce(
        target: LanTarget,
        userAgent: String,
        timeoutBudget: SubscriptionAttemptTimeoutBudget,
        deadlineMs: Long
    ): Response {
        val socket = Socket()
        try {
            socket.connect(
                InetSocketAddress(target.address, target.port),
                timeoutMs(deadlineMs, timeoutBudget.connectTimeoutSeconds)
            )
            socket.soTimeout = timeoutMs(deadlineMs, timeoutBudget.readTimeoutSeconds)
            socket.getOutputStream().apply {
                write(buildRequest(target, userAgent).toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream())
            return readResponse(input, deadlineMs)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 超时映射现有 SubscriptionAttemptTimeoutBudget：取单项预算与 call 整体截止的更紧者。 */
    private fun timeoutMs(deadlineMs: Long, budgetSeconds: Long): Int {
        val remainingMs = deadlineMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            throw SocketTimeoutException("Subscription request call timeout exceeded")
        }
        return minOf(remainingMs, budgetSeconds * 1000L)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun checkCallDeadline(deadlineMs: Long) {
        if (System.currentTimeMillis() > deadlineMs) {
            throw SocketTimeoutException("Subscription request call timeout exceeded")
        }
    }

    private fun buildRequest(target: LanTarget, userAgent: String): String {
        val uri = target.uri
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val requestTarget = if (uri.rawQuery != null) "$path?${uri.rawQuery}" else path
        val hostHeader = buildString {
            val host = uri.host.orEmpty()
            if (host.contains(':')) {
                append('[').append(host).append(']')
            } else {
                append(host)
            }
            if (uri.port >= 0) append(':').append(uri.port)
        }
        return buildString {
            append("GET ").append(requestTarget).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: ").append(userAgent).append("\r\n")
            append("Accept: ").append(SUBSCRIPTION_ACCEPT_HEADER).append("\r\n")
            append("Accept-Encoding: gzip\r\n")
            append("Connection: close\r\n")
            uri.userInfo?.let { userInfo ->
                val encoded = Base64.getEncoder()
                    .encodeToString(userInfo.toByteArray(Charsets.UTF_8))
                append("Authorization: Basic ").append(encoded).append("\r\n")
            }
            append("\r\n")
        }
    }

    private class ResponseHead(
        val code: Int,
        val message: String,
        val headers: List<Pair<String, String>>
    ) {
        fun header(name: String): String? =
            headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private fun readResponse(input: InputStream, deadlineMs: Long): Response {
        var head = readHead(input, deadlineMs)
        // 1xx 为中间响应（101 除外），跳过后继续读最终响应头
        while (head.code in 100..199 && head.code != 101) {
            head = readHead(input, deadlineMs)
        }
        val bodyBytes = readBody(input, head, deadlineMs)
        val body = decodeBody(bodyBytes, head.header("Content-Type"))
        return Response(code = head.code, message = head.message, headers = head.headers, body = body)
    }

    private fun readHead(input: InputStream, deadlineMs: Long): ResponseHead {
        val raw = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val value = input.read()
            if (value == -1) throw IOException("Unexpected EOF while reading subscription response headers")
            raw.write(value)
            if (raw.size() > MAX_HEADER_BYTES) {
                throw IOException("Subscription response headers exceed $MAX_HEADER_BYTES bytes")
            }
            if (raw.size() % 512 == 0) checkCallDeadline(deadlineMs)
            matched = when {
                value == HEADER_TERMINATOR[matched] -> matched + 1
                value == HEADER_TERMINATOR[0] -> 1
                else -> 0
            }
            if (matched == HEADER_TERMINATOR.size) break
        }
        val lines = raw.toByteArray().toString(Charsets.ISO_8859_1).split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val statusParts = statusLine.split(' ', limit = 3)
        val code = statusParts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed subscription response status line: $statusLine")
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) {
                null
            } else {
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
        }
        return ResponseHead(code = code, message = statusParts.getOrNull(2).orEmpty(), headers = headers)
    }

    private fun readBody(input: InputStream, head: ResponseHead, deadlineMs: Long): ByteArray {
        val contentLength = head.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val isGzip = head.header("Content-Encoding")
            ?.split(',')
            ?.any { it.trim().equals("gzip", ignoreCase = true) } == true
        // 与 readSubscriptionResponseBody 的前置口径一致：无 gzip 时 Content-Length 超限直接失败
        if (!isGzip && contentLength != null && contentLength > SUBSCRIPTION_RESPONSE_MAX_BYTES) {
            throw IllegalArgumentException("Subscription response body is too large: $contentLength bytes")
        }
        val framed = bodyStream(input, head)
        val decoded = readAllWithCap(
            input = if (isGzip) GZIPInputStream(framed) else framed,
            deadlineMs = deadlineMs
        )
        if (!isGzip && contentLength != null && head.code != 204 && head.code != 304 &&
            decoded.size.toLong() != contentLength
        ) {
            throw IOException("Unexpected end of stream: expected $contentLength bytes, got ${decoded.size}")
        }
        return decoded
    }

    /** body 成帧三选一：chunked / Content-Length / 读到 EOF（请求已声明 Connection: close）。 */
    private fun bodyStream(input: InputStream, head: ResponseHead): InputStream {
        if (head.code == 204 || head.code == 304) return ByteArrayInputStream(ByteArray(0))
        val transferEncoding = head.header("Transfer-Encoding")
            ?.split(',')
            ?.map { it.trim().lowercase() }
        if (!transferEncoding.isNullOrEmpty()) {
            return if (transferEncoding.last() == "chunked") {
                ChunkedInputStream(input)
            } else {
                input
            }
        }
        val contentLength = head.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        if (contentLength != null) return ContentLengthInputStream(input, contentLength)
        return input
    }

    /** 边读边执行与 readSubscriptionResponseBody 相同的 1 MiB 上限（作用于解压后的字节）。 */
    private fun readAllWithCap(input: InputStream, deadlineMs: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            checkCallDeadline(deadlineMs)
            val read = input.read(buffer)
            if (read == -1) break
            totalBytes += read
            require(totalBytes <= SUBSCRIPTION_RESPONSE_MAX_BYTES) {
                "Subscription response body exceeds $SUBSCRIPTION_RESPONSE_MAX_BYTES bytes"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** 与 OkHttp ResponseBody 的字符集口径一致：取 Content-Type 的 charset 参数，缺省 UTF-8。 */
    private fun decodeBody(bodyBytes: ByteArray, contentType: String?): String {
        val charsetName = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
        val charset = charsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        return bodyBytes.toString(charset)
    }

    private class ContentLengthInputStream(
        private val input: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = input.read()
            if (value != -1) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }
    }

    private class ChunkedInputStream(private val input: InputStream) : InputStream() {
        private var chunkRemaining = 0L
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            val read = read(single, 0, 1)
            return if (read == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (finished) return -1
            if (chunkRemaining == 0L && !startNextChunk()) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read == -1) throw IOException("Unexpected EOF in chunked subscription response body")
            chunkRemaining -= read
            if (chunkRemaining == 0L) readLine() // 消费每个 chunk 尾部的 CRLF
            return read
        }

        private fun startNextChunk(): Boolean {
            val sizeLine = readLine()
            val sizeText = sizeLine.substringBefore(';').trim()
            val size = sizeText.toLongOrNull(16)
                ?: throw IOException("Malformed chunk size in subscription response: $sizeLine")
            if (size < 0L) throw IOException("Negative chunk size in subscription response: $sizeLine")
            if (size == 0L) {
                // 终结 chunk：消费 trailer 直到空行
                while (readLine().isNotEmpty()) {
                    // 仅消费，不解析 trailer
                }
                finished = true
                return false
            }
            chunkRemaining = size
            return true
        }

        /** 读一行（以 LF 结尾、剥掉尾部 CR），带行长上限；EOF 时返回已读内容。 */
        private fun readLine(): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value == -1 || value == '\n'.code) break
                line.write(value)
                if (line.size() > MAX_HEADER_LINE_BYTES) {
                    throw IOException("Subscription response line exceeds $MAX_HEADER_LINE_BYTES bytes")
                }
            }
            val bytes = line.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                bytes.size - 1
            } else {
                bytes.size
            }
            return String(bytes, 0, end, Charsets.ISO_8859_1)
        }
    }
}
