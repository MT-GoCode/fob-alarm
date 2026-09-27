package com.mtrinh.fobalarm.core

/**
 * ONE definition of every check: its key, what it is called, what it means, whether it
 * blocks, and how it is fixed.
 *
 * Previously this was spread across five hand-maintained parallel lists -- the Gates
 * fields, the failing() switch, the UI row list, the explain() table and the fix()
 * intent switch. Adding or changing a check meant five edits, and nothing failed if you
 * missed one. It is also why a gate could require something the app never did.
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
                "Without this Android can delay the alarm by minutes or hours.",
                GateKind.PERMISSION, blocking = true, fix = FixAction.DEEP_LINK),
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
            GateInfo("localNetworkPermission", "Nearby devices",
                "Required to pair the two phones over Wi-Fi Direct.",
                GateKind.PERMISSION, blocking = false, fix = FixAction.REQUEST),
            GateInfo("notificationPolicyAccess", "Do Not Disturb access",
                "Lets the app warn you if a DND rule would mute the alarm.",
                GateKind.PERMISSION, blocking = false, fix = FixAction.DEEP_LINK),

            // ---- compatibility: facts, nothing to grant ----
            GateInfo("gyroscopePresent", "Gyroscope",
                "Needed for rotate-to-snooze. The alarm rings either way.",
                GateKind.COMPAT, blocking = false, fix = FixAction.NONE),
            GateInfo("p2pSupported", "Wi-Fi Direct",
                "Used for the phone-to-phone link.",
                GateKind.COMPAT, blocking = false, fix = FixAction.NONE),
            GateInfo("staApConcurrent", "Wi-Fi and group at once",
                "Whether this phone can host the link and stay on home Wi-Fi together.",
                GateKind.COMPAT, blocking = false, fix = FixAction.NONE),

            // ---- conditions: tonight's state, never a setup step ----
            GateInfo("scheduleExists", "An alarm is scheduled",
                "No alarm is registered with Android at all.",
                GateKind.CONDITION, blocking = true, fix = FixAction.NONE),
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
    }
}

/** Pair each definition with its measured value, so every consumer reads one list. */
fun Gates.entries(): List<Pair<GateInfo, Boolean>> = GateInfo.ALL.map { it to value(it.key) }

fun Gates.value(key: String): Boolean = when (key) {
    "scheduleExists" -> scheduleExists
    "exactAlarm" -> exactAlarm
    "foregroundService" -> foregroundService
    "p2pSupported" -> p2pSupported
    "gyroscopePresent" -> gyroscopePresent
    "staApConcurrent" -> staApConcurrent
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
    else -> true
}
