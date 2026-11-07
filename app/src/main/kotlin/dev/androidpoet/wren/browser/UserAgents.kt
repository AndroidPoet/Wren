/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import androidx.webkit.UserAgentMetadata

/**
 * The user agent Chrome on Android sends since it reduced the string: no device model, no
 * build id, no Android version, no WebView markers, only the engine's major version.
 *
 * The system WebView still names the exact phone (`Pixel 8 Build/AP2A...`) and marks itself
 * with `; wv` and `Version/4.0`, which together identify the device and single out WebView
 * browsers. Chrome's own reduced form is what the largest number of Android users already
 * send, so matching it is the most anonymous choice and the one sites are built for.
 */
object UserAgents {

  const val PLATFORM_MOBILE = "Android"
  const val PLATFORM_DESKTOP = "Linux"

  /** The model Chrome reports for every Android device since the reduction. */
  const val REDUCED_MODEL = "K"

  /** The Android version Chrome reports for every device since the reduction. */
  const val REDUCED_PLATFORM_VERSION = "10.0.0"

  fun mobile(chromeMajor: Int): String =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/$chromeMajor.0.0.0 Mobile Safari/537.36"

  fun desktop(chromeMajor: Int): String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/$chromeMajor.0.0.0 Safari/537.36"

  /**
   * The Chrome major in a WebView package version (`133.0.6943.137`) or in a user agent
   * string, or null when neither names one.
   */
  fun chromeMajor(versionName: String?, defaultUserAgent: String?): Int? {
    versionName?.substringBefore('.')?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    val ua = defaultUserAgent ?: return null
    val at = ua.indexOf("Chrome/")
    if (at < 0) return null
    return ua.substring(at + "Chrome/".length).takeWhile { it.isDigit() }.toIntOrNull()
  }

  /**
   * Client hints to match the string: what a page gets from `navigator.userAgentData` and
   * the `Sec-CH-UA-*` headers. Without this the engine answers with its own brand
   * (`Android WebView`), the real model and the real Android version, undoing the string.
   */
  fun metadata(chromeMajor: Int, desktop: Boolean, engineVersion: String?): UserAgentMetadata {
    val fullVersion = fullVersion(chromeMajor, engineVersion)
    val brands = brandList(chromeMajor, fullVersion).map { (brand, major, full) ->
      UserAgentMetadata.BrandVersion.Builder()
        .setBrand(brand)
        .setMajorVersion(major)
        .setFullVersion(full)
        .build()
    }
    return UserAgentMetadata.Builder()
      .setBrandVersionList(brands)
      .setFullVersion(fullVersion)
      .setPlatform(if (desktop) PLATFORM_DESKTOP else PLATFORM_MOBILE)
      .setPlatformVersion(if (desktop) "" else REDUCED_PLATFORM_VERSION)
      .setArchitecture(if (desktop) "x86" else "")
      .setModel(if (desktop) "" else REDUCED_MODEL)
      .setMobile(!desktop)
      .setBitness(if (desktop) 64 else 0)
      .setWow64(false)
      .build()
  }

  /**
   * The engine's real four-part version when it belongs to this major, else the reduced form.
   * Chrome's full-version hint is never `x.0.0.0`; sending that would mark the browser.
   */
  fun fullVersion(chromeMajor: Int, engineVersion: String?): String {
    val real = engineVersion?.trim().orEmpty()
    val isFull = real.matches(FOUR_PART) && real.substringBefore('.') == chromeMajor.toString()
    return if (isFull) real else "$chromeMajor.0.0.0"
  }

  /** One brand entry: name, major version, full version. */
  data class Brand(val name: String, val major: String, val full: String)

  /**
   * Chromium's own brand list for a given major: a GREASE entry whose punctuation, version and
   * position are chosen from the major number, then Chromium and the product brand, in the
   * order Chrome of that major sends. Anything else is a brand list no Chrome ever sent.
   */
  fun brandList(chromeMajor: Int, fullVersion: String): List<Brand> {
    val seed = chromeMajor
    val greaseName = "Not" + GREASE_CHARS[seed % GREASE_CHARS.size] + "A" +
      GREASE_CHARS[(seed + 1) % GREASE_CHARS.size] + "Brand"
    val greaseVersion = GREASE_VERSIONS[seed % GREASE_VERSIONS.size]
    val major = chromeMajor.toString()
    val entries = listOf(
      Brand(greaseName, greaseVersion, "$greaseVersion.0.0.0"),
      Brand("Chromium", major, fullVersion),
      Brand(PRODUCT_BRAND, major, fullVersion),
    )
    return GREASE_ORDERS[seed % GREASE_ORDERS.size].map(entries::get)
  }

  private val FOUR_PART = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")
  private val GREASE_CHARS = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
  private val GREASE_VERSIONS = listOf("8", "99", "24")
  private val GREASE_ORDERS = listOf(
    listOf(0, 1, 2),
    listOf(0, 2, 1),
    listOf(1, 0, 2),
    listOf(1, 2, 0),
    listOf(2, 0, 1),
    listOf(2, 1, 0),
  )

  /** The string claims Chrome, so the brand list does too; a lone Chromium is its own marker. */
  private const val PRODUCT_BRAND = "Google Chrome"
}
