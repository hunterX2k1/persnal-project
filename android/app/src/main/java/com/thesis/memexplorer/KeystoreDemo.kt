package com.thesis.memexplorer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Keystore (TEE-backed) contrast for plan §6. The key is generated
 * inside the Keystore and is NOT exportable: getEncoded() returns null, so the
 * key bytes never exist in the app's RAM to be found — unlike a software key.
 * This is the Android equivalent of the Windows TPM/VBS wall.
 */
object KeystoreDemo {

    private const val ALIAS = "thesis_keystore_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** Returns (ciphertext+iv, keyIsExportable, strongBoxOrTee). */
    data class Result(val blob: ByteArray, val keyExportable: Boolean, val note: String)

    fun encrypt(plaintext: ByteArray): Result {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        val blob = ByteArray(iv.size + ct.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ct, 0, it, iv.size, ct.size)
        }
        // The defining property: a Keystore key cannot be read out as bytes.
        val exportable = key.encoded != null
        return Result(blob, exportable,
            if (exportable) "key IS exportable (unexpected)"
            else "key.getEncoded() == null — key bytes live in the TEE, never in app RAM")
    }
}
