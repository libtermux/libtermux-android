# Contributing to AeonRuntime

Thanks for helping. AeonRuntime is a reusable Android **library**; changes should keep the core independent of
optional features (`:os`, `:shizuku`, future engines and AI).

## Getting started

1. Fork and clone the repository.
2. `./scripts/setup_dev.sh`
3. Create a branch from `main`.
4. Make a small, focused change.
5. Run the checks below, then open a pull request.

## Checks before a pull request

```bash
python3 scripts/verify_repo.py
python3 scripts/verify_jni.py
./gradlew :core:testDebugUnitTest :os:testDebugUnitTest :shizuku:testDebugUnitTest
./gradlew :core:assembleDebug :os:assembleDebug :terminal-view:assembleDebug :shizuku:assembleDebug
```

Please state in the pull request what you ran. Do not claim tests passed that you did not run.

## Guidelines

- Do not describe or expose features that are not implemented (engines, AI, acceleration).
- Do not change upstream attribution or license notices.
- Keep the naming rules: project-owned identifiers use AeonRuntime names. Upstream Termux names
  (`TERMUX_PREFIX`, `libtermux-exec.so`, `termux-packages` URLs…) stay as they are for compatibility.
  `scripts/verify_repo.py` fails on unclassified `termux` tokens; if you add a legitimate upstream reference,
  add an explained pattern there.
- Changing a JNI function means changing Kotlin and native code together; `verify_jni.py` checks the pair.
- Changing the bootstrap asset path means changing both the Gradle plugin (producer) and `BootstrapInstaller`
  (consumer) in the same commit.
- Keep public APIs usable from Java (`@JvmStatic`, `@JvmOverloads` where appropriate).
- Document pre-existing bugs you find in `docs/migration/KNOWN_ISSUES.md` instead of hiding them in unrelated
  changes.

## License

By contributing you agree that your contribution is licensed under the Apache License 2.0.
