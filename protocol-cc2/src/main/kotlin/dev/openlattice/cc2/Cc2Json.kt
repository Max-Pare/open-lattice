package dev.openlattice.cc2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal val Cc2Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/** Keys whose value is replaced wholesale by a delta instead of being deep-merged. */
private val REPLACE_KEYS = setOf("exception_code", "exception_status")

/**
 * Applies a method-6000 status delta to the cached full status.
 * Objects merge recursively; arrays, primitives and [REPLACE_KEYS] replace.
 */
internal fun JsonObject.mergeDelta(delta: JsonObject): JsonObject {
    val out = toMutableMap()
    for ((key, value) in delta) {
        val old = out[key]
        out[key] = if (key !in REPLACE_KEYS && old is JsonObject && value is JsonObject) old.mergeDelta(value) else value
    }
    return JsonObject(out)
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal operator fun JsonObject?.div(key: String): JsonElement? = this?.get(key)

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.booleanOrNull?.let { b -> if (b) 1.0 else 0.0 } }
internal fun JsonElement?.lng(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonElement?.int(): Int? = lng()?.toInt()
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }
