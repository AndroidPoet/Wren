/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */


plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.parcelize)
  alias(libs.plugins.ksp)
}

android {
  namespace = "dev.androidpoet.wren.core.model"
}

dependencies {
  // json parsing
  implementation(libs.moshi)
  ksp(libs.moshi.codegen)

  // logger
  api(libs.timber)
}
