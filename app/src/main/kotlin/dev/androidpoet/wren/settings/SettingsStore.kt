/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.androidpoet.wren.browser.tor.BridgeMode
import dev.androidpoet.wren.core.model.SearchEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Browser preferences. Deliberately backed by [SharedPreferences] rather than DataStore: every
 * value here is read synchronously while a WebView is being configured, before any page loads.
 */
@Singleton
class SettingsStore @Inject constructor(
  private val preferences: SharedPreferences,
) {

  var searchEngine: SearchEngine
    get() = SearchEngine.fromNameOrDefault(preferences.getString(KEY_SEARCH_ENGINE, null))
    set(value) = preferences.edit { putString(KEY_SEARCH_ENGINE, value.name) }

  /** A user-supplied search URL template with a %s placeholder, or blank to use [searchEngine]. */
  var customSearchTemplate: String
    get() = preferences.getString(KEY_CUSTOM_SEARCH, "").orEmpty()
    set(value) = preferences.edit { putString(KEY_CUSTOM_SEARCH, value) }

  var customSearchName: String
    get() = preferences.getString(KEY_CUSTOM_SEARCH_NAME, "").orEmpty()
    set(value) = preferences.edit { putString(KEY_CUSTOM_SEARCH_NAME, value) }

  val usingCustomSearch: Boolean get() = customSearchTemplate.contains("%s")

  /** The active search template, custom if one is set, otherwise the chosen engine's. */
  val searchTemplate: String
    get() = customSearchTemplate.takeIf { it.contains("%s") } ?: searchEngine.queryTemplate

  val searchLabel: String
    get() = if (usingCustomSearch) customSearchName.ifBlank { "Custom" } else searchEngine.label

  val searchHomeUrl: String
    get() = if (usingCustomSearch) {
      "https://" + searchTemplate.substringAfter("://", "").substringBefore('/')
    } else {
      searchEngine.homeUrl
    }

  /**
   * When false the omnibox never contacts the search engine while typing. Off until the user
   * turns it on: every keystroke it sends is a query the user has not yet decided to make.
   */
  var remoteSuggestions: Boolean
    get() = preferences.getBoolean(KEY_REMOTE_SUGGESTIONS, false)
    set(value) = preferences.edit { putBoolean(KEY_REMOTE_SUGGESTIONS, value) }

  var javaScriptEnabled: Boolean
    get() = preferences.getBoolean(KEY_JAVASCRIPT, true)
    set(value) = preferences.edit { putBoolean(KEY_JAVASCRIPT, value) }

  var blockThirdPartyCookies: Boolean
    get() = preferences.getBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, true)
    set(value) = preferences.edit { putBoolean(KEY_BLOCK_THIRD_PARTY_COOKIES, value) }

  var sendDoNotTrack: Boolean
    get() = preferences.getBoolean(KEY_DO_NOT_TRACK, true)
    set(value) = preferences.edit { putBoolean(KEY_DO_NOT_TRACK, value) }

  /** Google's dangerous-site check through the system; always off under Tor regardless. */
  var safeBrowsing: Boolean
    get() = preferences.getBoolean(KEY_SAFE_BROWSING, true)
    set(value) = preferences.edit { putBoolean(KEY_SAFE_BROWSING, value) }

  /** Wipes cookies and cache when the browser is left; bookmarks and history stay. */
  var clearOnExit: Boolean
    get() = preferences.getBoolean(KEY_CLEAR_ON_EXIT, false)
    set(value) = preferences.edit { putBoolean(KEY_CLEAR_ON_EXIT, value) }

  /**
   * Set when the browser goes to the background with [clearOnExit] on, cleared when it comes
   * back. A cold start that finds it set knows the process died in between and finishes the
   * clear that the exit never got to run.
   */
  var clearPending: Boolean
    get() = preferences.getBoolean(KEY_CLEAR_PENDING, false)
    set(value) = preferences.edit(commit = true) { putBoolean(KEY_CLEAR_PENDING, value) }

  var recordHistory: Boolean
    get() = preferences.getBoolean(KEY_RECORD_HISTORY, true)
    set(value) = preferences.edit { putBoolean(KEY_RECORD_HISTORY, value) }

  /**
   * Warm up, preconnect and prefetch the page the user is most likely to open next. Fetches a
   * page before it is committed to, so it is off in private tabs regardless of this flag.
   */
  var preloadPages: Boolean
    get() = preferences.getBoolean(KEY_PRELOAD_PAGES, true)
    set(value) = preferences.edit { putBoolean(KEY_PRELOAD_PAGES, value) }

  /** The named session the open tabs belong to. */
  var currentSession: String
    get() = preferences.getString(KEY_CURRENT_SESSION, DEFAULT_SESSION).orEmpty()
    set(value) = preferences.edit { putString(KEY_CURRENT_SESSION, value) }

  /** Puts the address bar at the bottom of the screen, within thumb reach. */
  var bottomAddressBar: Boolean
    get() = preferences.getBoolean(KEY_BOTTOM_BAR, false)
    set(value) = preferences.edit { putBoolean(KEY_BOTTOM_BAR, value) }

  /** Keep the screen awake while the browser is in the foreground. */
  var keepScreenOn: Boolean
    get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
    set(value) = preferences.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

  /** 0 follows the sensor, 1 locks portrait, 2 locks landscape. */
  var orientationLock: Int
    get() = preferences.getInt(KEY_ORIENTATION_LOCK, 0)
    set(value) = preferences.edit { putInt(KEY_ORIENTATION_LOCK, value) }

  /** Ask sites for lighter images and payloads (Save-Data) while on a metered network. */
  var dataSaver: Boolean
    get() = preferences.getBoolean(KEY_DATA_SAVER, true)
    set(value) = preferences.edit { putBoolean(KEY_DATA_SAVER, value) }

  /** Require the device credential to open the app after it leaves the foreground. */
  var appLock: Boolean
    get() = preferences.getBoolean(KEY_APP_LOCK, false)
    set(value) = preferences.edit { putBoolean(KEY_APP_LOCK, value) }

  /** URL of a subscribed hosts-format filter list; blank means bundled-only blocking. */
  var blocklistUrl: String
    get() = preferences.getString(KEY_BLOCKLIST_URL, "").orEmpty()
    set(value) = preferences.edit { putString(KEY_BLOCKLIST_URL, value) }

  var blocklistUpdatedAt: Long
    get() = preferences.getLong(KEY_BLOCKLIST_UPDATED, 0L)
    set(value) = preferences.edit { putLong(KEY_BLOCKLIST_UPDATED, value) }

  /** Ids of the filter lists in the catalog the user has switched on; null until first changed. */
  var enabledFilterLists: Set<String>?
    get() = preferences.getStringSet(KEY_FILTER_LISTS, null)?.toSet()
    set(value) = preferences.edit { putStringSet(KEY_FILTER_LISTS, value) }

  fun filterListUpdatedAt(id: String): Long = preferences.getLong("$KEY_FILTER_LIST_UPDATED$id", 0L)

  fun setFilterListUpdatedAt(id: String, value: Long) =
    preferences.edit { putLong("$KEY_FILTER_LIST_UPDATED$id", value) }

  /** The shape of the cached filter lists; see FilterListManager.LIST_FORMAT. */
  var filterListFormat: Int
    get() = preferences.getInt(KEY_FILTER_LIST_FORMAT, 0)
    set(value) = preferences.edit { putInt(KEY_FILTER_LIST_FORMAT, value) }

  fun filterListEtag(id: String): String? = preferences.getString("$KEY_FILTER_LIST_ETAG$id", null)

  fun setFilterListEtag(id: String, value: String?) =
    preferences.edit { putString("$KEY_FILTER_LIST_ETAG$id", value) }

  /** Ordinal of the active [dev.androidpoet.wren.browser.RenderingMode]. */
  var renderingMode: Int
    get() = preferences.getInt(KEY_RENDERING_MODE, 0)
    set(value) = preferences.edit { putInt(KEY_RENDERING_MODE, value) }

  /** WebView text scaling as a percentage; 100 is the page's own size. */
  var textZoom: Int
    get() = preferences.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
    set(value) = preferences.edit { putInt(KEY_TEXT_ZOOM, value) }

  /** One of [AppCompatDelegate]'s night modes; the default follows the system. */
  var themeMode: Int
    get() = preferences.getInt(KEY_THEME_MODE, MODE_NIGHT_FOLLOW_SYSTEM)
    set(value) = preferences.edit { putInt(KEY_THEME_MODE, value) }

  /**
   * Fetch a site's own icon for the home page when browsing has not already produced one.
   * Contacts each site directly, never a favicon service.
   */
  var loadSiteIcons: Boolean
    get() = preferences.getBoolean(KEY_SITE_ICONS, true)
    set(value) = preferences.edit { putBoolean(KEY_SITE_ICONS, value) }

  var homeConfigEtag: String?
    get() = preferences.getString(KEY_HOME_CONFIG_ETAG, null)
    set(value) = preferences.edit { putString(KEY_HOME_CONFIG_ETAG, value) }

  var homeConfigFetchedAt: Long
    get() = preferences.getLong(KEY_HOME_CONFIG_FETCHED, 0L)
    set(value) = preferences.edit { putLong(KEY_HOME_CONFIG_FETCHED, value) }

  /** Routes every tab, and the app's own requests, through the bundled Tor client. */
  var torMode: Boolean
    get() = preferences.getBoolean(KEY_TOR_MODE, false)
    set(value) = preferences.edit { putBoolean(KEY_TOR_MODE, value) }

  var torSocksPort: Int
    get() = preferences.getInt(KEY_TOR_SOCKS_PORT, 0)
    set(value) = preferences.edit { putInt(KEY_TOR_SOCKS_PORT, value) }

  var torHttpPort: Int
    get() = preferences.getInt(KEY_TOR_HTTP_PORT, 0)
    set(value) = preferences.edit { putInt(KEY_TOR_HTTP_PORT, value) }

  /** How Tor reaches its first relay where plain Tor is blocked; [BridgeMode.NONE] by default. */
  var torBridgeMode: BridgeMode
    get() = preferences.getString(KEY_TOR_BRIDGE_MODE, null)
      ?.let { name -> BridgeMode.entries.firstOrNull { it.name == name } }
      ?: BridgeMode.NONE
    set(value) = preferences.edit { putString(KEY_TOR_BRIDGE_MODE, value.name) }

  /**
   * Tor Browser's "Safest" security level for Tor pages: JavaScript off everywhere and web
   * fonts blocked. Off by default, since most of the web stops working at this level.
   */
  var torSafest: Boolean
    get() = preferences.getBoolean(KEY_TOR_SAFEST, false)
    set(value) = preferences.edit { putBoolean(KEY_TOR_SAFEST, value) }

  /** True while pages are on Tor and the user asked for the Safest level. */
  val torSafestActive: Boolean get() = torMode && torSafest

  /** The user's own bridge lines, kept as pasted; validated again before they reach torrc. */
  var torCustomBridges: String
    get() = preferences.getString(KEY_TOR_CUSTOM_BRIDGES, "").orEmpty()
    set(value) = preferences.edit { putString(KEY_TOR_CUSTOM_BRIDGES, value) }

  /** Answer cookie consent pop-ups with the most private option and hide them. */
  var answerCookiePopups: Boolean
    get() = preferences.getBoolean(KEY_COOKIE_POPUPS, true)
    set(value) = preferences.edit { putBoolean(KEY_COOKIE_POPUPS, value) }

  /** Perturb canvas, audio and WebGL readings so they cannot be used as an identifier. */
  var blockFingerprinting: Boolean
    get() = preferences.getBoolean(KEY_FINGERPRINTING, true)
    set(value) = preferences.edit { putBoolean(KEY_FINGERPRINTING, value) }

  /** Send only the site name as the referrer, never the full page address. */
  var trimReferrers: Boolean
    get() = preferences.getBoolean(KEY_TRIM_REFERRERS, true)
    set(value) = preferences.edit { putBoolean(KEY_TRIM_REFERRERS, value) }

  /** Try https before http on every navigation. */
  var httpsUpgrade: Boolean
    get() = preferences.getBoolean(KEY_HTTPS_UPGRADE, true)
    set(value) = preferences.edit { putBoolean(KEY_HTTPS_UPGRADE, value) }

  /**
   * Refuse plain-http documents and mixed content outright, instead of upgrading and falling
   * back. A site the user has excused on the error page is the one exception, for the session.
   */
  var httpsOnly: Boolean
    get() = preferences.getBoolean(KEY_HTTPS_ONLY, false)
    set(value) = preferences.edit { putBoolean(KEY_HTTPS_ONLY, value) }

  /** Strip campaign and click identifiers from addresses before loading them. */
  var stripTrackingParameters: Boolean
    get() = preferences.getBoolean(KEY_STRIP_TRACKING, true)
    set(value) = preferences.edit { putBoolean(KEY_STRIP_TRACKING, value) }

  /** Block requests to known ad and tracking hosts. */
  /**
   * Read on every request the blocker sees, so both live in fields rather than behind the
   * preference lock. The setters update the fields first; the listener covers writes made
   * from elsewhere, such as the settings screen's own preference widgets.
   */
  @Volatile
  private var blockAdsCached: Boolean = preferences.getBoolean(KEY_BLOCK_ADS, true)

  @Volatile
  private var allowlistCached: Set<String> =
    HashSet(preferences.getStringSet(KEY_ALLOWLIST, emptySet()).orEmpty())

  private val hotKeyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    when (key) {
      KEY_BLOCK_ADS -> blockAdsCached = preferences.getBoolean(KEY_BLOCK_ADS, true)
      KEY_ALLOWLIST ->
        allowlistCached = HashSet(preferences.getStringSet(KEY_ALLOWLIST, emptySet()).orEmpty())
    }
  }

  init {
    preferences.registerOnSharedPreferenceChangeListener(hotKeyListener)
  }

  var blockAds: Boolean
    get() = blockAdsCached
    set(value) {
      blockAdsCached = value
      preferences.edit { putBoolean(KEY_BLOCK_ADS, value) }
    }

  /** Hosts the user has chosen to stop blocking on, usually because a site broke. */
  private var jsOffHosts: Set<String>
    get() = preferences.getStringSet(KEY_JS_OFF_HOSTS, emptySet()).orEmpty()
    set(value) = preferences.edit { putStringSet(KEY_JS_OFF_HOSTS, value) }

  /**
   * Per-site choices made in a private or Tor tab, by kind and then host. They are kept in
   * memory and never written: a host name on disk would be a record of a site visited where
   * nothing is recorded, past Forget, Burn and New identity. [forgetSessionSiteChoices] drops
   * them when that session ends. A choice here is read before the stored one.
   */
  private val sessionSiteChoices = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()

  fun forgetSessionSiteChoices() = sessionSiteChoices.clear()

  private fun sessionChoice(kind: String, host: String): Boolean? = sessionSiteChoices[kind]?.get(
    host,
  )

  /** True when the choice was for this session only, and so has been kept off the disk. */
  private fun choose(kind: String, host: String, value: Boolean, sessionOnly: Boolean): Boolean {
    if (sessionOnly) {
      sessionSiteChoices.getOrPut(kind) { ConcurrentHashMap() }[host] = value
      return true
    }
    sessionSiteChoices[kind]?.remove(host)
    return false
  }

  fun isJsDisabledFor(host: String): Boolean =
    sessionChoice(KEY_JS_OFF_HOSTS, host) ?: (host in jsOffHosts)

  fun setJsDisabledFor(host: String, disabled: Boolean, sessionOnly: Boolean = false) {
    if (host.isBlank()) return
    if (choose(KEY_JS_OFF_HOSTS, host, disabled, sessionOnly)) return
    val updated = jsOffHosts.toMutableSet()
    if (disabled) updated.add(host) else updated.remove(host)
    jsOffHosts = updated
  }

  private var desktopHosts: Set<String>
    get() = preferences.getStringSet(KEY_DESKTOP_HOSTS, emptySet()).orEmpty()
    set(value) = preferences.edit { putStringSet(KEY_DESKTOP_HOSTS, value) }

  fun isDesktopSite(host: String): Boolean =
    sessionChoice(KEY_DESKTOP_HOSTS, host) ?: (host in desktopHosts)

  fun setDesktopSite(host: String, desktop: Boolean, sessionOnly: Boolean = false) {
    if (host.isBlank()) return
    if (choose(KEY_DESKTOP_HOSTS, host, desktop, sessionOnly)) return
    val updated = desktopHosts.toMutableSet()
    if (desktop) updated.add(host) else updated.remove(host)
    desktopHosts = updated
  }

  var allowlistedHosts: Set<String>
    get() = allowlistCached
    private set(value) {
      allowlistCached = HashSet(value)
      preferences.edit { putStringSet(KEY_ALLOWLIST, value) }
    }

  fun isAllowlisted(host: String): Boolean =
    sessionChoice(KEY_ALLOWLIST, host) ?: (host in allowlistCached)

  fun setAllowlisted(host: String, allowlisted: Boolean, sessionOnly: Boolean = false) {
    if (choose(KEY_ALLOWLIST, host, allowlisted, sessionOnly)) return
    // getStringSet hands back a live set that must not be mutated in place.
    val updated = allowlistedHosts.toMutableSet()
    if (allowlisted) updated.add(host) else updated.remove(host)
    allowlistedHosts = updated
  }

  /**
   * Sites whose cookies and storage survive "clear browsing data". Clearing is for the sites
   * a person visited once; the handful they are signed in to every day stay signed in.
   */
  fun keptSignedInHosts(): Set<String> {
    val stored = preferences.getStringSet(KEY_KEEP_SIGNED_IN, emptySet()).orEmpty().toMutableSet()
    sessionSiteChoices[KEY_KEEP_SIGNED_IN]?.forEach { (host, kept) ->
      if (kept) stored.add(host) else stored.remove(host)
    }
    return stored
  }

  fun isKeptSignedIn(host: String): Boolean = host in keptSignedInHosts()

  fun setKeptSignedIn(host: String, kept: Boolean, sessionOnly: Boolean = false) {
    if (host.isBlank()) return
    if (choose(KEY_KEEP_SIGNED_IN, host, kept, sessionOnly)) return
    val updated = preferences.getStringSet(KEY_KEEP_SIGNED_IN, emptySet()).orEmpty().toMutableSet()
    if (kept) updated.add(host) else updated.remove(host)
    preferences.edit { putStringSet(KEY_KEEP_SIGNED_IN, updated) }
  }

  /**
   * Repaint sites that ship no dark theme of their own. Off by default: Chrome leaves its
   * equivalent off because algorithmic darkening mangles images and low-contrast text.
   */
  var darkenWebPages: Boolean
    // An earlier build had this setting twice under two names; whichever one a person turned
    // on still counts.
    get() = preferences.getBoolean(
      KEY_DARKEN_WEB_PAGES,
      preferences.getBoolean(KEY_DARK_WEB_PAGES, false),
    )
    set(value) = preferences.edit { putBoolean(KEY_DARKEN_WEB_PAGES, value) }

  var desktopMode: Boolean
    get() = preferences.getBoolean(KEY_DESKTOP_MODE, false)
    set(value) = preferences.edit { putBoolean(KEY_DESKTOP_MODE, value) }

  /** Emits the store itself whenever any preference changes, plus once on collection. */
  fun changes(): Flow<SettingsStore> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
      trySend(this@SettingsStore)
    }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
  }.onStart { emit(this@SettingsStore) }

  companion object {
    const val MODE_NIGHT_FOLLOW_SYSTEM = -1
    const val MODE_NIGHT_NO = 1
    const val MODE_NIGHT_YES = 2
    val THEME_MODES = listOf(MODE_NIGHT_FOLLOW_SYSTEM, MODE_NIGHT_NO, MODE_NIGHT_YES)

    const val DEFAULT_TEXT_ZOOM = 100

    /** The sizes offered in settings, so the stored value is always one the UI can show. */
    val TEXT_ZOOM_STEPS = listOf(75, 90, 100, 115, 130, 150, 175, 200)

    private const val KEY_SEARCH_ENGINE = "search_engine"
    private const val KEY_CUSTOM_SEARCH = "custom_search"
    private const val KEY_CUSTOM_SEARCH_NAME = "custom_search_name"
    private const val KEY_REMOTE_SUGGESTIONS = "remote_suggestions"
    private const val KEY_JAVASCRIPT = "javascript_enabled"
    private const val KEY_BLOCK_THIRD_PARTY_COOKIES = "block_third_party_cookies"
    private const val KEY_DO_NOT_TRACK = "do_not_track"
    private const val KEY_SAFE_BROWSING = "safe_browsing"
    private const val KEY_CLEAR_ON_EXIT = "clear_on_exit"
    private const val KEY_RECORD_HISTORY = "record_history"
    private const val KEY_PRELOAD_PAGES = "preload_pages"
    const val DEFAULT_SESSION = "Home"
    private const val KEY_CURRENT_SESSION = "current_session"
    private const val KEY_DARK_WEB_PAGES = "dark_web_pages"
    private const val KEY_BOTTOM_BAR = "bottom_address_bar"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_ORIENTATION_LOCK = "orientation_lock"
    private const val KEY_DATA_SAVER = "data_saver"
    private const val KEY_APP_LOCK = "app_lock"
    private const val KEY_BLOCKLIST_URL = "blocklist_url"
    private const val KEY_BLOCKLIST_UPDATED = "blocklist_updated"
    private const val KEY_FILTER_LISTS = "filter_lists"
    private const val KEY_FILTER_LIST_UPDATED = "filter_list_updated_"
    private const val KEY_FILTER_LIST_FORMAT = "filter_list_format"
    private const val KEY_FILTER_LIST_ETAG = "filter_list_etag_"
    private const val KEY_RENDERING_MODE = "rendering_mode"
    private const val KEY_TEXT_ZOOM = "text_zoom"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_SITE_ICONS = "site_icons"
    private const val KEY_HOME_CONFIG_ETAG = "home_config_etag"
    private const val KEY_HOME_CONFIG_FETCHED = "home_config_fetched_at"
    private const val KEY_TOR_MODE = "tor_mode"
    private const val KEY_TOR_SOCKS_PORT = "tor_socks_port"
    private const val KEY_TOR_HTTP_PORT = "tor_http_port"
    private const val KEY_TOR_BRIDGE_MODE = "tor_bridge_mode"
    private const val KEY_TOR_CUSTOM_BRIDGES = "tor_custom_bridges"
    private const val KEY_TOR_SAFEST = "tor_safest"
    private const val KEY_COOKIE_POPUPS = "cookie_popups"
    private const val KEY_FINGERPRINTING = "block_fingerprinting"
    private const val KEY_TRIM_REFERRERS = "trim_referrers"
    private const val KEY_HTTPS_UPGRADE = "https_upgrade"
    private const val KEY_STRIP_TRACKING = "strip_tracking"
    private const val KEY_HTTPS_ONLY = "https_only"
    private const val KEY_CLEAR_PENDING = "clear_pending"
    private const val KEY_BLOCK_ADS = "block_ads"
    private const val KEY_ALLOWLIST = "blocking_allowlist"
    private const val KEY_JS_OFF_HOSTS = "js_off_hosts"
    private const val KEY_DESKTOP_HOSTS = "desktop_hosts"
    private const val KEY_DARKEN_WEB_PAGES = "darken_web_pages"
    private const val KEY_KEEP_SIGNED_IN = "keep_signed_in_hosts"
    private const val KEY_DESKTOP_MODE = "desktop_mode"
  }
}
