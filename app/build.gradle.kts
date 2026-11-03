/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import dev.androidpoet.wren.Configuration
import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.parcelize)
  alias(libs.plugins.hilt.plugin)
  alias(libs.plugins.androidx.baselineprofile)
}

// Release signing comes from a gitignored keystore.properties at the repo root. Without it the
// release build stays unsigned rather than failing, so a fresh clone still compiles.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
  namespace = "dev.androidpoet.wren"

  defaultConfig {
    applicationId = "dev.androidpoet.wren"
    versionCode = Configuration.versionCode
    versionName = Configuration.versionName
    testInstrumentationRunner = "dev.androidpoet.wren.AppTestRunner"
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
  }

  hilt {
    enableAggregatingTask = true
  }

  kotlin {
    sourceSets.configureEach {
      kotlin.srcDir(layout.buildDirectory.files("generated/ksp/$name/kotlin/"))
    }
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
    }
    create("benchmark") {
      isDebuggable = true
      signingConfig = getByName("debug").signingConfig
      matchingFallbacks += listOf("release")
    }
  }
}

tasks.withType<Test>().configureEach {
  // Opt-in micro-benchmarks print their numbers only when asked for on the command line.
  systemProperty("wren.bench", System.getProperty("wren.bench") ?: "false")
}

dependencies {
  // modules
  implementation(projects.coreData)

  // modules for unit test
  testImplementation(projects.coreNetwork)
  testImplementation(projects.coreDatabase)
  testImplementation(projects.coreTest)
  androidTestImplementation(projects.coreTest)

  // androidx
  implementation(libs.material)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.swiperefresh)
  implementation(libs.androidx.lifecycle)
  implementation(libs.androidx.collection)
  implementation(libs.androidx.startup)

  // startup + baseline profile
  implementation(libs.profileinstaller)
  // The generated profile is compiled into the release APK, so the first run is already warm.
  baselineProfile(projects.benchmark)

  // browser engine
  implementation(libs.androidx.webkit)

  // onion routing
  implementation(libs.tor.android)
  implementation(libs.jtorctl)
  implementation(libs.androidx.localbroadcast)

  // native playback for media lifted out of the page

  // di
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  ksp(libs.kotlin.metadata.jvm)
  androidTestImplementation(libs.hilt.testing)
  kspAndroidTest(libs.hilt.compiler)
  kspAndroidTest(libs.kotlin.metadata.jvm)

  // coroutines
  implementation(libs.coroutines)

  // network
  implementation(libs.okhttp.interceptor)

  // recyclerView
  implementation(libs.recyclerview)

  // QR code generation
  implementation(libs.zxing)

  // app lock
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.lifecycle.process)

  // unit test
  testImplementation(libs.junit)
  testImplementation(libs.turbine)
  testImplementation(libs.truth)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.truth)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso)
  androidTestImplementation(libs.android.test.runner)
}
