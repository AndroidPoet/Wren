#!/usr/bin/env bash
# Copyright 2026 AndroidPoet
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Gathers a release's APKs into one folder for GitHub Releases: the per-processor and universal
# APKs, the same arm64-v8a and armeabi-v7a files again under wren-tv-* names (the two processors
# Android TV boxes run; the bytes are identical, so phone and TV share one update path), and a
# SHA256SUMS over all of them.
#
#   ./gradlew :app:assembleRelease && tools/release-assets.sh out/
#   gh release upload vX.Y.Z out/*
set -euo pipefail

out="${1:?usage: tools/release-assets.sh <output-dir>}"
apks="$(dirname "$0")/../app/build/outputs/apk/release"
mkdir -p "$out"
cp "$apks"/app-*-release.apk "$out"/
cp "$apks"/app-arm64-v8a-release.apk "$out"/wren-tv-arm64-v8a.apk
cp "$apks"/app-armeabi-v7a-release.apk "$out"/wren-tv-armeabi-v7a.apk
(cd "$out" && shasum -a 256 ./*.apk | sed 's| \./| |' > SHA256SUMS)
cat "$out"/SHA256SUMS
