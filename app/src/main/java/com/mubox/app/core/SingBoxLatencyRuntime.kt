@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.mubox.app.core

import android.util.Log
import com.mubox.app.model.AppSettings
import com.mubox.app.model.DnsConfig
import com.mubox.app.model.LatencyTestMethod
import com.mubox.app.model.DomainResolveConfig
import com.mubox.app.model.Outbound
import com.mubox.app.model.SingBoxConfig
import com.mubox.app.repository.*
import com.mubox.app.repository.config.OutboundFixer
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** 非 WireGuard 走 OutboundFixer；WireGuard 保留逻辑 outbound 供路由 tag / endpoint 转换。 */
internal fun SingBoxCore.prepareLatencyProbeTarget(outbound: Outbound): Outbound? {
    if (SingBoxCore.isWireGuardOutbound(outbound)) {
        return SingBoxCore.prepareWireGuardLatencyTarget(outbound)
    }
    return OutboundFixer.buildForRuntime(context, outbound)?.let {
        SingBoxCore.applyLatencyBootstrapDomainResolver(it)
    }
}

internal fun SingBoxCore.stripLatencyRuntimeMetadata(config: SingBoxConfig): SingBoxConfig {
    val runtimeOutbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
        config.outbounds.orEmpty().map { stripLatencyRuntimeMetadata(it) },
        ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
    )
    val runtimeDnsServers = config.dns?.servers.orEmpty().mapNotNull { server ->
        runCatching { ConfigRepository.normalizeInjectedDnsServer(server) }.getOrNull()
    }
    val runtimeDns = config.dns?.copy(
        servers = runtimeDnsServers,
        rules = ConfigRepository.sanitizeDnsRulesForRuntime(
            config.dns.rules.orEmpty(),
            runtimeDnsServers.mapNotNullTo(mutableSetOf()) { it.tag },
            availableRuleSetTags = config.route?.ruleSet.orEmpty().mapNotNullTo(mutableSetOf()) { it.tag }
        ),
        independentCache = null,
        fakeip = null
    )
    return config.copy(
        dns = runtimeDns,
        outbounds = runtimeOutbounds,
        endpoints = config.endpoints?.map(ConfigRepository::normalizeEndpointDomainResolverForRuntime),
        proxies = config.proxies?.let { proxies ->
            ConfigRepository.applyDefaultOutboundDomainResolver(
                proxies.map { stripLatencyRuntimeMetadata(it) },
                ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
            )
        }
    )
}

internal fun SingBoxCore.stripLatencyRuntimeMetadata(outbound: Outbound): Outbound {
    val tls = outbound.tls ?: return outbound
    val ech = tls.ech ?: return outbound
    if (ech.dnsServer == null) return outbound
    return outbound.copy(tls = tls.copy(ech = ech.copy(dnsServer = null)))
}

@Suppress("LongParameterList")
internal suspend fun SingBoxCore.testOutboundsLatencyOfflineWithTemporaryService(
    outbounds: List<Outbound>,
    targetUrl: String,
    timeoutMs: Int,
    settings: AppSettings,
    dnsConfig: DnsConfig? = null,
    dependencySourceOutbounds: List<Outbound> = outbounds,
    portReadyTimeoutMs: Long = SingBoxCore.DEFAULT_PORT_READY_TIMEOUT_MS,
    trafficKind: LatencyProbeTrafficKind,
    onResult: (tag: String, latency: Long) -> Unit
) = withContext(Dispatchers.IO) {

    val batchSize = 50

    outbounds.chunked(batchSize).forEach { batch ->

        testOutboundsLatencyBatchInternal(
            batch,
            targetUrl,
            timeoutMs,
            settings,
            dnsConfig,
            dependencySourceOutbounds,
            portReadyTimeoutMs,
            trafficKind,
            onResult
        )
    }
}

@Suppress("LongParameterList")
private suspend fun SingBoxCore.testOutboundsLatencyBatchInternal(
    batchOutbounds: List<Outbound>,
    targetUrl: String,
    timeoutMs: Int,
    settings: AppSettings,
    dnsConfig: DnsConfig? = null,
    dependencySourceOutbounds: List<Outbound> = batchOutbounds,
    portReadyTimeoutMs: Long = SingBoxCore.DEFAULT_PORT_READY_TIMEOUT_MS,
    trafficKind: LatencyProbeTrafficKind,
    onResult: (tag: String, latency: Long) -> Unit
) {
    if (batchOutbounds.isEmpty()) return

    val prepared = prepareLatencyBatchTargets(batchOutbounds, dependencySourceOutbounds, onResult)
        ?: return

    // TCPing never starts a core service: it connects to the server endpoints directly.
    if (settings.latencyTestMethod.normalized() == LatencyTestMethod.TCP_PING) {
        runTcpingBatch(prepared.targets, timeoutMs, settings, onResult)
        return
    }

    // One extra port hosts the temporary service's Clash API (core urltest entry point).
    val ports = allocateLatencyBatchPorts(prepared.targets.size + 1, batchOutbounds, onResult)
        ?: return
    val clashPort = ports.last()
    val probePorts = ports.dropLast(1)
    val clashSecret = UUID.randomUUID().toString()

    val portToTagMap = probePorts.zip(prepared.targets.map { it.tag }).toMap()
    val config = buildBatchTestConfig(
        prepared.targets,
        probePorts,
        settings,
        dnsConfig,
        prepared.dependencySources,
        clashPort,
        clashSecret
    )
    if (config == null) {
        batchOutbounds.forEach { onResult(it.tag, -1L) }
        return
    }
    libboxMutex.withLock {
        runLatencyBatchService(
            config = config,
            ports = probePorts,
            portToTagMap = portToTagMap,
            targetUrl = targetUrl,
            timeoutMs = timeoutMs,
            settings = settings,
            portReadyTimeoutMs = portReadyTimeoutMs,
            batchOutbounds = batchOutbounds,
            trafficKind = trafficKind,
            clashPort = clashPort,
            clashSecret = clashSecret,
            onResult = onResult
        )
    }
}

internal data class LatencyBatchPrepared(
    val targets: List<Outbound>,
    val dependencySources: List<Outbound>
)

private fun SingBoxCore.prepareLatencyBatchTargets(
    batchOutbounds: List<Outbound>,
    dependencySourceOutbounds: List<Outbound>,
    onResult: (tag: String, latency: Long) -> Unit
): LatencyBatchPrepared? {
    val fixedOutbounds = batchOutbounds.mapNotNull { outbound ->
        val fixed = prepareLatencyProbeTarget(outbound)
        if (fixed == null) {
            onResult(outbound.tag, -1L)
        }
        fixed
    }
    if (fixedOutbounds.isEmpty()) return null
    val fixedDependencySourceOutbounds = dependencySourceOutbounds.mapNotNull {
        prepareLatencyProbeTarget(it)
    }
    return LatencyBatchPrepared(fixedOutbounds, fixedDependencySourceOutbounds)
}

private fun SingBoxCore.allocateLatencyBatchPorts(
    count: Int,
    batchOutbounds: List<Outbound>,
    onResult: (tag: String, latency: Long) -> Unit
): List<Int>? {
    return try {
        allocateMultipleLocalPorts(count)
    } catch (e: Exception) {
        Log.e(SingBoxCore.TAG, "Failed to allocate ports for batch test", e)
        batchOutbounds.forEach { onResult(it.tag, -1L) }
        null
    }
}

@Suppress("LongParameterList")
private suspend fun SingBoxCore.runLatencyBatchService(
    config: SingBoxConfig,
    ports: List<Int>,
    portToTagMap: Map<Int, String>,
    targetUrl: String,
    timeoutMs: Int,
    settings: AppSettings,
    portReadyTimeoutMs: Long,
    batchOutbounds: List<Outbound>,
    trafficKind: LatencyProbeTrafficKind,
    clashPort: Int,
    clashSecret: String,
    onResult: (tag: String, latency: Long) -> Unit
) {
    val configJson = gson.toJson(stripLatencyRuntimeMetadata(config))
    val batchTestDbPath = config.experimental?.cacheFile?.path
    var commandServer: CommandServer? = null
    var trafficRecorder: TemporaryProbeTrafficRecorder? = null
    try {
        SingBoxCore.ensureLibboxSetup(context)
        val platformInterface = TestPlatformInterface(context)
        val serverHandler = TestCommandServerHandler()
        commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
        commandServer.start()
        val overrideOptions = OverrideOptions().apply {
            autoRedirect = false
        }
        commandServer.startOrReloadService(configJson, overrideOptions)
        trafficRecorder = TemporaryProbeTrafficRecorder(context, trafficKind).also { it.start() }
        val portsReady = waitForPortsReady(ports + clashPort, portReadyTimeoutMs)
        if (!portsReady) {
            Log.e(SingBoxCore.TAG, "Batch test: ports not ready")
            batchOutbounds.forEach { onResult(it.tag, -1L) }
            return
        }
        runCoreUrlTests(portToTagMap.values, clashPort, clashSecret, targetUrl, timeoutMs, settings, onResult)
    } catch (e: Exception) {
        Log.e(SingBoxCore.TAG, "Batch test failed", e)
        batchOutbounds.forEach { onResult(it.tag, -1L) }
    } finally {
        trafficRecorder?.stopAndRecord()
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        batchTestDbPath?.let { path ->
            runCatching { File(path).delete() }
            runCatching { File("$path-shm").delete() }
            runCatching { File("$path-wal").delete() }
        }
    }
}

private fun SingBoxCore.buildBatchTestConfig(
    batchOutbounds: List<Outbound>,
    ports: List<Int>,
    settings: AppSettings,
    dnsConfig: DnsConfig? = null,
    dependencySourceOutbounds: List<Outbound> = batchOutbounds,
    clashPort: Int,
    clashSecret: String
): SingBoxConfig? {
    val probeParts = assembleLatencyProbeParts(batchOutbounds, dependencySourceOutbounds)
        ?: return null

    val inbounds = ArrayList<com.mubox.app.model.Inbound>()
    val rules = ArrayList<com.mubox.app.model.RouteRule>()
    batchOutbounds.forEachIndexed { index, outbound ->
        val port = ports[index]
        val inboundTag = "test-in-$index"
        inbounds.add(
            com.mubox.app.model.Inbound(
                type = "mixed",
                tag = inboundTag,
                listen = "127.0.0.1",
                listenPort = port
            )
        )
        rules.add(
            com.mubox.app.model.RouteRule(
                inbound = listOf(inboundTag),
                outbound = outbound.tag
            )
        )
    }

    val dnsSourceOutbounds = probeParts.outbounds + batchOutbounds
    val latencyDnsConfig = dnsConfig ?: SingBoxCore.buildLatencyTestDnsConfig(settings, dnsSourceOutbounds)
    val batchTestDbPath = File(tempDir, "batch_test_${UUID.randomUUID()}.db").absolutePath

    return SingBoxConfig(
        log = com.mubox.app.model.LogConfig(level = "debug", timestamp = true),
        dns = latencyDnsConfig,
        inbounds = inbounds,
        outbounds = probeParts.outbounds,
        endpoints = probeParts.endpoints.takeIf { it.isNotEmpty() },
        route = com.mubox.app.model.RouteConfig(
            rules = listOf(
                com.mubox.app.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct")
            ) + rules,
            finalOutbound = "direct",
            autoDetectInterface = true,
            defaultDomainResolver = com.mubox.app.model.DomainResolveConfig(
                server = SingBoxCore.LATENCY_LOCAL_DNS_TAG,
                strategy = "prefer_ipv4"
            )
        ),
        experimental = com.mubox.app.model.ExperimentalConfig(
            cacheFile = com.mubox.app.model.CacheFileConfig(
                enabled = false,
                path = batchTestDbPath,
                storeFakeip = false
            ),
            clashApi = com.mubox.app.model.ClashApiConfig(
                externalController = "127.0.0.1:$clashPort",
                secret = clashSecret
            )
        )
    )
}

/**
 * 将延迟探测目标拆成 outbounds + endpoints。
 * WireGuard 不得出现在 outbounds（sing-box 1.13 仅 endpoint）。
 */
internal fun SingBoxCore.assembleLatencyProbeParts(
    targets: List<Outbound>,
    dependencySourceOutbounds: List<Outbound>
): LatencyProbeParts? {
    return SingBoxCore.buildLatencyProbeParts(
        targets = targets,
        resolveDependencies = { target ->
            resolveDependencyOutbounds(target, dependencySourceOutbounds)
        }
    )
}

private suspend fun SingBoxCore.waitForPortsReady(
    ports: List<Int>,
    portReadyTimeoutMs: Long = SingBoxCore.DEFAULT_PORT_READY_TIMEOUT_MS
): Boolean {
    val boundedTimeoutMs = portReadyTimeoutMs.coerceAtLeast(50L)
    val deadline = System.currentTimeMillis() + boundedTimeoutMs
    while (System.currentTimeMillis() < deadline) {
        val allPortsReady = ports.all { port ->
            isLocalPortReady(port)
        }
        if (allPortsReady) {
            return true
        }
        delay(50)
    }
    Log.e(SingBoxCore.TAG, "Batch test: ports not ready after ${boundedTimeoutMs}ms")
    return false
}

private fun SingBoxCore.isLocalPortReady(port: Int): Boolean {
    return try {
        Socket().use { s ->
            s.soTimeout = 50
            s.connect(InetSocketAddress("127.0.0.1", port), 50)
        }
        true
    } catch (_: Exception) {
        false
    }
}

private suspend fun SingBoxCore.runCoreUrlTests(
    tags: Collection<String>,
    clashPort: Int,
    clashSecret: String,
    targetUrl: String,
    timeoutMs: Int,
    settings: AppSettings,
    onResult: (tag: String, latency: Long) -> Unit
) {
    val semaphore = Semaphore(settings.latencyTestConcurrency)
    coroutineScope {
        val jobs = tags.map { tag ->
            async {
                semaphore.withPermit {
                    val latency = LatencyProbe.urlTestViaCore(
                        clashPort = clashPort,
                        clashSecret = clashSecret,
                        outboundTag = tag,
                        targetUrl = targetUrl,
                        timeoutMs = timeoutMs
                    )
                    onResult(tag, latency)
                }
            }
        }
        jobs.awaitAll()
    }
}

private suspend fun SingBoxCore.runTcpingBatch(
    targets: List<Outbound>,
    timeoutMs: Int,
    settings: AppSettings,
    onResult: (tag: String, latency: Long) -> Unit
) {
    val semaphore = Semaphore(settings.latencyTestConcurrency)
    coroutineScope {
        val jobs = targets.map { target ->
            async {
                semaphore.withPermit {
                    val latency = LatencyProbe.tcpPing(target.server, target.serverPort, timeoutMs)
                    onResult(target.tag, latency)
                }
            }
        }
        jobs.awaitAll()
    }
}

private fun SingBoxCore.allocateMultipleLocalPorts(count: Int): List<Int> {
    val ports = mutableListOf<Int>()
    val sockets = mutableListOf<ServerSocket>()
    try {
        for (i in 0 until count) {
            val socket = ServerSocket(0)
            socket.reuseAddress = true
            ports.add(socket.localPort)
            sockets.add(socket)
        }
    } catch (e: Exception) {
        sockets.forEach { runCatching { it.close() } }
        throw RuntimeException("Failed to allocate $count ports (allocated ${ports.size})", e)
    }
    sockets.forEach { runCatching { it.close() } }
    return ports
}
