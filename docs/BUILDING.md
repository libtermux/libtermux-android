# Building from source

## Requirements

- JDK 17 (the CI uses Temurin 17)
- Android SDK with platform 34. AGP installs the NDK and CMake 3.22.1 on demand when SDK licenses are accepted
- Network access: the `bootstrap-*` modules and the sample download the bootstrap zip at build time
- Gradle is provided by the wrapper (`gradle-wrapper.properties`); versions are pinned in
  `gradle/libs.versions.toml`

`./scripts/setup_dev.sh` checks JDK and `ANDROID_HOME`.

## Common tasks

```bash
./gradlew :core:lint
./gradlew :core:testDebugUnitTest :os:testDebugUnitTest :shizuku:testDebugUnitTest
./gradlew :core:assembleDebug :os:assembleDebug :terminal-view:assembleDebug :shizuku:assembleDebug
./gradlew :bootstrap-arm64:assembleRelease     # downloads the bootstrap zip
./gradlew :sample:assembleDebug
./scripts/publish_local.sh                     # local Maven (~/.m2) only
```

Set `GITHUB_TOKEN` to avoid GitHub API rate limits when resolving the latest bootstrap release.
Use `-Paeonruntime.bootstrapRepo=<owner>/<repo>` / `-Paeonruntime.bootstrapTag=<tag>` to choose the source.

## Source-level checks (no Android SDK needed)

```bash
python3 scripts/verify_repo.py     # packages, imports, manifests, shrinker rules, plugin ids, legacy-name gate
python3 scripts/verify_jni.py      # Kotlin external funs <-> native Java_* symbols <-> CMake target
python3 scripts/verify_jni.py --so path/to/libaeonruntime_jni.so ...   # built libraries (needs readelf)
```

These are static checks. They do not compile code or run tests. CI (`.github/workflows/ci.yml`) additionally
builds every module for all four ABIs, runs unit tests, inspects the exported JNI symbols of the built `.so`
files, checks that each bootstrap AAR contains `assets/aeonruntime/bootstrap-<arch>.zip`, and builds the sample.

## Releasing

Pushing a `v*` tag runs `.github/workflows/release.yml`, which requires the tag to equal `LIB_VERSION` and,
if the Maven Central secrets are configured, publishes. Do not push release tags until the publishing task
names have been verified (see the comment in that workflow).
