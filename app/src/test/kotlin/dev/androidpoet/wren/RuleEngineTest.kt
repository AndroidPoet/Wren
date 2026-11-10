/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.blocking.rules.FilterRule
import dev.androidpoet.wren.browser.blocking.rules.RequestContext
import dev.androidpoet.wren.browser.blocking.rules.RequestType
import dev.androidpoet.wren.browser.blocking.rules.RuleEngine
import dev.androidpoet.wren.browser.blocking.rules.UrlPattern
import org.junit.Test

class UrlPatternTest {

  private fun matches(pattern: String, url: String): Boolean {
    val request = RequestContext(url)
    return UrlPattern.parse(pattern).matches(request.url, request.hostStart, request.hostEnd)
  }

  @Test
  fun `plain pattern matches anywhere and is case-insensitive`() {
    assertThat(matches("/ads/banner", "https://cdn.Example.com/x/ADS/banner.png")).isTrue()
    assertThat(matches("/ads/banner", "https://cdn.example.com/x/ad/banner.png")).isFalse()
  }

  @Test
  fun `a trailing dot on the host is the same host`() {
    assertThat(matches("||tracker.example^", "https://tracker.example./x.js")).isTrue()
    assertThat(matches("||tracker.example^", "https://tracker.example.:443/x.js")).isTrue()
    val request = RequestContext("https://Tracker.Example../x?y=1")
    assertThat(request.host).isEqualTo("tracker.example")
    assertThat(request.url).isEqualTo("https://tracker.example/x?y=1")
    assertThat(
      request.url.substring(request.hostStart, request.hostEnd),
    ).isEqualTo("tracker.example")
  }

  @Test
  fun `host anchor needs a label boundary`() {
    assertThat(matches("||example.com/ad", "https://sub.example.com/ad.js")).isTrue()
    assertThat(matches("||example.com/ad", "https://example.com/ad.js")).isTrue()
    assertThat(matches("||example.com/ad", "https://notexample.com/ad.js")).isFalse()
    assertThat(matches("||example.com^", "https://other.org/?u=example.com/")).isFalse()
    assertThat(matches("||example.com^", "https://example.com:8443/")).isTrue()
  }

  @Test
  fun `separator matches punctuation and the end of the address`() {
    assertThat(matches("||ads.example.com^", "https://ads.example.com")).isTrue()
    assertThat(matches("||ads.example.com^", "https://ads.example.com/x")).isTrue()
    assertThat(matches("||ads.example.com^", "https://ads.example.company/x")).isFalse()
    assertThat(matches("/ad^", "https://a.com/ad?x=1")).isTrue()
    assertThat(matches("/ad^", "https://a.com/ad_1")).isFalse()
  }

  @Test
  fun `wildcards span any run and backtrack`() {
    assertThat(matches("/img/*/ad-*.gif", "https://a.com/img/2024/x/ad-300x250.gif")).isTrue()
    assertThat(matches("/img/*/ad-*.gif", "https://a.com/img/ad-300x250.png")).isFalse()
    assertThat(matches("||a.com/*/track^*$", "https://a.com/v1/track?q=1$")).isTrue()
  }

  @Test
  fun `start and end anchors pin the address`() {
    assertThat(matches("|https://a.com/ad", "https://a.com/ad.js")).isTrue()
    assertThat(matches("|https://a.com/ad", "https://b.com/?u=https://a.com/ad")).isFalse()
    assertThat(matches("/ad.js|", "https://a.com/ad.js")).isTrue()
    assertThat(matches("/ad.js|", "https://a.com/ad.js?x")).isFalse()
    assertThat(matches("||a.com/*.js|", "https://a.com/x/y.js")).isTrue()
    assertThat(matches("||a.com/*.js|", "https://a.com/x/y.jsp")).isFalse()
  }

  @Test
  fun `request type comes from the accept header then the extension`() {
    assertThat(RequestType.infer("https://a.com/x", "image/avif,image/webp,*/*;q=0.8"))
      .isEqualTo(RequestType.IMAGE)
    assertThat(RequestType.infer("https://a.com/x.js?v=1", "*/*")).isEqualTo(RequestType.SCRIPT)
    assertThat(RequestType.infer("https://a.com/frame", "text/html,application/xhtml+xml"))
      .isEqualTo(RequestType.SUBDOCUMENT)
    assertThat(RequestType.infer("https://a.com/api/v1", "*/*") and RequestType.XHR).isNotEqualTo(0)
    assertThat(RequestType.infer("https://a.com/api/v1", "*/*") and RequestType.IMAGE).isEqualTo(0)
  }
}

class FilterRuleTest {

  @Test
  fun `parses options`() {
    val rule = FilterRule.parse(
      "||ads.example.com^\$script,third-party,domain=news.com|~safe.news.com",
    )!!
    assertThat(rule.types).isEqualTo(RequestType.SCRIPT)
    assertThat(rule.party).isEqualTo(FilterRule.THIRD_PARTY)
    assertThat(rule.domains).asList().containsExactly("news.com")
    assertThat(rule.excludedDomains).asList().containsExactly("safe.news.com")
    assertThat(rule.appliesTo(RequestType.SCRIPT, true, "m.news.com")).isTrue()
    assertThat(rule.appliesTo(RequestType.SCRIPT, true, "safe.news.com")).isFalse()
    assertThat(rule.appliesTo(RequestType.SCRIPT, true, "other.com")).isFalse()
    assertThat(rule.appliesTo(RequestType.IMAGE, true, "news.com")).isFalse()
    assertThat(rule.appliesTo(RequestType.SCRIPT, false, "news.com")).isFalse()
  }

  @Test
  fun `negated types subtract from everything`() {
    val rule = FilterRule.parse("ads/\$~stylesheet,~image")!!
    assertThat(rule.types and RequestType.STYLESHEET).isEqualTo(0)
    assertThat(rule.types and RequestType.IMAGE).isEqualTo(0)
    assertThat(rule.types and RequestType.SCRIPT).isNotEqualTo(0)
  }

  @Test
  fun `rules the engine cannot honour are dropped`() {
    assertThat(FilterRule.parse("||a.com^\$popup")).isNull()
    assertThat(FilterRule.parse("@@||a.com^\$redirect-rule=noopjs")).isNull()
    assertThat(FilterRule.parse("/banner\\d+/\$image")).isNull()
    assertThat(FilterRule.parse("/ads/")).isNull()
    assertThat(FilterRule.parse("! comment")).isNull()
    assertThat(FilterRule.parse("a.com##.ad")).isNull()
    assertThat(FilterRule.parse("a.com#@#.ad")).isNull()
    assertThat(FilterRule.parse("*\$third-party")).isNotNull()
    assertThat(FilterRule.parse("*")).isNull()
    assertThat(FilterRule.parse("\$domain=~a.com")).isNull()
  }

  @Test
  fun `wildcard sites name the site under every public suffix`() {
    // uBlock filters carries `*$script,1p,domain=~kat.ai|...|kat.*|kickass.*`: in 1.0.12 the
    // `kat.*` entries were dropped and the leftover exclusions blocked every first-party script.
    val rule = FilterRule.parse("*\$script,1p,domain=~kat.ai|kat.*|kickass.*")!!
    assertThat(rule.entities!!.toList()).containsExactly("kat", "kickass")
    assertThat(rule.excludedDomains!!.toList()).containsExactly("kat.ai")
    assertThat(rule.domains).isNull()
    assertThat(rule.appliesTo(RequestType.SCRIPT, false, "kat.sx")).isTrue()
    assertThat(rule.appliesTo(RequestType.SCRIPT, false, "www.kickass.co.uk")).isTrue()
    assertThat(rule.appliesTo(RequestType.SCRIPT, false, "kat.ai")).isFalse()
    assertThat(rule.appliesTo(RequestType.SCRIPT, false, "example.com")).isFalse()
    assertThat(rule.appliesTo(RequestType.SCRIPT, false, "notkat.sx")).isFalse()
    assertThat(rule.appliesTo(RequestType.SCRIPT, false, null)).isFalse()

    // An excluded wildcard site is honoured too: dropping it would block on shop.com.
    val excluded = FilterRule.parse("||cdn.example/ad.js\$script,domain=~safe.example|~shop.*")!!
    assertThat(excluded.excludedEntities!!.toList()).containsExactly("shop")
    assertThat(excluded.appliesTo(RequestType.SCRIPT, true, "shop.com")).isFalse()
    assertThat(excluded.appliesTo(RequestType.SCRIPT, true, "www.shop.co.uk")).isFalse()
    assertThat(excluded.appliesTo(RequestType.SCRIPT, true, "other.com")).isTrue()

    // A wildcard anywhere else is no form a list defines.
    assertThat(FilterRule.parse("||x.com/y\$script,domain=a*b.com")).isNull()
    assertThat(FilterRule.parse("||x.com/y\$script,domain=*.com")).isNull()
  }

  @Test
  fun `wildcard sites are matched by the engine`() {
    val engine = RuleEngine.parse(
      sequenceOf(
        "||cdn.example/ad.js\$script,domain=~shop.*",
        "/collector\$script,1p,domain=kat.*",
      ),
    )
    val ad = RequestContext("https://cdn.example/ad.js")
    assertThat(engine.decide(ad, RequestType.SCRIPT, true, "shop.com", false)).isNull()
    assertThat(
      engine.decide(ad, RequestType.SCRIPT, true, "news.com", false),
    ).isNotNull()
    val collector = RequestContext("https://kat.sx/collector")
    assertThat(engine.decide(collector, RequestType.SCRIPT, false, "kat.sx", false)).isNotNull()
    assertThat(
      engine.decide(collector, RequestType.SCRIPT, false, "example.com", false),
    ).isNull()
  }

  @Test
  fun `a rule that needs case to count is left out`() {
    // Patterns and addresses are compared lowercased; honouring the rule would widen it.
    assertThat(FilterRule.parse("/Ad.js\$script,match-case")).isNull()
  }

  @Test
  fun `every token of a long address is seen`() {
    val engine = RuleEngine.parse(sequenceOf("/analytics-collector\$script"))
    val padding = "x/".repeat(600)
    val url = "https://tracker.example/${padding}analytics-collector"
    assertThat(
      engine.decide(RequestContext(url), RequestType.SCRIPT, true, "site.example", false),
    ).isNotNull()
  }

  @Test
  fun `a long address repeating a rule's token is scanned once per rule`() {
    val engine = RuleEngine.parse(
      sequenceOf("/koko-analytics/assets/dist/js/*script.js", "/oo/*/l.js"),
    )
    val padding = "koko-analytics/assets/dist/js/oo/".repeat(7_000)
    val miss = "https://evil.example/$padding"
    val started = System.nanoTime()
    assertThat(
      engine.decide(RequestContext(miss), RequestType.SCRIPT, false, "evil.example", false),
    ).isNull()
    val hit = "https://evil.example/${padding}script.js"
    assertThat(
      engine.decide(RequestContext(hit), RequestType.SCRIPT, false, "evil.example", false),
    ).isNotNull()
    // Unbounded, the miss alone was about 10^9 character steps; bounded, it is milliseconds.
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000L)
  }

  @Test
  fun `a wildcard rule still matches at a later occurrence of its token`() {
    val engine = RuleEngine.parse(sequenceOf("/ads/*^banner"))
    val url = "https://site.example/ads/x/ads/y/banner?z"
    assertThat(
      engine.decide(RequestContext(url), RequestType.IMAGE, false, "site.example", false),
    ).isNotNull()
    assertThat(
      engine.decide(
        RequestContext("https://site.example/ads/x/ads/ybanner"),
        RequestType.IMAGE,
        false,
        "site.example",
        false,
      ),
    ).isNull()
  }

  @Test
  fun `document exceptions are recognised by host`() {
    assertThat(
      FilterRule.documentExceptionHost("@@||example.com^\$document"),
    ).isEqualTo("example.com")
    assertThat(FilterRule.documentExceptionHost("@@||example.com/x\$document")).isNull()
    assertThat(FilterRule.documentExceptionHost("@@||example.com^\$document,script")).isNull()
  }
}

class RuleEngineTest {

  private val engine = RuleEngine.parse(
    sequenceOf(
      "||ads.example.com^",
      "/banner/*/ad.",
      "||tracker.net^\$third-party",
      "||cdn.site.com/widget.js\$script,domain=~site.com",
      "@@||ads.example.com/allowed.js",
      "@@||tracker.net^\$domain=partner.org",
      "||evil.org^\$important",
      "@@||evil.org^",
      "@@||exempt.com^\$document",
      "-ad-sidebar.\$image",
      "*\$ping,third-party",
    ),
  )

  private fun blocked(
    url: String,
    page: String? = "https://news.com/",
    accept: String = "*/*",
    hostHit: Boolean = false,
  ): Boolean {
    val pageHost = page?.let { it.substringAfter("://").substringBefore('/') }
    val requestHost = url.substringAfter("://").substringBefore('/')
    val thirdParty = pageHost == null || !requestHost.endsWith(pageHost.substringAfter("www."))
    return engine.shouldBlock(
      RequestContext(url),
      RequestType.infer(url, accept),
      thirdParty,
      pageHost,
      hostHit,
    )
  }

  @Test
  fun `blocks by host and by path`() {
    assertThat(blocked("https://ads.example.com/x.js")).isTrue()
    assertThat(blocked("https://ads.example.com.evil/x.js")).isFalse()
    assertThat(blocked("https://news.com/banner/2024/ad.png")).isTrue()
    assertThat(blocked("https://news.com/banner/ad.png")).isFalse()
  }

  @Test
  fun `exceptions win over ordinary blocks and over host list hits`() {
    assertThat(blocked("https://ads.example.com/allowed.js")).isFalse()
    assertThat(blocked("https://tracker.net/p.gif", page = "https://partner.org/")).isFalse()
    assertThat(blocked("https://tracker.net/p.gif", page = "https://other.org/")).isTrue()
    assertThat(blocked("https://tracker.net/p.gif", page = "https://partner.org/", hostHit = true))
      .isFalse()
    assertThat(blocked("https://anything.com/x", hostHit = true)).isTrue()
  }

  @Test
  fun `important blocks cannot be excepted`() {
    assertThat(blocked("https://evil.org/x")).isTrue()
  }

  @Test
  fun `party and domain scopes apply`() {
    assertThat(blocked("https://tracker.net/p", page = "https://tracker.net/")).isFalse()
    assertThat(blocked("https://cdn.site.com/widget.js", page = "https://site.com/")).isFalse()
    assertThat(blocked("https://cdn.site.com/widget.js", page = "https://blog.net/")).isTrue()
  }

  @Test
  fun `type scopes apply`() {
    assertThat(blocked("https://news.com/img-ad-sidebar.png", accept = "image/*")).isTrue()
    assertThat(blocked("https://news.com/img-ad-sidebar.css", accept = "text/css")).isFalse()
  }

  @Test
  fun `a ping-only rule never catches an ordinary fetch`() {
    assertThat(blocked("https://api.vendor.com/v1/items")).isFalse()
    assertThat(blocked("https://api.vendor.com/v1/items.js")).isFalse()
  }

  @Test
  fun `document exceptions exempt the page`() {
    assertThat(engine.isPageExempt("m.exempt.com")).isTrue()
    assertThat(engine.isPageExempt("exempt.com.evil")).isFalse()
  }

  @Test
  fun `bundled rules load and decide quickly`() {
    val file = java.io.File("src/main/assets/rules.txt")
    val bundled = file.bufferedReader().useLines(RuleEngine::parse)
    assertThat(bundled.size).isGreaterThan(10_000)
    val urls = listOf(
      "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
      "https://platform.theverge.com/wp-content/uploads/sites/2/2026/09/mariokart8a.jpg",
      "https://cdn.concert.io/lib/concert-ads/v2-latest/concert_ads.js",
      "https://www.google.com/recaptcha/api.js?render=6LeCFmwfAAAAAA4qBtkgg3WVJWVYGkt05",
      "https://fonts.gstatic.com/s/roboto/v48/KFO7CnqEu92Fr1ME7kSn66aGLdTylUAMa3yUBHMda",
      "https://sb.scorecardresearch.com/cs/7976662/beacon.js",
      "https://example.com/static/app.bundle.js",
      "https://example.com/api/v2/items?page=3&sort=asc",
    )
    var blockedCount = 0
    repeat(20) {
      for (url in urls) {
        val request = RequestContext(url)
        val mask = RequestType.infer(url, "*/*")
        if (bundled.shouldBlock(request, mask, true, "www.theverge.com", false)) blockedCount++
      }
    }
    val rounds = 2_000
    val started = System.nanoTime()
    repeat(rounds) {
      for (url in urls) {
        val request = RequestContext(url)
        bundled.shouldBlock(request, RequestType.infer(url, "*/*"), true, "www.theverge.com", false)
      }
    }
    val perDecisionNs = (System.nanoTime() - started) / (rounds * urls.size)
    println("bundled rules: ${bundled.size}; ${perDecisionNs / 1000.0} us per decision on the JVM")
    assertThat(blockedCount).isGreaterThan(0)
    assertThat(perDecisionNs).isLessThan(200_000)
  }

  class RuleOptionsTest {

    private fun decide(engine: RuleEngine, url: String): FilterRule? {
      val pageHost = "news.com"
      return engine.decide(RequestContext(url), RequestType.SCRIPT, true, pageHost, false)
    }

    @Test
    fun `a redirect rule keeps its block and names its resource`() {
      val rule = FilterRule.parse(
        "||imasdk.googleapis.com/js/sdkloader/ima3.js\$script,redirect=google-ima.js",
      )!!
      assertThat(rule.redirect).isEqualTo("google-ima.js")
      assertThat(FilterRule.parse("||a.com/x.js\$script,redirect=noopjs:10")!!.redirect)
        .isEqualTo("noopjs")
      assertThat(FilterRule.parse("||a.com/x.js\$redirect-rule=noopjs")!!.redirectOnly).isTrue()
      assertThat(FilterRule.parse("@@||a.com/x.js\$redirect=noopjs")).isNull()
    }

    @Test
    fun `a redirect-rule blocks nothing but names the stand-in for a block made elsewhere`() {
      val engine = RuleEngine.parse(
        sequenceOf(
          "||ads.example.com/x.js\$script,redirect-rule=noopjs",
          "||ads.example.com/y.js\$script",
          "||ads.example.com/y.js\$script,redirect-rule=nooptext",
        ),
      )
      assertThat(decide(engine, "https://ads.example.com/x.js")).isNull()
      assertThat(decide(engine, "https://ads.example.com/y.js")!!.redirect).isEqualTo("nooptext")
      val hostHit = RequestContext("https://ads.example.com/x.js")
      assertThat(engine.decide(hostHit, RequestType.SCRIPT, true, "news.com", true)!!.redirect)
        .isEqualTo("noopjs")
    }

    @Test
    fun `decide returns the winning rule and the host sentinel`() {
      val engine = RuleEngine.parse(sequenceOf("||ads.example.com/x.js\$script,redirect=noopjs"))
      assertThat(decide(engine, "https://ads.example.com/x.js")!!.redirect).isEqualTo("noopjs")
      assertThat(decide(engine, "https://other.example.com/x.js")).isNull()
      val request = RequestContext("https://t.example/x.js")
      val byHost = engine.decide(request, RequestType.SCRIPT, true, "news.com", true)
      assertThat(byHost).isSameInstanceAs(RuleEngine.HOST_BLOCK)
    }

    @Test
    fun `a host set hit still serves the redirect a rule names for that url`() {
      val engine = RuleEngine.parse(
        sequenceOf(
          "||pagead2.googlesyndication.com/pagead/js/adsbygoogle.js\$script,redirect=noopjs",
          "||pagead2.googlesyndication.com/other.js\$script,redirect=noopjs,domain=~news.com",
        ),
      )
      val shimmed = RequestContext("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
      assertThat(engine.decide(shimmed, RequestType.SCRIPT, true, "news.com", true)!!.redirect)
        .isEqualTo("noopjs")
      val plain = RequestContext("https://pagead2.googlesyndication.com/x.js")
      assertThat(engine.decide(plain, RequestType.SCRIPT, true, "news.com", true))
        .isSameInstanceAs(RuleEngine.HOST_BLOCK)
      val excluded = RequestContext("https://pagead2.googlesyndication.com/other.js")
      assertThat(engine.decide(excluded, RequestType.SCRIPT, true, "news.com", true))
        .isSameInstanceAs(RuleEngine.HOST_BLOCK)
    }

    @Test
    fun `a badfilter cancels the rule it repeats`() {
      val engine = RuleEngine.parse(
        sequenceOf(
          "||smartlook.com^",
          "||smartlook.com^\$badfilter",
          "||top.mail.ru^\$script",
          "||keep.example^",
        ),
      )
      assertThat(decide(engine, "https://smartlook.com/x.js")).isNull()
      assertThat(decide(engine, "https://top.mail.ru/x.js")).isNotNull()
      assertThat(decide(engine, "https://keep.example/x.js")).isNotNull()
    }

    @Test
    fun `generichide exceptions are kept by host`() {
      val engine = RuleEngine.parse(
        sequenceOf("@@||bild.de^\$ghide", "@@||kbb.com^\$generichide,important"),
      )
      assertThat(engine.isGenericHideExempt("www.bild.de")).isTrue()
      assertThat(engine.isGenericHideExempt("kbb.com")).isTrue()
      assertThat(engine.isGenericHideExempt("news.com")).isFalse()
    }

    @Test
    fun `an important rule beats an exception even after a host set hit`() {
      val engine = RuleEngine.parse(sequenceOf("||evil.org^\$important", "@@||evil.org/ok.js"))
      val request = RequestContext("https://evil.org/ok.js")
      assertThat(engine.decide(request, RequestType.SCRIPT, true, "news.com", true)).isNotNull()
      assertThat(engine.decide(request, RequestType.SCRIPT, true, "news.com", false)).isNotNull()
    }
  }
}
