package com.shelfit.sentinel.platform.smarthome.tuya

import com.shelfit.sentinel.core.smarthome.DeviceId
import com.shelfit.sentinel.core.smarthome.DeviceKind
import com.shelfit.sentinel.core.smarthome.SmartHomeCommand
import com.shelfit.sentinel.core.smarthome.SmartHomeFailure
import com.shelfit.sentinel.core.smarthome.SmartHomeResult
import com.shelfit.sentinel.core.smarthome.SmartHomeState
import com.shelfit.sentinel.core.smarthome.StructureId
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tuya client, driven by canned responses.
 *
 * This exists because of a real defect. The first version asked
 * `/v1.0/users/{uid}/devices` using the `uid` from a client-credentials token — which is the
 * *project's* identifier, not the linked app account's. Tuya refused it, and the refusal read
 * exactly like bad credentials, so the integration appeared to work right up until somebody
 * with a real account tried it.
 *
 * Nothing could have caught that while the client owned its own HTTP layer. Now the network is
 * a seam, and the endpoint, the paging and the failure paths are pinned to the response shapes
 * Tuya documents.
 */
class TuyaCloudClientTest {

    private val credentials = { TuyaCredentials("id", "secret", TuyaRegion.CENTRAL_EUROPE) }

    /** Records the paths asked for, and answers with whatever the test scripted. */
    private class FakeApi(
        private val responses: MutableMap<String, TuyaResponse> = mutableMapOf(),
        var tokenResponse: TuyaResponse = ok("""{"success":true,"result":{"uid":"projectUid"}}"""),
    ) : TuyaApi {

        val paths = mutableListOf<String>()
        val posts = mutableListOf<Pair<String, String>>()

        fun on(path: String, response: TuyaResponse) {
            responses[path] = response
        }

        override fun forgetToken() = Unit

        override suspend fun verifyCredentials(): TuyaResponse = tokenResponse

        override suspend fun get(path: String, query: Map<String, String>): TuyaResponse {
            paths += path
            return responses[path] ?: ok("""{"success":true,"result":{}}""")
        }

        override suspend fun post(path: String, body: String): TuyaResponse {
            posts += path to body
            return responses[path] ?: ok("""{"success":true,"result":true}""")
        }

        companion object {
            fun ok(json: String): TuyaResponse = TuyaResponse.Ok(JSONObject(json))
            fun error(kind: SmartHomeFailure.Kind, detail: String? = null): TuyaResponse =
                TuyaResponse.Error(SmartHomeFailure(kind, detail = detail))
        }
    }

    private val deviceListPath = "/v1.0/iot-01/associated-users/devices"

    /** One lamp, in the shape Tuya's associated-users endpoint returns. */
    private fun oneLamp(hasMore: Boolean = false) = FakeApi.ok(
        """
        {"success":true,"result":{"has_more":$hasMore,"last_row_key":"k1","devices":[
          {"id":"bf01","name":"Living room lamp","local_key":"aabbccdd11223344",
           "category":"dj","online":true,"ip":"192.168.1.40",
           "status":[{"code":"switch_led","value":false}]}
        ]}}
        """.trimIndent(),
    )

    private fun client(api: FakeApi) = TuyaCloudClient(credentials, api)

    // ---- the regression ----------------------------------------------------------------

    @Test
    fun `the device list is fetched without a user id in the path`() = runTest {
        // The whole point. A path containing "users/" means the uid mistake is back.
        val api = FakeApi().apply { on(deviceListPath, oneLamp()) }

        client(api).connect()

        assertTrue("asked: ${api.paths}", api.paths.contains(deviceListPath))
        assertTrue(
            "no request may embed a user id: ${api.paths}",
            api.paths.none { it.contains("/users/") },
        )
    }

    @Test
    fun `the token's uid is never used to build a path`() = runTest {
        // The token response below advertises a uid. It must be ignored: it identifies the
        // cloud project, not the app account whose devices we want.
        val api = FakeApi(
            tokenResponse = FakeApi.ok(
                """{"success":true,"result":{"access_token":"t","uid":"projectUid"}}""",
            ),
        ).apply { on(deviceListPath, oneLamp()) }

        client(api).connect()

        assertTrue(api.paths.none { it.contains("projectUid") })
    }

    // ---- connect -----------------------------------------------------------------------

    @Test
    fun `connecting succeeds when the keys work and devices are shared`() = runTest {
        val api = FakeApi().apply { on(deviceListPath, oneLamp()) }
        val client = client(api)

        val result = client.connect()

        assertTrue("$result", result is SmartHomeResult.Success)
        assertTrue(client.state.value.isConnected)
    }

    @Test
    fun `bad keys are reported as bad keys, not as a missing account`() = runTest {
        val api = FakeApi(
            tokenResponse = FakeApi.error(SmartHomeFailure.Kind.PERMISSION_DENIED, "Tuya 1004: sign invalid"),
        )

        val result = client(api).connect()

        val failure = (result as SmartHomeResult.Failure).failure
        assertEquals(SmartHomeFailure.Kind.PERMISSION_DENIED, failure.kind)
        assertTrue("Tuya's own text must survive", failure.detail!!.contains("sign invalid"))
    }

    @Test
    fun `keys that work but no shared devices names the actual fix`() = runTest {
        // The commonest setup mistake, and precisely distinguishable: the token call passed,
        // the list call was permitted, and the list is empty.
        val api = FakeApi().apply {
            on(deviceListPath, FakeApi.ok("""{"success":true,"result":{"devices":[]}}"""))
        }

        val result = client(api).connect()

        val failure = (result as SmartHomeResult.Failure).failure
        assertEquals(SmartHomeFailure.Kind.HOME_UNAVAILABLE, failure.kind)
        assertTrue(
            "should point at the Devices tab: ${failure.detail}",
            failure.detail!!.contains("Devices tab"),
        )
    }

    @Test
    fun `a refused device list keeps Tuya's message rather than paraphrasing it`() = runTest {
        val api = FakeApi().apply {
            on(
                deviceListPath,
                FakeApi.error(SmartHomeFailure.Kind.HOME_UNAVAILABLE, "Tuya 28841105: No permissions"),
            )
        }

        val result = client(api).connect()

        val failure = (result as SmartHomeResult.Failure).failure
        assertTrue(failure.detail!!.contains("28841105"))
        assertTrue(client(api).state.value !is SmartHomeState.Connected)
    }

    // ---- devices ----------------------------------------------------------------------

    @Test
    fun `a device is mapped from Tuya's fields`() = runTest {
        val api = FakeApi().apply { on(deviceListPath, oneLamp()) }
        val client = client(api)
        client.connect()

        val devices = (client.devices(StructureId("tuya:account")) as SmartHomeResult.Success).value
        val lamp = devices.single()

        assertEquals(DeviceId("bf01"), lamp.id)
        assertEquals("Living room lamp", lamp.name)
        assertEquals(DeviceKind.LIGHT, lamp.kind)
        assertTrue(lamp.reachable)
        assertEquals(false, lamp.isOn)
    }

    @Test
    fun `a device with no recognised switch is listed but not selectable`() = runTest {
        val api = FakeApi().apply {
            on(
                deviceListPath,
                FakeApi.ok(
                    """
                    {"success":true,"result":{"devices":[
                      {"id":"bf99","name":"Hallway thermostat","local_key":"k",
                       "category":"wk","online":true,"status":[{"code":"temp_set","value":21}]}
                    ]}}
                    """.trimIndent(),
                ),
            )
        }
        val client = client(api)
        client.connect()

        val lamp = (client.devices(StructureId("x")) as SmartHomeResult.Success).value.single()

        assertEquals(DeviceKind.UNSUPPORTED, lamp.kind)
        assertTrue(!lamp.selectable)
    }

    @Test
    fun `devices cannot be listed before connecting`() = runTest {
        val result = client(FakeApi()).devices(StructureId("x"))

        assertEquals(
            SmartHomeFailure.Kind.NOT_CONNECTED,
            (result as SmartHomeResult.Failure).failure.kind,
        )
    }

    // ---- paging -----------------------------------------------------------------------

    /**
     * A home larger than one page.
     *
     * The fake answers the same path every time, so a loop that ignored `has_more` going false
     * would spin until the page cap and return twenty copies of the same lamp. Asserting the
     * exact count catches both non-termination and duplication.
     */
    @Test
    fun `paging stops when Tuya says there is no more`() = runTest {
        // Pages are decided by the last_row_key sent, exactly as a real API does, so the fake
        // behaves the same however many times it is called. A loop ignoring has_more would spin
        // to the page cap and return twenty duplicates; asserting the exact list catches both
        // non-termination and duplication.
        val api = object : TuyaApi {
            override fun forgetToken() = Unit
            override suspend fun verifyCredentials() =
                FakeApi.ok("""{"success":true,"result":{"access_token":"t"}}""")

            override suspend fun get(path: String, query: Map<String, String>): TuyaResponse {
                val first = query["last_row_key"] == null
                val id = if (first) "bf01" else "bf02"
                return FakeApi.ok(
                    """
                    {"success":true,"result":{"has_more":$first,"last_row_key":"k1",
                     "devices":[{"id":"$id","name":"Lamp","local_key":"k",
                       "category":"dj","online":true,
                       "status":[{"code":"switch_led","value":true}]}]}}
                    """.trimIndent(),
                )
            }

            override suspend fun post(path: String, body: String) =
                FakeApi.ok("""{"success":true,"result":true}""")
        }

        val client = TuyaCloudClient(credentials, api)
        client.connect()
        val devices = (client.devices(StructureId("x")) as SmartHomeResult.Success).value

        assertEquals("both pages, no duplicates", 2, devices.size)
        assertEquals(listOf(DeviceId("bf01"), DeviceId("bf02")), devices.map { it.id })
    }

    // ---- local keys -------------------------------------------------------------------

    @Test
    fun `local keys come from the same device list`() = runTest {
        val api = FakeApi().apply { on(deviceListPath, oneLamp()) }
        val client = client(api)
        client.connect()

        val keys = (client.localCredentials() as SmartHomeResult.Success).value

        assertEquals("aabbccdd11223344", keys.single().localKey)
        assertEquals("bf01", keys.single().deviceId)
        assertTrue(keys.single().usable)
    }

    // ---- switching --------------------------------------------------------------------

    @Test
    fun `a toggle reads the device's own state before writing`() = runTest {
        val api = FakeApi().apply {
            on(deviceListPath, oneLamp())
            on(
                "/v1.0/devices/bf01/status",
                FakeApi.ok("""{"success":true,"result":[{"code":"switch_led","value":false}]}"""),
            )
        }
        val client = client(api)
        client.connect()

        val report = client.execute(SmartHomeCommand.TOGGLE, listOf(DeviceId("bf01") to "Lamp"))

        assertTrue(report.summarise(), report.allSucceeded)
        // Off, so the command sent must be on — and under the code the device reported.
        val (path, body) = api.posts.single()
        assertEquals("/v1.0/devices/bf01/commands", path)
        assertTrue(body, body.contains("switch_led"))
        assertTrue(body, body.contains("true"))
    }

    @Test
    fun `a device exposing no switch fails as unsupported rather than being guessed at`() =
        runTest {
            val api = FakeApi().apply {
                on(deviceListPath, oneLamp())
                on(
                    "/v1.0/devices/bf01/status",
                    FakeApi.ok("""{"success":true,"result":[{"code":"temp_set","value":21}]}"""),
                )
            }
            val client = client(api)
            client.connect()

            val report = client.execute(SmartHomeCommand.ON, listOf(DeviceId("bf01") to "Thing"))

            assertEquals(
                SmartHomeFailure.Kind.DEVICE_UNSUPPORTED,
                report.results.single().failure?.kind,
            )
            assertTrue("nothing should have been sent", api.posts.isEmpty())
        }

    @Test
    fun `commands are refused before connecting`() = runTest {
        val report = client(FakeApi())
            .execute(SmartHomeCommand.ON, listOf(DeviceId("bf01") to "Lamp"))

        assertEquals(
            SmartHomeFailure.Kind.NOT_CONNECTED,
            report.results.single().failure?.kind,
        )
    }
}
