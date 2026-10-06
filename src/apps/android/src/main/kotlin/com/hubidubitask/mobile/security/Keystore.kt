package com.hubidubitask.mobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest. The database passphrase and the paired device token are
 * random values, encrypted with an AES key that never leaves the Android
 * Keystore (hardware-backed where available). Only ciphertext touches disk.
 */
object Keystore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    // Named when the app was HubidubiOS. They hold the key to the phone's
    // database and its stored secrets, so they keep those names.
    private const val KEY_ALIAS = "hubidubios-master"
    private const val PREFS = "hubidubios-secure"
    private const val GCM_TAG_BITS = 128

    fun databasePassphrase(context: Context): ByteArray =
        sealedRandom(context, "db-passphrase", 32)

    /** A stable random secret, generated once and thereafter decrypted. */
    private fun sealedRandom(context: Context, name: String, bytes: Int): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("$name.ct", null)?.let { ct ->
            val iv = android.util.Base64.decode(prefs.getString("$name.iv", ""), android.util.Base64.NO_WRAP)
            return decrypt(android.util.Base64.decode(ct, android.util.Base64.NO_WRAP), iv)
        }
        val secret = ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }
        val (ct, iv) = encrypt(secret)
        prefs.edit()
            .putString("$name.ct", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
            .putString("$name.iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .apply()
        return secret
    }

    fun putSecret(context: Context, name: String, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (ct, iv) = encrypt(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("$name.ct", android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP))
            .putString("$name.iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .apply()
    }

    fun getSecret(context: Context, name: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ct = prefs.getString("$name.ct", null) ?: return null
        val iv = android.util.Base64.decode(prefs.getString("$name.iv", ""), android.util.Base64.NO_WRAP)
        return String(decrypt(android.util.Base64.decode(ct, android.util.Base64.NO_WRAP), iv), Charsets.UTF_8)
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return cipher.doFinal(plain) to cipher.iv
    }

    private fun decrypt(ct: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
