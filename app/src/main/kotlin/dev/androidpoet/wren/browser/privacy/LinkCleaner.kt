/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.privacy

/**
 * Strips tracking parameters from links, the way DuckDuckGo's Link Tracking Protection does.
 *
 * These parameters are how a click is attributed back to a campaign and, in the case of the
 * click identifiers, to an individual: `fbclid` survives in the address bar, in what you copy,
 * and in what you paste to somebody else.
 *
 * The list is an allowlist-of-known-junk rather than a pattern, because a parameter that merely
 * looks like tracking may be load-bearing. Removing the wrong one breaks the page, which is a
 * far worse outcome than leaving one behind.
 */
object LinkCleaner {

  private val TRACKING_PARAMETERS = setOf(
    // Google Analytics campaign tags
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "utm_id", "utm_name", "utm_reader", "utm_social", "utm_brand",
    // Click identifiers - these identify the click, not the campaign
    "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid",
    "twclid", "ttclid", "yclid", "igshid", "igsh", "rdt_cid", "li_fat_id",
    // Email and marketing platforms
    "mc_cid", "mc_eid", "mkt_tok", "vero_id", "vero_conv", "oly_enc_id", "oly_anon_id",
    "_hsenc", "_hsmi", "hsCtaTracking", "ml_subscriber", "ml_subscriber_hash",
    // Ad platforms
    "s_kwcid", "ef_id", "hsa_acc", "hsa_cam", "hsa_grp", "hsa_ad", "hsa_src",
    "hsa_tgt", "hsa_kw", "hsa_mt", "hsa_net", "hsa_ver",
    // Miscellaneous analytics
    "_openstat", "icid", "ncid", "cmpid", "sc_cid", "trk", "trkCampaign", "spm",
    // Cross-site linkers and merchant click ids
    "_ga", "_gl", "srsltid", "mibextid", "ref_src", "ref_url", "cvid", "ocid",
  )

  /** Returns [url] with tracking parameters removed, or [url] unchanged when there are none. */
  fun clean(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url

    val fragmentStart = url.indexOf('#', queryStart)
    val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
    val queryEnd = if (fragmentStart >= 0) fragmentStart else url.length

    val base = url.substring(0, queryStart)
    val query = url.substring(queryStart + 1, queryEnd)
    if (query.isEmpty()) return url

    val kept = query.split('&').filter { parameter ->
      parameter.isNotEmpty() && parameter.substringBefore('=') !in TRACKING_PARAMETERS
    }

    if (kept.size == query.split('&').count { it.isNotEmpty() }) return url
    return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") + fragment
  }

  /** True when [clean] would change [url]; lets callers avoid a pointless reload. */
  fun hasTrackingParameters(url: String): Boolean = clean(url) != url
}
