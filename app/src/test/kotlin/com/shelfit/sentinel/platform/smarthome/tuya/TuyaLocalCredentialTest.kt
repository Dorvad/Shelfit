package com.shelfit.sentinel.platform.smarthome.tuya

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Working out which devices sit behind a hub.
 *
 * This matters more than it looks. A Wi-Fi device answers on its own IP, so the plain LAN
 * protocol reaches it; a Zigbee device has no IP at all and every command must be routed
 * through its gateway. Getting it wrong means implementing local control and then wondering
 * why two thirds of a house never answers.
 *
 * The awkward part is that Tuya reports `sub` reliably but the parent only sometimes. The
 * fallback is `tinytuya`'s, and its comment explains the otherwise-arbitrary heuristic: *"The
 * only link between parent and child appears to be the local key."*
 */
class TuyaLocalCredentialTest {

    private fun devices(vararg json: String): List<JSONObject> =
        json.map { JSONObject(it) }

    private fun jsonList(raw: String): List<JSONObject> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `a wifi device is directly reachable`() {
        val parsed = TuyaLocalCredential.fromDeviceList(
            devices("""{"id":"bf01","name":"Kettle plug","local_key":"unique1","ip":"10.0.0.5"}"""),
        )

        val plug = parsed.single()
        assertTrue(plug.directlyReachable)
        assertTrue(!plug.isSubDevice)
        assertNull(plug.gatewayId)
        assertEquals("10.0.0.5", plug.ip)
    }

    @Test
    fun `the cloud's own gateway_id is used when present`() {
        val parsed = TuyaLocalCredential.fromDeviceList(
            jsonList(
                """
                [{"id":"gw","name":"Multi-mode Gateway","local_key":"hubkey"},
                 {"id":"bf02","name":"Study light","local_key":"hubkey","sub":true,
                  "gateway_id":"gw","node_id":"0x1234"}]
                """.trimIndent(),
            ),
        )

        val light = parsed.first { it.deviceId == "bf02" }
        assertTrue(light.isSubDevice)
        assertTrue(!light.directlyReachable)
        assertEquals("gw", light.gatewayId)
        assertEquals("a command for a sub-device must carry it", "0x1234", light.nodeId)
    }

    /**
     * The real-world case: Tuya hands a sub-device the same local key as its hub, and does not
     * always say which hub. Matching keys is the only available link.
     */
    @Test
    fun `a sub-device with no gateway_id finds its parent by the shared local key`() {
        val parsed = TuyaLocalCredential.fromDeviceList(
            jsonList(
                """
                [{"id":"gw","name":"Multi-mode Gateway","local_key":"hubkey"},
                 {"id":"bf02","name":"Study light switch","local_key":"hubkey","sub":true},
                 {"id":"bf03","name":"Bathroom dimmer","local_key":"hubkey","sub":true},
                 {"id":"bf04","name":"Kettle plug","local_key":"unique1"}]
                """.trimIndent(),
            ),
        )

        assertEquals("gw", parsed.first { it.deviceId == "bf02" }.gatewayId)
        assertEquals("gw", parsed.first { it.deviceId == "bf03" }.gatewayId)

        // The hub itself is on Wi-Fi, and so is the unrelated plug.
        assertTrue(parsed.first { it.deviceId == "gw" }.directlyReachable)
        assertTrue(parsed.first { it.deviceId == "bf04" }.directlyReachable)
        assertNull(parsed.first { it.deviceId == "bf04" }.gatewayId)
    }

    @Test
    fun `a hub is never treated as its own parent`() {
        // The hub shares its key with its children by definition, so a naive match would point
        // the hub at itself and make it look unreachable.
        val parsed = TuyaLocalCredential.fromDeviceList(
            jsonList(
                """
                [{"id":"gw","name":"Gateway","local_key":"hubkey"},
                 {"id":"bf02","name":"Sub","local_key":"hubkey","sub":true}]
                """.trimIndent(),
            ),
        )

        val hub = parsed.first { it.deviceId == "gw" }
        assertNull(hub.gatewayId)
        assertTrue(hub.directlyReachable)
    }

    @Test
    fun `a sub-device whose parent is absent is still reported as a sub-device`() {
        // Better "through a hub we cannot name" than silently claiming it is on Wi-Fi.
        val parsed = TuyaLocalCredential.fromDeviceList(
            devices("""{"id":"bf05","name":"Orphan sensor","local_key":"lonely","sub":true}"""),
        )

        val orphan = parsed.single()
        assertTrue(orphan.isSubDevice)
        assertTrue(!orphan.directlyReachable)
        assertNull(orphan.gatewayId)
    }

    @Test
    fun `a device with no local key is not usable for local control`() {
        val parsed = TuyaLocalCredential.fromDeviceList(
            devices("""{"id":"bf06","name":"No key","local_key":""}"""),
        )

        assertTrue(!parsed.single().usable)
    }

    @Test
    fun `a device with no name still gets something readable`() {
        val parsed = TuyaLocalCredential.fromDeviceList(
            devices("""{"id":"bf07","local_key":"k"}"""),
        )

        assertTrue(parsed.single().name.isNotBlank())
    }

    /**
     * Sized like the reporter's actual home: one hub with seven children, three other Wi-Fi
     * devices. The split is the number that decides how much of a house local control can
     * reach.
     */
    @Test
    fun `a hub with several children is separated from the wifi devices`() {
        val subs = (1..7).joinToString(",") {
            """{"id":"sub$it","name":"Sub $it","local_key":"hubkey","sub":true}"""
        }
        val wifi = (1..3).joinToString(",") {
            """{"id":"wifi$it","name":"Wi-Fi $it","local_key":"key$it"}"""
        }
        val parsed = TuyaLocalCredential.fromDeviceList(
            jsonList("""[{"id":"gw","name":"Gateway","local_key":"hubkey"},$subs,$wifi]"""),
        )

        assertEquals(11, parsed.size)
        assertEquals("the hub plus three Wi-Fi devices", 4, parsed.count { it.directlyReachable })
        assertEquals(7, parsed.count { it.isSubDevice })
        assertTrue(parsed.filter { it.isSubDevice }.all { it.gatewayId == "gw" })
    }
}
