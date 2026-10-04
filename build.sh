#!/usr/bin/env bash
# Debug build, tuned for low-memory machines.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew assembleDebug --no-daemon --max-workers=1
echo
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
echo "Install: adb install -r app/build/outputs/apk/debug/app-debug.apk"
