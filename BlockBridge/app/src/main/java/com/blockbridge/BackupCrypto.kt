package com.blockbridge

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption for backups that include VPN servers (their private keys):
 * AES-256-GCM with a key from PBKDF2-HMAC-SHA256 (150,000 rounds, random salt).
 */
object BackupCrypto {
    private const val ITERATIONS = 150_000
    private val rnd = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): JSONObject {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", 1).put("kdf", "PBKDF2-HMAC-SHA256").put("iter", ITERATIONS)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv)).put("data", b64.encodeToString(data))
    }

    /** Throws if the password is wrong or the data was changed. */
    fun decrypt(o: JSONObject, password: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // The file says how many rounds were used; refuse silly values (a crafted file could
        // freeze the app with billions of rounds, or weaken nothing useful with very few).
        val iter = o.optInt("iter", ITERATIONS)
        require(iter in 100_000..2_000_000) { "Not a BlockBridge backup (bad key settings)" }
        c.init(Cipher.DECRYPT_MODE, key(password, unb64.decode(o.getString("salt")), iter),
            GCMParameterSpec(128, unb64.decode(o.getString("iv"))))
        return String(c.doFinal(unb64.decode(o.getString("data"))), Charsets.UTF_8)
    }
}
