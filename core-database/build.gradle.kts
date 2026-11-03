/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */


import com.android.build.api.variant.HostTestBuilder

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.ksp)
}

android {
  namespace = "dev.androidpoet.wren.core.database"

  defaultConfig {
    // The schemas directory contains a schema file for each version of the Room database.
    // This is required to enable Room auto migrations.
    ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
    }
  }
}

androidComponents {
  onVariants { variant ->
    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]
      ?.sources
      ?.assets
      ?.addStaticSourceDirectory("$projectDir/schemas")
  }
}

dependencies {
  implementation(projects.coreModel)
  testImplementation(projects.coreTest)

  // coroutines
  implementation(libs.coroutines)
  testImplementation(libs.coroutines)
  testImplementation(libs.coroutines.test)

  // database
  api(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  testImplementation(libs.androidx.arch.core)

  // di
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)

  // unit test
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.robolectric)
}
