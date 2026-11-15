package com.airplaytv.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.airplaytv.core.AppLog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets at rest with an AES-256-GCM key that lives in the Android
 * Keystore (hardware-backed where the device supports it) and never leaves it.
 * No custom cryptography: platform AES-GCM with a random 96-bit IV per message.
 *
 * File format: [1 byte version][12 byte IV][ciphertext + 16 byte tag].
 */
class SecretStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    private val key: SecretKey by lazy { loadOrCreateKey() }

    fun write(name: String, plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain)
        val out = ByteArray(1 + IV_LEN + ct.size)
        out[0] = VERSION
        System.arraycopy(cipher.iv, 0, out, 1, IV_LEN)
        System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.size)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file(name))) {
            file(name).delete()
            tmp.renameTo(file(name))
        }
    }

    /** Returns null if absent or unreadable (e.g. the Keystore key was wiped). */
    fun read(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        return try {
            val data = f.readBytes()
            require(data.size > 1 + IV_LEN && data[0] == VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 1, IV_LEN))
            cipher.doFinal(data, 1 + IV_LEN, data.size - 1 - IV_LEN)
        } catch (t: Throwable) {
            AppLog.e(TAG, "cannot decrypt secret '$name'", t)
            null
        }
    }

    fun delete(name: String) {
        file(name).delete()
    }

    fun exists(name: String) = file(name).exists()

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9_]{1,40}"))) { "bad secret name" }
        return File(dir, "$name.bin")
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TAG = "SecretStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "airplaytv_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val VERSION: Byte = 1
    }
}
