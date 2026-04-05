package com.storyreader.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {

    private val syncSettingsStore: SyncSettingsStore by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SyncSettingsStore.create(context)
    }

    private fun makeSyncManager(
        providers: List<SyncProvider>,
        countLocalBooks: suspend () -> Int = { 0 },
        onBooksAdded: (Int) -> Unit = {}
    ): SyncManager {
        return SyncManager(
            providers = providers,
            syncSettingsStore = syncSettingsStore,
            countLocalBooks = countLocalBooks,
            onBooksAdded = onBooksAdded
        )
    }

    private fun makeProvider(
        id: String,
        enabled: Boolean,
        configured: Boolean,
        syncResult: Result<Unit> = Result.success(Unit)
    ) = object : SyncProvider {
        override val id = id
        override val displayName = "Provider $id"
        override val isEnabled = enabled
        override val isConfigured = configured
        override suspend fun sync(onProgress: (SyncProgress) -> Unit) = syncResult
    }

    @Test
    fun `hasEnabledConfiguredProviders returns false when no providers`() {
        assertFalse(makeSyncManager(emptyList()).hasEnabledConfiguredProviders())
    }

    @Test
    fun `hasEnabledConfiguredProviders returns false when provider is disabled`() {
        val manager = makeSyncManager(listOf(makeProvider("p1", enabled = false, configured = true)))
        assertFalse(manager.hasEnabledConfiguredProviders())
    }

    @Test
    fun `hasEnabledConfiguredProviders returns false when provider is not configured`() {
        val manager = makeSyncManager(listOf(makeProvider("p1", enabled = true, configured = false)))
        assertFalse(manager.hasEnabledConfiguredProviders())
    }

    @Test
    fun `hasEnabledConfiguredProviders returns true when one provider is enabled and configured`() {
        val manager = makeSyncManager(listOf(makeProvider("p1", enabled = true, configured = true)))
        assertTrue(manager.hasEnabledConfiguredProviders())
    }

    @Test
    fun `hasEnabledConfiguredProviders returns true even if other providers are disabled`() {
        val manager = makeSyncManager(listOf(
            makeProvider("p1", enabled = false, configured = true),
            makeProvider("p2", enabled = true, configured = true)
        ))
        assertTrue(manager.hasEnabledConfiguredProviders())
    }

    @Test
    fun `syncEnabledProviders succeeds with no providers`() = runTest {
        assertTrue(makeSyncManager(emptyList()).syncEnabledProviders().isSuccess)
    }

    @Test
    fun `syncEnabledProviders succeeds when all providers succeed`() = runTest {
        val manager = makeSyncManager(listOf(
            makeProvider("p1", enabled = true, configured = true),
            makeProvider("p2", enabled = true, configured = true)
        ))
        assertTrue(manager.syncEnabledProviders().isSuccess)
    }

    @Test
    fun `syncEnabledProviders succeeds with no enabled and configured providers`() = runTest {
        val manager = makeSyncManager(listOf(
            makeProvider("p1", enabled = false, configured = true),
            makeProvider("p2", enabled = true, configured = false)
        ))
        assertTrue(manager.syncEnabledProviders().isSuccess)
    }

    @Test
    fun `syncEnabledProviders fails when a provider fails`() = runTest {
        val manager = makeSyncManager(listOf(
            makeProvider("p1", enabled = true, configured = true,
                syncResult = Result.failure(RuntimeException("network error")))
        ))
        val result = manager.syncEnabledProviders()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("network error") == true)
    }

    @Test
    fun `syncEnabledProviders includes provider display name in failure message`() = runTest {
        val manager = makeSyncManager(listOf(
            makeProvider("nextcloud", enabled = true, configured = true,
                syncResult = Result.failure(RuntimeException("timeout")))
        ))
        val result = manager.syncEnabledProviders()
        assertTrue(result.exceptionOrNull()?.message?.contains("Provider nextcloud") == true)
    }

    @Test
    fun `syncEnabledProviders aggregates multiple failures`() = runTest {
        val manager = makeSyncManager(listOf(
            makeProvider("p1", enabled = true, configured = true,
                syncResult = Result.failure(RuntimeException("err1"))),
            makeProvider("p2", enabled = true, configured = true,
                syncResult = Result.failure(RuntimeException("err2")))
        ))
        val result = manager.syncEnabledProviders()
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Should contain first error", msg.contains("err1"))
        assertTrue("Should contain second error", msg.contains("err2"))
    }

    @Test
    fun `syncEnabledProviders skips disabled providers`() = runTest {
        var syncCalled = false
        val provider = object : SyncProvider {
            override val id = "p1"
            override val displayName = "P1"
            override val isEnabled = false
            override val isConfigured = true
            override suspend fun sync(onProgress: (SyncProgress) -> Unit): Result<Unit> {
                syncCalled = true
                return Result.success(Unit)
            }
        }
        makeSyncManager(listOf(provider)).syncEnabledProviders()
        assertFalse("Disabled provider should not be synced", syncCalled)
    }

    @Test
    fun `syncEnabledProviders skips unconfigured providers`() = runTest {
        var syncCalled = false
        val provider = object : SyncProvider {
            override val id = "p1"
            override val displayName = "P1"
            override val isEnabled = true
            override val isConfigured = false
            override suspend fun sync(onProgress: (SyncProgress) -> Unit): Result<Unit> {
                syncCalled = true
                return Result.success(Unit)
            }
        }
        makeSyncManager(listOf(provider)).syncEnabledProviders()
        assertFalse("Unconfigured provider should not be synced", syncCalled)
    }

    @Test
    fun `syncEnabledProviders runs subsequent providers after one fails`() = runTest {
        var p2Called = false
        val p1 = makeProvider("p1", enabled = true, configured = true,
            syncResult = Result.failure(RuntimeException("p1 failed")))
        val p2 = object : SyncProvider {
            override val id = "p2"
            override val displayName = "P2"
            override val isEnabled = true
            override val isConfigured = true
            override suspend fun sync(onProgress: (SyncProgress) -> Unit): Result<Unit> {
                p2Called = true
                return Result.success(Unit)
            }
        }
        makeSyncManager(listOf(p1, p2)).syncEnabledProviders()
        assertTrue("p2 should run even though p1 failed", p2Called)
    }

    @Test
    fun `syncEnabledProviders result is success when only disabled provider fails`() = runTest {
        val manager = makeSyncManager(listOf(
            makeProvider("disabled-fail", enabled = false, configured = true,
                syncResult = Result.failure(RuntimeException("would fail")))
        ))
        assertTrue(manager.syncEnabledProviders().isSuccess)
    }

    @Test
    fun `syncEnabledProviders publishes progress updates to status`() = runTest {
        lateinit var manager: SyncManager
        val provider = object : SyncProvider {
            override val id = "p1"
            override val displayName = "Provider p1"
            override val isEnabled = true
            override val isConfigured = true

            override suspend fun sync(onProgress: (SyncProgress) -> Unit): Result<Unit> {
                onProgress(SyncProgress(message = "Restoring books", completed = 2, total = 5))
                val status = manager.status.value as SyncStatus.Syncing
                assertEquals("Provider p1", status.providerName)
                assertEquals("Restoring books", status.progress?.message)
                assertEquals(2, status.progress?.completed)
                assertEquals(5, status.progress?.total)
                return Result.success(Unit)
            }
        }

        manager = makeSyncManager(listOf(provider))
        assertTrue(manager.syncEnabledProviders().isSuccess)
    }

    @Test
    fun `concurrent syncEnabledProviders calls share one in-flight sync`() = runTest {
        var syncCalls = 0
        val manager = makeSyncManager(
            listOf(
                object : SyncProvider {
                    override val id = "p1"
                    override val displayName = "Provider p1"
                    override val isEnabled = true
                    override val isConfigured = true

                    override suspend fun sync(onProgress: (SyncProgress) -> Unit): Result<Unit> {
                        syncCalls++
                        delay(50)
                        return Result.success(Unit)
                    }
                }
            )
        )

        val results = awaitAll(
            async { manager.syncEnabledProviders() },
            async { manager.syncEnabledProviders() }
        )

        assertTrue(results.all { it.isSuccess })
        assertEquals(1, syncCalls)
    }

    @Test
    fun `syncEnabledProviders calls onBooksAdded when local library grows`() = runTest {
        var bookCount = 0
        var addedBooks = 0
        val manager = makeSyncManager(
            providers = listOf(
                object : SyncProvider {
                    override val id = "p1"
                    override val displayName = "Provider p1"
                    override val isEnabled = true
                    override val isConfigured = true

                    override suspend fun sync(onProgress: (SyncProgress) -> Unit): Result<Unit> {
                        bookCount = 3
                        return Result.success(Unit)
                    }
                }
            ),
            countLocalBooks = { bookCount },
            onBooksAdded = { addedBooks = it }
        )

        val result = manager.syncEnabledProviders()

        assertTrue(result.isSuccess)
        assertEquals(3, addedBooks)
    }

    @Test
    fun `syncEnabledProviders emits reload event when sync repopulates empty library`() = runTest {
        var bookCount = 0
        val manager = makeSyncManager(
            providers = listOf(
                object : SyncProvider {
                    override val id = "p1"
                    override val displayName = "Provider p1"
                    override val isEnabled = true
                    override val isConfigured = true

                    override suspend fun sync(onProgress: (SyncProgress) -> Unit): Result<Unit> {
                        bookCount = 2
                        return Result.success(Unit)
                    }
                }
            ),
            countLocalBooks = { bookCount }
        )

        val reloadEvent = async {
            withTimeout(1_000) { manager.events.first() }
        }
        runCurrent()
        assertTrue(manager.syncEnabledProviders().isSuccess)
        assertEquals(SyncEvent.ReloadAppRequested, reloadEvent.await())
    }
}
