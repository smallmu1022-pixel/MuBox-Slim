package com.mubox.app.ipc

import com.mubox.app.model.DnsConfig
import com.mubox.app.model.Outbound

/**
 * UI 进程经 AIDL 委托 :bg 进程执行临时实例测速的请求载荷，以 Gson JSON 字符串传输。
 *
 * VPN 连接时主进程的临时测速实例无法 protect 出站 socket（VpnService 在 :bg 进程），
 * 出站会被自身 TUN 回环捕获导致整批超时；服务进程内执行则与主平台同构，protect 真实可用。
 *
 * @param single true 时只测 [outbounds] 的第一个节点，走单节点测速路径；
 *   false 时按批量测速路径执行，结果逐个按 tag 回传。
 * @param methodOverride 本次测速方式覆盖（[com.mubox.app.model.LatencyTestMethod] 的枚举名），
 *   为 null 时用设置中的默认方式。
 */
internal data class LatencyTestRequest(
    val single: Boolean = false,
    val outbounds: List<Outbound> = emptyList(),
    val allOutbounds: List<Outbound> = emptyList(),
    val dnsConfig: DnsConfig? = null,
    val methodOverride: String? = null
)
