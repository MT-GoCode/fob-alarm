package com.mtrinh.fobalarm.data

import com.mtrinh.fobalarm.core.*
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The snapshot codec, for the away verdicts the settings rows depend on.
 *
 * The alarm phone hands its own screen the object and never encodes it, so a field that silently
 * failed to cross the wire would look perfect on the phone that has the answer and be missing on
 * the phone that is asking.
 */
class WireSnapshotTest {

    private val device = DeviceView(80, false, "0.3", Role.ALARM, "dev-1")

    private fun snapshot(away: List<AwayView>) = Snapshot(
        stateVersion = 7, serverTimeMs = 1_700_000_000_000, bootedAtMs = 1_000,
        gates = Gates(evaluatedAtMs = 1, scheduleExists = true, exactAlarm = true,
            foregroundService = true, p2pSupported = true, groupCredentialsSet = true,
            localNetworkPermission = true, notificationPolicyAccess = true, dndAllowsAlarms = true,
            volumeNotFixed = true, fullScreenIntent = true, notHibernating = true, thermalOk = true,
            audioPlayable = true, powerOk = true, vibrationEnabled = true, freeDiskOk = true,
            noBluetoothAudio = true, batteryUnrestricted = true),
        armGate = null, nextFire = null, ring = null,
        clock = ClockView(1, 2, 3, "ntp", 4),
        ap = ApView("DIRECT-xy", true, 1, 5, null),
        self = device, peer = null, lastOutcome = null,
        appVersion = "0.3", settingsSchemaVersion = 1,
        tomorrow = TomorrowView("NONE", null, null), nap = NapView(false, null),
        settings = Settings(role = Role.ALARM), lastEvents = emptyList(),
        away = away)

    private fun roundTrip(away: List<AwayView>) =
        Wire.snapshotFrom(JSONObject(Wire.snapshotToJson(snapshot(away)).toString())).away

    @Test fun `every away verdict survives the wire with its variant intact`() {
        // The variant, not just the words: the row routes on it, and Present decoded as Vetoed
        // would describe a working block as broken, which is the whole thing these rows exist to
        // stop. Every variant, because a tag is only as good as its least-exercised arm.
        val sent = listOf(
            AwayView(Role.SENTINEL, RoleVerdict.Unknown),
            AwayView(Role.CONTROLLER, RoleVerdict.Away("controller away 1200s")))
        assertEquals(sent, roundTrip(sent))
        listOf(RoleVerdict.Present("present:30s"), RoleVerdict.Vetoed("power_save")).forEach {
            val one = listOf(AwayView(Role.SENTINEL, it))
            assertEquals(one, roundTrip(one))
        }
    }

    @Test fun `a state this build does not know drops the row rather than inventing a verdict`() {
        // Forward skew: a newer alarm phone must not be able to make this build's row claim
        // anything.
        val o = Wire.snapshotToJson(snapshot(listOf(AwayView(Role.SENTINEL, RoleVerdict.Unknown))))
        o.getJSONArray("away").getJSONObject(0).put("state", "SOMETHING_NEWER")
        assertTrue(Wire.snapshotFrom(JSONObject(o.toString())).away.isEmpty())
    }

    @Test fun `a role this build does not know drops the row instead of failing the snapshot`() {
        // The snapshot carries the ring state to the phone that dismisses the alarm. A role name
        // from a newer build must cost one row, never the whole decode.
        val o = Wire.snapshotToJson(snapshot(listOf(AwayView(Role.SENTINEL, RoleVerdict.Unknown))))
        o.getJSONArray("away").getJSONObject(0).put("role", "WITNESS")
        val decoded = Wire.snapshotFrom(JSONObject(o.toString()))
        assertTrue(decoded.away.isEmpty())
        assertEquals(7, decoded.stateVersion, "the rest of the snapshot must survive")
    }

    @Test fun `an older alarm phone that sends no away array decodes to no rows`() {
        val o = Wire.snapshotToJson(snapshot(listOf(AwayView(Role.SENTINEL, RoleVerdict.Unknown))))
        o.remove("away")
        assertTrue(Wire.snapshotFrom(JSONObject(o.toString())).away.isEmpty())
    }
}
