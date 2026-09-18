// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reading someone else's JSON without trusting its shape.
 *
 * `JsonNull` is a value, not a Kotlin `null`, so `element?.jsonObject` does NOT protect: the safe call passes JsonNull
 * through and the cast throws «Element class kotlinx.serialization.json.JsonNull is not a JsonObject». Every wire we
 * read sends explicit nulls as a matter of course — an OpenAI-compatible stream carries `"usage": null` in every chunk
 * until the last, ACP answers `{"result": null}` where a result is optional — so this shape of bug ends a turn with a
 * stack trace in the feed (caught on two machines 18.09.2026).
 *
 * [obj] and [arr] answer null for anything that is not that kind, JsonNull included. Prefer them everywhere a payload
 * comes from outside; `.jsonObject` stays legal only right after parsing our own file inside `runCatching`.
 */
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
