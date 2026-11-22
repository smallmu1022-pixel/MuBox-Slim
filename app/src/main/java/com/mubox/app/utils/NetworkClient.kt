package com.mubox.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetworkClient {
    private const val TAG = "NetworkClient"

    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 20L
    private const val WRITE_TIMEOUT = 20L
    private const val CALL_TIMEOUT = 60L

    private val connectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 10
    }

    private val isVpnActive = AtomicBoolean(false)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // Rely on OkHttp built-in retry logic to avoid retry amplification.
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun newBuilder(): OkHttpClient.Builder {
        return client.newBuilder()
    }

    fun createClientWithTimeout(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = null
    ): OkHttpClient {
        val builder = newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        callTimeoutSeconds?.let { builder.callTimeout(it, TimeUnit.SECONDS) }
        return builder.build()
    }

    fun createClientWithoutRetry(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = null
    ): OkHttpClient {
        val builder = newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
        builder.callTimeout(callTimeoutSeconds ?: 0L, TimeUnit.SECONDS)
        return builder.build()
    }

    /**
     * 与 [createClientWithTimeout] 同语义，但 socket 绑物理网络、DNS 走物理网络
     * 解析，绕开本机 TUN（见 [applyTunnelBypass]）。供更新检查的直连回退用：
     * 全局模式下普通直连会被自身隧道捕获，与代理尝试同路失败。
     */
    fun createBypassClientWithTimeout(
        context: Context,
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = null
    ): OkHttpClient {
        val builder = newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .applyTunnelBypass(context)
        callTimeoutSeconds?.let { builder.callTimeout(it, TimeUnit.SECONDS) }
        return builder.build()
    }

    /**
     * 与 [createClientWithoutRetry] 同语义（retry 关、callTimeout 显式给足），
     * 另加 TUN 绕行。供更新包下载的直连回退用。
     */
    fun createBypassClientWithoutRetry(
        context: Context,
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = null
    ): OkHttpClient {
        val builder = newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .applyTunnelBypass(context)
        builder.callTimeout(callTimeoutSeconds ?: 0L, TimeUnit.SECONDS)
        return builder.build()
    }

    /**
     * 给直连客户端装上绕隧道能力，套路与 LatencyProbe.tcpPing 一致：
     * [DefaultNetworkListener.selectBestPhysicalNetwork] 选非 VPN 的物理网络，
     * socket 建好后 bindSocket 到该网络再由 OkHttp 连接，DNS 也经该网络的
     * getAllByName 解析，避免解析被隧道劫持。物理网络在每次建 socket/解析时
     * 现选（WiFi/蜂窝切换后不拿旧网络）；找不到物理网络或绑定失败时回退系统
     * 默认行为（与普通直连一致）。
     */
    private fun OkHttpClient.Builder.applyTunnelBypass(context: Context): OkHttpClient.Builder {
        val connectivityManager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return this
        socketFactory(PhysicalNetworkSocketFactory(connectivityManager))
        dns(PhysicalNetworkDns(connectivityManager))
        return this
    }

    private class PhysicalNetworkSocketFactory(
        private val connectivityManager: ConnectivityManager
    ) : SocketFactory() {
        // javax.net.SocketFactory 除无参 createSocket() 外还有四个抽象的带参重载，
        // 必须全部实现才能通过编译。OkHttp 实际只调无参版（连接由它自己完成）；
        // 带参版按同一套路实现：先绑物理网络，再按需绑本地地址（网络绑定已占用
        // 本地地址时跳过，避免重复 bind 抛异常），最后连接。
        override fun createSocket(): Socket = createBoundSocket()

        override fun createSocket(host: String, port: Int): Socket =
            createBoundSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = createBoundSocket().apply {
            if (!isBound) bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            createBoundSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = createBoundSocket().apply {
            if (!isBound) bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }

        private fun createBoundSocket(): Socket {
            val socket = Socket()
            val network = DefaultNetworkListener.selectBestPhysicalNetwork(connectivityManager)
            if (network != null) {
                runCatching { network.bindSocket(socket) }
                    .onFailure { Log.w(TAG, "Failed to bind socket to physical network", it) }
            }
            return socket
        }
    }

    private class PhysicalNetworkDns(
        private val connectivityManager: ConnectivityManager
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val network = DefaultNetworkListener.selectBestPhysicalNetwork(connectivityManager)
            if (network != null) {
                val resolved = runCatching { network.getAllByName(hostname) }.getOrNull()
                if (!resolved.isNullOrEmpty()) {
                    return resolved.toList()
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    fun createClientWithProxy(
        proxyPort: Int,
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = CALL_TIMEOUT
    ): OkHttpClient {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress("127.0.0.1", proxyPort)
        )

        val builder = newBuilder()
            .proxy(proxy)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
        builder.callTimeout(callTimeoutSeconds ?: 0L, TimeUnit.SECONDS)
        return builder.build()
    }

    fun onVpnStateChanged(active: Boolean) {
        val previousState = isVpnActive.getAndSet(active)
        if (previousState != active) {
            Log.i(TAG, "VPN state changed: $previousState -> $active, clearing connection pool")
            clearConnectionPool()
        }
    }

    fun clearConnectionPool() {
        connectionPool.evictAll()
    }

    suspend fun <T> executeCancellable(
        call: Call,
        block: (Response) -> T
    ): T {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }

            try {
                val result = call.execute().use(block)
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            } catch (e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    suspend fun <T> executeCancellable(
        client: OkHttpClient,
        request: Request,
        block: (Response) -> T
    ): T {
        return executeCancellable(client.newCall(request), block)
    }
}
