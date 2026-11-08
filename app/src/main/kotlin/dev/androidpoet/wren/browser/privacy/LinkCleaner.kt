/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.privacy

import java.net.URLDecoder

/**
 * Strips tracking parameters from links, the way DuckDuckGo's Link Tracking Protection and
 * Brave's query filter do.
 *
 * These parameters are how a click is attributed back to a campaign and, in the case of the
 * click identifiers, to an individual: `fbclid` survives in the address bar, in what you copy,
 * and in what you paste to somebody else.
 *
 * The list is an allowlist-of-known-junk rather than a pattern, because a parameter that merely
 * looks like tracking may be load-bearing. Removing the wrong one breaks the page, which is a
 * far worse outcome than leaving one behind. Names are compared case-insensitively and after
 * percent-decoding, so `UTM_SOURCE` and `utm%5Fsource` do not slip past; the values and the
 * rest of the address are left byte for byte as they were.
 */
object LinkCleaner {

  private val TRACKING_PARAMETERS = setOf(
    // Google Analytics campaign tags
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "utm_id", "utm_name", "utm_reader", "utm_social", "utm_brand", "utm_cid", "utm_referrer",
    "utm_source_platform", "utm_creative_format", "utm_marketing_tactic",
    // Click identifiers - these identify the click, not the campaign
    "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "gad_source",
    "gad_campaignid", "twclid", "ttclid", "yclid", "ysclid", "ymclid", "igshid", "igsh",
    "rdt_cid", "li_fat_id", "rb_clickid", "unicorn_click_id", "wickedid", "s_cid", "sfnsn",
    "_branch_match_id", "_branch_referrer", "_kx", "vgo_ee", "dm_i", "ebid",
    // Email and marketing platforms
    "mc_cid", "mc_eid", "mkt_tok", "vero_id", "vero_conv", "oly_enc_id", "oly_anon_id",
    "_hsenc", "_hsmi", "__hsfp", "__hssc", "__hstc", "hsctatracking", "ml_subscriber",
    "ml_subscriber_hash", "at_recipient_id", "at_recipient_list", "bsft_clkid", "bsft_uid",
    "bsft_mid", "bsft_eid", "bsft_aaid", "bsft_ek", "__s", "guce_referrer", "guce_referrer_sig",
    // Ad platforms
    "s_kwcid", "ef_id", "hsa_acc", "hsa_cam", "hsa_grp", "hsa_ad", "hsa_src",
    "hsa_tgt", "hsa_kw", "hsa_mt", "hsa_net", "hsa_ver",
    // Miscellaneous analytics
    "_openstat", "icid", "ncid", "cmpid", "sc_cid", "trk", "trkcampaign", "spm",
    // Cross-site linkers and merchant click ids
    "_ga", "_gl", "srsltid", "mibextid", "ref_src", "ref_url", "cvid", "ocid",
  )

  /** Returns [url] with tracking parameters removed, or [url] unchanged when there are none. */
  fun clean(url: String): String {
    val fragmentStart = url.indexOf('#')
    val beforeFragment = if (fragmentStart >= 0) url.substring(0, fragmentStart) else url
    val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
    // A '?' inside the fragment is part of the fragment, not a second query.
    val queryStart = beforeFragment.indexOf('?')
    if (queryStart < 0) return url

    val base = beforeFragment.substring(0, queryStart)
    val query = beforeFragment.substring(queryStart + 1)
    if (query.isEmpty()) return url

    val parameters = query.split('&')
    val kept = parameters.filter { parameter ->
      parameter.isNotEmpty() && !isTracking(parameter.substringBefore('='))
    }

    if (kept.size == parameters.count { it.isNotEmpty() }) return url
    return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") + fragment
  }

  /** True when [clean] would change [url]; lets callers avoid a pointless reload. */
  fun hasTrackingParameters(url: String): Boolean = clean(url) != url

  private fun isTracking(rawName: String): Boolean {
    if (rawName.isEmpty() || rawName.length > MAX_NAME_LENGTH) return false
    val name = if (rawName.indexOf('%') >= 0 || rawName.indexOf('+') >= 0) {
      runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
    } else {
      rawName
    }
    return name.lowercase() in TRACKING_PARAMETERS
  }

  private const val MAX_NAME_LENGTH = 64
}
