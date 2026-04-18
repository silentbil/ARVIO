package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Clean-room `AppUtils` — real CloudStream exposes a rag-bag of helpers here.
 * We implement the subset observable in published plugin dex: `parseJson`,
 * `tryParseJson`, `toJson`. Plugins use these around the jackson mapper.
 */
object AppUtils {
    @JvmStatic
    val jacksonMapper: ObjectMapper = jacksonObjectMapper()

    @JvmStatic
    inline fun <reified T> parseJson(json: String): T =
        jacksonMapper.readValue(json, object : TypeReference<T>() {})

    @JvmStatic
    inline fun <reified T> tryParseJson(json: String?): T? =
        if (json.isNullOrBlank()) null else runCatching {
            jacksonMapper.readValue(json, object : TypeReference<T>() {})
        }.getOrNull()

    @JvmStatic
    fun toJson(value: Any?): String = jacksonMapper.writeValueAsString(value)
}
