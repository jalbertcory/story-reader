package com.storyreader.data.catalog

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

class OpdsParser {

    fun parse(body: String, requestUrl: String, contentType: String?): OpdsCatalogPage {
        val trimmed = body.trimStart()
        return when {
            contentType?.contains("json", ignoreCase = true) == true || trimmed.startsWith("{") -> {
                parseJson(trimmed, requestUrl)
            }
            else -> parseXml(body, requestUrl)
        }
    }

    private fun parseJson(body: String, requestUrl: String): OpdsCatalogPage {
        val root = JSONObject(body)
        val title = root.optJSONObject("metadata")?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?: "OPDS Catalog"
        val entries = buildList {
            addNavigationEntries(root.optJSONArray("navigation"), requestUrl)
            addPublicationEntries(root.optJSONArray("publications"), requestUrl)
            addGroupedPublicationEntries(root.optJSONArray("groups"), requestUrl)
        }
        return OpdsCatalogPage(title = title, entries = entries)
    }

    private fun MutableList<OpdsCatalogEntry>.addNavigationEntries(items: JSONArray?, requestUrl: String) {
        if (items == null) return
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val href = item.optJSONArray("links")
                ?.firstHref()
                ?: item.optString("href").takeIf { it.isNotBlank() }
                ?: continue
            add(
                OpdsCatalogEntry(
                    id = "nav:$index:$href",
                    title = item.optString("title").ifBlank { "Untitled" },
                    subtitle = null,
                    isNavigation = true,
                    navigationUrl = resolveUrl(requestUrl, href)
                )
            )
        }
    }

    private fun MutableList<OpdsCatalogEntry>.addPublicationEntries(items: JSONArray?, requestUrl: String) {
        if (items == null) return
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val metadata = item.optJSONObject("metadata")
            val title = metadata?.optString("title").orEmpty().ifBlank { "Untitled" }
            val author = metadata?.optJSONArray("author")?.joinNames()
                ?: metadata?.optJSONArray("authors")?.joinNames()
            val acquisitionLink = item.optJSONArray("links")?.findAcquisitionHref()
            if (acquisitionLink != null) {
                add(
                    OpdsCatalogEntry(
                        id = "pub:$index:$acquisitionLink",
                        title = title,
                        subtitle = author,
                        isNavigation = false,
                        acquisitionUrl = resolveUrl(requestUrl, acquisitionLink)
                    )
                )
            }
        }
    }

    private fun MutableList<OpdsCatalogEntry>.addGroupedPublicationEntries(groups: JSONArray?, requestUrl: String) {
        if (groups == null) return
        for (index in 0 until groups.length()) {
            val group = groups.optJSONObject(index) ?: continue
            addPublicationEntries(group.optJSONArray("publications"), requestUrl)
            addNavigationEntries(group.optJSONArray("navigation"), requestUrl)
        }
    }

    private fun parseXml(body: String, requestUrl: String): OpdsCatalogPage {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
        val feed = document.documentElement
        val title = feed.childElements("title").firstOrNull()?.textContent?.trim().orEmpty().ifBlank {
            "OPDS Catalog"
        }
        val entries = feed.childElements("entry").mapNotNullIndexed { index, entry ->
            parseXmlEntry(entry, requestUrl, index)
        }
        return OpdsCatalogPage(title = title, entries = entries)
    }

    private fun parseXmlEntry(entry: Element, requestUrl: String, index: Int): OpdsCatalogEntry? {
        val title = entry.childElements("title").firstOrNull()?.textContent?.trim().orEmpty().ifBlank {
            "Untitled"
        }
        val subtitle = entry.childElements("author")
            .mapNotNull { author -> author.childElements("name").firstOrNull()?.textContent?.trim() }
            .firstOrNull()

        val links = entry.childElements("link")
        val acquisition = links.firstNotNullOfOrNull { link ->
            val rel = link.getAttribute("rel")
            val type = link.getAttribute("type")
            if (
                rel.contains("acquisition") ||
                type.contains("application/epub+zip")
            ) {
                link.getAttribute("href").takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
        if (acquisition != null) {
            return OpdsCatalogEntry(
                id = "pub:$index:$acquisition",
                title = title,
                subtitle = subtitle,
                isNavigation = false,
                acquisitionUrl = resolveUrl(requestUrl, acquisition)
            )
        }

        val navigation = links.firstNotNullOfOrNull { link ->
            val rel = link.getAttribute("rel")
            val type = link.getAttribute("type")
            if (
                rel.contains("subsection") ||
                rel.contains("collection") ||
                type.contains("application/atom+xml") ||
                type.contains("application/opds+json")
            ) {
                link.getAttribute("href").takeIf { it.isNotBlank() }
            } else {
                null
            }
        } ?: return null

        return OpdsCatalogEntry(
            id = "nav:$index:$navigation",
            title = title,
            subtitle = subtitle,
            isNavigation = true,
            navigationUrl = resolveUrl(requestUrl, navigation)
        )
    }

    private fun resolveUrl(requestUrl: String, href: String): String {
        val absolute = requestUrl.toHttpUrlOrNull()?.resolve(href)?.toString()
        return absolute ?: href
    }

    private fun JSONArray.firstHref(): String? {
        for (index in 0 until length()) {
            val value = optJSONObject(index)?.optString("href").orEmpty()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun JSONArray.findAcquisitionHref(): String? {
        for (index in 0 until length()) {
            val link = optJSONObject(index) ?: continue
            val href = link.optString("href")
            val type = link.optString("type")
            val rels = link.optJSONArray("rel")
            val relValue = when {
                rels != null && rels.length() > 0 -> buildString {
                    for (relIndex in 0 until rels.length()) {
                        append(rels.optString(relIndex))
                        append(' ')
                    }
                }
                else -> link.optString("rel")
            }
            if (
                href.isNotBlank() && (
                    type.contains("application/epub+zip") ||
                        relValue.contains("acquisition")
                    )
            ) {
                return href
            }
        }
        return null
    }

    private fun JSONArray.joinNames(): String? {
        val names = buildList {
            for (index in 0 until length()) {
                val value = optJSONObject(index)?.optString("name").orEmpty().trim()
                if (value.isNotBlank()) add(value)
            }
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString()
    }
}

private fun Element.childElements(localName: String): List<Element> {
    val items = mutableListOf<Element>()
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType == Node.ELEMENT_NODE) {
            val element = child as Element
            val name = element.localName ?: element.tagName.substringAfterLast(':')
            if (name == localName) {
                items += element
            }
        }
    }
    return items
}

private inline fun <T, R : Any> Iterable<T>.mapNotNullIndexed(transform: (index: Int, T) -> R?): List<R> {
    val result = mutableListOf<R>()
    forEachIndexed { index, value ->
        transform(index, value)?.let(result::add)
    }
    return result
}
