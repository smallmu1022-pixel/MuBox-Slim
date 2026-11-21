package com.mubox.app.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Latency probes backing the two test methods since v1.0.1.
 *
 * URL_TEST: delegated to the sing-box core itself through the temporary
 * service's Clash API delay endpoint, so the reported number is the core's
 * own urltest result (the same caliber other sing-box clients show).
 * Note: the core refuses http:// probe URLs and falls back to its built-in
 * https://www.gstatic.com/generate_204 default; https URLs are honored.
 *
 * TCP_PING: a plain TCP connect to the node's server endpoint. DNS
 * resolution time is not counted, only the connect handshake.
 */
internal object LatencyProbe {
    private const val TAG = "LatencyProbe"

    // The core's per-proxy delay endpoint parses timeout as int16; stay below.
    const val MAX_CORE_TIMEOUT_MS = 30_000

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    suspend fun urlTestViaCore(
        clashPort: Int,
        clashSecret: String,
        outboundTag: String,
        targetUrl: String,
        timeoutMs: Int
    ): Long = withContext(Dispatchers.IO) {
        val boundedTimeout = timeoutMs.coerceIn(1, MAX_CORE_TIMEOUT_MS)
        val url = "http://127.0.0.1:$clashPort".toHttpUrl().newBuilder()
            .addPathSegment("proxies")
            .addPathSegment(outboundTag)
            .addPathSegment("delay")
            .addQueryParameter("url", targetUrl)
            .addQueryParameter("timeout", boundedTimeout.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $clashSecret")
            .get()
            .build()
        val callClient = baseClient.newBuilder()
            .readTimeout(boundedTimeout + 5_000L, TimeUnit.MILLISECONDS)
            .callTimeout(boundedTimeout + 8_000L, TimeUnit.MILLISECONDS)
            .build()
        try {
            callClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext -1L
                val body = response.body?.string() ?: return@withContext -1L
                val delay = JSONObject(body).optLong("delay", -1L)
                if (delay > 0 && delay <= boundedTimeout) delay else -1L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Core urltest failed for tag=$outboundTag: ${e.message}")
            -1L
        }
    }

    suspend fun tcpPing(server: String?, serverPort: Int?, timeoutMs: Int): Long =
        withContext(Dispatchers.IO) {
            val host = server?.trim().orEmpty()
            val port = serverPort ?: -1
            if (host.isEmpty() || port !in 1..65535) return@withContext -1L
            val boundedTimeout = timeoutMs.coerceIn(1, MAX_CORE_TIMEOUT_MS)
            try {
                val address = InetAddress.getByName(host)
                Socket().use { socket ->
                    val started = System.nanoTime()
                    socket.connect(InetSocketAddress(address, port), boundedTimeout)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    if (elapsedMs in 0..boundedTimeout) elapsedMs else -1L
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCPing failed for $host:$port: ${e.message}")
                -1L
            }
        }
}
