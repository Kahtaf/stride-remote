# Contributing

Small, focused fixes and compatibility reports are welcome. Discuss a new protocol or major interface change in an issue before implementing it.

## Development

Use JDK 17, Python 3, Android SDK platform 35 and build-tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest
scripts/format-java.sh --check
```

Use `scripts/format-java.sh --fix` to format Java with the pinned, checksum-verified google-java-format release. Keep changes readable and avoid unrelated refactors. Source files use Apache-2.0 SPDX identifiers.

`ClassicProtocol`, `CommandQueue` and `PadController` are plain Java. Prefer deterministic local tests for protocol and sequencing fixes. Android UI tests run the real controls through a debug-only simulated pad. See [verification](docs/VERIFICATION.md) for the emulator command. Release builds exclude the simulator.

Never issue movement commands to somebody else's hardware. In a hardware report, state the exact watch and pad models, app version, and which behaviors you verified. Stop the belt before replacing the installed app. Do not describe emulator results as hardware validation.

## Pull requests

Explain the concrete problem and resulting behavior. Include a focused regression test when practical. Run the tests, lint and formatter. Preserve the minimal watch UI, 48dp touch targets, pinned Stop, telemetry freshness checks, command expiry and explicit Start confirmation. Do not add automatic reconnection that can restart a belt.

Do not commit credentials, signing keys, generated APKs, local diagnostics, device addresses or emulator images. Git ignores `qa/`, `artifacts/` and `references/` intentionally. Put reusable documentation and sanitized screenshots in `docs/`.

Changes are accepted under the project's Apache-2.0 license. Preserve upstream attribution in `NOTICE` and the APK assets. Be respectful, assume good intent, and keep discussions focused on the work. Harassment and disclosure of someone else's private information are not welcome.

## Maintainers

[Release instructions](docs/RELEASING.md) describe versioning, signing and publishing. Keep the release signing key outside the repository and back it up securely. CI has no signing credentials and cannot publish an installable release APK.
