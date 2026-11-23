package com.mubox.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LatencyProbeTest {

    @Test
    fun urlTestUsesCoreDelayEndpointAndBearerToken() {
        val probe = serveCoreDelayRequest(
            timeoutMs = LatencyProbe.MAX_CORE_TIMEOUT_MS + 10_000,
            responseStatus = "HTTP/1.1 503 Service Unavailable",
            responseBody = "unavailable"
        )

        assertEquals(-1L, probe.latencyMs)
        assertNull(probe.serverFailure)
        assertNotNull(probe.requestLines)
        val requestLines = requireNotNull(probe.requestLines)
        val requestLine = requestLines.first()
        assertTrue(requestLine.startsWith("GET /proxies/proxy-node/delay?"))
        assertTrue(requestLine.contains("url=https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204"))
        assertTrue(requestLine.endsWith("timeout=${LatencyProbe.MAX_CORE_TIMEOUT_MS} HTTP/1.1"))
        assertTrue(
            requestLines.drop(1).any {
                it.equals("Authorization: Bearer test-secret", ignoreCase = true)
            }
        )
    }

    @Test
    fun urlTestReturnsFailureForCoreErrorResponse() {
        val probe = serveCoreDelayRequest(
            timeoutMs = 1,
            responseStatus = "HTTP/1.1 401 Unauthorized",
            responseBody = "unauthorized"
        )

        assertEquals(-1L, probe.latencyMs)
        assertNull(probe.serverFailure)
        assertNotNull(probe.requestLines)
        val requestLines = requireNotNull(probe.requestLines)
        assertTrue(requestLines.first().endsWith("timeout=1 HTTP/1.1"))
    }

    private fun serveCoreDelayRequest(
        timeoutMs: Int,
        responseStatus: String,
        responseBody: String
    ): ProbeResult {
        val server = ServerSocket(0)
        val requestLines = AtomicReference<List<String>?>(null)
        val serverFailure = AtomicReference<Throwable?>(null)
        val serverThread = thread(name = "latency-probe-test-server", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
                    )
                    val captured = mutableListOf<String>()
                    captured += reader.readLine() ?: error("No HTTP request line received")
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        captured += header
                    }
                    requestLines.set(captured)

                    val bodyBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                    val responseHeaders = (
                        "$responseStatus\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    socket.getOutputStream().use { output ->
                        output.write(responseHeaders)
                        output.write(bodyBytes)
                        output.flush()
                    }
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }

        val latencyMs = try {
            runBlocking {
                LatencyProbe.urlTestViaCore(
                    clashPort = server.localPort,
                    clashSecret = "test-secret",
                    outboundTag = "proxy-node",
                    targetUrl = "https://www.gstatic.com/generate_204",
                    timeoutMs = timeoutMs
                )
            }
        } finally {
            server.close()
            serverThread.join(5_000)
        }

        return ProbeResult(latencyMs, requestLines.get(), serverFailure.get())
    }

    private data class ProbeResult(
        val latencyMs: Long,
        val requestLines: List<String>?,
        val serverFailure: Throwable?
    )
}
