# Releasing sshovel

Every APK that leaves the project (GitHub release, F-Droid, Play) is built from a public, tagged
commit, and its About screen links to that tag (GPL-3.0 §6, ARCHITECTURE §12 item 4). This is the
procedure. It uses the toolbox container (`tools/android-env/`), which has every tool the build
needs and puts the checkout at the same path each time.

## Pinned toolchain

| Tool | Pinned in | Version |
|---|---|---|
| Go | `core/go.mod` (`go` line; `GOTOOLCHAIN=auto` in the toolbox fetches it) | 1.26.3 |
| gomobile / gobind | `core/go.mod` (`tool` directive) | as pinned |
| Go modules | `core/go.sum` | as pinned |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` | 9.8.0 |
| AGP, Kotlin, libraries | `gradle/libs.versions.toml` | as pinned |
| Android build-tools | `app/build.gradle.kts` (`buildToolsVersion`), toolbox `Dockerfile` | 36.1.0 |
| compile SDK | `app/build.gradle.kts`, toolbox `Dockerfile` | android-37.0 |
| NDK | toolbox `Dockerfile`: the newest stable (r28+), or `SSHOVEL_NDK`; recorded per release | see `release-<tag>.txt` |
| JDK | toolbox `Dockerfile` (Ubuntu 24.04 `openjdk-21-jdk-headless`) | 21 |
| go-licenses, reuse | toolbox `Dockerfile` | v1.6.0, latest |

The NDK compiles the cgo glue in `libgojni.so`, so a rebuild that should match a published APK
byte for byte needs the NDK recorded in that release's `release-<tag>.txt` ("SDK packages"). Pass
it when building the toolbox: `SSHOVEL_NDK=28.2.13676358 tools/android-env/run.sh release <tag>`
(the version without the `ndk;` prefix).

## 1. Prepare the release commit

1. On `main`, with CI and the acceptance run green (`tools/android-env/run.sh m8`, or the latest
   milestone script).
2. In `app/build.gradle.kts`, set `versionName` to the new version (`MAJOR.MINOR.PATCH`, e.g.
   `0.9.0`) and increase `versionCode` by one. The Go core gets the same version through
   `-ldflags` (`Mobile.version()` on the About screen).
3. Add the release to `docs/RELEASE_NOTES.md`, and copy its user-facing part to
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (at most 500 characters).
4. Commit with a sign-off (`git commit -s -m "Release 0.9.0"`).

## 2. Tag it

```bash
git tag -s v0.9.0 -m "sshovel 0.9.0"     # -a instead of -s without a GPG/SSH signing key
git push origin main v0.9.0
```

The tag is `v` followed by `versionName`, exactly. Gradle reads it with
`git describe --tags --exact-match` and builds `https://github.com/dennisklein/sshovel/tree/<tag>`
into the APK as the About screen's source link; on an untagged commit the link names the commit
instead.

## 3. Build from a clean checkout of the tag

```bash
tools/android-env/run.sh release v0.9.0
```

`tools/android-env/release.sh` clones the repository into `/build/sshovel` inside the toolbox,
checks out the tag, and then:

- refuses a tag that doesn't match `versionName`
- runs `:app:assembleRelease`, whose `preReleaseBuild` first runs `checkLicenses` (AboutLibraries
  strict mode + `go-licenses check`) and `reuseLint`, then `collectGoLicenses` and
  `verifyPageAlignment`
- checks that the APK carries the source link for the tag
- writes `tools/android-env/out/sshovel-<tag>.apk` and `release-<tag>.txt` (commit, signer, SDK
  packages, Go and JDK versions, SHA-256 of the APK and each native library)

**Signing.** Without signing properties the release build is signed with the debug key (a
placeholder: fine for testing, not for publishing, since an APK signed with it can't be updated by
one signed with the real key). For a published release, keep the keystore outside the repository
and pass it in:

```bash
export SSHOVEL_SIGNING_STORE_FILE=/work/../keys/sshovel-release.jks   # a path inside the toolbox
export SSHOVEL_SIGNING_STORE_PASSWORD=… SSHOVEL_SIGNING_KEY_ALIAS=sshovel SSHOVEL_SIGNING_KEY_PASSWORD=…
tools/android-env/run.sh release v0.9.0
```

The script hands them to Gradle as `sshovel.signing.*` properties (`ORG_GRADLE_PROJECT_…`, so the
passwords don't show up in the process list). Outside the toolbox, put the same four properties in
`~/.gradle/gradle.properties`. Never commit the keystore or its passwords.

**Reproducibility.** The Go library is built with `-trimpath`, and the APK carries no
dependency-metadata block (`dependenciesInfo`, which is encrypted and differs on every build), so
the same tag, toolchain (NDK included), checkout path and signing key give a byte-identical APK.
In M8, two builds of the same tag produced the same APK hash. Compare the hashes in
`release-<tag>.txt` against a rebuild; with a different key, compare the native libraries' hashes
and the unzipped contents instead.

## 4. Check the result

1. Install it: `adb install tools/android-env/out/sshovel-v0.9.0.apk`.
2. Settings → About shows version `0.9.0`, the copyright line, the "free software … ABSOLUTELY NO
   WARRANTY" statement, and "View license" opens the full GPL text.
3. "Source code" opens `https://github.com/dennisklein/sshovel/tree/v0.9.0`, and the page loads
   (the tag is pushed).
4. Open-source licenses lists the Android libraries (AboutLibraries), the Go modules
   (`go-licenses report`), the Go runtime, and the bundled font and icons.
5. Run the manual test matrix (IMPLEMENTATION_PLAN §7) on a device, at least the rows marked for
   physical devices in the latest acceptance summary.

## 5. Publish

Create a GitHub release for the tag with the notes from `docs/RELEASE_NOTES.md`, and attach the APK
and `release-<tag>.txt`. F-Droid builds from the tag itself, using the fastlane metadata in the
repository.
