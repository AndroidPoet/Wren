/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network

import com.google.common.truth.Truth.assertThat
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.retrofit.adapters.ApiResponseCallAdapterFactory
import dev.androidpoet.wren.core.model.SearchEngine
import dev.androidpoet.wren.core.model.SearchEngine.SuggestFormat
import dev.androidpoet.wren.core.network.service.SuggestParser
import dev.androidpoet.wren.core.network.service.SuggestService
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SuggestServiceTest {

  private lateinit var server: MockWebServer
  private lateinit var service: SuggestService

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    service = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .client(OkHttpClient.Builder().build())
      .addConverterFactory(MoshiConverterFactory.create())
      .addCallAdapterFactory(ApiResponseCallAdapterFactory.create())
      .build()
      .create(SuggestService::class.java)
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `duckduckgo phrases are parsed in order`() {
    val phrases = SuggestParser.parse(
      SuggestFormat.PHRASE_OBJECTS,
      """[{"phrase":"kotlin flow"},{"phrase":"kotlin coroutines"}]""",
    )

    assertThat(phrases).containsExactly("kotlin flow", "kotlin coroutines").inOrder()
  }

  @Test
  fun `opensearch suggestions are the second element`() {
    val phrases = SuggestParser.parse(
      SuggestFormat.OPENSEARCH,
      """["kot",["kotlin","kotlin flow"],["",""],["https://a","https://b"]]""",
    )

    assertThat(phrases).containsExactly("kotlin", "kotlin flow").inOrder()
  }

  @Test
  fun `junk never throws on the keystroke path`() {
    assertThat(SuggestParser.parse(SuggestFormat.OPENSEARCH, "<html>")).isEmpty()
    assertThat(SuggestParser.parse(SuggestFormat.OPENSEARCH, """{"a":1}""")).isEmpty()
    assertThat(SuggestParser.parse(SuggestFormat.PHRASE_OBJECTS, """[1,2,{"x":"y"}]""")).isEmpty()
  }

  @Test
  fun `the query is url-encoded into the engine's own template`() = runTest {
    server.enqueue(MockResponse.Builder().code(200).body("[]").build())
    val url = server.url("/ac/").toString() + "?q=%s&kl=wt-wt"

    service.fetch(url.replace("%s", "privacy%20first"))
    val request = server.takeRequest()

    assertThat(request.target).contains("q=privacy%20first")
    assertThat(request.target).contains("kl=wt-wt")
  }

  @Test
  fun `a server error surfaces as a failure rather than an exception`() = runTest {
    server.enqueue(MockResponse.Builder().code(500).body("").build())

    val response = service.fetch(server.url("/ac/?q=kotlin").toString())

    assertThat(response).isInstanceOf(ApiResponse.Failure::class.java)
  }

  @Test
  fun `every engine's suggest template carries the query placeholder over https`() {
    SearchEngine.entries.forEach { engine ->
      assertThat(engine.suggestTemplate).startsWith("https://")
      assertThat(engine.suggestTemplate).contains("%s")
    }
  }
}
