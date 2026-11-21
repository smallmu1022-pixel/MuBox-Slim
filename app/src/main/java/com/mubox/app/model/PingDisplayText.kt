package com.mubox.app.model

object PingDisplayText {
    fun resolve(
        isConnected: Boolean,
        displayPing: Long?,
        timeoutText: String,
        unavailableText: String,
        ipv6OnlyText: String,
        dnsFailedText: String = "",
        refusedText: String = ""
    ): String {
        return when {
            !isConnected -> "-"
            displayPing != null && displayPing > 0 -> "$displayPing ms"
            displayPing == PingResultCode.FAILED_TIMEOUT -> timeoutText
            displayPing == PingResultCode.UNAVAILABLE -> unavailableText
            displayPing == PingResultCode.IPV6_ONLY -> ipv6OnlyText
            displayPing == PingResultCode.DNS_FAILED && dnsFailedText.isNotEmpty() -> dnsFailedText
            displayPing == PingResultCode.REFUSED && refusedText.isNotEmpty() -> refusedText
            else -> "-"
        }
    }
}
