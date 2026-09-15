# AGENTS.md — Nepali Keyboard (FlorisBoard base)

Machine-readable orientation for an AI agent (or a new human) working in this
repository. Read this before touching code.

---

## 1. What this project is

Nepali Keyboard is **FlorisBoard** (Apache-2.0, © The FlorisBoard Contributors,
cloned from `florisboard/florisboard` at commit `5d6e1ef`) rebranded and
extended into a Nepali input method editor. More than half of this tree is
upstream code; our changes layer identity, Nepali input, and our services on
top. When in doubt, upstream's architecture wins — do not fight it.

| Fact | Value |
|---|---|
| Application ID | `com.nikit.nepalikeyboard` |
| Upstream application ID | `dev.patrickgold.florisboard` (renamed — see § 4) |
| Package root | `app/src/main/kotlin/com/nikit/nepalikeyboard/` |
| Lib namespaces | `org.florisboard.lib.*` (intentionally NOT renamed — see § 4) |
| `minSdk` | 26 |
| `targetSdk` / `compileSdk` | 36 |
| Kotlin | 2.3.20 |
| AGP | 9.0.0 |
| Gradle | 9.4.1 (wrapper, committed) |
| Java toolchain | JVM 11 bytecode; CI runs JDK 17 (matches upstream) |
| Native | Rust via CMake (`:lib:native`); NDK 29, CMake 4.1.2 |
| Upstream version base | 0.6.0-alpha02 → our `versionName` 1.1.0, `versionCode` 3 |

**There is exactly one build artifact:** a single universal release APK. No
flavours, no ABI splits, no bundles.

---

## 2. Environment constraints — read this first

### There is no local Android toolchain

The development environment has **no Android SDK, no NDK, no Rust toolchain,
no Gradle daemon, and no ability to compile**. Do not attempt:

```bash
./gradlew build            # will fail: no SDK
./gradlew test             # will fail: no SDK
```

Do not claim to have run, built, or verified anything that you did not actually
execute. If you write "the project compiles", the only acceptable evidence is a
green run of `.github/workflows/build-release.yml`.

### The Python checkers do not run on this branch

`tools/kotlin_lint.py` and `tools/kotlin_refcheck.py` encode the conventions
of the previous hand-written engine and are not executed by this branch's CI:
the upstream tree legitimately violates their package/marker/allow-list rules.
They return if/when our own engine packages land with their own rules. Do not
"fix" upstream code to satisfy them.

### What CI provisions (mirrors upstream FlorisBoard CI)

JDK 17 (Temurin), CMake+Ninja via `lukka/get-cmake`, preinstalled Rust, and
AGP's automatic NDK install. If the build needs a tool beyond these, add the
provisioning step to the workflow — do not assume the runner has it.

---

## 3. Repository layout ( FlorisBoard tree + our overlays)

```
.
├── .github/workflows/build-release.yml   CI: build + GitHub Release
├── AGENTS.md                             this file
├── LICENSE                               Apache-2.0 (covers upstream reuse)
├── build.gradle.kts                      root build
├── settings.gradle.kts                   modules: app + lib/*
├── gradle.properties                     SDK levels, versionCode/Name
├── gradle/libs.versions.toml             version catalog
├── gradle/tools.versions.toml            buildTools/cmake/jdk/ndk/rust pins
├── gradlew, gradlew.bat                  committed wrapper scripts
├── gradle/wrapper/                       committed wrapper jar + properties
├── keystore/                             committed PUBLIC demo keystore (§ 7)
├── tools/                                legacy checkers (not run — see § 2)
├── app/                                  the keyboard app (upstream + ours)
└── lib/                                  android, color, compose, kotlin,
                                          native (Rust), snygg (theme engine)
```

Excluded from the import on purpose: upstream `.github/`, `.idea/`,
`benchmark/` (unreferenced module), `utils/`, `fastlane/`, root docs, Crowdin
config. Nothing that affects the build was left out.

### Module boundaries (upstream)

`app` (IME service, UI, settings) depends on `lib/*`. The Snygg theme engine
(`lib/snygg`) owns key shapes/colours — do not fight it with Material theme
overrides; author stylesheets instead. The Rust core (`lib/native`) exposes
exactly one JNI symbol (`Java_org_florisboard_libnative_TestKt_dummyAdd`) and
is otherwise inert at this commit.

---

## 4. The rename — what changed vs upstream

`dev.patrickgold.florisboard` → `com.nikit.nepalikeyboard`, applied to:

* `namespace` + `applicationId` in `app/build.gradle.kts`
* every `package` / `import` in `app/` (all source sets incl. tests)
* manifest component names and `${applicationId}`-based authorities
* one hardcoded alias string in
  `lib/android/.../PackageManager.kt` (`SettingsLauncherAlias`)

Deliberately NOT renamed:

* `org.florisboard.lib.*` namespaces — internal libraries with no
  user-visible surface; the JNI symbol embeds its package name, so renaming
  would require touching Rust sources for zero benefit.
* `florisboard__*` string *keys* — only their URL *values* were repointed at
  this repo.
* Copyright headers — Apache-2.0 requires keeping them. Never strip or
  rewrite an upstream header.

Verify a rename with: `grep -rn 'dev\.patrickgold' app lib` must return
nothing (it currently returns nothing).

---

## 5. Our customizations on top of upstream

1. **Identity:** app label "Nepali Keyboard", launcher icon (white क on
   crimson adaptive background), crimson icon/splash background
   (`ic_app_icon_background`), repo/issue/changelog URLs repointed.
2. **Versioning:** `projectVersionCode=3`, `projectVersionName=1.1.0`.
3. **Release signing:** `release` signingConfig wired to
   `keystore/release.keystore` (see § 7).
4. **CI:** push/PR on any branch with path filters, `assembleRelease`,
   versioned artifact name, verification, and a separate `release` job
   publishing full releases from `main` and prereleases from other branches.

Not yet ported (planned slices): Nepali subtypes/layouts, the Romanized→
Devanagari transliteration engine, the offline Nepali lexicon, the in-app
auto-updater, and the crimson keyboard theme. The previous engine
(`translit/`, `lexicon/`, `unicode/`) lives in git history on
`feat/nepali-ime-complete` and will be reintegrated against FlorisBoard's
composer and layout/subtype system — not by reviving the old IME service.

---

## 6. CI

`.github/workflows/build-release.yml`. This is the project's only compiler.

**Triggers:** push to any branch and pull request to any branch, both filtered
by paths (`app/**`, `lib/**`, `libnative/**`, `gradle/**`, wrapper scripts,
root Gradle files, the workflow itself); plus `workflow_dispatch`.

**Jobs:** `build` (JDK 17, CMake, `assembleRelease`, rename, verify, upload)
then `release` (download artifact, `softprops/action-gh-release`). Release
runs after every successful *push* build — full release from `main`,
prerelease otherwise — never on pull requests.

**Least privilege:** workflow defaults to `contents: read`; only `release`
holds `contents: write`, and it never runs Gradle.

**Runner:** `ubuntu-latest`, JDK 17 (Temurin), `timeout-minutes: 60` (the
native build is the long pole on a cold cache).

**Verification:** file exists, size ≥ 1 MB, valid zip, contains `classes.dex`.
Signature verification is deliberately absent: a release build fails outright
without `signingConfig`, so the check would be redundant.

**Artifact:** one artifact `nepali-keyboard-v<VERSION>-<SHORT_SHA>` with one
APK. Retention 30 days. Do not add a second artifact path.

---

## 7. Release signing

`keystore/release.keystore` is a **deliberately public demo key**, committed
with its credentials in plain text at `keystore/release.keystore.properties`.
It lets CI produce a *signed* release APK with zero external secrets.

**Its limits — state these, do not paper over them:**

* The private key is public. **Anybody can forge an update signed with it.**
* It is not hardware-backed.
* It **must never** be used for store distribution.

`.gitignore` explicitly does **not** ignore it. Do not add it to `.gitignore`.

---

## 8. Definition of done

Before you consider a change complete:

- [ ] A green run of `.github/workflows/build-release.yml` on your branch
- [ ] No new permission without a reason recorded in the manifest
- [ ] Upstream copyright headers intact on every touched upstream file
- [ ] No `dev.patrickgold` string left anywhere functional
- [ ] Exactly one APK artifact; release job untouched unless releases change
- [ ] You have not claimed to have built, run, or tested anything you did not
      actually execute
