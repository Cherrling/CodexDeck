package cc.cherr.codexdeck.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.string(name: String): String? =
    this[name]?.takeUnless { it is JsonNull }?.runCatching { jsonPrimitive.contentOrNull }?.getOrNull()

internal fun JsonObject.long(name: String): Long? =
    this[name]?.takeUnless { it is JsonNull }?.runCatching { jsonPrimitive.longOrNull }?.getOrNull()

internal fun JsonObject.int(name: String): Int? =
    this[name]?.takeUnless { it is JsonNull }?.runCatching { jsonPrimitive.intOrNull }?.getOrNull()

internal fun JsonObject.boolean(name: String, default: Boolean = false): Boolean =
    this[name]?.takeUnless { it is JsonNull }?.runCatching { jsonPrimitive.booleanOrNull }?.getOrNull()
        ?: default

internal fun JsonObject.double(name: String): Double? =
    this[name]?.takeUnless { it is JsonNull }?.runCatching { jsonPrimitive.doubleOrNull }?.getOrNull()

internal fun JsonObject.obj(name: String): JsonObject? =
    this[name]?.takeUnless { it is JsonNull }?.runCatching { jsonObject }?.getOrNull()

internal fun JsonObject.array(name: String): List<JsonElement> =
    this[name]?.takeUnless { it is JsonNull }?.runCatching { (this as JsonArray) }?.getOrNull().orEmpty()

internal fun JsonElement.asObject(): JsonObject? =
    takeUnless { it is JsonNull }?.runCatching { jsonObject }?.getOrNull()

internal fun String.toJsonPrimitive(): JsonPrimitive = JsonPrimitive(this)
