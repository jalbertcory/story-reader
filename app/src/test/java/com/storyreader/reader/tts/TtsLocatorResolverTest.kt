package com.storyreader.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsLocatorResolverTest {

    @Test
    fun `selects first candidate on or after target progression`() {
        val target = locator(progression = 0.42)
        val candidates = listOf(
            locator(progression = 0.10, cssSelector = "p:nth-of-type(1)"),
            locator(progression = 0.40, cssSelector = "p:nth-of-type(2)"),
            locator(progression = 0.70, cssSelector = "p:nth-of-type(3)")
        )

        val resolved = selectPreferredTtsStartLocator(target, candidates)

        assertEquals(candidates[2], resolved)
    }

    @Test
    fun `keeps exact progression match`() {
        val target = locator(progression = 0.40)
        val candidates = listOf(
            locator(progression = 0.10, cssSelector = "p:nth-of-type(1)"),
            locator(progression = 0.40, cssSelector = "p:nth-of-type(2)"),
            locator(progression = 0.70, cssSelector = "p:nth-of-type(3)")
        )

        val resolved = selectPreferredTtsStartLocator(target, candidates)

        assertEquals(candidates[1], resolved)
    }

    @Test
    fun `falls back to last candidate near resource end`() {
        val target = locator(progression = 0.95)
        val candidates = listOf(
            locator(progression = 0.10, cssSelector = "p:nth-of-type(1)"),
            locator(progression = 0.40, cssSelector = "p:nth-of-type(2)"),
            locator(progression = 0.70, cssSelector = "p:nth-of-type(3)")
        )

        val resolved = selectPreferredTtsStartLocator(target, candidates)

        assertEquals(candidates[2], resolved)
    }

    @Test
    fun `uses total progression when local progression is unavailable`() {
        val target = locator(totalProgression = 0.45)
        val candidates = listOf(
            locator(totalProgression = 0.20, cssSelector = "p:nth-of-type(1)"),
            locator(totalProgression = 0.50, cssSelector = "p:nth-of-type(2)")
        )

        val resolved = selectPreferredTtsStartLocator(target, candidates)

        assertEquals(candidates[1], resolved)
    }

    @Test
    fun `returns null when no candidates exist`() {
        val resolved = selectPreferredTtsStartLocator(locator(progression = 0.5), emptyList())

        assertNull(resolved)
    }

    private fun locator(
        progression: Double? = null,
        totalProgression: Double? = progression,
        cssSelector: String? = null
    ): Locator =
        Locator(
            href = Url("https://example.com/chapter-1.xhtml")!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = progression,
                totalProgression = totalProgression,
                otherLocations = buildMap {
                    cssSelector?.let { put("cssSelector", it as Any) }
                }
            )
        )
}
