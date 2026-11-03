/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.Bangs
import org.junit.Test

/**
 * A stray "!" must never hijack a search, so the "leaves it alone" cases matter as much as the
 * hits.
 */
class BangsTest {

  @Test
  fun `a leading bang goes to that site's search`() {
    assertThat(Bangs.resolve("!w einstein"))
      .isEqualTo("https://en.wikipedia.org/w/index.php?search=einstein")
  }

  @Test
  fun `a trailing bang works the same`() {
    assertThat(Bangs.resolve("einstein !w"))
      .isEqualTo("https://en.wikipedia.org/w/index.php?search=einstein")
  }

  @Test
  fun `a multi-word query is encoded`() {
    assertThat(Bangs.resolve("!gh kotlin coroutines"))
      .isEqualTo("https://github.com/search?q=kotlin+coroutines&type=repositories")
  }

  @Test
  fun `a bang with no query goes to the homepage`() {
    assertThat(Bangs.resolve("!yt")).isEqualTo("https://m.youtube.com/")
  }

  @Test
  fun `an alias resolves to the same site`() {
    assertThat(Bangs.resolve("!github kotlin")).isEqualTo(Bangs.resolve("!gh kotlin"))
  }

  @Test
  fun `the trigger is case-insensitive`() {
    assertThat(Bangs.resolve("!W einstein"))
      .isEqualTo("https://en.wikipedia.org/w/index.php?search=einstein")
  }

  @Test
  fun `an unknown bang is not resolved`() {
    assertThat(Bangs.resolve("!notabang hello")).isNull()
  }

  @Test
  fun `a bare exclamation is not a bang`() {
    assertThat(Bangs.resolve("hello world!")).isNull()
    assertThat(Bangs.resolve("!")).isNull()
  }

  @Test
  fun `only the first known bang is consumed, the rest stay in the query`() {
    // The second !g is not a known bang position - it becomes part of the search text.
    assertThat(Bangs.resolve("!w foo !bar"))
      .isEqualTo("https://en.wikipedia.org/w/index.php?search=foo+%21bar")
  }

  @Test
  fun `typing a bang token is detected, typing a query is not`() {
    assertThat(Bangs.isTypingBang("!w")).isTrue()
    assertThat(Bangs.isTypingBang("!")).isTrue()
    assertThat(Bangs.isTypingBang("!w einstein")).isFalse()
    assertThat(Bangs.isTypingBang("einstein")).isFalse()
  }

  @Test
  fun `suggestions complete the bang and carry a completion to keep typing`() {
    val matches = Bangs.suggestions("!g")
    assertThat(matches).isNotEmpty()
    assertThat(matches.all { it.completion != null && it.completion!!.startsWith("!g") }).isTrue()
    // Every match still needs a query typed after it, so none of them navigate on their own.
    assertThat(matches.all { it.url == null }).isTrue()
  }
}
