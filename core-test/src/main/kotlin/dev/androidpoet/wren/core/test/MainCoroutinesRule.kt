/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Swaps the main dispatcher for a [TestDispatcher] for the duration of a test. */
class MainCoroutinesRule(
  val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

  override fun starting(description: Description) {
    super.starting(description)
    Dispatchers.setMain(testDispatcher)
  }

  override fun finished(description: Description) {
    super.finished(description)
    Dispatchers.resetMain()
  }
}
