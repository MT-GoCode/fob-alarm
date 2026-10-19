package com.mtrinh.fobalarm.core

/**
 * Which copy of [Settings] wins at startup, and whether that copy may be written back.
 *
 * The alarm phone keeps settings in two places: a device-protected mirror, readable before the
 * phone's first unlock and written synchronously on every change, and a credential-encrypted
 * database, unreadable until unlock and written asynchronously. Choosing between them has twice
 * been got wrong in ways that cost the owner the alarm, so the choice is pure and lives here
 * rather than inside the Android plumbing that reads the two stores.
 *
 * [trustworthy] is the whole subtlety. A copy that may be partial or provisional must fill memory
 * without being written back to the mirror, because once written it is indistinguishable from a
 * real one on the next start.
 */
data class SettingsChoice(val settings: Settings, val trustworthy: Boolean)

object SettingsSource {

    /**
     * The choice before the database is readable.
     *
     * - [blob] is the whole of Settings as this version last mirrored it. Authoritative.
     * - [legacy] is the subset a previous version mirrored field by field. A SEED only: it lacks
     *   whatever that version did not store, so the database holds better values and must be
     *   allowed to win. Treating it as authoritative silently resets the fields it omits.
     * - Otherwise defaults, which are not worth writing back either: a fresh install has nothing
     *   to say yet, and a mirrored default blob would outrank the database that does.
     */
    fun atStartup(blob: Settings?, legacy: Settings?, defaults: Settings): SettingsChoice = when {
        blob != null -> SettingsChoice(blob, trustworthy = true)
        legacy != null -> SettingsChoice(legacy, trustworthy = false)
        else -> SettingsChoice(defaults, trustworthy = false)
    }

    /**
     * The choice when the database opens.
     *
     * [room] is null when the database holds no settings at all, which is NOT the same as holding
     * defaults: replacing memory with defaults loses the role, and with no role nothing is armed.
     */
    fun onDatabaseLoad(inMemory: Settings, trustworthy: Boolean, room: Settings?): SettingsChoice =
        // Always trustworthy afterwards, whichever side won: the database has now spoken, so the
        // result is worth writing back. Returned rather than set by the caller, so that a mutant
        // deleting it is caught by a test instead of by nobody.
        SettingsChoice(if (trustworthy) inMemory else room ?: inMemory, trustworthy = true)
}
