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
