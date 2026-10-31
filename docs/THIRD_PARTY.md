# Third-party software

Stride Remote is licensed under Apache-2.0. Its original source and vector icon use the same license. The root `LICENSE` is also bundled in the APK assets.

## Belt Control

The classic FE00 command/telemetry implementation is adapted from [Belt Control](https://github.com/morsela/beltcontrol), revision `6809303910f87a4f1fe68eb7c5caad00015edff9`, specifically `packages/belt-drivers/src/drivers.ts`.

- License: Apache-2.0.
- Copyright: 2026 Belt Control contributors.
- Upstream notice is retained verbatim after the Stride Remote attribution in the root `NOTICE` and `app/src/main/assets/NOTICE`.
- Modifications: Java/Android transport, queue and lifecycle, confirmation/freshness/expiry behavior, standalone Wear OS interface, notification and release tooling.
- See [PROTOCOL.md](PROTOCOL.md) for the pinned source and research snapshot checksum.

No GPL research source or unrelated local research files are distributed in the repository or APK.

## Runtime dependencies

The app uses AndroidX Wear Ongoing 1.1.0 and AndroidX Core 1.15.0 (Apache-2.0, Android Open Source Project). Core is pinned for the current SDK 35 toolchain; dependency updates are reviewed through CI. Their resolved dependencies include AndroidX Annotation, Collection, Concurrent Futures, Interpolator, Lifecycle, Arch Core, ProfileInstaller, Startup, Tracing and VersionedParcelable, also under Apache-2.0.

Other resolved runtime dependencies are Kotlin standard library 1.8.22 and kotlinx.coroutines 1.6.4 (JetBrains, Apache-2.0), JetBrains annotations 13.0 (Apache-2.0), Guava ListenableFuture 1.0 (Google, Apache-2.0), and JSpecify 1.0.0 (JSpecify contributors, Apache-2.0).

See each upstream project for its complete notices and source:

- [AndroidX](https://android.googlesource.com/platform/frameworks/support/)
- [Kotlin](https://github.com/JetBrains/kotlin/tree/v1.8.22)
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.6.4)
- [JetBrains annotations](https://github.com/JetBrains/java-annotations)
- [Guava](https://github.com/google/guava)
- [JSpecify](https://github.com/jspecify/jspecify/tree/1.0.0)

To inspect the exact resolved dependency tree for a build:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## Development tools

The Gradle wrapper and google-java-format use Apache-2.0. JUnit 4.13.2 (EPL-1.0) and its test dependencies are used for local tests and are not included in the release APK. Android SDK and JDK installations are build prerequisites and are not redistributed here. CI action licenses remain with their upstream projects; their use is pinned to commit IDs.
