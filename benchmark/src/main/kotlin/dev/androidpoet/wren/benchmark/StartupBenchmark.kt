/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

  @get:Rule
  val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun startup() = benchmarkRule.measureRepeated(
    packageName = PACKAGE_NAME,
    metrics = listOf(StartupTimingMetric()),
    iterations = ITERATIONS,
    startupMode = StartupMode.COLD,
  ) {
    pressHome()
    startActivityAndWait()
  }
}
