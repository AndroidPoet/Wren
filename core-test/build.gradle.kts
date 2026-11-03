/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */


plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "dev.androidpoet.wren.core.test"
}

dependencies {
  implementation(projects.coreModel)
  implementation(libs.coroutines)
  implementation(libs.coroutines.test)
  implementation(libs.junit)
}
