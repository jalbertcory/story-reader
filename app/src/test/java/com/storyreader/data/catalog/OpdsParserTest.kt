package com.storyreader.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpdsParserTest {

    private val parser = OpdsParser()

    @Test
    fun `parses opds2 navigation and publications`() {
        val body = """
            {
              "metadata": { "title": "Demo Catalog" },
              "navigation": [
                {
                  "title": "Fiction",
                  "links": [{ "href": "/fiction", "type": "application/opds+json" }]
                }
              ],
              "publications": [
                {
                  "metadata": {
                    "title": "Sample Book",
                    "author": [{ "name": "A. Writer" }]
                  },
                  "links": [
                    {
                      "href": "/books/sample.epub",
                      "type": "application/epub+zip",
                      "rel": ["http://opds-spec.org/acquisition"]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val page = parser.parse(body, "https://catalog.example.com/root.json", "application/opds+json")

        assertEquals("Demo Catalog", page.title)
        assertEquals(2, page.entries.size)
        assertTrue(page.entries.first().isNavigation)
        assertEquals("https://catalog.example.com/fiction", page.entries.first().navigationUrl)
        assertFalse(page.entries.last().isNavigation)
        assertEquals("https://catalog.example.com/books/sample.epub", page.entries.last().acquisitionUrl)
        assertEquals("A. Writer", page.entries.last().subtitle)
    }

    @Test
    fun `parses opds1 acquisition feed`() {
        val body = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Acquisition Feed</title>
              <entry>
                <title>Sample EPUB</title>
                <author><name>Writer One</name></author>
                <link rel="http://opds-spec.org/acquisition"
                      type="application/epub+zip"
                      href="/downloads/sample.epub" />
              </entry>
              <entry>
                <title>Series</title>
                <link rel="subsection"
                      type="application/atom+xml;profile=opds-catalog"
                      href="/series" />
              </entry>
            </feed>
        """.trimIndent()

        val page = parser.parse(body, "https://catalog.example.com/root.xml", "application/atom+xml")

        assertEquals("Acquisition Feed", page.title)
        assertEquals(2, page.entries.size)
        assertFalse(page.entries.first().isNavigation)
        assertEquals("https://catalog.example.com/downloads/sample.epub", page.entries.first().acquisitionUrl)
        assertTrue(page.entries.last().isNavigation)
        assertEquals("https://catalog.example.com/series", page.entries.last().navigationUrl)
    }
}
