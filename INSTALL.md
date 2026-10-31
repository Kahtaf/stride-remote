# Install on a Wear OS watch

Use a Wear OS watch with Android API 33 or newer and Bluetooth LE. The initial tested setup is Galaxy Watch8 + WalkingPad A1 Pro (WPA1F Pro). Other models using the classic FE00 protocol may work but are unverified.

## Download

Get the APK and `SHA256SUMS` from the same [GitHub release](https://github.com/Kahtaf/stride-remote/releases/latest). Install on the watch, not the phone. No companion app or Google Play listing is required.

You can verify the downloaded file from its folder with:

```sh
# Linux
sha256sum -c SHA256SUMS
# macOS
shasum -a 256 -c SHA256SUMS
```

Official releases share one signing certificate so later versions can update in place. A self-built APK has a different certificate; Android requires uninstalling the other build first, which clears remembered device settings.

## Wireless ADB

1. Stop the pad. Connect the watch and computer to the same Wi-Fi network.
2. Install Android [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) on the computer.
3. Enable developer options on the watch by repeatedly tapping **Software version** under **Settings → About watch → Software information**. Menu labels vary by watch.
4. In **Developer options**, enable **ADB debugging** and **Wireless debugging**. Accept the network prompt.
5. Choose **Pair new device** and keep the pairing screen open.

Use that screen's **pairing port**:

```sh
adb pair WATCH_IP:PAIRING_PORT
```

Enter the displayed pairing code. Return to the main **Wireless debugging** screen, which shows a different **connection port**:

```sh
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r stride-remote-1.0.5.apk
```

Replace the placeholders and filename with your current address, port and downloaded APK. Keep the watch awake during transfer. `adb devices -l` lists available devices; use `-s` when more than one is attached. Google documents the [Wear OS debugging workflow](https://developer.android.com/training/wearables/get-started/debugging).

Open **Stride Remote** from the watch launcher. Wireless debugging can now be turned off. Normal operation needs only Bluetooth, the watch and the pad.

## If a transfer drops

The source repository includes a small-chunk installer. It verifies that the target is an Android watch with API 33+, uploads 128 KiB chunks, checks the entire device-side SHA256, then installs the APK locally without clearing app data. It wakes the screen before each chunk and does not change persistent power settings.

From a clone of this repository, with Python 3 and `adb` available:

```sh
python3 scripts/install-watch.py WATCH_IP:CONNECTION_PORT /path/to/stride-remote-1.0.5.apk
```

An optional `--adb-port PORT` selects a nonstandard local ADB server. If debugging was toggled off and back on, check the watch's current connection port and run `adb connect` again.

## Connect and walk

1. Power on the pad and enable Bluetooth on the watch.
2. Close/disconnect Belt Control, KS Fit and other pad controllers. Classic pads generally accept one controller at a time.
3. Choose **Find pad**, allow **Nearby devices** and notifications, then select the advertised pad name. Unrecognized GATT services are rejected before control commands are sent.
4. Wait for live data and **READY**. Connecting to a stopped pad prepares Manual mode without sending Start.
5. Tap **Start**, check the target, then tap **GO**. `−` / `+` adjust by 0.1 km/h. **STOP** requests zero speed and standby.

Swipe up for pad-reported steps, distance (km), elapsed time, and Manual/Auto. Mode changes require a stopped pad, and highlight only after the pad confirms. Start always requests Manual. Stop stays pinned onscreen.

The notification and watch-face shortcut return to the app; the notification also has Stop. Long-press speed while stopped to Disconnect. Disconnect requests Stop/standby and waits for confirmation. Closing the app leaves the foreground connection service running.

Keep the belt visible and the physical power control available during the first test. Verify live speed, Start, speed down/up, Stop, a second Start after standby, and reopening the app. Emulator checks do not prove compatibility with your hardware.

If the app says **NO LIVE DATA** or **CHECK BELT**, do not infer that the belt stopped. A radio loss or killed process cannot send Stop. Use the pad's physical control if needed.

If discovery fails, check power, Bluetooth, permissions and other connected controllers. An **Unsupported device** result means this classic driver cannot control the discovered interface. File a report with model names and app version, omitting addresses, serial numbers and active pairing codes.
