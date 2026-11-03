/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

/** A filter list the user can switch on; the bundled snapshot covers the default ones offline. */
class FilterList(
  val id: String,
  val title: String,
  val url: String,
  val enabledByDefault: Boolean,
  val group: Group,
) {
  enum class Group { CORE, ANNOYANCES, REGIONAL }
}

/**
 * The lists on offer. Kept to sources that are maintained daily and licensed for reuse; the
 * first four are what the bundled snapshot was compiled from.
 */
object FilterListCatalog {
  const val CUSTOM_ID = "custom"

  val all: List<FilterList> = listOf(
    FilterList(
      "easylist",
      "EasyList (ads)",
      "https://easylist.to/easylist/easylist.txt",
      true,
      FilterList.Group.CORE,
    ),
    FilterList(
      "easyprivacy",
      "EasyPrivacy (trackers)",
      "https://easylist.to/easylist/easyprivacy.txt",
      true,
      FilterList.Group.CORE,
    ),
    FilterList(
      "ublock-filters",
      "uBlock filters",
      "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
      true,
      FilterList.Group.CORE,
    ),
    FilterList(
      "ublock-quick-fixes",
      "uBlock quick fixes",
      "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt",
      true,
      FilterList.Group.CORE,
    ),
    FilterList(
      "ublock-unbreak",
      "uBlock unbreak",
      "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
      true,
      FilterList.Group.CORE,
    ),
    FilterList(
      "ublock-privacy",
      "uBlock privacy",
      "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
      true,
      FilterList.Group.CORE,
    ),
    FilterList(
      "stevenblack",
      "Malware and tracker hosts (StevenBlack)",
      "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
      true,
      FilterList.Group.CORE,
    ),
    FilterList(
      "cookies",
      "Cookie banners (EasyList Cookie)",
      "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
      false,
      FilterList.Group.ANNOYANCES,
    ),
    FilterList(
      "annoyances",
      "Popups and overlays (Fanboy Annoyance)",
      "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
      false,
      FilterList.Group.ANNOYANCES,
    ),
    FilterList(
      "social",
      "Social widgets (Fanboy Social)",
      "https://easylist.to/easylist/fanboy-social.txt",
      false,
      FilterList.Group.ANNOYANCES,
    ),
    FilterList(
      "germany",
      "Germany",
      "https://easylist.to/easylistgermany/easylistgermany.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "india",
      "India",
      "https://easylist-downloads.adblockplus.org/indianlist.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "china",
      "China",
      "https://easylist-downloads.adblockplus.org/easylistchina.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "france",
      "France",
      "https://easylist-downloads.adblockplus.org/liste_fr.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "spain",
      "Spain and Latin America",
      "https://easylist-downloads.adblockplus.org/easylistspanish.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "italy",
      "Italy",
      "https://easylist-downloads.adblockplus.org/easylistitaly.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "russia",
      "Russia",
      "https://easylist-downloads.adblockplus.org/advblock.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "portuguese",
      "Portugal and Brazil",
      "https://easylist-downloads.adblockplus.org/easylistportuguese.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "netherlands",
      "Netherlands",
      "https://easylist-downloads.adblockplus.org/easylistdutch.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "arabic",
      "Arabic",
      "https://easylist-downloads.adblockplus.org/Liste_AR.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
    FilterList(
      "indonesia",
      "Indonesia",
      "https://raw.githubusercontent.com/ABPindo/indonesianadblockrules/master/subscriptions/" +
        "abpindo.txt",
      false,
      FilterList.Group.REGIONAL,
    ),
  )

  /** The lists whose content the bundled snapshot already carries. */
  val bundledIds: Set<String> = setOf(
    "easylist",
    "easyprivacy",
    "ublock-filters",
    "ublock-quick-fixes",
    "ublock-unbreak",
    "ublock-privacy",
    "stevenblack",
  )

  val defaultIds: Set<String> = all.filter { it.enabledByDefault }.map { it.id }.toSet()

  fun byId(id: String): FilterList? = all.firstOrNull { it.id == id }
}
