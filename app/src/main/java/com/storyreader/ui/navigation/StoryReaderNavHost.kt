package com.storyreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.storyreader.StoryReaderApplication
import com.storyreader.ui.library.GoogleDriveBrowserScreen
import com.storyreader.ui.library.LibraryScreen
import com.storyreader.ui.library.NextcloudBrowserScreen
import com.storyreader.ui.library.OpdsBrowserScreen
import com.storyreader.ui.reader.ReaderScreen
import com.storyreader.ui.settings.AppSettingsScreen
import com.storyreader.ui.stats.StatsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StoryReaderNavHost() {
    val app = LocalContext.current.applicationContext as StoryReaderApplication
    val navController = rememberNavController()
    val startDestination by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            app.database.readingSessionDao()
                .getMostRecentlyReadVisibleBookId()
                ?.let(Screen.Reader::createRoute)
                ?: Screen.Library.route
        }
    }

    val resolvedStartDestination = startDestination ?: return

    NavHost(navController = navController, startDestination = resolvedStartDestination) {
        composable(Screen.Library.route) {
            LibraryScreen(
                onBookClick = { bookId ->
                    navController.navigate(Screen.Reader.createRoute(bookId))
                },
                onSyncSettingsClick = {
                    navController.navigate(Screen.AppSettings.route)
                },
                onNextcloudImportClick = {
                    navController.navigate(Screen.NextcloudBrowser.route)
                },
                onGoogleDriveImportClick = {
                    navController.navigate(Screen.GoogleDriveBrowser.route)
                },
                onOpdsImportClick = {
                    navController.navigate(Screen.OpdsBrowser.route)
                },
                onStatsClick = {
                    navController.navigate(Screen.Stats.route)
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
        composable(Screen.AppSettings.route) {
            AppSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.NextcloudBrowser.route) {
            NextcloudBrowserScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.GoogleDriveBrowser.route) {
            GoogleDriveBrowserScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.OpdsBrowser.route) {
            OpdsBrowserScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Stats.route) {
            StatsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
