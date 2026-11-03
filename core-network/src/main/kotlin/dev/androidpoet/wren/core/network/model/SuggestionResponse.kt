/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * One entry of the DuckDuckGo autocomplete payload, which is a bare JSON array of
 * `{"phrase": "..."}` objects.
 */
@JsonClass(generateAdapter = true)
data class SuggestionResponse(
  @Json(name = "phrase") val phrase: String,
)
