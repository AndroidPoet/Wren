/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.UrlFormatter
import dev.androidpoet.wren.core.model.SearchEngine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Address-or-search is the decision the omnibox gets wrong most visibly, so both directions are
 * pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class UrlFormatterTest {

  @Test
  fun `bare host is treated as an address`() {
    assertThat(UrlFormatter.toUrlOrSearch("example.com", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://example.com")
  }

  @Test
  fun `host with path is treated as an address`() {
    assertThat(UrlFormatter.toUrlOrSearch("example.com/a/b?c=d", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://example.com/a/b?c=d")
  }

  @Test
  fun `explicit scheme is left alone`() {
    assertThat(UrlFormatter.toUrlOrSearch("http://example.com", SearchEngine.DUCKDUCKGO))
      .isEqualTo("http://example.com")
  }

  @Test
  fun `localhost is an address even without a dot`() {
    assertThat(UrlFormatter.toUrlOrSearch("localhost:8080", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://localhost:8080")
  }

  @Test
  fun `words with spaces are a search`() {
    assertThat(UrlFormatter.toUrlOrSearch("kotlin flow test", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://duckduckgo.com/?q=kotlin+flow+test")
  }

  @Test
  fun `single word without a dot is a search, not a host`() {
    assertThat(UrlFormatter.toUrlOrSearch("recipes", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://duckduckgo.com/?q=recipes")
  }

  @Test
  fun `search goes to the configured engine`() {
    assertThat(UrlFormatter.toUrlOrSearch("privacy", SearchEngine.MOJEEK))
      .isEqualTo("https://www.mojeek.com/search?q=privacy")
  }

  @Test
  fun `non web schemes are recognised for hand-off`() {
    assertThat(UrlFormatter.isNonWebScheme("mailto:someone@example.com")).isTrue()
    assertThat(UrlFormatter.isNonWebScheme("https://example.com")).isFalse()
  }

  @Test
  fun `an address typed with padding is still an address`() {
    assertThat(UrlFormatter.toUrlOrSearch("  example.com  ", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://example.com")
  }

  @Test
  fun `a bare ipv4 address with a port is an address`() {
    assertThat(UrlFormatter.toUrlOrSearch("10.0.0.1:8080", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://10.0.0.1:8080")
  }

  @Test
  fun `an empty omnibox goes to the new tab page rather than searching for nothing`() {
    assertThat(UrlFormatter.toUrlOrSearch("   ", SearchEngine.DUCKDUCKGO))
      .isEqualTo(UrlFormatter.NEW_TAB_URL)
  }

  @Test
  fun `about blank is left alone`() {
    assertThat(UrlFormatter.toUrlOrSearch("about:blank", SearchEngine.DUCKDUCKGO))
      .isEqualTo("about:blank")
  }

  @Test
  fun `typed javascript url becomes a search`() {
    val typed = "javascript:alert(document.cookie)"

    assertThat(UrlFormatter.toUrlOrSearch(typed, SearchEngine.DUCKDUCKGO))
      .startsWith("https://duckduckgo.com/?q=javascript%3Aalert")
    assertThat(UrlFormatter.toUrlOrSearch(" JavaScript://%0aalert(1)", SearchEngine.DUCKDUCKGO))
      .startsWith("https://duckduckgo.com/?q=JavaScript%3A%2F%2F")
  }

  @Test
  fun `typed data file content and blob urls become a search`() {
    val typed =
      listOf("data:text/html,hi", "file:///etc/hosts", "content://media/1", "blob:https://a.b/x")

    for (text in typed) {
      assertThat(UrlFormatter.toUrlOrSearch(text, SearchEngine.DUCKDUCKGO))
        .startsWith("https://duckduckgo.com/?q=")
    }
  }

  @Test
  fun `a sentence containing a dot is a search, not a host`() {
    assertThat(UrlFormatter.toUrlOrSearch("what is node.js used for", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://duckduckgo.com/?q=what+is+node.js+used+for")
  }

  @Test
  fun `a query is percent-encoded, so a search never becomes a different search`() {
    assertThat(UrlFormatter.toUrlOrSearch("c++ & rust?", SearchEngine.DUCKDUCKGO))
      .isEqualTo("https://duckduckgo.com/?q=c%2B%2B+%26+rust%3F")
  }

  @Test
  fun `a custom engine template receives the query the same way`() {
    assertThat(UrlFormatter.toUrlOrSearch("kotlin", "https://example.com/find?s=%s"))
      .isEqualTo("https://example.com/find?s=kotlin")
  }

  @Test
  fun `security is judged on the scheme actually in use`() {
    assertThat(UrlFormatter.isSecure("https://example.com")).isTrue()
    assertThat(UrlFormatter.isSecure("http://example.com")).isFalse()
  }

  @Test
  fun `pretty host drops the scheme and www`() {
    assertThat(UrlFormatter.prettyHost("https://www.example.com/a/b")).isEqualTo("example.com")
    assertThat(UrlFormatter.prettyHost(UrlFormatter.NEW_TAB_URL)).isEmpty()
  }

  @Test
  fun `pretty host names the real host, never the credentials in front of it`() {
    assertThat(
      UrlFormatter.prettyHost("https://bank.com@evil.example/login"),
    ).isEqualTo("evil.example")
    assertThat(UrlFormatter.prettyHost("https://a:b@www.example.com#x")).isEqualTo("example.com")
  }

  @Test
  fun `a custom search template with a stray percent still searches`() {
    val url = UrlFormatter.search("kotlin flow", "https://x.example/s?q=%s&ratio=100%")
    assertThat(url).isEqualTo("https://x.example/s?q=kotlin+flow&ratio=100%")
  }

  @Test
  fun `only web schemes are accepted from other apps`() {
    assertThat(UrlFormatter.isWebUrl("https://example.com")).isTrue()
    assertThat(UrlFormatter.isWebUrl(" HTTP://example.com ")).isTrue()
    assertThat(UrlFormatter.isWebUrl("javascript:alert(1)")).isFalse()
    assertThat(UrlFormatter.isWebUrl("file:///android_asset/x")).isFalse()
    assertThat(UrlFormatter.isWebUrl("content://media/1")).isFalse()
  }

  @Test
  fun `script and data urls are recognised regardless of case`() {
    assertThat(UrlFormatter.isScriptOrDataUrl("JavaScript:void(0)")).isTrue()
    assertThat(UrlFormatter.isScriptOrDataUrl("data:text/html,hi")).isTrue()
    assertThat(UrlFormatter.isScriptOrDataUrl("https://data.example")).isFalse()
  }

  @Test
  fun `schemes the engine cannot render belong to another app`() {
    assertThat(UrlFormatter.isExternalScheme("whatsapp://send?text=hi")).isTrue()
    assertThat(UrlFormatter.isExternalScheme("intent://x/#Intent;end")).isTrue()
    assertThat(UrlFormatter.isExternalScheme("MAILTO:a@b.c")).isTrue()
    assertThat(UrlFormatter.isExternalScheme("https://example.com")).isFalse()
    assertThat(UrlFormatter.isExternalScheme("about:blank")).isFalse()
    assertThat(UrlFormatter.isExternalScheme("blob:https://example.com/1")).isFalse()
    assertThat(UrlFormatter.isExternalScheme("data:text/html,x")).isFalse()
    assertThat(UrlFormatter.isExternalScheme("example.com/path")).isFalse()
  }

  @Test
  fun `a tab loads web addresses and the new tab page, nothing else`() {
    assertThat(UrlFormatter.mayLoad("https://example.com")).isTrue()
    assertThat(UrlFormatter.mayLoad("about:blank")).isTrue()
    assertThat(UrlFormatter.mayLoad("file:///android_asset/x.html")).isFalse()
    assertThat(UrlFormatter.mayLoad("content://downloads/1")).isFalse()
    assertThat(UrlFormatter.mayLoad("javascript:alert(1)")).isFalse()
  }
}
