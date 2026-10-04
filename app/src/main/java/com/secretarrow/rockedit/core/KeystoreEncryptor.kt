package com.secretarrow.rockedit.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryptor backed by an Android Keystore key. The cipher text is
 * stored as Base64(iv + ciphertext); the key never leaves secure hardware.
 */
object KeystoreEncryptor : RemoteConnectionStore.RemoteEncryptor {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "rockedit_remote_credentials"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val combined = Base64.decode(cipherText, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val payload = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * No-op encryptor for JVM unit tests (real encryption is exercised on the
 * device through [KeystoreEncryptor]).
 */
object PlainEncryptor : RemoteConnectionStore.RemoteEncryptor {
    override fun encrypt(plain: String): String = "plain:$plain"
    override fun decrypt(cipher: String): String = cipher.removePrefix("plain:")
}
