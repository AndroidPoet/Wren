@file:Suppress("UnstableApiUsage")

//Copyright 2026 AndroidPoet
//
//SPDX-License-Identifier: GPL-3.0-or-later

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
  repositories {
    // fetch plugins from google maven (https://maven.google.com)
    google() {
      content {
        includeGroupByRegex("androidx\\..*")
        includeGroupByRegex("com\\.android(\\..*|)")
        includeGroupByRegex("com\\.google\\.android\\..*")
        includeGroupByRegex("com\\.google\\.firebase(\\..*|)")
        includeGroupByRegex("com\\.google\\.gms(\\..*|)")
        includeGroupByRegex("com\\.google\\.mlkit")
        includeGroupByRegex("com\\.google\\.oboe")
        includeGroupByRegex("com\\.google\\.prefab")
        includeGroupByRegex("com\\.google\\.testing\\.platform")
      }
      mavenContent {
        releasesOnly()
      }
    }

    // fetch dagger plugin only
    mavenCentral() {
      content {
        includeGroup("com.google.dagger")
        includeGroup("com.google.dagger.hilt.android")
      }
      mavenContent {
        releasesOnly()
      }
    }

    // fetch plugins from gradle plugin portal (https://plugins.gradle.org)
    gradlePluginPortal()

    // fetch snapshot plugins from sonatype
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots/") {
      mavenContent {
        snapshotsOnly()
      }
    }
  }
}

plugins {
  id("com.android.settings") version "9.3.2"
}

android {
  minSdk = 24
  // 37.1: tor-android 0.4.9.14 (two client-side Tor security releases) requires it.
  compileSdk {
    version = release(37) { minorApiLevel = 1 }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    // fetch libraries from google maven (https://maven.google.com)
    google() {
      content {
        includeGroupByRegex("androidx\\..*")
        includeGroupByRegex("com\\.android(\\..*|)")
        includeGroupByRegex("com\\.google\\.android\\..*")
        includeGroupByRegex("com\\.google\\.firebase(\\..*|)")
        includeGroupByRegex("com\\.google\\.gms(\\..*|)")
        includeGroupByRegex("com\\.google\\.mlkit")
        includeGroupByRegex("com\\.google\\.oboe")
        includeGroupByRegex("com\\.google\\.prefab")
        includeGroupByRegex("com\\.google\\.testing\\.platform")
      }
      mavenContent {
        releasesOnly()
      }
    }

    // fetch libraries from maven central
    mavenCentral() {
      mavenContent {
        releasesOnly()
      }
    }

    // fetch snapshot libraries from sonatype
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots/") {
      mavenContent {
        snapshotsOnly()
      }
    }
  }
}
rootProject.name = "Wren"
include(":app")
include(":benchmark")
include(":core-model")
include(":core-network")
include(":core-database")
include(":core-data")
include(":core-test")