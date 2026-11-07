/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import dev.androidpoet.wren.browser.blocking.CompactHostSet
import dev.androidpoet.wren.browser.blocking.HostMatcher
import dev.androidpoet.wren.browser.blocking.rules.EngineBuilder
import dev.androidpoet.wren.browser.blocking.rules.PageRules
import dev.androidpoet.wren.browser.blocking.rules.RequestContext
import dev.androidpoet.wren.browser.blocking.rules.RequestType
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Times the per-request decision against the bundled lists. Opt in with
 * `-Dwren.bench=true`; the numbers are for a developer's eyes, not for CI to gate on.
 */
class BlockerBenchmark {

  @Test
  fun `decision cost per request`() {
    assumeTrue(System.getProperty("wren.bench") == "true")
    val assets = File("src/main/assets")
    val started = System.nanoTime()
    val builder = EngineBuilder()
      .addHostSet(assets.resolve("hosts.bin").inputStream().use(CompactHostSet::load))
    for (name in listOf("blocklist.txt", "rules.txt")) {
      assets.resolve(name).bufferedReader().useLines { builder.addLines(it) }
    }
    val engine = builder.build(version = 0)
    println(
      "engine: ${engine.hosts.size} hosts, ${engine.rules.size} rules, built in " +
        "${(System.nanoTime() - started) / 1_000_000} ms",
    )

    val corpus = CORPUS.flatMap { (page, urls) -> urls.map { page to it } }

    // The blocker keeps one page's facts across its requests; the corpus does the same.
    class Page(val host: String?, val exempt: Boolean, val rules: PageRules)
    val pages = HashMap<String, Page>()
    fun pageOf(url: String) = pages.getOrPut(url) {
      val host = HostMatcher.hostOf(url)
      Page(host, host != null && engine.rules.isPageExempt(host), engine.rules.pageRulesFor(host))
    }
    fun decide(pageUrl: String, url: String): Boolean {
      val context = RequestContext(url)
      val requestHost = context.host
      if (requestHost.isEmpty()) return false
      val page = pageOf(pageUrl)
      val pageHost = page.host
      if (page.exempt) return false
      val thirdParty = pageHost == null || !HostMatcher.isSameParty(requestHost, pageHost)
      val hostHit = thirdParty && engine.hosts.matchesSuffixOf(requestHost)
      val typeMask = RequestType.infer(context.url) { null }
      return engine.rules.shouldBlock(context, typeMask, thirdParty, pageHost, hostHit, page.rules)
    }

    var blocked = 0
    repeat(3) { round ->
      blocked = 0
      val t0 = System.nanoTime()
      repeat(ROUNDS) {
        for ((page, url) in corpus) if (decide(page, url)) blocked++
      }
      val perRequest = (System.nanoTime() - t0) / (ROUNDS.toLong() * corpus.size)
      println("round $round: $perRequest ns/request, ${blocked / ROUNDS}/${corpus.size} blocked")
    }
  }

  @Test
  fun `full bundled build`() {
    assumeTrue(System.getProperty("wren.bench") == "true")
    val assets = File("src/main/assets")
    repeat(3) { round ->
      val started = System.nanoTime()
      val builder = EngineBuilder()
        .addHostSet(assets.resolve("hosts.bin").inputStream().use(CompactHostSet::load))
      for (name in listOf("blocklist.txt", "rules.txt", "cosmetic.txt")) {
        assets.resolve(name).bufferedReader().useLines { builder.addLines(it) }
      }
      val engine = builder.build(version = 0)
      println(
        "full build round $round: ${(System.nanoTime() - started) / 1_000_000} ms " +
          "(${engine.hosts.size} hosts, ${engine.rules.size} rules, " +
          "${engine.cosmetics.size} cosmetic)",
      )
    }
  }

  @Test
  fun `cost by phase`() {
    assumeTrue(System.getProperty("wren.bench") == "true")
    val assets = File("src/main/assets")
    val builder = EngineBuilder()
      .addHostSet(assets.resolve("hosts.bin").inputStream().use(CompactHostSet::load))
    for (name in listOf("blocklist.txt", "rules.txt")) {
      assets.resolve(name).bufferedReader().useLines { builder.addLines(it) }
    }
    val engine = builder.build(version = 0)
    val corpus = CORPUS.flatMap { (page, urls) -> urls.map { page to it } }
    val hosts = corpus.map { (page, url) ->
      HostMatcher.hostOf(page)!! to HostMatcher.hostOf(url)!!
    }
    val contexts = corpus.map { (_, url) -> RequestContext(url) }
    var sink = 0
    fun time(label: String, block: () -> Unit) {
      repeat(2) { block() }
      val t0 = System.nanoTime()
      repeat(ROUNDS) { block() }
      val perRequest = (System.nanoTime() - t0) / (ROUNDS.toLong() * corpus.size)
      println("%-22s %5d ns/request".format(label, perRequest))
    }
    time("hostOf x2") {
      for ((page, url) in corpus) {
        sink += HostMatcher.hostOf(url)!!.length + HostMatcher.hostOf(page)!!.length
      }
    }
    time("isSameParty") {
      for ((page, host) in hosts) if (HostMatcher.isSameParty(host, page)) sink++
    }
    time("isPageExempt") { for ((page, _) in hosts) if (engine.rules.isPageExempt(page)) sink++ }
    time("hosts.matchesSuffixOf") {
      for ((_, host) in hosts) if (engine.hosts.matchesSuffixOf(host)) sink++
    }
    time("RequestType.infer") { for ((_, url) in corpus) sink += RequestType.infer(url, null) }
    time("RequestContext") { for ((_, url) in corpus) sink += RequestContext(url).tokenCount }
    time("shouldBlock") {
      for (i in corpus.indices) {
        val (page, host) = hosts[i]
        if (engine.rules.shouldBlock(contexts[i], RequestType.SCRIPT, true, page, false)) sink++
      }
    }
    println("sink $sink")
    println(engine.rules.describe())
    for ((i, ctx) in contexts.withIndex()) {
      val pageRules = engine.rules.pageRulesFor(hosts[i].first)
      val examined = engine.rules.rulesExamined(ctx, pageRules)
      println("%4d rules  %s".format(examined, corpus[i].second.take(80)))
    }
  }

  private companion object {
    const val ROUNDS = 2000

    /** A handful of pages and the kinds of subresources they actually request. */
    val CORPUS: List<Pair<String, List<String>>> = listOf(
      "https://www.theverge.com/tech" to listOf(
        "https://www.theverge.com/_next/static/chunks/main-4f2a1c.js",
        "https://www.theverge.com/_next/static/css/app.css",
        "https://cdn.vox-cdn.com/uploads/chorus_image/image/72345/verge.0.jpg",
        "https://www.googletagmanager.com/gtm.js?id=GTM-ABC123",
        "https://www.google-analytics.com/analytics.js",
        "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
        "https://cdn.permutive.com/abc-web.js",
        "https://sb.scorecardresearch.com/beacon.js",
        "https://fonts.gstatic.com/s/inter/v12/UcCO3FwrK3iLTeHuS_fvQtMwCp50KnMw2boKoduKmMEVu.woff2",
        "https://www.theverge.com/api/user?ts=1693000000",
        "https://static.chartbeat.com/js/chartbeat.js",
        "https://c.amazon-adsystem.com/aax2/apstag.js",
        "https://cdn.cookielaw.org/scripttemplates/otSDKStub.js",
        "https://www.theverge.com/images/logo.svg",
      ),
      "https://en.wikipedia.org/wiki/Tor_(network)" to listOf(
        "https://en.wikipedia.org/w/load.php?lang=en&modules=startup&only=scripts",
        "https://upload.wikimedia.org/wikipedia/commons/thumb/1/15/Tor-logo.svg/300px.png",
        "https://en.wikipedia.org/w/index.php?title=MediaWiki:Gadget-styles.css",
        "https://en.wikipedia.org/static/images/icons/wikipedia.png",
        "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=info",
      ),
      "https://www.reddit.com/r/android/" to listOf(
        "https://www.redditstatic.com/shreddit/en-US/shreddit.js",
        "https://styles.redditmedia.com/t5_2qlqh/styles/communityIcon_abc.png",
        "https://events.reddit.com/v2?ts=1&id=abc",
        "https://alb.reddit.com/rp.gif?ts=1693000000",
        "https://www.redditstatic.com/ads/pixel.js",
        "https://preview.redd.it/abc123.jpg?width=640&crop=smart&auto=webp&s=deadbeef",
        "https://www.google.com/recaptcha/api.js?render=explicit",
        "https://pixel.adsafeprotected.com/services/pub?anId=929017",
      ),
      "https://www.amazon.in/dp/B0C1234567" to listOf(
        "https://m.media-amazon.com/images/I/61abc._AC_SL1500_.jpg",
        "https://images-eu.ssl-images-amazon.com/images/G/31/x-locale/common/transparent-pixel.gif",
        "https://fls-eu.amazon.in/1/batch/1/OP/A21TJRUUN4KGV:262-0000000-0000000:ABC\$uedata=s:",
        "https://unagi.amazon.in/1/events/com.amazon.csm.csa.prod",
        "https://m.media-amazon.com/images/S/sash/abc.css",
        "https://completion.amazon.in/api/2017/suggestions?prefix=tor",
        "https://www.amazon.in/gp/product/ajax/ref=dp_aod_NEW_mbc",
      ),
      "https://news.ycombinator.com/" to listOf(
        "https://news.ycombinator.com/news.css?abc",
        "https://news.ycombinator.com/hn.js?abc",
        "https://news.ycombinator.com/y18.svg",
        "https://news.ycombinator.com/s.gif",
      ),
      "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to listOf(
        "https://www.youtube.com/s/desktop/abc/jsbin/desktop_polymer.vflset/desktop_polymer.js",
        "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        "https://www.youtube.com/api/stats/watchtime?ns=yt&el=detailpage&cpn=abc",
        "https://www.youtube.com/pagead/interaction/?ai=abc&sigh=def",
        "https://googleads.g.doubleclick.net/pagead/id",
        "https://static.doubleclick.net/instream/ad_status.js",
        "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&ei=abc&mime=video%2Fmp4",
        "https://fonts.googleapis.com/css?family=Roboto:400,500",
        "https://www.youtube.com/youtubei/v1/log_event?alt=json&key=abc",
        "https://www.gstatic.com/youtube/img/promos/growth/abc.png",
      ),
    )
  }
}
