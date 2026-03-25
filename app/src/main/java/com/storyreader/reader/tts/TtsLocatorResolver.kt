package com.storyreader.reader.tts

import kotlin.math.abs
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.content.content

internal fun selectTtsStartLocator(
    preferManualPage: Boolean,
    manualPageLocator: Locator?,
    resumeLocator: Locator?,
    visibleLocator: Locator?,
    currentLocator: Locator?
): Locator? =
    if (preferManualPage) {
        manualPageLocator ?: visibleLocator ?: currentLocator ?: resumeLocator
    } else {
        resumeLocator ?: visibleLocator ?: currentLocator ?: manualPageLocator
    }

/**
 * If the locator lacks a cssSelector (e.g. saved from the visual epub reader), Readium's
 * HtmlResourceContentIterator cannot position within the chapter and falls back to position 0.
 * Work around this by iterating the publication content to find the element whose
 * totalProgression is closest to the saved value — its locator will carry a cssSelector.
 */
@OptIn(ExperimentalReadiumApi::class)
internal suspend fun Publication.enhanceLocatorForTts(locator: Locator): Locator {
    if (locator.locations.otherLocations.containsKey("cssSelector")) return locator

    val targetProgression = locator.locations.totalProgression ?: return locator
    val readingOrderIndex = readingOrder.indexOfFirstWithHref(locator.href) ?: return locator
    val link = readingOrder[readingOrderIndex]
    val chapterLocator = locatorFromLink(link) ?: return locator

    val content = content(chapterLocator) ?: return locator
    val iterator = content.iterator()

    var bestLocator: Locator? = null
    var bestDiff = Double.MAX_VALUE

    while (iterator.hasNext()) {
        val element = iterator.next()
        if (element.locator.href != locator.href) break
        val elementProgression = element.locator.locations.totalProgression ?: continue
        val diff = abs(elementProgression - targetProgression)
        if (diff < bestDiff) {
            bestDiff = diff
            bestLocator = element.locator
        }
        if (elementProgression > targetProgression) break
    }

    return bestLocator ?: locator
}
