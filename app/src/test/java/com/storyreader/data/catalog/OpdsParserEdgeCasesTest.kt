package com.storyreader.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Edge-case tests for [OpdsParser] complementing the happy-path scenarios in
 * [OpdsParserTest].
 *
 * Covers:
 *  - Missing or blank titles → default "OPDS Catalog"
 *  - JSON auto-detection without explicit content-type header
 *  - Navigation entries without an acquisition link are excluded
 *  - Publications without an acquisition link are excluded
 *  - Multiple authors are joined with a comma
 *  - Relative URL resolution against the request URL
 *  - Grouped publications (OPDS 2 groups array)
 *  - Empty navigation / publications arrays
 *  - OPDS 1 XML with epub+zip type match (no rel attribute)
 *  - OPDS 1 entry with both acquisition and navigation links: acquisition wins
 */
@RunWith(RobolectricTestRunner::class)
class OpdsParserEdgeCasesTest {

    private val parser = OpdsParser()
    private val baseUrl = "https://catalog.example.com/opds"

    // ── JSON auto-detection ──────────────────────────────────────────────────

    @Test
    fun `json format is detected from leading brace when content type is null`() {
        val body = """{"metadata":{"title":"Auto"},"navigation":[],"publications":[]}"""
        val page = parser.parse(body, baseUrl, contentType = null)
        assertEquals("Auto", page.title)
    }

    @Test
    fun `json format is detected from leading brace when content type is xml`() {
        // If the body starts with '{', JSON parser should take precedence.
        val body = """{"metadata":{"title":"JsonOverride"},"navigation":[],"publications":[]}"""
        val page = parser.parse(body, baseUrl, contentType = "application/atom+xml")
        assertEquals("JsonOverride", page.title)
    }

    // ── title fallbacks ──────────────────────────────────────────────────────

    @Test
    fun `json catalog with missing metadata title defaults to OPDS Catalog`() {
        val body = """{"navigation":[],"publications":[]}"""
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals("OPDS Catalog", page.title)
    }

    @Test
    fun `json catalog with blank title defaults to OPDS Catalog`() {
        val body = """{"metadata":{"title":"  "},"navigation":[],"publications":[]}"""
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals("OPDS Catalog", page.title)
    }

    @Test
    fun `xml feed with missing title defaults to OPDS Catalog`() {
        val body = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>A Book</title>
                <link rel="http://opds-spec.org/acquisition"
                      type="application/epub+zip"
                      href="/book.epub" />
              </entry>
            </feed>
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/atom+xml")
        assertEquals("OPDS Catalog", page.title)
    }

    // ── empty collections ────────────────────────────────────────────────────

    @Test
    fun `empty navigation and publications arrays produce no entries`() {
        val body = """{"metadata":{"title":"Empty"},"navigation":[],"publications":[]}"""
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun `xml feed with no entries produces no results`() {
        val body = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Empty Feed</title>
            </feed>
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/atom+xml")
        assertEquals("Empty Feed", page.title)
        assertTrue(page.entries.isEmpty())
    }

    // ── publications without acquisition link ────────────────────────────────

    @Test
    fun `json publication without acquisition link is excluded`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "publications": [
                {
                  "metadata": {"title": "No Link Book"},
                  "links": [
                    {"href": "/thumbnail.jpg", "type": "image/jpeg"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun `xml entry with no acquisition or navigation link is excluded`() {
        val body = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Feed</title>
              <entry>
                <title>No Link Entry</title>
                <link rel="alternate" type="text/html" href="/page.html" />
              </entry>
            </feed>
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/atom+xml")
        assertTrue(page.entries.isEmpty())
    }

    // ── multiple authors ─────────────────────────────────────────────────────

    @Test
    fun `json publication with multiple authors joins them with comma`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "publications": [
                {
                  "metadata": {
                    "title": "Co-authored Book",
                    "author": [{"name": "Alice"}, {"name": "Bob"}]
                  },
                  "links": [
                    {"href": "/book.epub", "type": "application/epub+zip"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals(1, page.entries.size)
        assertEquals("Alice, Bob", page.entries[0].subtitle)
    }

    @Test
    fun `json publication with authors key instead of author is parsed`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "publications": [
                {
                  "metadata": {
                    "title": "Book",
                    "authors": [{"name": "Writer One"}]
                  },
                  "links": [
                    {"href": "/book.epub", "type": "application/epub+zip"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals("Writer One", page.entries[0].subtitle)
    }

    // ── relative URL resolution ──────────────────────────────────────────────

    @Test
    fun `relative navigation href is resolved against request url`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "navigation": [
                {
                  "title": "Science Fiction",
                  "links": [{"href": "fiction/scifi", "type": "application/opds+json"}]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, "https://catalog.example.com/opds/root", contentType = "application/opds+json")
        assertEquals("https://catalog.example.com/opds/fiction/scifi", page.entries[0].navigationUrl)
    }

    @Test
    fun `absolute href in navigation is kept as-is`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "navigation": [
                {
                  "title": "External",
                  "links": [{"href": "https://other.example.com/catalog", "type": "application/opds+json"}]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals("https://other.example.com/catalog", page.entries[0].navigationUrl)
    }

    // ── groups (OPDS 2) ──────────────────────────────────────────────────────

    @Test
    fun `grouped publications are included in results`() {
        val body = """
            {
              "metadata": {"title": "Grouped Catalog"},
              "groups": [
                {
                  "metadata": {"title": "New Arrivals"},
                  "publications": [
                    {
                      "metadata": {"title": "Fresh Book"},
                      "links": [
                        {"href": "/new.epub", "type": "application/epub+zip"}
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals(1, page.entries.size)
        assertFalse(page.entries[0].isNavigation)
        assertEquals("Fresh Book", page.entries[0].title)
    }

    @Test
    fun `groups and top-level publications are combined`() {
        val body = """
            {
              "metadata": {"title": "Mixed Catalog"},
              "publications": [
                {
                  "metadata": {"title": "Top Level"},
                  "links": [{"href": "/top.epub", "type": "application/epub+zip"}]
                }
              ],
              "groups": [
                {
                  "publications": [
                    {
                      "metadata": {"title": "In Group"},
                      "links": [{"href": "/group.epub", "type": "application/epub+zip"}]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals(2, page.entries.size)
    }

    // ── XML acquisition via epub+zip type (no rel attribute) ────────────────

    @Test
    fun `xml entry with epub+zip type but no rel attribute is treated as acquisition`() {
        val body = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Feed</title>
              <entry>
                <title>Type-only Link</title>
                <link type="application/epub+zip" href="/book.epub" />
              </entry>
            </feed>
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/atom+xml")
        assertEquals(1, page.entries.size)
        assertFalse(page.entries[0].isNavigation)
        assertTrue(page.entries[0].acquisitionUrl?.endsWith("/book.epub") == true)
    }

    // ── navigation entries with fallback href field (OPDS 2) ─────────────────

    @Test
    fun `navigation entry with top-level href instead of links array`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "navigation": [
                {
                  "title": "Section",
                  "href": "/section"
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals(1, page.entries.size)
        assertTrue(page.entries[0].isNavigation)
        assertEquals("https://catalog.example.com/section", page.entries[0].navigationUrl)
    }

    // ── untitled entries get a default title ─────────────────────────────────

    @Test
    fun `json navigation entry with blank title falls back to Untitled`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "navigation": [
                {
                  "title": "",
                  "links": [{"href": "/empty-title"}]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals("Untitled", page.entries[0].title)
    }

    // ── acquisition rel as string vs array ───────────────────────────────────

    @Test
    fun `json publication with rel as plain string is parsed`() {
        val body = """
            {
              "metadata": {"title": "Catalog"},
              "publications": [
                {
                  "metadata": {"title": "String Rel Book"},
                  "links": [
                    {
                      "href": "/book.epub",
                      "type": "application/epub+zip",
                      "rel": "http://opds-spec.org/acquisition"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val page = parser.parse(body, baseUrl, contentType = "application/opds+json")
        assertEquals(1, page.entries.size)
        assertFalse(page.entries[0].isNavigation)
    }
}
