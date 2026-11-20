package de.kopffrei.core.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speichert den API-Schlüssel des Nutzers verschlüsselt (AES-GCM, Schlüssel im Android-Keystore).
 * Die Datei liegt in `noBackupFilesDir`: Sie wird weder in Backups noch bei der Geräte-Übertragung
 * mitgenommen (der Keystore-Schlüssel wäre auf einem anderen Gerät ohnehin nutzlos). Der Schlüssel
 * ist nie Teil des JSON-Exports.
 */
@Singleton
class AiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.noBackupFilesDir, FILE_NAME)

    fun hasKey(): Boolean = file.exists() && file.length() > IV_BYTES

    /** @return der Schlüssel oder `null`, wenn keiner gespeichert oder nicht mehr lesbar ist. */
    fun load(): String? = try {
        if (!file.exists()) {
            null
        } else {
            val bytes = file.readBytes()
            if (bytes.size <= IV_BYTES) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
                String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        null
    }

    /** @return `true`, wenn gespeichert wurde. Leere oder offensichtlich ungültige Schlüssel werden abgelehnt. */
    fun save(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (!isPlausible(key)) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            file.writeBytes(iv + encrypted)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear() {
        file.delete()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "kopffrei_ai_key"
        private const val FILE_NAME = "ai_key.bin"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Keine Leerzeichen/Zeilenumbrüche und nicht absurd kurz oder lang. */
        fun isPlausible(key: String): Boolean = key.length in 20..300 && key.none { it.isWhitespace() }
    }
}
