package com.mtrinh.fobalarm.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Password + one-time recovery code. Validation lives in :core so the HTTP path
 * cannot bypass it. It NEVER gates dismiss -- nothing on the 4 AM path can require
 * a password. SPEC.md section 8.
 */
object Auth {
    private const val ITERATIONS = 20_000

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    fun hash(secret: String, saltHex: String): String {
        var d = (saltHex + secret).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(ITERATIONS) { d = md.digest(d) }
        return d.toHex()
    }

    fun verifies(secret: String, hash: String?, salt: String?): Boolean {
        if (hash == null || salt == null) return false
        return constantTimeEquals(hash(secret, salt), hash)
    }

    /** True when no password is set: the gate is optional and declining is supported. */
    fun gateOpen(s: Settings): Boolean = s.passwordHash == null

    fun accepts(s: Settings, secret: String): Boolean =
        gateOpen(s) || verifies(secret, s.passwordHash, s.passwordSalt)

    /**
     * True while the factory default is still in use. Surfaced in Settings so a known
     * password is never mistaken for a private one.
     */
    fun isDefault(s: Settings, default: String): Boolean =
        s.passwordHash != null && verifies(default, s.passwordHash, s.passwordSalt)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Snooze gesture estimator. Path traveled, not displacement.
 *
 * Primary: summed geodesic angle between consecutive GAME_ROTATION_VECTOR quaternions.
 * GAME_ rather than plain ROTATION_VECTOR because the latter is a 9-axis fusion including
 * the magnetometer, and a path integral RECTIFIES magnetic yaw corrections exactly as it
 * rectifies gyro bias -- monotonic, never cancelling, reaching threshold while motionless.
 * SPEC.md section 7.
 */
/**
 * How far the phone has been turned, in total, in any direction: tilting up thirty and
 * back down counts sixty. Each step is the movement over one short window (100 ms), so a
 * buzz of the vibrator, which shakes the phone back and forth many times inside that
 * window, nets to nothing; a hand turn nets to the turn. Steps under a degree are noise
 * and are dropped, and three seconds of no steps ends the gesture.
 */
class RotationAccumulator(
    private val thresholdDeg: Int,
    private val windowMs: Long = 100,
    private val minStepDeg: Double = 1.0,
    private val decayAfterMs: Long = 3_000,
) {
    var degrees: Double = 0.0; private set
    var gyroBiasDps: Double = 0.0; private set
    private var winStart: DoubleArray? = null
    private var winStartMs: Long = 0
    private var lastMotionMs: Long = 0
    private var biasSamples = 0
    private var biasSum = 0.0

    var lastGyroMs: Long = 0; private set
    var lastRvMs: Long = 0; private set

    fun reset() {
        degrees = 0.0; winStart = null; winStartMs = 0; lastMotionMs = 0
    }

    fun gyroStale(nowMs: Long) = lastGyroMs != 0L && nowMs - lastGyroMs > 2_000
    fun rvStale(nowMs: Long) = lastRvMs != 0L && nowMs - lastRvMs > 2_000

    /** Cross-check stream. Also supplies the live zero-rate bias estimate. */
    fun onGyro(wx: Double, wy: Double, wz: Double, accelMagG: Double, nowMs: Long) {
        lastGyroMs = nowMs
        val mag = Math.sqrt(wx * wx + wy * wy + wz * wz) * 180.0 / Math.PI
        // Zero-rate update: while barely moving and |accel| ~= 1g, average the gyro.
        if (mag < 15.0 && Math.abs(accelMagG - 1.0) < 0.1) {
            biasSum += mag; biasSamples++
            if (biasSamples > 50) { gyroBiasDps = biasSum / biasSamples; biasSum = 0.0; biasSamples = 0 }
        }
    }

    /** Primary stream. Returns true when the threshold is crossed on this sample. */
    fun onRotationVector(q: DoubleArray, nowMs: Long): Boolean {
        lastRvMs = nowMs
        val start = winStart
        if (start == null) { winStart = q; winStartMs = nowMs; lastMotionMs = nowMs; return false }
        if (nowMs - winStartMs < windowMs) return false

        val step = geodesicAngleDeg(start, q)      // net movement over the window
        winStart = q; winStartMs = nowMs
        if (step < minStepDeg) {
            if (degrees > 0.0 && nowMs - lastMotionMs > decayAfterMs) degrees = 0.0   // one gesture
            return false
        }
        lastMotionMs = nowMs
        degrees += step
        return degrees >= thresholdDeg
    }

    private fun geodesicAngleDeg(a: DoubleArray, b: DoubleArray): Double {
        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        dot = Math.abs(dot).coerceAtMost(1.0)
        return 2.0 * Math.acos(dot) * 180.0 / Math.PI
    }

    companion object {
        /** SensorManager gives (x,y,z[,w]); normalise to (w,x,y,z) with w recovered if absent. */
        fun quatFromSensor(v: FloatArray): DoubleArray {
            val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
            val w = if (v.size >= 4) v[3].toDouble()
                    else Math.sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
            return doubleArrayOf(w, x, y, z)
        }
    }
}
