#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gradle >/dev/null || { echo 'Gradle 8.13 is required. Use the included GitHub Actions workflow from your phone.'; exit 1; }
test -n "${ANDROID_HOME:-}" || { echo 'ANDROID_HOME with platform 35 and build-tools 35.0.0 is required'; exit 1; }
test -f "$ANDROID_HOME/platforms/android-35/android.jar" || { echo 'Android platform 35 is missing'; exit 1; }
gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
