package com.storyreader.ui.stats

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.dao.BookSessionStats
import com.storyreader.data.db.entity.BookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar

data class BookStatItem(
    val book: BookEntity,
    val firstReadMs: Long,
    val lastReadMs: Long,
    val totalReadingSeconds: Int,
    val sessionCount: Int
)

data class GlobalStats(
    val allTimeTotalSeconds: Long,
    val allTimeBooksStarted: Int,
    val ytdTotalSeconds: Long,
    val ytdBooksStarted: Int,
    val goalHoursPerYear: Int,
    val goalBooksPerYear: Int
)

data class StatsUiState(
    val bookStats: List<BookStatItem> = emptyList(),
    val globalStats: GlobalStats? = null,
    val isLoading: Boolean = true
)

private const val PREFS_NAME = "reading_goals"
private const val KEY_GOAL_HOURS = "goal_hours_per_year"
private const val KEY_GOAL_BOOKS = "goal_books_per_year"
private const val DEFAULT_GOAL_HOURS = 50
private const val DEFAULT_GOAL_BOOKS = 12

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val sessionDao = app.database.readingSessionDao()
    private val bookRepository = app.bookRepository
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    private fun loadStats() {
        val yearStartMs = getYearStartMs()

        viewModelScope.launch {
            @Suppress("UNCHECKED_CAST")
            combine(
                bookRepository.observeAll(),
                sessionDao.getBookSessionStats(),
                sessionDao.getTotalReadingSeconds(),
                sessionDao.getTotalBooksStarted(),
                sessionDao.getReadingSecondsSince(yearStartMs),
                sessionDao.getBooksStartedSince(yearStartMs)
            ) { values ->
                val books = values[0] as List<BookEntity>
                val statsPerBook = values[1] as List<BookSessionStats>
                val totalSecs = values[2] as Long?
                val totalBooks = values[3] as Int
                val ytdSecs = values[4] as Long?
                val ytdBooks = values[5] as Int

                val statsMap = statsPerBook.associateBy { it.bookId }
                val bookItems = books
                    .mapNotNull { book ->
                        val s = statsMap[book.bookId] ?: return@mapNotNull null
                        BookStatItem(
                            book = book,
                            firstReadMs = s.firstSessionStart,
                            lastReadMs = s.lastSessionStart,
                            totalReadingSeconds = s.totalDurationSeconds,
                            sessionCount = s.sessionCount
                        )
                    }
                    .sortedByDescending { it.lastReadMs }

                val global = GlobalStats(
                    allTimeTotalSeconds = totalSecs ?: 0L,
                    allTimeBooksStarted = totalBooks,
                    ytdTotalSeconds = ytdSecs ?: 0L,
                    ytdBooksStarted = ytdBooks,
                    goalHoursPerYear = prefs.getInt(KEY_GOAL_HOURS, DEFAULT_GOAL_HOURS),
                    goalBooksPerYear = prefs.getInt(KEY_GOAL_BOOKS, DEFAULT_GOAL_BOOKS)
                )

                StatsUiState(bookStats = bookItems, globalStats = global, isLoading = false)
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setGoalHours(hours: Int) {
        prefs.edit().putInt(KEY_GOAL_HOURS, hours).apply()
        _uiState.value = _uiState.value.copy(
            globalStats = _uiState.value.globalStats?.copy(goalHoursPerYear = hours)
        )
    }

    fun setGoalBooks(books: Int) {
        prefs.edit().putInt(KEY_GOAL_BOOKS, books).apply()
        _uiState.value = _uiState.value.copy(
            globalStats = _uiState.value.globalStats?.copy(goalBooksPerYear = books)
        )
    }

    private fun getYearStartMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
