import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "dev.androidpoet.wren.benchmark"

  kotlin {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_11
    }
  }

  defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    // This benchmark buildType is used for benchmarking, and should function like your
    // release build (for example, with minification on). It"s signed with a debug key
    // for easy local/CI testing.
    create("benchmark") {
      isDebuggable = true
      signingConfig = getByName("debug").signingConfig
      matchingFallbacks += listOf("release")
    }
  }

  targetProjectPath = ":app"
  experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
  implementation(libs.profileinstaller)
  implementation(libs.macrobenchmark)
  implementation(libs.uiautomator)
  implementation(libs.android.test.runner)
  implementation(libs.androidx.junit)
}

androidComponents {
  beforeVariants(selector().all()) {
    // The macrobenchmarks run against the release-like "benchmark" type; the baseline profile
    // plugin adds its own release-derived types and needs them left enabled.
    it.enable = it.buildType != "debug"
  }
}
