/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

/**
 * Names the company behind a blocked host, so the site panel can say "Google, 7 hosts" instead
 * of listing seven subdomains of doubleclick.net.
 *
 * A short table of the owners that account for most of what any blocker stops, keyed by
 * registrable domain. Deliberately not exhaustive: a host that is not in it is still blocked,
 * it is just reported under its own name. The facts here are public (who owns which ad or
 * analytics domain); the table is Wren's own.
 */
object TrackerOwners {
  private val OWNERS: Map<String, String> = buildMap {
    owner(
      "Google",
      "google.com", "googlesyndication.com", "doubleclick.net", "googleadservices.com",
      "google-analytics.com", "googletagmanager.com", "googletagservices.com", "gstatic.com",
      "youtube.com", "admob.com", "2mdn.net", "app-measurement.com", "crashlytics.com",
      "firebaseio.com", "googleusercontent.com", "ggpht.com", "googleoptimize.com",
      "googleapis.com", "ampproject.org", "recaptcha.net",
    )
    owner(
      "Meta",
      "facebook.com",
      "facebook.net",
      "fbcdn.net",
      "instagram.com",
      "whatsapp.com",
      "atdmt.com",
    )
    owner("Amazon", "amazon-adsystem.com", "assoc-amazon.com", "media-amazon.com")
    owner(
      "Microsoft",
      "bing.com", "microsoft.com", "msn.com", "clarity.ms", "linkedin.com", "licdn.com",
      "live.com", "msecnd.net", "ads.microsoft.com",
    )
    owner(
      "Adobe",
      "omtrdc.net",
      "demdex.net",
      "2o7.net",
      "adobedtm.com",
      "everesttech.net",
      "adobe.com",
    )
    owner("Oracle", "bluekai.com", "addthis.com", "moatads.com", "eloqua.com", "bkrtx.com")
    owner("Criteo", "criteo.com", "criteo.net")
    owner("Taboola", "taboola.com")
    owner("Outbrain", "outbrain.com")
    owner("X", "twitter.com", "t.co", "twimg.com", "ads-twitter.com", "x.com")
    owner("TikTok", "tiktok.com", "tiktokcdn.com", "byteoversea.com")
    owner("Yandex", "yandex.ru", "yandex.net", "yandex.com")
    owner("Comscore", "scorecardresearch.com", "comscore.com")
    owner("Nielsen", "imrworldwide.com")
    owner("Quantcast", "quantserve.com", "quantcount.com")
    owner("Hotjar", "hotjar.com")
    owner("Mixpanel", "mixpanel.com")
    owner("Twilio Segment", "segment.io", "segment.com")
    owner("Amplitude", "amplitude.com")
    owner("New Relic", "newrelic.com", "nr-data.net")
    owner("Sentry", "sentry.io")
    owner("PubMatic", "pubmatic.com")
    owner("Magnite", "rubiconproject.com")
    owner("Index Exchange", "casalemedia.com", "indexww.com")
    owner("OpenX", "openx.net")
    owner("Xandr", "adnxs.com")
    owner("The Trade Desk", "adsrvr.org")
    owner("MediaMath", "mathtag.com")
    owner("Yahoo", "yahoo.com", "advertising.com", "yimg.com", "flurry.com")
    owner("LiveRamp", "rlcdn.com", "pippio.com")
    owner("Salesforce", "krxd.net", "exacttarget.com", "pardot.com")
    owner("Snap", "snapchat.com", "sc-static.net")
    owner("Pinterest", "pinterest.com", "pinimg.com")
    owner("Reddit", "reddit.com", "redditstatic.com")
    owner("Chartbeat", "chartbeat.com", "chartbeat.net")
    owner("Optimizely", "optimizely.com")
    owner("Braze", "braze.com", "appboy.com")
    owner("OneSignal", "onesignal.com")
    owner("Sharethrough", "sharethrough.com")
    owner("Teads", "teads.tv")
    owner("Equativ", "smartadserver.com")
    owner("Media.net", "media.net")
    owner("Sovrn", "lijit.com", "sovrn.com")
    owner("33Across", "33across.com")
    owner("ID5", "id5-sync.com")
    owner("Lotame", "crwdcntrl.net")
    owner("Adform", "adform.net")
    owner("Ezoic", "ezoic.net")
    owner("Permutive", "permutive.com")
    owner("Parse.ly", "parsely.com")
    owner("Brightline", "brightline.tv")
    owner("Cloudflare", "cloudflareinsights.com")
    owner("Matomo", "matomo.cloud")
  }

  private fun MutableMap<String, String>.owner(name: String, vararg domains: String) {
    domains.forEach { put(it, name) }
  }

  /** The owner of [host], or null when the table does not know it. */
  fun ownerOf(host: String): String? = OWNERS[HostMatcher.registrableDomain(host.lowercase())]

  /**
   * [hosts] grouped by owner, largest group first; hosts with no known owner share one group
   * named [unknownLabel], which always comes last.
   */
  fun group(hosts: List<String>, unknownLabel: String): List<Pair<String, List<String>>> {
    val (known, unknown) = hosts.distinct().partition { ownerOf(it) != null }
    val groups = known.groupBy { ownerOf(it)!! }.toList()
      .sortedWith(
        compareByDescending<Pair<String, List<String>>> { it.second.size }.thenBy { it.first },
      )
    return if (unknown.isEmpty()) groups else groups + (unknownLabel to unknown)
  }
}
