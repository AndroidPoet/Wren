/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/** Swaps in [HiltTestApplication] so instrumentation tests get an injectable component. */
class AppTestRunner : AndroidJUnitRunner() {

  override fun newApplication(
    classLoader: ClassLoader?,
    className: String?,
    context: Context?,
  ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
