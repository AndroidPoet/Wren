/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.blocking.rules.PageRules
import dev.androidpoet.wren.browser.blocking.rules.RequestContext
import dev.androidpoet.wren.browser.blocking.rules.RequestType
import dev.androidpoet.wren.browser.blocking.rules.RuleEngine
import org.junit.Test

class RuleEngineIndexTest {

  private fun RuleEngine.blocks(
    url: String,
    page: String?,
    type: Int = RequestType.SCRIPT,
    thirdParty: Boolean = true,
  ): Boolean = shouldBlock(RequestContext(url), type, thirdParty, page, false)

  @Test
  fun `domain scoped rules without a token apply only on their pages`() {
    val engine = RuleEngine.parse(sequenceOf(".gif\$image,domain=example.com"))
    val gif = "https://cdn.x.com/a.gif"
    assertThat(engine.blocks(gif, "www.example.com", RequestType.IMAGE)).isTrue()
    assertThat(engine.blocks(gif, "example.com", RequestType.IMAGE)).isTrue()
    assertThat(engine.blocks(gif, "www.other.com", RequestType.IMAGE)).isFalse()
    assertThat(engine.blocks(gif, null, RequestType.IMAGE)).isFalse()
  }

  @Test
  fun `page rules are collected per page and empty elsewhere`() {
    val engine = RuleEngine.parse(
      sequenceOf(".gif\$image,domain=example.com", "@@.xml\$domain=example.com"),
    )
    val forPage = engine.pageRulesFor("www.example.com")
    assertThat(forPage.blocks).hasLength(1)
    assertThat(forPage.exceptions).hasLength(1)
    assertThat(engine.pageRulesFor("other.com")).isSameInstanceAs(PageRules.EMPTY)
    assertThat(engine.pageRulesFor(null)).isSameInstanceAs(PageRules.EMPTY)
  }

  @Test
  fun `a domain scoped exception still overturns a block on its page`() {
    val engine = RuleEngine.parse(
      sequenceOf("||tracker.com^", "@@.js\$domain=example.com"),
    )
    val script = "https://tracker.com/t.js"
    assertThat(engine.blocks(script, "www.example.com")).isFalse()
    assertThat(engine.blocks(script, "www.other.com")).isTrue()
  }

  @Test
  fun `aligned matching finds a pattern at any occurrence of its token`() {
    val engine = RuleEngine.parse(sequenceOf("/pixel/track^"))
    assertThat(engine.blocks("https://a.com/track/pixel/track/", "b.com")).isTrue()
    assertThat(engine.blocks("https://a.com/pixel/tracker", "b.com")).isFalse()
    assertThat(engine.blocks("https://a.com/x/pixel/track", "b.com")).isTrue()
  }

  @Test
  fun `host anchored rules respect label boundaries when aligned`() {
    val engine = RuleEngine.parse(sequenceOf("||ads.example.com^"))
    assertThat(engine.blocks("https://ads.example.com/x.js", "b.com")).isTrue()
    assertThat(engine.blocks("https://s.ads.example.com/x.js", "b.com")).isTrue()
    assertThat(engine.blocks("https://x.com/ads.example.com/x.js", "b.com")).isFalse()
    assertThat(engine.blocks("https://bads.example.com/x.js", "b.com")).isFalse()
  }

  @Test
  fun `start anchored rules only match at the start when aligned`() {
    val engine = RuleEngine.parse(sequenceOf("|https://cdn.tracker.net/"))
    assertThat(engine.blocks("https://cdn.tracker.net/a.js", "b.com")).isTrue()
    assertThat(engine.blocks("https://b.com/?u=https://cdn.tracker.net/", "b.com")).isFalse()
  }

  @Test
  fun `a wildcard before the token falls back to a full search`() {
    val engine = RuleEngine.parse(sequenceOf("/ad*/banner.js"))
    assertThat(engine.blocks("https://a.com/adserver/x/banner.js", "b.com")).isTrue()
    assertThat(engine.blocks("https://a.com/banner.js", "b.com")).isFalse()
  }

  @Test
  fun `request context reads the host past credentials and port`() {
    val context = RequestContext("https://User:pw@Ads.Example.COM:8443/a/b?c=D")
    assertThat(context.host).isEqualTo("ads.example.com")
    assertThat(context.url.substring(context.hostStart, context.hostEnd))
      .isEqualTo("ads.example.com")
    assertThat(RequestContext("https://example.com./x").host).isEqualTo("example.com")
    assertThat(RequestContext("about:blank").host).isEmpty()
    assertThat(RequestContext("data:text/plain,hi").host).isEmpty()
  }

  @Test
  fun `an already lowercase address is used as is`() {
    val url = "https://example.com/path?x=1"
    assertThat(RequestContext(url).url).isSameInstanceAs(url)
    assertThat(RequestContext("https://Example.com/").url).isEqualTo("https://example.com/")
  }
}
