package com.mtrinh.fobalarm.data

import com.mtrinh.fobalarm.core.Role
import com.mtrinh.fobalarm.core.Settings
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The at-rest settings encoding, which Room, the device-protected mirror and backups all share.
 *
 * A field silently dropped here is not a cosmetic loss: without the password the settings lock
 * opens, and without the role no alarm is armed at all.
 */
class WireAtRestTest {

    /** Every field different from its default, so a dropped one cannot hide behind one. */
    private val full = Settings(
        defaultAlarmTime = "06:17",
        alarmVolumePercent = 95,
        maxRingMinutes = 42,
        snoozeSeconds = 77,
        snoozeHoldSeconds = 7,
        resnoozeAfterSeconds = 11,
        napMinutes = 13,
        vibrate = false,
        ringtoneUri = "content://media/external/audio/9",
        ringtoneName = "Church Bells",
        role = Role.SENTINEL,
        ssid = "DIRECT-xy-fobalarm-test",
        passphrase = "correct-horse-battery",
        passwordHash = "f00dcafe",
        passwordSalt = "beefsalt",
        blockRingIfSentinelAway = true,
        blockRingIfControllerAway = true,
        awayAfterSeconds = 1234,
    )

    @Test fun `every at-rest field survives a round trip`() {
        val back = Wire.settingsAtRestFrom(JSONObject(Wire.settingsAtRestToJson(full).toString()))
        assertEquals(full, back)
    }

    @Test fun `the credentials survive, because losing them opens the lock`() {
        // The wire encoding deliberately omits these: a password hash must not cross the link to
        // the controller. Using that encoding for an at-rest copy erased the password on every
        // process restart, and the gate silently opened.
        val back = Wire.settingsAtRestFrom(Wire.settingsAtRestToJson(full))
        assertEquals("f00dcafe", back.passwordHash)
        assertEquals("beefsalt", back.passwordSalt)
        assertNull(Wire.settingsToJson(full).opt("passwordHash"),
            "the WIRE encoding must still omit the hash")
    }

    @Test fun `no password round-trips as no password, not as the string null`() {
        val none = full.copy(passwordHash = null, passwordSalt = null)
        val back = Wire.settingsAtRestFrom(Wire.settingsAtRestToJson(none))
        assertNull(back.passwordHash)
        assertNull(back.passwordSalt)
    }

    @Test fun `the role survives, because losing it arms no alarm`() {
        assertEquals(Role.SENTINEL,
            Wire.settingsAtRestFrom(Wire.settingsAtRestToJson(full)).role)
        assertNull(Wire.settingsAtRestFrom(Wire.settingsAtRestToJson(full.copy(role = null))).role)
    }
}
