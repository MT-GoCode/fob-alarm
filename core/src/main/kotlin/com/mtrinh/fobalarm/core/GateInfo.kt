package com.mtrinh.fobalarm.core

/**
 * ONE definition of every check: its key, what it is called, what it means, whether it
 * blocks, and how it is fixed.
 *
 * This list is the single one: the UI rows, the explanations, the fix actions and the wire
 * encoding all iterate it, and [Gates.value] errors on a key that has no field, so the table
 * and the data class cannot drift apart in silence. Only the `Gates` fields themselves and
 * the JSON decode are separate, because both are what a typed data class costs.
 */
enum class GateKind {
    /** The user can grant it. Android shows a dialog, or we deep-link to one page. */
    PERMISSION,
    /** A fact about the hardware. Nothing to grant. */
    COMPAT,
    /** True or false right now; not a setup step. Shown on the status screen. */
    CONDITION,
}

enum class FixAction {
    /** requestPermissions() -- Android's own dialog. */
    REQUEST,
    /** A specific settings page. */
    DEEP_LINK,
    /** Nothing to open; the user acts in the physical world or not at all. */
    NONE,
}

data class GateInfo(
    val key: String,
    val label: String,
    val explain: String,
    val kind: GateKind,
    val blocking: Boolean,
    val fix: FixAction,
) {
    companion object {
        val ALL: List<GateInfo> = listOf(
            // ---- permissions: setup, blocking, actionable ----
            GateInfo("foregroundService", "Notifications",
                "Needed to show the alarm screen and keep the link running.",
                GateKind.PERMISSION, blocking = true, fix = FixAction.REQUEST),
            GateInfo("exactAlarm", "Exact alarms",
                "Not granted. Reinstall the app to get it back.",
                GateKind.PERMISSION, blocking = true, fix = FixAction.NONE),
            GateInfo("fullScreenIntent", "Show over the lock screen",
                "Lets the ring screen appear without unlocking the phone.",
                GateKind.PERMISSION, blocking = true, fix = FixAction.DEEP_LINK),
            // NOT blocking. It matters on a horizon of months, the setting is buried
            // under an OEM-dependent name, and there is no reliable deep link to it --
            // so making it a wall stopped setup dead over something that cannot affect
            // tonight. It stays visible, and the 22:00 check will keep complaining.
            GateInfo("notHibernating", "Keep the app active",
                "Turn off \"Pause app activity if unused\" so Android never stops the alarm.",
                GateKind.PERMISSION, blocking = false, fix = FixAction.DEEP_LINK),
            GateInfo("batteryUnrestricted", "Battery: unrestricted",
                "Lets the link run for months without Android slowing it down.",
                GateKind.PERMISSION, blocking = false, fix = FixAction.DEEP_LINK),
            GateInfo("localNetworkPermission", "Nearby devices",
                "Required to pair the two phones over Wi-Fi Direct.",
                GateKind.PERMISSION, blocking = false, fix = FixAction.REQUEST),
            GateInfo("notificationPolicyAccess", "Do Not Disturb access",
                "Lets the app warn you if a DND rule would mute the alarm.",
                GateKind.PERMISSION, blocking = false, fix = FixAction.DEEP_LINK),

            // ---- compatibility: facts, nothing to grant ----
            GateInfo("p2pSupported", "Wi-Fi Direct",
                "No Wi-Fi Direct, so the two phones cannot link.",
                GateKind.COMPAT, blocking = false, fix = FixAction.NONE),

            // ---- conditions: tonight's state, never a setup step ----
            // A CONDITION, not a setup step, and meaningless on the controller, which
            // never arms anything. Reported on Status, never a gate to the UI.
            GateInfo("scheduleExists", "An alarm is scheduled",
                "No alarm is registered with Android at all.",
                GateKind.CONDITION, blocking = false, fix = FixAction.NONE),
            GateInfo("groupCredentialsSet", "Phones paired",
                "Set a group name and passphrase to link the two phones.",
                GateKind.CONDITION, blocking = false, fix = FixAction.NONE),
            GateInfo("audioPlayable", "Ringtone is readable",
                "The selected audio file cannot be opened.",
                GateKind.CONDITION, blocking = false, fix = FixAction.DEEP_LINK),
            GateInfo("dndAllowsAlarms", "Do Not Disturb allows alarms",
                "A DND or Bedtime rule could mute the alarm stream tonight.",
                GateKind.CONDITION, blocking = false, fix = FixAction.DEEP_LINK),
            GateInfo("volumeNotFixed", "Alarm volume is settable",
                "The alarm stream is muted or cannot be changed.",
                GateKind.CONDITION, blocking = false, fix = FixAction.DEEP_LINK),
            GateInfo("vibrationEnabled", "Vibration on",
                "Vibration is the backstop if the speaker is muted.",
                GateKind.CONDITION, blocking = false, fix = FixAction.DEEP_LINK),
            GateInfo("noBluetoothAudio", "No Bluetooth speaker",
                "A connected speaker could route the alarm out of the room.",
                GateKind.CONDITION, blocking = false, fix = FixAction.DEEP_LINK),
            GateInfo("powerOk", "Plugged in",
                "Right now this phone is on battery or under 50%. Just plug it in.",
                GateKind.CONDITION, blocking = false, fix = FixAction.NONE),
            GateInfo("thermalOk", "Not overheating",
                "Sustained heat can throttle audio.",
                GateKind.CONDITION, blocking = false, fix = FixAction.NONE),
            GateInfo("freeDiskOk", "Enough free storage",
                "Low storage can fail the write that records a ring session.",
                GateKind.CONDITION, blocking = false, fix = FixAction.NONE),
        )

        fun of(key: String): GateInfo? = ALL.firstOrNull { it.key == key }
        fun ofKind(kind: GateKind) = ALL.filter { it.kind == kind }

        /**
         * THE list of permissions a phone in a role shows and reports, with `blocking`
         * meaning "the product does not work on this phone without it". The alarm phone
         * needs everything; the controller needs to run its link service, to show its
         * alert over the lock screen, to join the group, and (recommended) to stay awake.
         * `blocking` in the table above means "the alarm will not ring", which is what
         * Health reports; this is the setup meaning, per role.
         */
        fun forRole(role: Role): List<GateInfo> = ofKind(GateKind.PERMISSION)
            .filter { it.key in needs(role) }
            .map { if (it.key in PRODUCT_NEEDS) it.copy(blocking = true) else it }

        /**
         * Exhaustive. As `alarmRole: Boolean` this handed the SENTINEL the controller's set, and
         * that set is not a display detail: a joiner REPORTS its missing blocking gates to the
         * alarm phone, which vetoes the away block of any peer that is missing one. So a sentinel
         * was required to grant "Show over the lock screen" -- on a phone that never rings and
         * never alerts -- or its absence could never silence anything.
         *
         * What stays is deliberate and is not "fewer permissions". `batteryUnrestricted` keeps
         * vetoing a sentinel, because it decides whether Android may throttle its polling: a
         * sentinel the OS can stall goes quiet while sitting on the bedside table, and trusting
         * that silence would suppress the alarm. Vetoing an untrustworthy sentinel fails toward
         * ringing, which is the gate's whole invariant. `fullScreenIntent` is different in kind --
         * it bears on alerting, which a sentinel never does -- so including it was a category
         * error rather than a conservative choice.
         */
        private fun needs(role: Role): Set<String> = when (role) {
            Role.ALARM -> ALL.map { it.key }.toSet()
            Role.CONTROLLER -> setOf("foregroundService", "fullScreenIntent",
                "localNetworkPermission", "batteryUnrestricted", "notHibernating")
            // Stated in full, NOT as the controller's set minus one. Subtracting made a sentinel
            // "a controller without alerting", so a permission added for the controller later
            // would reach the sentinel with nobody choosing it -- which is exactly the defect
            // this function was changed to fix.
            Role.SENTINEL -> setOf("foregroundService",
                "localNetworkPermission", "batteryUnrestricted", "notHibernating")
        }
        /** Not ring-blocking, but the two phones cannot stay linked for a month without them. */
        private val PRODUCT_NEEDS = setOf("localNetworkPermission", "batteryUnrestricted")
    }
}

/** Each permission a role needs, paired with whether this phone has it. */
fun Gates.rowsFor(role: Role): List<Pair<GateInfo, Boolean>> =
    GateInfo.forRole(role).map { it to value(it.key) }

/** The keys a phone in this role is missing and needs: what it reports to the other phone. */
fun Gates.missingFor(role: Role): List<String> =
    rowsFor(role).filter { it.first.blocking && !it.second }.map { it.first.key }

/** Pair each definition with its measured value, so every consumer reads one list. */
fun Gates.entries(): List<Pair<GateInfo, Boolean>> = GateInfo.ALL.map { it to value(it.key) }

fun Gates.value(key: String): Boolean = when (key) {
    "scheduleExists" -> scheduleExists
    "exactAlarm" -> exactAlarm
    "foregroundService" -> foregroundService
    "p2pSupported" -> p2pSupported
    "groupCredentialsSet" -> groupCredentialsSet
    "localNetworkPermission" -> localNetworkPermission
    "notificationPolicyAccess" -> notificationPolicyAccess
    "dndAllowsAlarms" -> dndAllowsAlarms
    "volumeNotFixed" -> volumeNotFixed
    "fullScreenIntent" -> fullScreenIntent
    "notHibernating" -> notHibernating
    "thermalOk" -> thermalOk
    "audioPlayable" -> audioPlayable
    "powerOk" -> powerOk
    "vibrationEnabled" -> vibrationEnabled
    "freeDiskOk" -> freeDiskOk
    "noBluetoothAudio" -> noBluetoothAudio
    "batteryUnrestricted" -> batteryUnrestricted
    // NOT `true`. An unrecognised key meant the gate silently PASSED, so a typo in
    // GateInfo.ALL -- or a field added to Gates and not to the table -- turned a real check
    // into a green row, which is the one thing a setup screen must never do.
    else -> error("unknown gate key: $key -- GateInfo.ALL and Gates have diverged")
}
