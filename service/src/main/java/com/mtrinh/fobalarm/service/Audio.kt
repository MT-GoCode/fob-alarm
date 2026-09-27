package com.mtrinh.fobalarm.service

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import com.mtrinh.fobalarm.core.Settings
import java.io.File

/**
 * "The app thinks it is ringing" and "sound is coming out" must be separately observable.
 * SPEC.md section 4.
 */
class Audio(private val ctx: Context) {

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val vm = ctx.getSystemService(VibratorManager::class.java)

    @Volatile var audible: String = "not started"
        private set
    @Volatile var chainLink: String = "none"
        private set

    companion object {
        const val BUNDLED = "bundled"

        /**
         * Fallback chain: user copy -> bundled asset -> system alarm default -> tone.
         * The user's pick is copied into app-private storage AT PICK TIME, so there is no
         * READ_MEDIA at 04:00, no SAF grant to lose, and no file that can vanish.
         *
         * The bundled asset is mirrored into device-protected storage so it is readable
         * after a reboot nobody unlocks -- a res/raw URI resolves through the package
         * manager and is fine, but the DE copy removes any doubt.
         */
        fun resolveUri(ctx: Context, ringtoneUri: String?): Uri? {
            if (ringtoneUri != null && ringtoneUri != BUNDLED) {
                val f = File(ctx.filesDir, "ringtone.bin")
                if (f.exists()) return Uri.fromFile(f)
            }
            val de = File(ctx.createDeviceProtectedStorageContext().filesDir, "bundled_alarm.wav")
            if (de.exists()) return Uri.fromFile(de)
            val resId = ctx.resources.getIdentifier("bundled_alarm", "raw", ctx.packageName)
            if (resId != 0) return Uri.parse("android.resource://" + ctx.packageName + "/" + resId)
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        /** Copy the bundled tone into DE storage once, so the ring path never needs CE. */
        fun ensureBundled(ctx: Context) {
            val de = File(ctx.createDeviceProtectedStorageContext().filesDir, "bundled_alarm.wav")
            if (de.exists() && de.length() > 0) return
            runCatching {
                val resId = ctx.resources.getIdentifier("bundled_alarm", "raw", ctx.packageName)
                if (resId == 0) return
                ctx.resources.openRawResource(resId).use { input ->
                    de.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    fun start(settings: Settings, silent: Boolean = false) {
        // Silent test: vibration and screen only. Proves the screen wakes and the
        // remote dismiss works, without waking the house at 23:00.
        if (silent) {
            chainLink = "silent"; audible = "silent test"
            if (settings.vibrate) startVibration()
            return
        }
        assertVolume(settings)
        startPlayer(settings)
        if (settings.vibrate) startVibration()
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun startPlayer(settings: Settings) {
        // resolveUri reads filesDir, which is credential-encrypted and may be
        // unreadable after a reboot nobody unlocked. It must not throw out of here.
        val uri = runCatching { resolveUri(ctx, settings.ringtoneUri) }.getOrNull()
        if (uri == null) { fallbackToTone(); return }
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(attrs())
                setDataSource(ctx, uri)
                // Without this, "ringing is continuous" is an assertion with no mechanism.
                isLooping = true
                setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
                setOnErrorListener { _, what, extra ->
                    Svc.log("media_error", "what" to what.toString(), "extra" to extra.toString())
                    fallbackToTone(); true
                }
                setOnCompletionListener {
                    Svc.log("media_completed_unexpectedly"); fallbackToTone()
                }
                prepare()
                start()
                preferredDevice = builtinSpeaker()
            }
            chainLink = "mediaplayer"
        }.onFailure {
            Svc.log("audio_failed", "error" to it.toString())
            fallbackToTone()
        }
    }

    private fun builtinSpeaker(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /**
     * The last link does NOT "cannot fail": ToneGenerator's constructor throws on
     * AudioTrack init failure, and it plays on STREAM_ALARM so the same zen mute bit
     * silences it. Vibration and the screen are the real last line -- and even those
     * are conditional.
     */
    private fun fallbackToTone() {
        runCatching { player?.release() }; player = null
        if (tone != null) return
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).also {
                it.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
                chainLink = "tonegenerator"
            }
        }.onFailure {
            Svc.log("tone_fallback_failed", "error" to it.toString())
            chainLink = "none"
        }.getOrNull()
    }

    fun assertVolume(settings: Settings) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (max * settings.alarmVolumePercent / 100).coerceAtLeast(max / 2)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
    }

    fun startVibration() {
        runCatching {
            vm.defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    /**
     * Heartbeat. The detector is isStreamMute -- under zen mute the volume INDEX is
     * untouched, so comparing indices reports healthy through a completely silent hour.
     * Returns true when everything is confirmed audible.
     */
    fun heartbeat(settings: Settings): Boolean {
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        val muted = am.isStreamMute(AudioManager.STREAM_ALARM)
        val vol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val routed = runCatching { player?.routedDevice?.type }.getOrNull()
        val onSpeaker = routed == null || routed == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val target = max * settings.alarmVolumePercent / 100

        audible = "playing=$playing muted=$muted vol=$vol/$max speaker=$onSpeaker link=$chainLink"

        var ok = true
        if (muted || vol < target) {
            Svc.log("volume_mismatch", "muted" to muted.toString(), "vol" to vol.toString())
            assertVolume(settings); ok = false
        }
        if (!playing && tone == null) {
            Svc.log("not_playing_recovering")
            fallbackToTone(); ok = false
        }
        if (!onSpeaker) {
            Svc.log("routing_off_speaker", "type" to routed.toString())
            runCatching { player?.preferredDevice = builtinSpeaker() }; ok = false
        }
        // Re-issue vibration: a power-button press cancels non-system vibrations, and
        // with the screen on during a ring that is the likeliest act mid-session.
        if (settings.vibrate) startVibration()
        return ok
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { tone?.stopTone(); tone?.release() }; tone = null
        runCatching { vm.defaultVibrator.cancel() }
        audible = "stopped"; chainLink = "none"
    }
}
