package com.storyreader.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EpubSanitizerTest {

    @Test
    fun `well-formed XHTML returns null`() {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Test</title></head>
            <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        assertNull(EpubSanitizer.sanitizeXhtml(html))
    }

    @Test
    fun `missing closing head tag is fixed`() {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Test</title>
            <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        assert(result!!.contains("</head>")) { "Should contain </head>" }
        assert(result.contains("<body")) { "Should still contain <body>" }
    }

    @Test
    fun `missing head element entirely is fixed`() {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        assert(result!!.contains("<head>")) { "Should contain <head>" }
        assert(result.contains("</head>")) { "Should contain </head>" }
    }

    @Test
    fun `missing body element is fixed`() {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Test</title></head>
            <p>Hello</p>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        assert(result!!.contains("<body>")) { "Should contain <body>" }
        assert(result.contains("</body>")) { "Should contain </body>" }
    }

    @Test
    fun `missing xml declaration is added`() {
        val html = """
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Test</title></head>
            <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        assert(result!!.startsWith("<?xml")) { "Should start with XML declaration" }
    }

    @Test
    fun `FanFicFare style cover is handled`() {
        // Single-line HTML like FanFicFare generates
        val html = "\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">" +
            "<head><title>Cover</title><style type=\"text/css\">body { margin: 0; }</style></head>" +
            "<body><div><img src=\"images/cover.jpg\" alt=\"cover\"/></div></body></html>"
        val result = EpubSanitizer.sanitizeXhtml(html)
        // Should add XML declaration since it's missing
        assertNotNull(result)
        assert(result!!.contains("<?xml")) { "Should have XML declaration" }
        assert(result.contains("</head>")) { "Should preserve </head>" }
    }

    @Test
    fun `head with no closing tag and no body`() {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Test</title>
            <p>Content here</p>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        assert(result!!.contains("</head>")) { "Should contain </head>" }
        assert(result.contains("<body>")) { "Should contain <body>" }
    }

    @Test
    fun `self-closing head tag is expanded`() {
        // Story Manager's ebooklib serializes empty head as <head/>
        val html = """
            <?xml version='1.0' encoding='utf-8'?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="en" xml:lang="en">
              <head/>
              <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        assert(!result!!.contains("<head/>")) { "Should not contain <head/>" }
        assert(result.contains("<head></head>")) { "Should contain <head></head>" }
        assert(result.contains("<body>")) { "Should preserve <body>" }
    }

    @Test
    fun `self-closing head with space is expanded`() {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head />
            <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        assert(!result!!.contains("<head />")) { "Should not contain <head />" }
        assert(result.contains("<head></head>")) { "Should contain <head></head>" }
    }

    @Test
    fun `preserves existing xml declaration`() {
        val html = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Test</title>
            <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        val result = EpubSanitizer.sanitizeXhtml(html)
        assertNotNull(result)
        val xmlDeclCount = Regex("""<\?xml""").findAll(result!!).count()
        assertEquals("Should have exactly one XML declaration", 1, xmlDeclCount)
    }
}
