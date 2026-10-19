package com.mtrinh.fobalarm.core

import kotlin.test.*

/**
 * Every startup combination of the two settings stores, because this choice has twice shipped
 * wrong: once discarding the database on upgrade so no role survived and nothing was armed, and
 * once letting a partial copy outrank the database so the owner's away-blocking turned itself off.
 *
 * The question each case answers is "does the owner keep what they chose", and the sharpest proxy
 * for that is the role: with no role nothing is scheduled at all.
 */
class SettingsSourceTest {

    private val defaults = Settings()

    /** What a previous version's field-by-field mirror could hold: no away settings, no nap. */
    private val legacy = Settings(
        defaultAlarmTime = "05:30", role = Role.ALARM, passwordHash = "h", passwordSalt = "s",
        ssid = "DIRECT-xy-real", passphrase = "realpassphrase")

    /** What the database holds: the same, plus the fields the old mirror never stored. */
    private val room = legacy.copy(
        blockRingIfSentinelAway = true, blockRingIfControllerAway = true,
        awayAfterSeconds = 1800, napMinutes = 7)

    /** What this version's mirror holds: everything, and fresher than the database. */
    private val blob = room.copy(defaultAlarmTime = "04:45")

    private fun startup(blob: Settings? = null, legacy: Settings? = null) =
        SettingsSource.atStartup(blob, legacy, defaults)

    private fun afterRoom(c: SettingsChoice, room: Settings?) =
        SettingsSource.onDatabaseLoad(c.settings, c.trustworthy, room).settings

    @Test fun `fresh install keeps defaults and claims nothing`() {
        val c = startup()
        assertEquals(defaults, c.settings)
        assertFalse(c.trustworthy, "defaults must not be written back over a database")
        // Room is empty too, so defaults stand and the owner picks a role.
        assertNull(afterRoom(c, null).role)
    }

    @Test fun `after the database has spoken, the result is worth writing back`() {
        // Without this the mirror is never written on a fresh install, so a reboot before the next
        // unlock finds no role and arms nothing. The flip lives in onDatabaseLoad rather than at
        // the call site precisely so that deleting it fails here.
        for (c in listOf(startup(), startup(legacy = legacy), startup(blob = blob))) {
            assertTrue(SettingsSource.onDatabaseLoad(c.settings, c.trustworthy, room).trustworthy,
                "whichever side won, the database has spoken")
            assertTrue(SettingsSource.onDatabaseLoad(c.settings, c.trustworthy, null).trustworthy,
                "including when the database had nothing to say")
        }
    }

    @Test fun `in steady state the mirror is fresher than the database and wins`() {
        val c = startup(blob = blob)
        assertTrue(c.trustworthy)
        assertEquals("04:45", afterRoom(c, room).defaultAlarmTime,
            "a change made before first unlock must not be reverted by the database")
    }

    @Test fun `upgrade keeps the role before unlock AND the away settings after`() {
        // The two failures this function exists to prevent, in one case.
        val c = startup(legacy = legacy)
        assertEquals(Role.ALARM, c.settings.role, "no role before unlock means nothing is armed")
        assertFalse(c.trustworthy, "a partial copy must not outrank the database")

        val merged = afterRoom(c, room)
        assertTrue(merged.blockRingIfSentinelAway, "the owner's away-blocking must survive")
        assertTrue(merged.blockRingIfControllerAway)
        assertEquals(1800, merged.awayAfterSeconds)
        assertEquals(7, merged.napMinutes)
    }

    @Test fun `upgrade with no database keeps what the legacy keys had`() {
        val merged = afterRoom(startup(legacy = legacy), null)
        assertEquals(Role.ALARM, merged.role)
        assertEquals("05:30", merged.defaultAlarmTime)
        // These are genuinely unrecoverable here; nothing stored them.
        assertFalse(merged.blockRingIfSentinelAway)
    }

    @Test fun `a wiped mirror lets the database restore the role`() {
        val c = startup()
        assertFalse(c.trustworthy)
        assertEquals(Role.ALARM, afterRoom(c, room).role,
            "the database is the only copy left and must win")
    }

    @Test fun `a database with no settings row never replaces memory`() {
        // Null means "the database has nothing to say", which is not the same as "defaults".
        // Replacing memory with defaults here loses the role and arms nothing.
        assertEquals(Role.ALARM, afterRoom(startup(legacy = legacy), null).role)
        assertEquals("04:45", afterRoom(startup(blob = blob), null).defaultAlarmTime)
    }

    @Test fun `an unreadable blob falls back to the legacy seed, still without authority`() {
        // The reader returns null for an unparseable blob, which is this case.
        val c = startup(blob = null, legacy = legacy)
        assertFalse(c.trustworthy, "stale legacy values must not be mirrored over the database")
        assertEquals(1800, afterRoom(c, room).awayAfterSeconds)
    }

    @Test fun `the blob wins over the legacy seed whenever both exist`() {
        val c = startup(blob = blob, legacy = legacy)
        assertEquals("04:45", c.settings.defaultAlarmTime)
        assertTrue(c.trustworthy)
    }
}
