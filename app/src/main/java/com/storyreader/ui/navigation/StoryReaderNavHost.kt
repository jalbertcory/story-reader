package com.storyreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.storyreader.ui.library.LibraryScreen
import com.storyreader.ui.library.NextcloudBrowserScreen
import com.storyreader.ui.library.SyncSettingsScreen
import com.storyreader.ui.reader.ReaderScreen

@Composable
fun StoryReaderNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Library.route) {
        composable(Screen.Library.route) {
            LibraryScreen(
                onBookClick = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
                onSyncSettingsClick = {
                    navController.navigate(Screen.SyncSettings.route)
                },
                onNextcloudImportClick = {
                    navController.navigate(Screen.NextcloudBrowser.route)
                }
            )
        }
        composable(
            route = Screen.Reader.route,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.SyncSettings.route) {
            SyncSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.NextcloudBrowser.route) {
            NextcloudBrowserScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
