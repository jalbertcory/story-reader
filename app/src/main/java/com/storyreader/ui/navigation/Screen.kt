package com.storyreader.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: String) = "reader/${Uri.encode(bookId)}"
    }
    data object AppSettings : Screen("app_settings")
    data object NextcloudBrowser : Screen("nextcloud_browser")
    data object Stats : Screen("stats")
}
