/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network.service

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface SuggestService {
  /**
   * Fetches an autocomplete document from whichever engine's endpoint [url] names. The engines
   * disagree on the response shape, so the body comes back raw and [SuggestParser] reads it.
   */
  @GET
  suspend fun fetch(@Url url: String): Response<ResponseBody>
}
