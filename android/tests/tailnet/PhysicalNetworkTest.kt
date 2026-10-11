package com.follow.clash.tailnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalNetworkTest {
    private val cellular = PhysicalNetworkCandidate("cellular", validated = true, wifi = false)
    private val wifi = PhysicalNetworkCandidate("wifi", validated = true, wifi = true)

    @Test fun activeWifiWinsEvenWhenCellularIsEnumeratedFirst() {
        assertEquals("wifi", selectPhysicalNetwork("wifi", listOf(cellular, wifi)))
    }

    @Test fun activeCellularIsRespected() {
        assertEquals("cellular", selectPhysicalNetwork("cellular", listOf(wifi, cellular)))
    }

    @Test fun vpnDefaultFallsBackToValidatedWifi() {
        assertEquals("wifi", selectPhysicalNetwork("vpn", listOf(cellular, wifi)))
    }

    @Test fun validatedCellularWinsOverAnUnvalidatedWifiFallback() {
        assertEquals("cellular", selectPhysicalNetwork("vpn", listOf(wifi.copy(validated = false), cellular)))
    }

    @Test fun missingPhysicalNetworksDoNotSelectVpn() {
        assertNull(selectPhysicalNetwork("vpn", emptyList<PhysicalNetworkCandidate<String>>()))
    }
}
