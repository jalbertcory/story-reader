package com.storyreader.ui.reader

import org.readium.r2.shared.publication.Link

/**
 * Result of matching a locator href against the TOC.
 */
internal sealed class ChapterMatch {
    /** Exactly one TOC entry matched by direct href comparison. */
    data class Single(val link: Link) : ChapterMatch()

    /** Multiple TOC entries share the same file (fragment-based chapters). */
    data class Multiple(val candidates: List<Link>) : ChapterMatch()

    /** No exact match, but a split-suffix normalized match was found. */
    data class NormalizedFallback(val link: Link) : ChapterMatch()

    /** No match found at all. */
    data object None : ChapterMatch()
}

/**
 * Strip `_split_NNN` suffixes that some EPUBs use to break large chapters
 * into multiple HTML files. E.g. `part0006_split_001.html` → `part0006.html`
 */
internal fun stripSplitSuffix(path: String): String =
    path.replace(Regex("_split_\\d+"), "")

/**
 * Recursively flatten a hierarchical TOC into a flat list preserving order.
 */
internal fun flattenTocLinks(links: List<Link>): List<Link> =
    links.flatMap { link -> listOf(link) + flattenTocLinks(link.children) }

/**
 * Match the current locator href against a flat list of TOC links.
 *
 * Priority:
 * 1. Exact href match (one-file-per-chapter EPUBs)
 * 2. Multiple exact matches (fragment-based chapters in one file)
 * 3. Normalized fallback (split-file EPUBs where chapter spans multiple HTML files)
 */
internal fun matchChapterByHref(locatorHref: String, allLinks: List<Link>): ChapterMatch {
    val hrefNoFragment = locatorHref.substringBefore("#")

    // 1. Try exact href match first
    val exactCandidates = allLinks.filter { link ->
        val linkHref = link.href.toString().substringBefore("#")
        locatorHref.startsWith(linkHref) || hrefNoFragment.endsWith(linkHref)
    }

    if (exactCandidates.size == 1) return ChapterMatch.Single(exactCandidates.first())
    if (exactCandidates.size > 1) return ChapterMatch.Multiple(exactCandidates)

    // 2. Fall back to normalized matching for split-file EPUBs
    val hrefNormalized = stripSplitSuffix(hrefNoFragment)
    val normalizedMatch = allLinks
        .filter { link ->
            val linkNormalized = stripSplitSuffix(link.href.toString().substringBefore("#"))
            hrefNormalized == linkNormalized
        }
        .maxByOrNull { it.href.toString().length }

    return if (normalizedMatch != null) ChapterMatch.NormalizedFallback(normalizedMatch)
    else ChapterMatch.None
}

/**
 * Overload accepting a [org.readium.r2.shared.publication.Locator] directly.
 */
internal fun matchChapterByHref(
    locator: org.readium.r2.shared.publication.Locator,
    allLinks: List<Link>
): ChapterMatch = matchChapterByHref(locator.href.toString(), allLinks)
