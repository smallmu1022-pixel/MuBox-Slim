package com.mubox.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpVersionModeRulesTest {

    @Test
    fun includesAddressFamiliesForEachMode() {
        assertTrue(IpVersionMode.IPV4_ONLY.includesIpv4)
        assertFalse(IpVersionMode.IPV4_ONLY.includesIpv6)

        assertTrue(IpVersionMode.DUAL_STACK.includesIpv4)
        assertTrue(IpVersionMode.DUAL_STACK.includesIpv6)

        assertTrue(IpVersionMode.PREFER_IPV6.includesIpv4)
        assertTrue(IpVersionMode.PREFER_IPV6.includesIpv6)

        assertFalse(IpVersionMode.IPV6_ONLY.includesIpv4)
        assertTrue(IpVersionMode.IPV6_ONLY.includesIpv6)
    }

    @Test
    fun autoDnsStrategyFollowsMode() {
        assertEquals("ipv4_only", IpVersionMode.IPV4_ONLY.autoDnsStrategy())
        assertEquals("prefer_ipv4", IpVersionMode.DUAL_STACK.autoDnsStrategy())
        assertEquals("prefer_ipv6", IpVersionMode.PREFER_IPV6.autoDnsStrategy())
        assertEquals("ipv6_only", IpVersionMode.IPV6_ONLY.autoDnsStrategy())
    }

    @Test
    fun explicitDnsStrategyIsClampedWhenAddressFamilyIsDisabled() {
        assertEquals("ipv4_only", IpVersionMode.IPV4_ONLY.resolveDnsStrategy(DnsStrategy.PREFER_IPV6))
        assertEquals("ipv6_only", IpVersionMode.IPV6_ONLY.resolveDnsStrategy(DnsStrategy.PREFER_IPV4))
        assertEquals("prefer_ipv6", IpVersionMode.PREFER_IPV6.resolveDnsStrategy(DnsStrategy.AUTO))
        assertEquals("prefer_ipv4", IpVersionMode.DUAL_STACK.resolveDnsStrategy(DnsStrategy.AUTO))
    }

    @Test
    fun enteringDualStackResetsLegacyDirectDnsToAutoAndPreservesOtherDnsPolicies() {
        val previous = AppSettings(
            ipVersionMode = IpVersionMode.IPV4_ONLY,
            directDnsStrategy = DnsStrategy.ONLY_IPV4,
            remoteDnsStrategy = DnsStrategy.ONLY_IPV6,
            dnsStrategy = DnsStrategy.PREFER_IPV4
        )

        val updated = previous.withIpVersionMode(IpVersionMode.DUAL_STACK)

        assertEquals(IpVersionMode.DUAL_STACK, updated.ipVersionMode)
        assertEquals(DnsStrategy.AUTO, updated.directDnsStrategy)
        assertEquals(DnsStrategy.ONLY_IPV6, updated.remoteDnsStrategy)
        assertEquals(DnsStrategy.PREFER_IPV4, updated.dnsStrategy)
    }

    @Test
    fun explicitDirectDnsChoiceIsNotOverriddenWhileRemainingInDualStack() {
        val settings = AppSettings(
            ipVersionMode = IpVersionMode.DUAL_STACK,
            directDnsStrategy = DnsStrategy.ONLY_IPV4
        )

        assertEquals(
            DnsStrategy.ONLY_IPV4,
            settings.withIpVersionMode(IpVersionMode.DUAL_STACK).directDnsStrategy
        )
    }

    @Test
    fun reenteringDualStackResetsDirectDnsToAutoAfterAnExplicitChoice() {
        val selectedExplicitly = AppSettings(
            ipVersionMode = IpVersionMode.DUAL_STACK,
            directDnsStrategy = DnsStrategy.PREFER_IPV6
        )

        val afterLeavingDualStack = selectedExplicitly.withIpVersionMode(IpVersionMode.IPV4_ONLY)
        assertEquals(DnsStrategy.PREFER_IPV6, afterLeavingDualStack.directDnsStrategy)

        val afterReenteringDualStack = afterLeavingDualStack.withIpVersionMode(IpVersionMode.DUAL_STACK)
        assertEquals(DnsStrategy.AUTO, afterReenteringDualStack.directDnsStrategy)
    }

    @Test
    fun appSettingsDefaultDirectDnsStrategyIsAuto() {
        assertEquals(DnsStrategy.AUTO, AppSettings().directDnsStrategy)
    }
}
