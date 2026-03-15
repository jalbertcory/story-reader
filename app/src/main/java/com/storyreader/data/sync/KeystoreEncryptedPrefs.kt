package com.storyreader.data.sync

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [SharedPreferences] decorator that transparently encrypts string values
 * using a key stored in the Android Keystore (AES-256-GCM).
 *
 * Only [getString] and [Editor.putString] are affected by encryption.
 * All other operations delegate directly to the backing [SharedPreferences].
 */
internal class KeystoreEncryptedPrefs(
    private val backing: SharedPreferences
) : SharedPreferences {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec) }
                .generateKey()
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        iv.copyInto(combined)
        ciphertext.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    override fun getString(key: String, defValue: String?): String? {
        val raw = backing.getString(key, null) ?: return defValue
        return decrypt(raw) ?: defValue
    }

    override fun edit(): SharedPreferences.Editor = Editor(backing.edit())

    override fun getAll(): Map<String, *> = backing.all
    override fun getStringSet(key: String, defValues: Set<String>?) = backing.getStringSet(key, defValues)
    override fun getInt(key: String, defValue: Int) = backing.getInt(key, defValue)
    override fun getLong(key: String, defValue: Long) = backing.getLong(key, defValue)
    override fun getFloat(key: String, defValue: Float) = backing.getFloat(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = backing.getBoolean(key, defValue)
    override fun contains(key: String) = backing.contains(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        backing.registerOnSharedPreferenceChangeListener(l)
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        backing.unregisterOnSharedPreferenceChangeListener(l)

    inner class Editor(private val delegate: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) delegate.putString(key, encrypt(value)) else delegate.remove(key)
            return this
        }
        override fun remove(key: String) = apply { delegate.remove(key) }
        override fun clear() = apply { delegate.clear() }
        override fun commit() = delegate.commit()
        override fun apply() = delegate.apply()
        override fun putStringSet(key: String, values: Set<String>?) = apply { delegate.putStringSet(key, values) }
        override fun putInt(key: String, value: Int) = apply { delegate.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { delegate.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = apply { delegate.putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { delegate.putBoolean(key, value) }
    }

    companion object {
        private const val KEY_ALIAS = "sync_credentials_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
