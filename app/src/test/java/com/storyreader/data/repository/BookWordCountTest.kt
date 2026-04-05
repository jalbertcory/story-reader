package com.storyreader.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storyreader.data.db.AppDatabase
import com.storyreader.reader.epub.EpubRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Method

/**
 * Tests [BookRepositoryImpl.countWordsInHtml] via reflection.
 *
 * This method strips HTML tags and entities then counts space-separated tokens.
 * It is private, so we access it reflectively rather than modifying production
 * code purely for testability.
 */
@RunWith(RobolectricTestRunner::class)
class BookWordCountTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: BookRepositoryImpl
    private lateinit var countWordsInHtml: Method

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = BookRepositoryImpl(
            context,
            db.bookDao(),
            db.readingPositionDao(),
            db.readingSessionDao(),
            EpubRepository(context)
        )

        countWordsInHtml = BookRepositoryImpl::class.java
            .getDeclaredMethod("countWordsInHtml", String::class.java)
            .also { it.isAccessible = true }
    }

    @After
    fun tearDown() = db.close()

    private fun count(html: String): Int =
        countWordsInHtml.invoke(repository, html) as Int

    @Test
    fun `plain text without tags is counted correctly`() {
        assertEquals(3, count("Hello World Today"))
    }

    @Test
    fun `html tags are stripped before counting`() {
        assertEquals(2, count("<p>Hello World</p>"))
    }

    @Test
    fun `nested tags are stripped`() {
        assertEquals(3, count("<div><p><strong>One Two Three</strong></p></div>"))
    }

    @Test
    fun `html entities are stripped before counting`() {
        // &nbsp; should be replaced with a space, not counted as a word
        assertEquals(2, count("Hello&nbsp;World"))
    }

    @Test
    fun `numeric html entities are stripped`() {
        assertEquals(2, count("Hello&#160;World"))
    }

    @Test
    fun `multiple whitespace is collapsed to single space`() {
        assertEquals(3, count("one   two\n\tthree"))
    }

    @Test
    fun `empty string returns zero`() {
        assertEquals(0, count(""))
    }

    @Test
    fun `blank html with only tags returns zero`() {
        assertEquals(0, count("<html><body>  </body></html>"))
    }

    @Test
    fun `self closing tags are stripped`() {
        assertEquals(2, count("Hello<br/>World"))
    }

    @Test
    fun `tag attributes are stripped`() {
        assertEquals(2, count("<p class=\"intro\" id=\"p1\">Hello World</p>"))
    }

    @Test
    fun `mixed tags and entities are both stripped`() {
        assertEquals(3, count("<p>Hello&amp;World Today</p>"))
    }

    @Test
    fun `typical epub paragraph returns correct word count`() {
        val html = """
            <p>It was the best of times, it was the worst of times,
            it was the age of wisdom, it was the age of foolishness.</p>
        """.trimIndent()
        assertEquals(24, count(html))
    }

    @Test
    fun `whitespace-only input returns zero`() {
        assertEquals(0, count("   \n\t  "))
    }
}
