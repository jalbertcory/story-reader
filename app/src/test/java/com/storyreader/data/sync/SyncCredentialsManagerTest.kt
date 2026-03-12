package com.storyreader.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [SyncCredentialsManager] logic using a plain SharedPreferences backend
 * (EncryptedSharedPreferences requires AndroidKeyStore which is unavailable on the JVM).
 * The encryption layer is a library concern; what we test here is correct key/value handling.
 */
@RunWith(RobolectricTestRunner::class)
class SyncCredentialsManagerTest {

    private lateinit var manager: SyncCredentialsManager

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("test_sync_credentials", Context.MODE_PRIVATE)
        manager = SyncCredentialsManager(prefs)
        manager.clear()
    }

    @Test
    fun `hasCredentials is false when nothing stored`() {
        assertFalse(manager.hasCredentials)
    }

    @Test
    fun `serverUrl is null initially`() {
        assertNull(manager.serverUrl)
    }

    @Test
    fun `storing credentials makes hasCredentials true`() {
        manager.serverUrl = "https://cloud.example.com"
        manager.username = "alice"
        manager.appPassword = "abc-def-ghi"

        assertTrue(manager.hasCredentials)
    }

    @Test
    fun `stored values round-trip correctly`() {
        manager.serverUrl = "https://cloud.example.com"
        manager.username = "bob"
        manager.appPassword = "secret-token"

        assertEquals("https://cloud.example.com", manager.serverUrl)
        assertEquals("bob", manager.username)
        assertEquals("secret-token", manager.appPassword)
    }

    @Test
    fun `hasCredentials is false when any field is blank`() {
        manager.serverUrl = "https://cloud.example.com"
        manager.username = "alice"
        // appPassword not set

        assertFalse(manager.hasCredentials)
    }

    @Test
    fun `clear wipes all credentials`() {
        manager.serverUrl = "https://cloud.example.com"
        manager.username = "alice"
        manager.appPassword = "token"
        manager.clear()

        assertNull(manager.serverUrl)
        assertNull(manager.username)
        assertNull(manager.appPassword)
        assertFalse(manager.hasCredentials)
    }
}
