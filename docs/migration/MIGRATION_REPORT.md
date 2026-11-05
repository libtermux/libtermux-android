# AeonRuntime migration report

Migration of the former `libtermux-android` repository to **AeonRuntime** (`AeonCoreX-Lab/AeonRuntime`).

> **Read this first.** The migration environment had Java 21 and `g++`, but **no Android SDK/NDK, no Gradle
> distribution, no Kotlin compiler and no network**. Therefore **nothing was compiled, no unit test was run,
> no APK/AAR was built, and nothing was run on a device.** Everything below marked "verified" was verified by
> static analysis scripts that are included in the repository, and each such claim says so. The first thing
> to do with this repository is run the Gradle build and CI.

Nothing was published and nothing was pushed to GitHub by the migration itself.

**Update after the first CI run.** Running the real build on GitHub Actions confirmed that `:core` compiles and
exposed five more baseline compile errors in `:os` and `:shizuku`, all present before the rename. They were
fixed in a separate commit (KNOWN_ISSUES A3–A7). The "Not run" rows below describe the state at hand-over,
not the state after CI.

## 1. Method

1. **Audit before changes.** All build files, native sources, manifests, shrinker rules, workflows, scripts and
   every Kotlin module were read. About 880 lines mentioning "termux" in any case were classified.
2. **Explicit rule table, not global replace.** An ordered list of project-owned renames was applied with
   per-rule hit counts; upstream references matched no rule.
3. **Manual pass** for everything a rule cannot do: the new entry point, Maven/Gradle metadata, publication
   blocks, sample theming, scripts, CI, documentation.
4. **Residual review.** Every remaining `termux` token was reviewed, then made machine-enforced
   (`scripts/verify_repo.py`).
5. **Pre-existing bugs documented, not hidden.** Two compile blockers were fixed in a *separate* commit; the
   rest are listed in [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md).

Commits (baseline → final): `ecb4a0b` baseline as uploaded; `ea6907f` moves + rule-based migration;
`2a3a8d3` manual migration; `95a9d07` CI/CD; `9e68a56` **pre-existing compile fixes (separate, revertible)**;
then sample/KDoc cleanup, docs and verifier improvements.

## 2. Results against the validation checklist (spec §19)

| # | Check | Status | Evidence / limit |
|---|---|---|---|
| 1 | Search repo for old project-owned identifiers | **Done** | `verify_repo.py` legacy gate: zero hits outside 4 explicitly allowlisted historical/tool files |
| 2 | Every remaining Termux reference intentional and documented | **Done** | 133 classified upstream references by category (`--list-upstream`); policy in [`RENAME_MAP.md`](RENAME_MAP.md). One is a defect, not an intentional keep (shebang, KNOWN_ISSUES B1) |
| 3 | Kotlin imports | **Done (heuristic)** | Every `import com.aeoncorex.aeonruntime…` resolves to a top-level declaration. Nested-member imports are not verified. Not a compiler |
| 4 | Package declarations | **Done** | All 52 Kotlin files: `package` equals directory path |
| 5 | Android manifests | **Done (static)** | All component/initializer class names resolve to sources; layout custom views resolve |
| 6 | JNI symbol compatibility | **Done (static)** | 11 Kotlin `external fun` ↔ 11 `Java_…` symbols with correct mangling; negative tests prove detection; also passes on the untouched baseline. CI additionally inspects the *built* `.so` of all 4 ABIs, **not run here** |
| 7 | Native library loading | **Not run** | Only name consistency (`loadLibrary("aeonruntime_jni")` ↔ CMake target) verified |
| 8 | Gradle plugin resolution | **Not run** | Plugin ids, precompiled-script names and `implementationClass` are consistent (static); Gradle never executed |
| 9 | Maven publishing configuration | **Not run** | Metadata reviewed; two new publication blocks mirror existing ones but are untested; publish task name unverified (B13) |
| 10 | Bootstrap asset paths | **Done (static)** | Producer plugin and `BootstrapInstaller` agree on `aeonruntime/bootstrap-<arch>.zip`; negative test proves detection; CI inspects the real AARs, **not run** |
| 11 | Build all existing modules | **Not run** | No toolchain. Wired into CI |
| 12 | Run all existing tests | **Not run** | No toolchain. Wired into CI (`:core`, `:os`, `:shizuku`) |
| 13 | Build the sample app | **Not run** | Wired into CI |
| 14 | Verify release artifacts | **Not run** | Verification steps added to `release.yml` |
| 15 | Documentation links | **Done** | Relative links in all `.md`/`.html` resolve (external URLs not fetched) |

Other checks that were run: all 17 XML/SVG files are well-formed; all 3 workflow YAML files parse; all 3 shell
scripts pass `bash -n`; no duplicate type declarations within any package; per-file bracket balance of all 69
modified code files is unchanged versus the baseline; no copyright/license line was altered and `LICENSE` is
byte-identical to the baseline.

Five negative tests were run against `verify_repo.py` (stale import, bad manifest class, package/directory
mismatch, unclassified `termux` token, shrinker rule to a missing class) plus asset-path and JNI negatives;
all were detected, and all test edits were reverted.

## 3. What changed

Full old→new tables: [`RENAME_MAP.md`](RENAME_MAP.md). Complete file list: [`FILES_CHANGED.txt`](FILES_CHANGED.txt)
(109 entries in total, 53 of them renames/moves, nearly all with content edits as well).

- **Namespace:** `com.libtermux` → `com.aeoncorex.aeonruntime`; `fs`→`filesystem`, `pkg`→`packagemanager`,
  `view`→`terminal`.
- **API:** the mapping in the spec was applied (plus `RuntimeDsl`, `AeonRuntimeInitializer`,
  `Architecture.bootstrapArch`). New SDK entry point `AeonRuntime.initialize(context) { … }`, `@JvmStatic`.
- **Native:** target and library `aeonruntime_jni`; 11 symbols re-namespaced; `termux_jni.cpp` →
  `aeonruntime_jni.cpp`. No logic changed in PTY/process code (only log tags).
- **Manifests / shrinker rules:** service, initializer and keep rules follow the renamed classes.
- **Bootstrap:** producer and consumer asset path, data dir, marker file moved together. Upstream names kept.
- **Gradle/Maven:** group `com.aeoncorex`; artifacts `aeonruntime-{core,os,terminal,shizuku,bootstrap-<abi>}`;
  plugin ids and properties migrated; the two modules that had no publication config got one.
- **Sample:** package/`applicationId`, theme, strings, AeonCoreX palette, DSL initialization showing only
  options that are actually consumed.
- **CI/CD:** CI builds all modules, runs `:os`/`:shizuku` tests, verifies JNI symbols for 4 ABIs, bootstrap
  packaging for 4 modules, builds the sample. Release verifies tag == `LIB_VERSION`, runs checks and artifacts
  verification. No verification step is skipped on failure.
- **Docs:** README, CHANGELOG, CONTRIBUTING, architecture overview, engine migration plan, local-AI design
  notes, integration guide, build guide, bootstrap-builder README, website, banner. Roadmap items are marked
  as not implemented everywhere.

### Decisions where the spec allowed judgement

| Topic | Decision | Reason |
|---|---|---|
| `ExecutionEngine` interface | **Not added;** migration plan documented | Existing runners are distro-centric with no engine lifecycle; an interface now would be a redesign or an empty shell |
| QEMU / UML / AI packages | **Not created** | Spec forbids fabricated modules |
| `RegisterNatives()` | **Not adopted** | Changes the native entry path; cannot be tested here. Listed as future work |
| `core` and `android` sub-packages | Not created | Avoids `…core.core` and any ambiguity with the platform `android.*` package |
| Version | Left at `1.0.0` | Pre-existing value; no release claimed |
| Artifacts for `:terminal-view`, `:shizuku` | Added publication blocks | Spec names them for real modules; untested |
| Compile fixes A1/A2 | Applied in a separate commit | Spec requires the "build all modules" outcome but bugs must not hide in the rename |
| README copyright holder | Set to `AeonCoreX-Lab / cybernahid-dev` | Matches source headers; the old README said "LibTermux Contributors". **Please confirm** |

## 4. Known limitations

- No compilation, tests or device runs (see top). Expect to fix additional baseline issues when CI first runs,
  especially in `:os`, `:shizuku`, `:terminal-view`, which upstream CI never built.
- Existing installs of a pre-migration build are not migrated (paths and names changed); acceptable because
  there was no public release.
- Bootstraps built for the old sample `applicationId` must be rebuilt.
- Android 10+ execution of downloaded binaries remains the library's central platform constraint; the
  install-time-native-library route (`BootstrapProvider`) was not verified on any device.
- The docs website links to Markdown files; on GitHub Pages these render as raw text unless a renderer is
  configured.

## 5. Remaining technical debt

See [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md), sections B and D. Highest impact: `bash()` shebang (B1), unconsumed
config options (B2), `SessionManager` not exposed (B3), duplicate bootstrap namespaces (B5), `minSdk`
mismatch (B6), missing shrinker files (B7), over-broad keep rule (B8).

## 6. Recommended next phase

1. Run `./gradlew` build + tests + CI; fix whatever the first real compile reveals; review A1/A2.
2. Fix B1 (shebang), B3 (expose sessions) and B4 (provider availability), then test `bash()`/bootstrap on a
   real device, on an API 28 target and an API 29+ target.
3. Resolve B2 (implement or remove dead config), B5–B8, B9.
4. Verify and fix the publish task, run a dry publication to `mavenLocal`, then decide on a first release.
5. Implement the capability model and common distro interface from the engine plan, behaviour-preserving and
   covered by the `:os` tests.
6. Only then start QEMU/UML or the local-AI module, each with measured, device-verified claims.
