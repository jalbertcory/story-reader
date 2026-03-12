package com.storyreader.ui.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: String) = "reader/$bookId"
    }
    data object SyncSettings : Screen("sync_settings")
}
