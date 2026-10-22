package dev.housecontrol.android.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where a secret (the Smart Life session, the Home Assistant token, the Hue key, the
 * sync key) is kept. The app encrypts each with a key that never leaves the Android
 * Keystore; the tests keep them in memory.
 */
interface SecretStore {
    fun read(): ByteArray?
    /** Stores `data`; null removes the secret. */
    fun write(data: ByteArray?)
    /** Changes whenever the secret is rewritten. */
    val stamp: Long?

    fun readText(): String? = read()?.toString(Charsets.UTF_8)
    fun writeText(text: String?) = write(text?.toByteArray(Charsets.UTF_8))
}

/**
 * A file in the app's private storage, encrypted with AES-GCM under a non-exportable
 * Android Keystore key. The file is useless without this very installation of the app,
 * so it is also left out of backups (see backup_rules.xml).
 */
class KeystoreSecretStore(private val file: File) : SecretStore {
    override fun read(): ByteArray? = synchronized(lock) {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        } catch (_: Exception) {
            null // a key lost with a restore: as good as never signed in
        }
    }

    override fun write(data: ByteArray?) = synchronized(lock) {
        if (data == null) {
            file.delete()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(data)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    override val stamp: Long? get() = if (file.exists()) file.lastModified() else null

    private companion object {
        const val ALIAS = "house-control-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        val lock = Any()

        fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}

/** Keeps a secret in memory only (tests, throw-away runs). */
class MemorySecretStore(private var data: ByteArray? = null) : SecretStore {
    private var version = 0L
    override fun read() = data
    override fun write(data: ByteArray?) {
        this.data = data
        version++
    }
    override val stamp: Long get() = version
}
