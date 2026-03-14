package com.storyreader.data.catalog

data class OpdsCredentials(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val isStoryManagerBackend: Boolean = false
)

data class OpdsCatalogEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val isNavigation: Boolean,
    val navigationUrl: String? = null,
    val acquisitionUrl: String? = null
)

data class OpdsCatalogPage(
    val title: String,
    val entries: List<OpdsCatalogEntry>
)
