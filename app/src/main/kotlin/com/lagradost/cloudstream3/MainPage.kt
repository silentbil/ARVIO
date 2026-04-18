package com.lagradost.cloudstream3

data class MainPageRequest(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

data class HomePageList(
    val name: String,
    val list: List<SearchResponse>,
    val isHorizontalImages: Boolean = false
)

data class HomePageResponse(
    val items: List<HomePageList>,
    val hasNext: Boolean = false
)

fun mainPage(data: String, name: String, horizontalImages: Boolean = false): MainPageRequest =
    MainPageRequest(name = name, data = data, horizontalImages = horizontalImages)

fun mainPageOf(vararg elements: MainPageRequest): List<MainPageRequest> = elements.toList()

/** Used by plugins: `mainPageOf("url1" to "Name1", "url2" to "Name2")`. */
fun mainPageOf(vararg elements: Pair<String, String>): List<MainPageRequest> =
    elements.map { (data, name) -> MainPageRequest(name = name, data = data) }
