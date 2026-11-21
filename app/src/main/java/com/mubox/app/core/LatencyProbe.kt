package com.mubox.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.mubox.app.model.PingResultCode
import com.mubox.app.utils.DefaultNetworkListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
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
 * TCP_PING: a TCP connect to the node's server endpoint, dialed on the
 * physical (non-VPN) network so a running tunnel can never capture the
 * probe and distort the number. The host is resolved on that same network
 * and every resolved address is tried in turn (IPv4 first); resolution
 * time is not counted, only the connect handshake. Failures keep their
 * cause: [PingResultCode.DNS_FAILED] when the name does not resolve,
 * [PingResultCode.REFUSED] when the endpoint actively refuses, and
 * [PingResultCode.FAILED_TIMEOUT] otherwise.
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

    suspend fun tcpPing(context: Context, server: String?, serverPort: Int?, timeoutMs: Int): Long =
        withContext(Dispatchers.IO) {
            val host = server?.trim().orEmpty()
            val port = serverPort ?: -1
            if (host.isEmpty() || port !in 1..65535) return@withContext PingResultCode.UNAVAILABLE
            val boundedTimeout = timeoutMs.coerceIn(1, MAX_CORE_TIMEOUT_MS)

            // Dial on the physical network: a socket bound to it bypasses the
            // app's own TUN entirely, so testing while connected measures the
            // real direct handshake instead of a trip through the tunnel.
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.let {
                DefaultNetworkListener.selectBestPhysicalNetwork(it)
            }

            val addresses = try {
                val resolved = if (network != null) {
                    network.getAllByName(host)
                } else {
                    InetAddress.getAllByName(host)
                }
                resolved?.toList().orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "TCPing resolution failed for $host: ${e.message}")
                return@withContext PingResultCode.DNS_FAILED
            }
            if (addresses.isEmpty()) return@withContext PingResultCode.DNS_FAILED

            // IPv4 first, matching the core's prefer_ipv4 caliber; the total
            // time budget is shared across all address attempts.
            val ordered = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
            val deadlineNanos = System.nanoTime() + boundedTimeout.toLong() * 1_000_000L
            var sawRefusal = false
            for (address in ordered) {
                val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
                if (remainingMs <= 0) break
                try {
                    Socket().use { socket ->
                        if (network != null) {
                            runCatching { network.bindSocket(socket) }
                        }
                        val started = System.nanoTime()
                        socket.connect(InetSocketAddress(address, port), remainingMs)
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000
                        return@withContext if (elapsedMs in 0..boundedTimeout) {
                            elapsedMs.coerceAtLeast(1L)
                        } else {
                            PingResultCode.FAILED_TIMEOUT
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "TCPing timeout for $host:$port via $address")
                } catch (e: ConnectException) {
                    sawRefusal = true
                    Log.w(TAG, "TCPing refused for $host:$port via $address: ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "TCPing failed for $host:$port via $address: ${e.message}")
                }
            }
            if (sawRefusal) PingResultCode.REFUSED else PingResultCode.FAILED_TIMEOUT
        }
}
