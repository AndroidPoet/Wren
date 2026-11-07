/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import com.google.common.truth.Truth.assertThat
import dev.androidpoet.wren.browser.blocking.rules.CosmeticRules
import dev.androidpoet.wren.browser.blocking.rules.EngineBuilder
import org.junit.Test

class CosmeticRulesTest {

  private val rules = CosmeticRules.Builder().apply {
    add("##.ad-banner")
    add("##.sponsored")
    add("news.com##.promo")
    add("news.com,~safe.news.com###sidebar-ad")
    add("~quiet.org##.popup")
    add("news.com#@#.sponsored")
    add("#@#.ad-banner")
    add("news.com##+js(set, ads.enabled, false)")
    add("news.com##+js(aopr, adBlockDetected)")
    add("m.news.com#@#+js(aopr, adBlockDetected)")
    add("off.com##+js(set, x, 1)")
    add("off.com#@#+js()")
    add("news.com##+js(trusted-unknown-thing, x)")
    add("##.broken(")
    add("news.com#?#.procedural:has-text(x)")
  }.build()

  @Test
  fun `specific selectors and exceptions travel per host, keyed generics stay behind`() {
    val payload = rules.hidingPayloadFor("m.news.com")
    assertThat(payload).contains("\"g\":[]")
    assertThat(payload).contains("\"s\":[\".promo\",\"#sidebar-ad\"]")
    assertThat(payload).contains("\"x\":[\".sponsored\"]")
    assertThat(rules.hidingPayloadFor("safe.news.com"))
      .contains("\"x\":[\"#sidebar-ad\",\".sponsored\"]")
    assertThat(rules.hidingPayloadFor("quiet.org")).contains("\"x\":[\".popup\"]")
    assertThat(rules.hidingPayloadFor("other.com")).contains("\"s\":[]")
  }

  @Test
  fun `a survey returns only the generic selectors its ids and classes can satisfy`() {
    assertThat(rules.genericFor(emptyList(), emptyList())).isEmpty()
    assertThat(rules.genericFor(emptyList(), listOf("sponsored")))
      .containsExactly(".sponsored")
    assertThat(rules.genericFor(emptyList(), listOf("popup", "sponsored", "unrelated")))
      .containsExactly(".popup", ".sponsored")
    assertThat(rules.genericFor(emptyList(), listOf("ad-banner"))).isEmpty()
    assertThat(rules.surveyPayloadFor(emptyList(), listOf("popup")))
      .isEqualTo("{\"m\":[\".popup\"]}")
  }

  @Test
  fun `generic selectors are keyed by their leading id or class`() {
    assertThat(CosmeticRules.genericKeyOf("#sidebar-ad")).isEqualTo("sidebar-ad")
    assertThat(CosmeticRules.genericKeyOf(".Promo > .banner")).isEqualTo("promo")
    assertThat(CosmeticRules.genericKeyOf(".ad_slot[data-x]:not(.keep)")).isEqualTo("ad_slot")
    assertThat(CosmeticRules.genericKeyOf("#top:has(.x)")).isEqualTo("top")
    assertThat(CosmeticRules.genericKeyOf("div.ad")).isNull()
    assertThat(CosmeticRules.genericKeyOf("[data-ad-slot]")).isNull()
    assertThat(CosmeticRules.genericKeyOf(".a, .b")).isNull()
    assertThat(CosmeticRules.genericKeyOf(".odd\\:name")).isNull()
    assertThat(CosmeticRules.genericKeyOf("AD-SLOT")).isNull()
    assertThat(CosmeticRules.genericKeyOf("#")).isNull()
    val keyed = CosmeticRules.Builder().apply {
      add("##div.ad")
      add("##[data-ad-slot]")
      add("###Header-Ad")
    }.build()
    assertThat(keyed.hidingPayloadFor("x.com")).contains("\"g\":[\"div.ad\",\"[data-ad-slot]\"]")
    assertThat(keyed.genericFor(listOf("header-ad"), emptyList())).containsExactly("#Header-Ad")
    assertThat(keyed.genericFor(listOf("Header-Ad"), emptyList())).containsExactly("#Header-Ad")
  }

  @Test
  fun `scriptlets resolve per host and honour exceptions`() {
    assertThat(rules.scriptletsFor("news.com"))
      .containsExactly("set, ads.enabled, false", "aopr, adBlockDetected")
    assertThat(rules.scriptletsFor("m.news.com")).containsExactly("set, ads.enabled, false")
    assertThat(rules.scriptletsFor("off.com")).isEmpty()
    assertThat(rules.scriptletsFor("other.com")).isEmpty()
    assertThat(rules.scriptletTableJson()).contains("\"news.com\":[\"set, ads.enabled, false\"")
    assertThat(rules.scriptletTableJson()).contains("\"x\":{")
  }

  @Test
  fun `unsupported and malformed lines are dropped`() {
    assertThat(rules.hidingPayloadFor("news.com")).doesNotContain("broken")
    assertThat(rules.hidingPayloadFor("news.com")).doesNotContain("procedural")
    assertThat(rules.scriptletsFor("news.com")).doesNotContain("trusted-unknown-thing, x")
  }

  @Test
  fun `json quoting escapes what would break a script`() {
    val payload = CosmeticRules.Builder().apply { add("##a[href=\"x\\y\"]<b") }.build()
      .hidingPayloadFor("a.com")
    assertThat(payload).contains("\"a[href=\\\"x\\\\y\\\"]\\u003cb\"")
  }
}

class EngineBuilderTest {

  @Test
  fun `mixed list lines land in the right store`() {
    val engine = EngineBuilder().addLines(
      sequenceOf(
        "! comment",
        "[Adblock Plus 2.0]",
        "# hosts comment",
        "0.0.0.0 tracker.example",
        "127.0.0.1 pixel.example # trailing",
        "bare.example",
        "0.0.0.0 0.0.0.0",
        "localhost",
        "||host.example^",
        "||3p.example^\$third-party",
        "||typed.example^\$script",
        "/ads/banner/*",
        "ip6-localhost",
        "::1 ip6-loopback",
        "##.generic-ad",
        "site.example##+js(set, a, 1)",
      ),
    ).build(version = 3)
    assertThat(engine.version).isEqualTo(3)
    val blocked =
      listOf("tracker.example", "sub.pixel.example", "bare.example", "host.example", "3p.example")
    for (host in blocked) {
      assertThat(engine.hosts.matchesSuffixOf(host)).isTrue()
    }
    assertThat(engine.hosts.matchesSuffixOf("typed.example")).isFalse()
    assertThat(engine.hosts.matchesSuffixOf("localhost")).isFalse()
    assertThat(engine.rules.size).isEqualTo(2)
    assertThat(engine.cosmetics.size).isEqualTo(2)
  }

  @Test
  fun `bundled cosmetic asset parses and carries the youtube scriptlets`() {
    val file = java.io.File("src/main/assets/cosmetic.txt")
    val cosmetics = CosmeticRules.Builder().also { builder ->
      file.bufferedReader().useLines { lines -> lines.forEach { builder.add(it) } }
    }.build()
    assertThat(cosmetics.size).isGreaterThan(20_000)
    val youtube = cosmetics.scriptletsFor("www.youtube.com")
    assertThat(youtube.any { it.startsWith("json-prune") }).isTrue()
    val started = System.nanoTime()
    val payload = cosmetics.hidingPayloadFor("www.theverge.com")
    val table = cosmetics.scriptletTableJson()
    val survey = cosmetics.genericFor(listOf("google_ads_frame"), listOf("adsbygoogle"))
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    println(
      "cosmetic payload ${payload.length / 1024} KB, " +
        "scriptlet table ${table.length / 1024} KB, " +
        "${cosmetics.genericKeyedCount} keyed / ${cosmetics.genericLowCount} unkeyed generic, " +
        "$elapsedMs ms",
    )
    assertThat(cosmetics.genericKeyedCount).isGreaterThan(cosmetics.genericLowCount * 10)
    assertThat(payload.length).isLessThan(60_000)
    assertThat(survey).contains("#google_ads_frame")
  }

  @Test
  fun `procedural and snippet lines never become blocked hosts`() {
    val engine = EngineBuilder().addLines(
      sequenceOf(
        "instagram.com#?#div:has-text(Paid partnership)",
        "x.com#\$#.foo { display: none !important; }",
        "yahoo.com#%#//scriptlet('abort-on-property-read', 'x')",
        "bing.com#@?#.cookie",
        "github.com#@\$#.x",
        "nytimes.com##.ad-slot",
      ),
    ).build(version = 0)
    assertThat(engine.hosts.size).isEqualTo(0)
    assertThat(engine.hosts.matchesSuffixOf("www.instagram.com")).isFalse()
    assertThat(engine.cosmetics.hidingPayloadFor("www.nytimes.com")).contains(".ad-slot")
  }

  @Test
  fun `a hash glued to text is not a hosts file comment`() {
    assertThat(EngineBuilder.hostsFileEntry("0.0.0.0 tracker.example # note"))
      .isEqualTo("tracker.example")
    assertThat(EngineBuilder.hostsFileEntry("# only a comment")).isNull()
    assertThat(EngineBuilder.hostsFileEntry("site.example#?#.x")).isNull()
  }

  @Test
  fun `procedural selectors and html filters stay out of the style sheet`() {
    val rules = CosmeticRules.Builder().apply {
      assertThat(add("a.com##div:has-text(Sponsored)")).isFalse()
      assertThat(add("a.com##^script:has-text(adblock)")).isFalse()
      assertThat(add("a.com##div:has(> .ad)")).isTrue()
      assertThat(add("a.com##.x:upward(2)")).isFalse()
    }.build()
    assertThat(rules.hidingPayloadFor("a.com")).contains("div:has(> .ad)")
    assertThat(rules.hidingPayloadFor("a.com")).doesNotContain("has-text")
  }

  @Test
  fun `scriptlet aliases and js suffixes are accepted`() {
    val rules = CosmeticRules.Builder().apply {
      assertThat(add("youtube.com##+js(trusted-rpfr, \"adPlacements\", \"no_ads\")")).isTrue()
      assertThat(add("a.com##+js(cookie-remover.js, x)")).isTrue()
      assertThat(add("a.com##+js(not-a-real-scriptlet, x)")).isFalse()
    }.build()
    assertThat(rules.scriptletsFor("www.youtube.com").single()).startsWith("trusted-rpfr")
  }

  @Test
  fun `if blocks follow the chromium mobile conditions`() {
    val engine = EngineBuilder().addLines(
      sequenceOf(
        "!#if env_firefox",
        "||firefox-only.example^",
        "!#else",
        "||not-firefox.example^",
        "!#endif",
        "!#if !env_mobile",
        "||desktop-only.example^",
        "!#endif",
        "!#if env_chromium || cap_html_filtering",
        "||chromium-or.example^",
        "!#endif",
        "||always.example^",
      ),
    ).build(version = 0)
    assertThat(engine.hosts.matchesSuffixOf("firefox-only.example")).isFalse()
    assertThat(engine.hosts.matchesSuffixOf("not-firefox.example")).isTrue()
    assertThat(engine.hosts.matchesSuffixOf("desktop-only.example")).isFalse()
    assertThat(engine.hosts.matchesSuffixOf("chromium-or.example")).isTrue()
    assertThat(engine.hosts.matchesSuffixOf("always.example")).isTrue()
  }
}
