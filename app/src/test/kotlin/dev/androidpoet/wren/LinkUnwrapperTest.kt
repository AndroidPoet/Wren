/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.privacy.LinkUnwrapper
import org.junit.Test

class LinkUnwrapperTest {

  @Test
  fun `a search result bounce goes straight to its target`() {
    assertThat(
      LinkUnwrapper.unwrap(
        "https://www.google.com/url?sa=t&q=https%3A%2F%2Fnews.example%2Fstory%3Fid%3D1&ved=x",
      ),
    ).isEqualTo("https://news.example/story?id=1")
  }

  @Test
  fun `social and mail redirectors are unwrapped`() {
    assertThat(LinkUnwrapper.unwrap("https://l.facebook.com/l.php?u=https%3A%2F%2Fa.com%2F&h=1"))
      .isEqualTo("https://a.com/")
    assertThat(
      LinkUnwrapper.unwrap(
        "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fa.com%2Fx&data=y",
      ),
    ).isEqualTo("https://a.com/x")
    assertThat(LinkUnwrapper.unwrap("https://href.li/?https://a.com/page"))
      .isEqualTo("https://a.com/page")
    assertThat(LinkUnwrapper.unwrap("https://out.reddit.com/?url=https%3A%2F%2Fa.com&token=1"))
      .isEqualTo("https://a.com")
  }

  @Test
  fun `amp cache addresses go to the publisher`() {
    assertThat(LinkUnwrapper.unwrap("https://www.google.com/amp/s/news.example/amp/story"))
      .isEqualTo("https://news.example/amp/story")
    assertThat(LinkUnwrapper.unwrap("https://news-example.cdn.ampproject.org/c/s/news.example/x"))
      .isEqualTo("https://news.example/x")
    assertThat(LinkUnwrapper.unwrap("https://news-example.cdn.ampproject.org/v/news.example/x"))
      .isEqualTo("http://news.example/x")
    assertThat(
      LinkUnwrapper.unwrap("https://www.google.com/amp/"),
    ).isEqualTo("https://www.google.com/amp/")
  }

  @Test
  fun `a redirector wrapped in a redirector unwinds, but not forever`() {
    val inner = "https://l.facebook.com/l.php?u=https%3A%2F%2Fa.com%2F"
    val outer = "https://www.google.com/url?q=" + java.net.URLEncoder.encode(inner, "UTF-8")
    assertThat(LinkUnwrapper.unwrap(outer)).isEqualTo("https://a.com/")

    var nested = "https://a.com/"
    repeat(6) {
      nested = "https://www.google.com/url?q=" + java.net.URLEncoder.encode(nested, "UTF-8")
    }
    val result = LinkUnwrapper.unwrap(nested)
    assertThat(result).startsWith("https://www.google.com/url?q=")
  }

  @Test
  fun `only a plain web address is accepted as a target`() {
    assertThat(LinkUnwrapper.unwrap("https://www.google.com/url?q=javascript:alert(1)"))
      .isEqualTo("https://www.google.com/url?q=javascript:alert(1)")
    assertThat(LinkUnwrapper.unwrap("https://www.google.com/url?q=intent://x#Intent;end"))
      .isEqualTo("https://www.google.com/url?q=intent://x#Intent;end")
    assertThat(LinkUnwrapper.unwrap("https://www.google.com/url?q=https://"))
      .isEqualTo("https://www.google.com/url?q=https://")
    assertThat(LinkUnwrapper.unwrap("https://www.google.com/url?q=https%3A%2F%2Fa.com%2F%20x"))
      .isEqualTo("https://www.google.com/url?q=https%3A%2F%2Fa.com%2F%20x")
  }

  @Test
  fun `ordinary pages on those hosts are left alone`() {
    assertThat(LinkUnwrapper.unwrap("https://www.google.com/search?q=cats"))
      .isEqualTo("https://www.google.com/search?q=cats")
    assertThat(LinkUnwrapper.unwrap("https://notgoogle.com/url?q=https://a.com"))
      .isEqualTo("https://notgoogle.com/url?q=https://a.com")
    assertThat(
      LinkUnwrapper.unwrap("https://a.com/?u=https://b.com"),
    ).isEqualTo("https://a.com/?u=https://b.com")
  }
}
