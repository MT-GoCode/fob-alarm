package com.mtrinh.fobalarm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mtrinh.fobalarm.service.Svc
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Dev update channel. Compiled out of the live flavour.
 *
 * Manual IP is PRIMARY and mDNS is the convenience layer, inverted from earlier drafts:
 * NsdManager has a long bug history (resolutions that never call back while wedging a
 * global busy flag) and is also exactly what Local Network Protection gates, so discovery
 * and permission risk are the same layer. SPEC.md section 13.
 */
object Updater {
    const val SERVICE_TYPE = "_alarmdev._tcp."
    @Volatile var host: String? = null
    @Volatile var status: String = "idle"
    @Volatile var lastCheckAtMs: Long = 0
    @Volatile var remoteVersionCode: Int = -1
    @Volatile var devModeUntilMs: Long = 0

    val devModeOn: Boolean get() = System.currentTimeMillis() < devModeUntilMs

    /**
     * Password-gated, expires after 60 minutes. Until it is on, the phone does not listen
     * for builds at all -- so at 06:00 you would need the password merely to OPEN the
     * channel before you could serve it anything.
     */
    fun enableDevMode(secret: String, minutes: Int = 60): Boolean {
        if (!com.mtrinh.fobalarm.core.Auth.accepts(Svc.settings, secret)) {
            Svc.log("dev_mode_rejected")
            return false
        }
        devModeUntilMs = System.currentTimeMillis() + minutes * 60_000L
        Svc.log("dev_mode_enabled", "minutes" to minutes.toString())
        return true
    }

    fun disableDevMode() { devModeUntilMs = 0 }

    fun useHost(ip: String) { host = ip }

    fun discover(ctx: Context) {
        if (!BuildConfig.DEV_CHANNEL || !devModeOn) return
        runCatching {
            val nsd = ctx.getSystemService(NsdManager::class.java)
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        runCatching {
                            nsd.registerServiceInfoCallback(info, { it.run() },
                                object : NsdManager.ServiceInfoCallback {
                                    override fun onServiceUpdated(si: NsdServiceInfo) {
                                        si.hostAddresses.firstOrNull()?.let { host = it.hostAddress }
                                    }
                                    override fun onServiceLost() {}
                                    override fun onServiceInfoCallbackRegistrationFailed(e: Int) {}
                                    override fun onServiceInfoCallbackUnregistered() {}
                                })
                        }
                    }
                    override fun onStartDiscoveryFailed(s: String?, e: Int) {}
                    override fun onStopDiscoveryFailed(s: String?, e: Int) {}
                    override fun onDiscoveryStarted(s: String?) {}
                    override fun onDiscoveryStopped(s: String?) {}
                    override fun onServiceLost(info: NsdServiceInfo?) {}
                })
        }
    }

    fun check(ctx: Context, onResult: (String) -> Unit) {
        if (!BuildConfig.DEV_CHANNEL) { onResult("dev channel not compiled in"); return }
        if (!devModeOn) { onResult("dev mode is off"); return }
        // Refuse while a ring session is open: PackageInstaller kills the process and
        // takes the foreground service with it.
        if (Svc.session != null) { onResult("refusing: alarm is ringing"); return }

        val h = host ?: run { onResult("no host — set the Mac's IP"); return }
        Thread {
            runCatching {
                lastCheckAtMs = System.currentTimeMillis()
                val manifest = JSONObject(URL("http://$h:8000/manifest.json").readText())
                remoteVersionCode = manifest.getInt("versionCode")
                val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
                if (remoteVersionCode <= mine) { status = "up to date ($mine)"; onResult(status); return@runCatching }

                status = "downloading $remoteVersionCode"
                val bytes = URL(manifest.getString("url")).readBytes()
                val want = manifest.getString("sha256")
                val got = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                if (want != got) { status = "checksum mismatch"; onResult(status); return@runCatching }

                install(ctx, bytes)
                status = "installing $remoteVersionCode"
                onResult(status)
            }.onFailure { status = "failed: ${it.message}"; onResult(status) }
        }.start()
    }

    private fun install(ctx: Context, apk: ByteArray) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Zero-tap self-update IS available (UPDATE_PACKAGES_WITHOUT_USER_ACTION is
        // protectionLevel=normal and "updating itself" qualifies). We DECLINE it: the
        // sabotage model rests on installing-over being a deliberate, visible act.
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("apk", 0, apk.size.toLong()).use { out -> out.write(apk); s.fsync(out) }
            val intent = Intent(ctx, MainActivity::class.java)
            val sender = android.app.PendingIntent.getActivity(ctx, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
            s.commit(sender.intentSender)
        }
    }
}
