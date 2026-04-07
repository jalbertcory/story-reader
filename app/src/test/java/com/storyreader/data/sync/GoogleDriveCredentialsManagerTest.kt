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

@RunWith(RobolectricTestRunner::class)
class GoogleDriveCredentialsManagerTest {

    private lateinit var manager: GoogleDriveCredentialsManager

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("test_google_drive_credentials", Context.MODE_PRIVATE)
        manager = GoogleDriveCredentialsManager(prefs)
        manager.clear()
    }

    @Test
    fun `hasAccount is false when nothing stored`() {
        assertFalse(manager.hasAccount)
    }

    @Test
    fun `authorization without profile details still counts as connected`() {
        manager.isAuthorized = true

        assertTrue(manager.hasAccount)
        assertNull(manager.displayLabel())
    }

    @Test
    fun `stored profile details still round-trip correctly`() {
        manager.isAuthorized = true
        manager.accountEmail = "reader@example.com"
        manager.accountDisplayName = "Story Reader"

        assertTrue(manager.hasAccount)
        assertEquals("Story Reader (reader@example.com)", manager.displayLabel())
    }

    @Test
    fun `clear wipes authorization and account details`() {
        manager.isAuthorized = true
        manager.accountEmail = "reader@example.com"
        manager.accountDisplayName = "Story Reader"

        manager.clear()

        assertFalse(manager.isAuthorized)
        assertFalse(manager.hasAccount)
        assertNull(manager.accountEmail)
        assertNull(manager.accountDisplayName)
    }
}
