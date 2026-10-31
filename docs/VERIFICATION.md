# Verification

## Local checks

With JDK 17, Python 3 and Android SDK platform/build-tools 35 configured:

```sh
./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest
scripts/format-java.sh --check
scripts/build-apk.sh
python3 scripts/verify-apk.py
```

The APK verifier checks the exact current version, package ID, watch and BLE requirements, standalone declaration, absence of Internet/location permissions, non-debuggable release, bundled license/notice, signature, alignment and checksum. It also checks that the debug preview, simulated pad and instrumentation classes are absent from the distributable.

The unit tests include command vectors, session decoding, limits, serialized writes, duplicate/late acknowledgements, command expiry, Stop replacing queued movement, fresh-state gating and mode confirmation. A regression test reproduces an expired Auto-mode command after scheduler suspension and verifies that it is discarded.

## Wear OS UI

Create and boot a round Wear OS emulator (Android API 33+, tested on Wear OS 6). Use its serial from `adb devices`, then run:

```sh
scripts/verify-emulator.sh emulator-5554 watch-ui
```

The helper checks `ro.kernel.qemu=1` before installing test packages. It builds and installs the debug app and instrumentation, runs the checks, verifies the success marker, and pulls screenshots into ignored `qa/watch-ui-screenshots/`. It must not be run against a physical pad/watch setup. An optional ADB server can be selected through the standard `ANDROID_ADB_SERVER_PORT` environment variable.

The debug-only PreviewActivity supplies a simulated pad to the production `PadController` and native UI. Instrumentation checks explicit Start confirmation, speed changes, Stop, touch scrolling, session values, mode echo/selection, mode controls disabled while moving, stale data, 48dp Stop targets and fully visible mode targets above Stop. It also exercises discovery/service startup and the ongoing notification/return path through Android's native APIs.

Use an AVD with the desired physical round-screen resolution. A `wm size` override alone can mismatch the round mask. Review increased font-size layouts as well as defaults. README screenshots are simulated-pad demonstrations, not screenshots of a live treadmill.

## Physical hardware

Only the Galaxy Watch8 / WalkingPad A1 Pro (WPA1F Pro) combination has initial user testing. The user reported working live speed synchronization, Stop and Start after selecting Manual. Earlier builds had speed-button gating problems; unit/UI regressions now cover those cases. The session/mode UI and other pad models still need hardware reports. Do not imply every model has been validated.

For a controlled hardware test, keep the belt visible and the physical power switch accessible. Stop it before installing. Verify live telemetry, Start, speed down/up, Stop, second Start after standby, screen-off/reopen and intentional Disconnect. Report what was actually tested and distinguish observed speed from requested speed. Do not send movement commands to unattended hardware.

`adb shell dumpsys activity service dev.kahtaf.walkpad/.PadService` can expose current status and a bounded in-memory protocol ring while the app is running. It makes no motor request. Remove names, addresses and other private information before sharing a capture.

## CI scope

GitHub Actions runs formatting, unit tests, Android lint, release compilation and instrumentation compilation with read-only repository permissions. It does not run a treadmill, emulator tests or a signed release publication. Hardware validation and signed release checks remain explicit maintainer steps.
