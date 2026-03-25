package com.storyreader.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsLocatorResolverTest {

    @Test
    fun `manual page wins after manual navigation`() {
        val manual = locator("manual", 0.55)
        val resume = locator("resume", 0.44)
        val visible = locator("visible", 0.56)
        val current = locator("current", 0.57)

        val result = selectTtsStartLocator(
            preferManualPage = true,
            manualPageLocator = manual,
            resumeLocator = resume,
            visibleLocator = visible,
            currentLocator = current
        )

        assertSame(manual, result)
    }

    @Test
    fun `resume locator wins when user has not navigated manually`() {
        val resume = locator("resume", 0.44)
        val visible = locator("visible", 0.56)
        val current = locator("current", 0.57)

        val result = selectTtsStartLocator(
            preferManualPage = false,
            manualPageLocator = null,
            resumeLocator = resume,
            visibleLocator = visible,
            currentLocator = current
        )

        assertSame(resume, result)
    }

    @Test
    fun `visible locator is used when no manual or resume locator exists`() {
        val visible = locator("visible", 0.56)
        val current = locator("current", 0.57)

        val result = selectTtsStartLocator(
            preferManualPage = true,
            manualPageLocator = null,
            resumeLocator = null,
            visibleLocator = visible,
            currentLocator = current
        )

        assertSame(visible, result)
    }

    @Test
    fun `returns null when every candidate is missing`() {
        val result = selectTtsStartLocator(
            preferManualPage = true,
            manualPageLocator = null,
            resumeLocator = null,
            visibleLocator = null,
            currentLocator = null
        )

        assertNull(result)
    }

    @Test
    fun `locator helper builds distinguishable locators`() {
        val locator = locator("chapter-7", 0.7)

        assertEquals("chapter-7.html", locator.href.toString())
        assertEquals(0.7, locator.locations.totalProgression ?: 0.0, 0.0001)
    }

    private fun locator(name: String, progression: Double): Locator =
        Locator(
            href = Url("$name.html")!!,
            mediaType = MediaType("text/html")!!,
            title = name,
            locations = Locator.Locations(totalProgression = progression)
        )
}
