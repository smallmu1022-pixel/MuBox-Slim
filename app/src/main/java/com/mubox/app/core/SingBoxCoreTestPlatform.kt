package com.mubox.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mubox.app.utils.DefaultNetworkListener
import io.nekohasekai.libbox.*
import java.net.NetworkInterface
import java.util.Collections

private const val TEST_PLATFORM_TAG = "SingBoxCore"

// --- Inner Classes for Platform Interface ---

internal class TestPlatformInterface(private val context: Context) : PlatformInterface {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun autoDetectInterfaceControl(fd: Int) {
        val service = com.mubox.app.service.SingBoxService.instance
        if (service != null) {
            // 与主平台 PlatformInterfaceImpl 对齐：protect 是防 TUN 回环捕获的主防线，
            // 失败必须让本次连接失败，不能只打日志继续（继续走默认路由会被自身 TUN 回环）。
            // 本平台只在 :bg 进程内运行时 instance 才非空，protect 才真实可用。
            val protected = service.protect(fd)
            if (!protected) {
                Log.e(TEST_PLATFORM_TAG, "autoDetectInterfaceControl: protect($fd) failed")
                throw java.io.IOException("VpnService.protect($fd) failed")
            }
            Log.d(TEST_PLATFORM_TAG, "autoDetectInterfaceControl: protected fd=$fd")
        }

        // 第二防线：把 fd 绑到物理网络。服务实例不可用（VPN 未运行）时只剩这一条，
        // 此时本就没有 TUN 捕获，绑定失败维持打日志继续的回退行为。
        try {
            val network = selectPhysicalNetwork()
            if (network != null) {

                val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
                try {
                    network.bindSocket(pfd.fileDescriptor)
                    Log.d(TEST_PLATFORM_TAG, "autoDetectInterfaceControl: bound fd=$fd to network")
                } finally {

                    pfd.detachFd()
                }
            } else {
                Log.w(TEST_PLATFORM_TAG, "autoDetectInterfaceControl: no physical network for fd=$fd")
            }
        } catch (e: Exception) {
            Log.w(TEST_PLATFORM_TAG, "autoDetectInterfaceControl: bind network error for fd=$fd: ${e.message}")
        }
    }

    override fun openTun(options: TunOptions?): Int {
        // Should not be called as we don't provide tun inbound
        Log.w(TEST_PLATFORM_TAG, "TestPlatformInterface: openTun called unexpected!")
        return -1
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {

        if (listener == null) return

        try {
            val activeNetwork = selectPhysicalNetwork()
            if (activeNetwork != null) {
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                val interfaceName = linkProperties?.interfaceName ?: ""
                if (interfaceName.isNotEmpty()) {
                    val index = try {
                        java.net.NetworkInterface.getByName(interfaceName)?.index ?: 0
                    } catch (e: Exception) { 0 }
                    val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                    val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                    listener.updateDefaultInterface(interfaceName, index, isExpensive, false)
                    Log.d(TEST_PLATFORM_TAG, "TestPlatformInterface: initialized default interface: $interfaceName (index=$index)")
                } else {
                    Log.w(TEST_PLATFORM_TAG, "TestPlatformInterface: no interface name for active network")
                }
            } else {
                Log.w(TEST_PLATFORM_TAG, "TestPlatformInterface: no physical network available")
            }
        } catch (e: Exception) {
            Log.w(TEST_PLATFORM_TAG, "TestPlatformInterface: failed to get default interface: ${e.message}")
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
    }

    private fun selectPhysicalNetwork() =
        DefaultNetworkListener.selectBestPhysicalNetwork(connectivityManager)

    override fun getInterfaces(): NetworkInterfaceIterator? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            object : NetworkInterfaceIterator {
                private val iterator = interfaces.filter { it.isUp && !it.isLoopback }.iterator()
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): io.nekohasekai.libbox.NetworkInterface {
                    val iface = iterator.next()
                    return io.nekohasekai.libbox.NetworkInterface().apply {
                        name = iface.name
                        index = iface.index
                        mtu = iface.mtu
                        // type = ... (Field removed/renamed in v1.10)
                        var flagsStr = 0
                        if (iface.isUp) flagsStr = flagsStr or 1
                        if (iface.isLoopback) flagsStr = flagsStr or 4
                        if (iface.isPointToPoint) flagsStr = flagsStr or 8
                        if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                        flags = flagsStr
                        val addrList = ArrayList<String>()
                        for (addr in iface.interfaceAddresses) {
                            val ip = addr.address.hostAddress
                            val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                            if (cleanIp != null) addrList.add("$cleanIp/${addr.networkPrefixLength}")
                        }
                        addresses = StringIteratorImpl(addrList)
                    }
                }
            }
        } catch (e: Exception) { null }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun findConnectionOwner(
        p0: Int,
        p1: String?,
        p2: Int,
        p3: String?,
        p4: Int
    ): ConnectionOwner {
        return ConnectionOwner()
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun sendNotification(p0: io.nekohasekai.libbox.Notification?) {}
    override fun cancelNotification(identifier: String?, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) =
        throw UnsupportedOperationException("Platform neighbor monitor is unavailable")
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = throw UnsupportedOperationException("Platform shell is unavailable")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("Platform shell is unavailable")
    override fun lookupUser(username: String?): PlatformUser =
        throw UnsupportedOperationException("Platform user lookup is unavailable")
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("Platform SFTP is unavailable")
    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("Platform SSH host key is unavailable")
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("Platform bridge is unavailable")
    override fun usePlatformAutoRedirect(): Boolean = false
    override fun createAutoRedirect(options: ByteArray?, handler: AutoRedirectHandler?): AutoRedirectSession =
        throw UnsupportedOperationException("Platform auto redirect is unavailable")
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
        return com.mubox.app.core.LocalResolverImpl
    }
}

internal class TestCommandServerHandler : io.nekohasekai.libbox.CommandServerHandler {
    override fun serviceStop() {}
    override fun serviceReload() {}
    override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
    override fun triggerNativeCrash() = Unit
    override fun connectSSHAgent(): Int = -1
    override fun writeDebugMessage(message: String?) {}
}
