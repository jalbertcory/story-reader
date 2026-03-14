package com.storyreader.data.sync

class SyncManager(
    private val providers: List<SyncProvider>
) {

    fun hasEnabledConfiguredProviders(): Boolean {
        return providers.any { it.isEnabled && it.isConfigured }
    }

    suspend fun syncEnabledProviders(): Result<Unit> {
        val enabledProviders = providers.filter { it.isEnabled && it.isConfigured }
        if (enabledProviders.isEmpty()) {
            return Result.success(Unit)
        }

        val failures = mutableListOf<String>()
        enabledProviders.forEach { provider ->
            provider.sync().onFailure { error ->
                failures += "${provider.displayName}: ${error.message ?: "Unknown error"}"
            }
        }

        return if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(failures.joinToString(separator = "\n")))
        }
    }
}
