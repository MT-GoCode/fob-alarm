package com.mtrinh.fobalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.*

class RingService : Service() {

    companion object {
        const val CHANNEL = "ring"
        const val NOTIF_ID = 42
        @Volatile var running = false
        @Volatile var lastAudible: String = "—"
    }

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        DeState.init(this); Log.init(this)
        audio = getSystemService(AudioManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true

        startForeground(NOTIF_ID, buildNotification())

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:session").also { it.acquire() }

        Alarm.armWatchdog(this)
        assertVolume()
        startAudio()
        startVibration()
        heartbeat.run()

        // Full-screen ring UI.
        startActivity(Intent(this, RingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

        return START_STICKY
    }

    // ---- audio ----

    private fun startAudio() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Fallback chain. Never request audio focus: USAGE_ALARM does not need it, and
        // honouring AUDIOFOCUS_LOSS would stop the alarm for an incoming call.
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(this@RingService, uri)
                isLooping = true
                setWakeMode(this@RingService, PowerManager.PARTIAL_WAKE_LOCK)
                setOnErrorListener { _, what, extra ->
                    Log.e("media_error", "what" to what, "extra" to extra)
                    fallbackToTone(); true
                }
                setOnCompletionListener { Log.e("media_completed_unexpectedly"); fallbackToTone() }
                prepare()
                start()
                preferredDevice = builtinSpeaker()
            }
            Log.e("audio_started", "uri" to uri.toString())
        } catch (e: Exception) {
            Log.e("audio_failed", "error" to e.toString())
            fallbackToTone()
        }
    }

    private fun builtinSpeaker(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun fallbackToTone() {
        runCatching { player?.release() }; player = null
        if (tone != null) return
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, 100).also {
                it.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
                Log.e("tone_fallback_started")
            }
        }.getOrElse { Log.e("tone_fallback_failed", "error" to it.toString()); null }
    }

    private fun assertVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (max * DeState.alarmVolumePercent / 100).coerceAtLeast(max / 2)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0) }
    }

    private fun startVibration() {
        val vm = getSystemService(VibratorManager::class.java)
        val v = vm.defaultVibrator
        val pattern = longArrayOf(0, 800, 400)
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    /**
     * Audibility heartbeat: "the app thinks it is ringing" and "sound is coming out" must be
     * separately observable. isStreamMute is the detector -- under zen mute the volume INDEX
     * is untouched, so comparing indices reports healthy through a silent hour.
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()

            if (now > DeState.sessionEndsByMs) {
                Log.e("capped", "afterMinutes" to Alarm.MAX_RING_MINUTES)
                stopRinging("CAPPED"); return
            }

            val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
            val muted = audio.isStreamMute(AudioManager.STREAM_ALARM)
            val vol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val routed = runCatching { player?.routedDevice?.type }.getOrNull()
            val onSpeaker = routed == null || routed == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

            lastAudible = "playing=$playing muted=$muted vol=$vol/$max speaker=$onSpeaker"

            if (muted || vol < max / 2) {
                Log.e("volume_mismatch", "muted" to muted, "vol" to vol, "max" to max)
                assertVolume()
            }
            if (!playing && tone == null) {
                Log.e("not_playing_recovering")
                fallbackToTone()
            }
            if (!onSpeaker) {
                Log.e("routing_off_speaker", "type" to routed)
                player?.preferredDevice = builtinSpeaker()
            }

            Alarm.armWatchdog(this@RingService)
            handler.postDelayed(this, 5_000)
        }
    }

    fun stopRinging(outcome: String) {
        Log.e("ring_stop", "outcome" to outcome,
            "durationMs" to (System.currentTimeMillis() - DeState.sessionStartedAtMs))
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { tone?.stopTone(); tone?.release() }; tone = null
        runCatching { getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
        DeState.closeSession()
        Alarm.cancelWatchdog(this)
        Alarm.recompute(this, "after:$outcome")
        runCatching { wakeLock?.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        runCatching { tone?.release() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val full = PendingIntent.getActivity(
            this, 0, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Alarm ringing")
            .setContentText("Press to dismiss")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .build()
    }
}
