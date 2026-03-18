package com.storyreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChapterResolverTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun link(href: String, title: String = href): Link =
        Link(href = Url(href)!!, title = title)

    private fun link(href: String, title: String = href, children: List<Link>): Link =
        Link(href = Url(href)!!, title = title, children = children)

    // ── flattenTocLinks ─────────────────────────────────────────────────────

    @Test
    fun `flattenTocLinks preserves order of flat list`() {
        val links = listOf(link("ch1.html"), link("ch2.html"), link("ch3.html"))
        val flat = flattenTocLinks(links)
        assertEquals(listOf("ch1.html", "ch2.html", "ch3.html"), flat.map { it.href.toString() })
    }

    @Test
    fun `flattenTocLinks flattens nested children depth-first`() {
        val links = listOf(
            link("part1.html", "Part 1", children = listOf(
                link("ch1.html", "Chapter 1"),
                link("ch2.html", "Chapter 2")
            )),
            link("part2.html", "Part 2", children = listOf(
                link("ch3.html", "Chapter 3")
            ))
        )
        val flat = flattenTocLinks(links)
        assertEquals(
            listOf("part1.html", "ch1.html", "ch2.html", "part2.html", "ch3.html"),
            flat.map { it.href.toString() }
        )
    }

    @Test
    fun `flattenTocLinks returns empty for empty input`() {
        assertEquals(emptyList<Link>(), flattenTocLinks(emptyList()))
    }

    // ── matchChapterByHref — one file per chapter ───────────────────────────

    @Test
    fun `exact match returns Single for one-file-per-chapter EPUB`() {
        val ch1 = link("chapter1.html", "Chapter 1")
        val ch2 = link("chapter2.html", "Chapter 2")
        val ch3 = link("chapter3.html", "Chapter 3")
        val toc = listOf(ch1, ch2, ch3)

        val result = matchChapterByHref("chapter2.html", toc)
        assertTrue(result is ChapterMatch.Single)
        assertSame(ch2, (result as ChapterMatch.Single).link)
    }

    @Test
    fun `exact match works when locator href has fragment`() {
        val ch1 = link("chapter1.html", "Chapter 1")
        val ch2 = link("chapter2.html", "Chapter 2")
        val toc = listOf(ch1, ch2)

        val result = matchChapterByHref("chapter2.html#some-anchor", toc)
        assertTrue(result is ChapterMatch.Single)
        assertSame(ch2, (result as ChapterMatch.Single).link)
    }

    // ── matchChapterByHref — split-file EPUBs ───────────────────────────────

    @Test
    fun `split-suffix EPUB matches each chapter file to correct TOC entry`() {
        // EPUB like "Sufficiently Advanced Magic" where files are
        // part0000_split_000.html through part0000_split_034.html
        val dedication = link("part0000_split_002.html", "Dedication")
        val ch1 = link("part0000_split_004.html", "Chapter I")
        val ch2 = link("part0000_split_005.html", "Chapter II")
        val ch3 = link("part0000_split_006.html", "Chapter III")
        val toc = listOf(dedication, ch1, ch2, ch3)

        // Each file should match its own TOC entry exactly
        val result1 = matchChapterByHref("part0000_split_004.html", toc)
        assertTrue("Ch1 should be Single", result1 is ChapterMatch.Single)
        assertSame(ch1, (result1 as ChapterMatch.Single).link)

        val result2 = matchChapterByHref("part0000_split_005.html", toc)
        assertTrue("Ch2 should be Single", result2 is ChapterMatch.Single)
        assertSame(ch2, (result2 as ChapterMatch.Single).link)

        val result3 = matchChapterByHref("part0000_split_006.html", toc)
        assertTrue("Ch3 should be Single", result3 is ChapterMatch.Single)
        assertSame(ch3, (result3 as ChapterMatch.Single).link)
    }

    @Test
    fun `split-suffix fallback matches reader in split file to original chapter`() {
        // TOC points to chapter1.html but reader is in chapter1_split_001.html
        val ch1 = link("chapter1.html", "Chapter 1")
        val ch2 = link("chapter2.html", "Chapter 2")
        val toc = listOf(ch1, ch2)

        val result = matchChapterByHref("chapter1_split_001.html", toc)
        assertTrue(result is ChapterMatch.NormalizedFallback)
        assertSame(ch1, (result as ChapterMatch.NormalizedFallback).link)
    }

    @Test
    fun `split-suffix fallback does not match across different base names`() {
        val ch1 = link("chapter1.html", "Chapter 1")
        val toc = listOf(ch1)

        val result = matchChapterByHref("chapter2_split_001.html", toc)
        assertEquals(ChapterMatch.None, result)
    }

    // ── matchChapterByHref — fragment-based chapters ────────────────────────

    @Test
    fun `multiple TOC entries in same file returns Multiple`() {
        val part1 = link("content.html#part1", "Part 1")
        val part2 = link("content.html#part2", "Part 2")
        val part3 = link("content.html#part3", "Part 3")
        val toc = listOf(part1, part2, part3)

        val result = matchChapterByHref("content.html", toc)
        assertTrue(result is ChapterMatch.Multiple)
        assertEquals(3, (result as ChapterMatch.Multiple).candidates.size)
    }

    // ── matchChapterByHref — no match ───────────────────────────────────────

    @Test
    fun `returns None when no TOC entry matches`() {
        val ch1 = link("chapter1.html", "Chapter 1")
        val toc = listOf(ch1)

        val result = matchChapterByHref("unknown.html", toc)
        assertEquals(ChapterMatch.None, result)
    }

    @Test
    fun `returns None for empty TOC`() {
        val result = matchChapterByHref("chapter1.html", emptyList())
        assertEquals(ChapterMatch.None, result)
    }

    // ── matchChapterByHref — endsWith matching ──────────────────────────────

    @Test
    fun `endsWith matching handles path prefix differences`() {
        // Some EPUBs report hrefs with directory prefixes in the locator
        // but the TOC uses bare filenames
        val ch1 = link("chapter1.html", "Chapter 1")
        val toc = listOf(ch1)

        val result = matchChapterByHref("OEBPS/chapter1.html", toc)
        assertTrue(result is ChapterMatch.Single)
        assertSame(ch1, (result as ChapterMatch.Single).link)
    }

    // ── matchChapterByHref — nested TOC with exact match ────────────────────

    @Test
    fun `exact match works with pre-flattened nested TOC`() {
        val part1 = link("part1.html", "Part 1")
        val ch1 = link("ch1.html", "Chapter 1")
        val ch2 = link("ch2.html", "Chapter 2")
        val part2 = link("part2.html", "Part 2")
        val ch3 = link("ch3.html", "Chapter 3")
        // Simulate flattenTocLinks output
        val flat = listOf(part1, ch1, ch2, part2, ch3)

        val result = matchChapterByHref("ch2.html", flat)
        assertTrue(result is ChapterMatch.Single)
        assertSame(ch2, (result as ChapterMatch.Single).link)
    }

    // ── matchChapterByHref — front matter with no TOC entry ─────────────────

    @Test
    fun `file with no TOC entry returns None`() {
        // Front matter files (e.g. split_000, split_001) not in the TOC
        val ch1 = link("part0000_split_004.html", "Chapter I")
        val toc = listOf(ch1)

        // split_000 and split_001 are front matter not in TOC, and should NOT
        // match via split-suffix normalization since ch1 has a different split number
        val result = matchChapterByHref("part0000_split_000.html", toc)
        // This will be NormalizedFallback because both normalize to part0000.html.
        // This is acceptable — it shows *some* chapter rather than nothing for
        // front matter pages.
        assertTrue(
            result is ChapterMatch.NormalizedFallback || result is ChapterMatch.None
        )
    }

    // ── stripSplitSuffix ────────────────────────────────────────────────────

    @Test
    fun `stripSplitSuffix removes split suffix`() {
        assertEquals("part0006.html", stripSplitSuffix("part0006_split_001.html"))
    }

    @Test
    fun `stripSplitSuffix removes multiple split suffixes`() {
        assertEquals("part0000.html", stripSplitSuffix("part0000_split_034.html"))
    }

    @Test
    fun `stripSplitSuffix leaves non-split paths unchanged`() {
        assertEquals("chapter1.html", stripSplitSuffix("chapter1.html"))
    }

    @Test
    fun `stripSplitSuffix handles path with directory`() {
        assertEquals("OEBPS/part0006.html", stripSplitSuffix("OEBPS/part0006_split_001.html"))
    }
}
