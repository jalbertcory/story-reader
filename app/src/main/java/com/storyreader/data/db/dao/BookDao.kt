package com.storyreader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.storyreader.data.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE hidden = 0 ORDER BY title ASC")
    fun getAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllIncludingHidden(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE hidden = 0 ORDER BY title ASC")
    suspend fun getAllOnce(): List<BookEntity>

    @Query("SELECT * FROM books WHERE bookId = :bookId")
    fun getById(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE bookId = :bookId LIMIT 1")
    suspend fun getByIdOnce(bookId: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("UPDATE books SET totalProgression = :progression WHERE bookId = :bookId")
    suspend fun updateProgression(bookId: String, progression: Float)

    @Query("UPDATE books SET wordCount = :count WHERE bookId = :bookId")
    suspend fun updateWordCount(bookId: String, count: Int)

    @Query("SELECT wordCount FROM books WHERE bookId = :bookId LIMIT 1")
    suspend fun getWordCountById(bookId: String): Int?

    @Query("UPDATE books SET hidden = :hidden WHERE bookId = :bookId")
    suspend fun setHidden(bookId: String, hidden: Boolean)

    @Query("SELECT * FROM books WHERE sourceType = 'web' AND hidden = 0 ORDER BY series ASC, title ASC")
    fun getWebBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE serverBookId = :serverBookId LIMIT 1")
    suspend fun getByServerBookId(serverBookId: Int): BookEntity?

    @Query("SELECT * FROM books WHERE sourceType = 'web'")
    suspend fun getWebBooksForSync(): List<BookEntity>

    @Query("UPDATE books SET lastChapterTitle = :title, lastChapterProgression = :progression WHERE bookId = :bookId")
    suspend fun updateChapterPosition(bookId: String, title: String?, progression: Float?)

    @Query("SELECT DISTINCT series FROM books WHERE series IS NOT NULL AND series != 'null' AND hidden = 0")
    suspend fun getLocalSeriesNames(): List<String>

    @Query("SELECT serverBookId FROM books WHERE serverBookId IS NOT NULL")
    suspend fun getAllServerBookIds(): List<Int>

    @Query("UPDATE books SET series = :series WHERE bookId = :bookId")
    suspend fun updateSeries(bookId: String, series: String)

    @Query("UPDATE books SET serverBookId = :serverBookId WHERE bookId = :bookId")
    suspend fun updateServerBookId(bookId: String, serverBookId: Int)

    @Query("SELECT * FROM books WHERE (series IS NULL OR series = 'null') AND hidden = 0")
    suspend fun getBooksWithoutSeries(): List<BookEntity>
}
