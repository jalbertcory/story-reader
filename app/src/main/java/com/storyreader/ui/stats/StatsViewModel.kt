package com.storyreader.ui.stats

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storyreader.StoryReaderApplication
import com.storyreader.data.db.dao.BookSessionStats
import com.storyreader.data.db.dao.MonthlyReadingStat
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingSessionEntity
import com.storyreader.data.sync.DEFAULT_GOAL_HOURS
import com.storyreader.data.sync.DEFAULT_GOAL_WORDS
import com.storyreader.data.sync.GOALS_PREFS_NAME
import com.storyreader.data.sync.KEY_GOAL_HOURS
import com.storyreader.data.sync.KEY_GOAL_WORDS
import com.storyreader.data.sync.KEY_SYNC_GOALS_UPDATED_AT
import kotlinx.coroutines.flow.Flow
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
    val totalWordsRead: Int,
    val sessionCount: Int,
    val manualDurationSeconds: Int,
    val manualWordsRead: Int,
    val ttsDurationSeconds: Int,
    val ttsWordsRead: Int
)

data class GlobalStats(
    val allTimeTotalSeconds: Long,
    val allTimeTotalWords: Long,
    val allTimeManualWpm: Int,
    val selectedYearTotalSeconds: Long,
    val selectedYearTotalWords: Long,
    val goalHoursPerYear: Int,
    val goalWordsPerYear: Int,
    val selectedYear: Int,
    val isCurrentYear: Boolean
)

data class StatsUiState(
    val bookStats: List<BookStatItem> = emptyList(),
    val globalStats: GlobalStats? = null,
    val availableYears: List<Int> = emptyList(),
    val selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val monthlyStats: List<MonthlyReadingStat> = emptyList(),
    val isLoading: Boolean = true
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as StoryReaderApplication
    private val sessionDao = app.database.readingSessionDao()
    private val bookRepository = app.bookRepository
    private val prefs = application.getSharedPreferences(GOALS_PREFS_NAME, Context.MODE_PRIVATE)

    private val currentYear = Calendar.getInstance().get(Calendar.YEAR)

    private val _uiState = MutableStateFlow(StatsUiState(selectedYear = currentYear))
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            // Load available years once
            val years = sessionDao.getReadingYears().ifEmpty { listOf(currentYear) }
            val initialYear = if (currentYear in years) currentYear else years.first()
            _uiState.value = _uiState.value.copy(availableYears = years, selectedYear = initialYear)
            loadYearData(initialYear)
        }

        // Observe reactive per-book and all-time data continuously
        // Use observeAllIncludingHidden so hidden books still show in stats
        viewModelScope.launch {
            @Suppress("UNCHECKED_CAST")
            combine(
                bookRepository.observeAllIncludingHidden(),
                sessionDao.getBookSessionStats(),
                sessionDao.getTotalReadingSeconds(),
                sessionDao.getTotalWordsRead()
            ) { values ->
                val books = values[0] as List<BookEntity>
                val statsPerBook = values[1] as List<BookSessionStats>
                val totalSecs = values[2] as Long?
                val totalWords = values[3] as Long?

                val statsMap = statsPerBook.associateBy { it.bookId }
                val bookItems = books
                    .mapNotNull { book ->
                        val s = statsMap[book.bookId] ?: return@mapNotNull null
                        BookStatItem(
                            book = book,
                            firstReadMs = s.firstSessionStart,
                            lastReadMs = s.lastSessionStart,
                            totalReadingSeconds = s.totalDurationSeconds,
                            totalWordsRead = s.totalWordsRead,
                            sessionCount = s.sessionCount,
                            manualDurationSeconds = s.manualDurationSeconds,
                            manualWordsRead = s.manualWordsRead,
                            ttsDurationSeconds = s.ttsDurationSeconds,
                            ttsWordsRead = s.ttsWordsRead
                        )
                    }
                    .sortedByDescending { it.lastReadMs }

                val totalManualSecs = bookItems.sumOf { it.manualDurationSeconds }
                val totalManualWords = bookItems.sumOf { it.manualWordsRead }
                val manualWpm = if (totalManualSecs > 60)
                    (totalManualWords.toFloat() / (totalManualSecs / 60f)).toInt()
                else 0

                Triple(bookItems, totalSecs ?: 0L, totalWords ?: 0L) to manualWpm
            }.collect { (triple, manualWpm) ->
                val (bookItems, totalSecs, totalWords) = triple
                val current = _uiState.value
                val existingGlobal = current.globalStats
                _uiState.value = current.copy(
                    bookStats = bookItems,
                    globalStats = existingGlobal?.copy(
                        allTimeTotalSeconds = totalSecs,
                        allTimeTotalWords = totalWords,
                        allTimeManualWpm = manualWpm
                    ) ?: GlobalStats(
                        allTimeTotalSeconds = totalSecs,
                        allTimeTotalWords = totalWords,
                        allTimeManualWpm = manualWpm,
                        selectedYearTotalSeconds = 0L,
                        selectedYearTotalWords = 0L,
                        goalHoursPerYear = prefs.getInt(KEY_GOAL_HOURS, DEFAULT_GOAL_HOURS),
                        goalWordsPerYear = prefs.getInt(KEY_GOAL_WORDS, DEFAULT_GOAL_WORDS),
                        selectedYear = current.selectedYear,
                        isCurrentYear = current.selectedYear == currentYear
                    ),
                    isLoading = false
                )
            }
        }
    }

    fun selectYear(year: Int) {
        _uiState.value = _uiState.value.copy(selectedYear = year)
        viewModelScope.launch { loadYearData(year) }
    }

    private suspend fun loadYearData(year: Int) {
        val yearStart = getYearStartMs(year)
        val yearEnd = getYearEndMs(year)
        val yearSecs = sessionDao.getReadingSecondsBetween(yearStart, yearEnd) ?: 0L
        val yearWords = sessionDao.getWordsReadBetween(yearStart, yearEnd) ?: 0L
        val monthly = sessionDao.getMonthlyStats(yearStart, yearEnd)

        val current = _uiState.value
        _uiState.value = current.copy(
            monthlyStats = monthly,
            globalStats = (current.globalStats ?: GlobalStats(
                allTimeTotalSeconds = 0L,
                allTimeTotalWords = 0L,
                allTimeManualWpm = 0,
                selectedYearTotalSeconds = 0L,
                selectedYearTotalWords = 0L,
                goalHoursPerYear = prefs.getInt(KEY_GOAL_HOURS, DEFAULT_GOAL_HOURS),
                goalWordsPerYear = prefs.getInt(KEY_GOAL_WORDS, DEFAULT_GOAL_WORDS),
                selectedYear = year,
                isCurrentYear = year == currentYear
            )).copy(
                selectedYearTotalSeconds = yearSecs,
                selectedYearTotalWords = yearWords,
                selectedYear = year,
                isCurrentYear = year == currentYear
            )
        )
    }

    fun setGoalHours(hours: Int) {
        prefs.edit {
            putInt(KEY_GOAL_HOURS, hours)
            putLong(KEY_SYNC_GOALS_UPDATED_AT, System.currentTimeMillis())
        }
        _uiState.value = _uiState.value.copy(
            globalStats = _uiState.value.globalStats?.copy(goalHoursPerYear = hours)
        )
    }

    fun getSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>> =
        sessionDao.getSessionsForBook(bookId)

    fun markAsRead(bookId: String) {
        viewModelScope.launch {
            bookRepository.updateProgression(bookId, 1f)
        }
    }

    fun setGoalWords(words: Int) {
        prefs.edit {
            putInt(KEY_GOAL_WORDS, words)
            putLong(KEY_SYNC_GOALS_UPDATED_AT, System.currentTimeMillis())
        }
        _uiState.value = _uiState.value.copy(
            globalStats = _uiState.value.globalStats?.copy(goalWordsPerYear = words)
        )
    }

    private fun getYearStartMs(year: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, Calendar.JANUARY, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getYearEndMs(year: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year + 1, Calendar.JANUARY, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
