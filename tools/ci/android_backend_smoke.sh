#!/usr/bin/env bash
# Disposable CI emulators only. Keep shell variables and loops in one process:
# android-emulator-runner executes each inline script line independently.
set -euo pipefail
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo 'Backend smoke checks require a disposable emulator' >&2
  exit 1
}
adb install $(find emulator-tests -name '*x86_64.apk')
adb install $(find emulator-tests -name '*androidTest.apk')
adb shell appops set com.v2ray.ang ACTIVATE_VPN allow
if (( $(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 33 )); then
  adb shell pm grant com.v2ray.ang android.permission.POST_NOTIFICATIONS
fi
adb shell am start -n com.v2ray.ang/.ui.main.MainActivity
adb logcat -c
adb shell am instrument -w -e class com.v2ray.ang.service.TunBackendSmokeTest,com.v2ray.ang.ui.settings.SettingsBackendUiTest com.v2ray.ang.test/androidx.test.runner.AndroidJUnitRunner | tee "$RUNNER_TEMP/instrumentation.txt"
adb logcat -d > "$RUNNER_TEMP/logcat.txt"
grep -q 'OK (2 tests)' "$RUNNER_TEMP/instrumentation.txt"
grep -q 'UI_BACKEND label=' "$RUNNER_TEMP/instrumentation.txt"
# A real package replacement must reclaim installed APKs while
# preserving in-flight and unrecognized cache files.
APK_SOURCE=$(adb shell pm path com.v2ray.ang | sed -n 's/^package://p' | tr -d '\r')
test -n "$APK_SOURCE"
adb shell run-as com.v2ray.ang mkdir -p cache/updates
adb shell run-as com.v2ray.ang cp "$APK_SOURCE" cache/updates/update-installed.apk
adb shell run-as com.v2ray.ang touch cache/updates/pending-in-flight.apk cache/updates/update-unknown.apk cache/unrelated-cache
adb install -r $(find emulator-tests -name '*x86_64.apk')
for ATTEMPT in $(seq 1 60); do
  if adb shell run-as com.v2ray.ang test ! -e cache/updates/update-installed.apk; then break; fi
  sleep 0.25
done
adb shell run-as com.v2ray.ang test ! -e cache/updates/update-installed.apk
adb shell run-as com.v2ray.ang test -e cache/updates/pending-in-flight.apk
adb shell run-as com.v2ray.ang test -e cache/updates/update-unknown.apk
adb shell run-as com.v2ray.ang test -e cache/unrelated-cache
echo 'UPDATE_CACHE_REPLACEMENT passed' | tee "$RUNNER_TEMP/update-cache-replacement.txt"
