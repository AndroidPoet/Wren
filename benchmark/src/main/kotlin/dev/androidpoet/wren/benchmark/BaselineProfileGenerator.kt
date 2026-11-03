/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the classes and methods a cold start actually touches, so the release build ships them
 * pre-compiled instead of interpreting them on the user's first run.
 *
 * The journey deliberately stops at the speed dial: everything past it is network-bound, and a
 * profile is only worth what it can reproduce on any machine that runs it.
 */
class BaselineProfileGenerator {

  @get:Rule
  val rule = BaselineProfileRule()

  @Test
  fun startupAndHome() = rule.collect(packageName = PACKAGE_NAME) {
    pressHome()
    startActivityAndWait()
    // The home page owns the first frame; the omnibox only exists once a page is showing, so
    // the journey stays on the speed dial and exercises what a cold start actually draws.
    device.wait(Until.hasObject(By.res(PACKAGE_NAME, "homeScroll")), UI_TIMEOUT_MS)
    device.findObject(By.res(PACKAGE_NAME, "homeSearchCard"))?.click()
    device.waitForIdle()
    device.pressBack()
    device.findObject(By.res(PACKAGE_NAME, "homeScroll"))?.let { home ->
      home.scroll(androidx.test.uiautomator.Direction.DOWN, SCROLL_FRACTION)
      home.scroll(androidx.test.uiautomator.Direction.UP, SCROLL_FRACTION)
    }
    device.waitForIdle()
  }

  private companion object {
    const val UI_TIMEOUT_MS = 5_000L
    const val SCROLL_FRACTION = 0.8f
  }
}
