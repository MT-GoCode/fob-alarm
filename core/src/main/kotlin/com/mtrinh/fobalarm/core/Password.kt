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

    /** Human-typable, unambiguous alphabet. Displayed exactly once. */
    fun newRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (0 until 16).joinToString("") { alphabet[r.nextInt(alphabet.length)].toString() }
            .chunked(4).joinToString("-")
    }

    fun verifies(secret: String, hash: String?, salt: String?): Boolean {
        if (hash == null || salt == null) return false
        return constantTimeEquals(hash(secret, salt), hash)
    }

    /** True when no password is set: the gate is optional and declining is supported. */
    fun gateOpen(s: Settings): Boolean = s.passwordHash == null

    /** Recovery codes are displayed grouped; accept them however they are typed. */
    fun normalizeRecovery(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }.chunked(4).joinToString("-")

    fun accepts(s: Settings, secret: String): Boolean =
        gateOpen(s) || verifies(secret, s.passwordHash, s.passwordSalt) ||
                verifies(normalizeRecovery(secret), s.recoveryHash, s.recoverySalt)

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
class RotationAccumulator(
    private val thresholdDeg: Int,
    private val deadbandDegPerSec: Double = 15.0,
    private val decayAfterMs: Long = 3_000,
) {
    var degrees: Double = 0.0; private set
    var gyroBiasDps: Double = 0.0; private set
    private var lastQ: DoubleArray? = null
    private var lastQAtMs: Long = 0
    private var lastMotionMs: Long = 0
    private var biasSamples = 0
    private var biasSum = 0.0

    var lastGyroMs: Long = 0; private set
    var lastRvMs: Long = 0; private set

    fun reset() {
        degrees = 0.0; lastQ = null; lastQAtMs = 0; lastMotionMs = 0
    }

    fun gyroStale(nowMs: Long) = lastGyroMs != 0L && nowMs - lastGyroMs > 2_000
    fun rvStale(nowMs: Long) = lastRvMs != 0L && nowMs - lastRvMs > 2_000

    /** Cross-check stream. Also supplies the live zero-rate bias estimate. */
    fun onGyro(wx: Double, wy: Double, wz: Double, accelMagG: Double, nowMs: Long) {
        lastGyroMs = nowMs
        val mag = Math.sqrt(wx * wx + wy * wy + wz * wz) * 180.0 / Math.PI
        // Zero-rate update: while under the deadband and |accel| ~= 1g, average the gyro.
        if (mag < deadbandDegPerSec && Math.abs(accelMagG - 1.0) < 0.1) {
            biasSum += mag; biasSamples++
            if (biasSamples > 50) { gyroBiasDps = biasSum / biasSamples; biasSum = 0.0; biasSamples = 0 }
        }
    }

    /** Primary stream. Returns true when the threshold is crossed on this sample. */
    fun onRotationVector(q: DoubleArray, nowMs: Long): Boolean {
        lastRvMs = nowMs
        val prev = lastQ
        val prevAt = lastQAtMs
        lastQ = q; lastQAtMs = nowMs
        if (prev == null || prevAt == 0L) return false

        val dtSec = (nowMs - prevAt) / 1000.0
        if (dtSec <= 0.0) return false

        val angle = geodesicAngleDeg(prev, q)
        val rate = angle / dtSec

        // Deadband on the QUATERNION rate, not on the gyro stream: a pure fusion
        // correction shows ~0 gyro and would otherwise never be discarded.
        if (rate < deadbandDegPerSec) {
            if (lastMotionMs != 0L && nowMs - lastMotionMs > decayAfterMs && degrees > 0.0) {
                degrees = 0.0      // a snooze is ONE continuous gesture, not a sum over an hour
            }
            return false
        }

        lastMotionMs = nowMs
        degrees += angle
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
