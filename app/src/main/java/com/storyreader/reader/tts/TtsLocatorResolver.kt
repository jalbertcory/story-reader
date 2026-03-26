package com.storyreader.reader.tts

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

private const val TTS_START_EPSILON = 0.0005

@OptIn(ExperimentalReadiumApi::class)
internal suspend fun Publication.resolveTtsStartLocator(locator: Locator?): Locator? {
    locator ?: return null
    if (locator.locations.otherLocations.containsKey("cssSelector")) return locator

    val readingOrderIndex = readingOrder.indexOfFirstWithHref(locator.href) ?: return locator
    val resourceLocator = locatorFromLink(readingOrder[readingOrderIndex]) ?: return locator
    val content = content(resourceLocator) ?: return locator
    val iterator = content.iterator()

    val candidates = buildList {
        while (iterator.hasNext()) {
            val element = iterator.next()
            if (element.locator.href != locator.href) break

            val text = (element as? Content.TextualElement)?.text
            if (text.isNullOrBlank()) continue

            add(element.locator)
        }
    }

    return selectPreferredTtsStartLocator(locator, candidates) ?: locator
}

internal fun selectPreferredTtsStartLocator(
    targetLocator: Locator,
    candidates: List<Locator>
): Locator? {
    if (candidates.isEmpty()) return null

    return candidates.firstOrNull { candidate ->
        isOnOrAfterTarget(targetLocator, candidate)
    } ?: candidates.last()
}

private fun isOnOrAfterTarget(targetLocator: Locator, candidate: Locator): Boolean {
    if (candidate.href == targetLocator.href) {
        val targetProgression = targetLocator.locations.progression
        val candidateProgression = candidate.locations.progression
        if (targetProgression != null && candidateProgression != null) {
            return candidateProgression + TTS_START_EPSILON >= targetProgression
        }
    }

    val targetTotalProgression = targetLocator.locations.totalProgression ?: return true
    val candidateTotalProgression = candidate.locations.totalProgression ?: return true
    return candidateTotalProgression + TTS_START_EPSILON >= targetTotalProgression
}
