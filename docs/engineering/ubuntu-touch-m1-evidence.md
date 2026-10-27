# Ubuntu Touch offline demo build evidence

On 2026-10-26 the new `app-ubuntu-touch` entry point built and ran on desktop Linux, and produced an Ubuntu Touch
24.04 ARM64 Click that passed Clickable review and native architecture verification. This is an offline demo using
bundled messages. Real-device acceptance and live-mail functionality remain outstanding.

## Source and toolchain

- Thunderbird base: `de8a03268415ce8e0c8b0327d3bbcb6b40a06a91`, with local changes on `feat/ubuntu-touch-demo`.
- Packaging reference: `mobileapp` commit `0477ae19007767f9580f0308eb8f773815b6f6ca` (read-only).
- Actual Gradle wrapper: 9.7.0. The existing version-catalog entry says 9.6.1; neither was changed by this port.
- Kotlin: 2.4.21; Compose: 1.12.1; Skiko: 0.150.1; Koin: 4.2.2.
- Host JDK: OpenJDK `21.0.12.1+1-1-26.04.4-Ubuntu`; JVM bytecode target: Java 17.
- ARM64 packaging/runtime JDK: OpenJDK `21.0.12.1+1-1-24.04.4-Ubuntu`.
- Packager image: `sha256:3a08b52420bd76280d8323db951a306e428fb17ac5e4cd60f53903e049a68563`.
- Docker: 29.5.2; Clickable: 8.9.0.

The build adds one application module and its settings entry. Shared source, Android application wiring, architecture
boundaries and sibling repositories are unchanged. The application binds only its own internal demo classes through Koin.
Crane exploration and planning were retained as reference; implementation was performed directly after the user changed
the workflow. No Crane plan was self-approved.

## Verification

With `ANDROID_HOME=/home/tom/Android/Sdk` and `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`:

```sh
xvfb-run -a ./gradlew :app-ubuntu-touch:test :app-ubuntu-touch:detekt \
  :app-ubuntu-touch:spotlessCheck :app-ubuntu-touch:installDist \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
```

Result: **BUILD SUCCESSFUL**, 88 actionable tasks. Three tests pass, with no failures or skips:

- Actual bundled demo mailbox: Inbox has seven messages; nested folder identifiers survive flattening; MIME text decodes;
  repeated loading returns the same display data.
- State navigation: folder → message → back → folders, including filter preservation/reset.
- Compose UI: open Inbox, enter a subject filter, open/read a message, navigate back, and open an empty folder.

Detekt reports zero findings. Two scoped `TooManyFunctions` suppressions cover implementations of existing backend
interfaces that require more methods than the configured limit; project-wide rules remain enabled.

The tests caught and verified a required MIME temp-directory initialisation. Loading now uses a temporary directory,
converts messages into display strings, restores the previous backend temp-directory setting, and removes temporary
message/attachment files. The default desktop distribution also exposed duplicate KMP JAR basenames; both the desktop
launcher and distribution now use matching SHA256-prefixed names, preserving all dependencies.

`packaging/test-launch.sh` passes, checking inherited DISPLAY, cache paths containing spaces, temp/Skiko JVM options and
argument boundaries. Shell syntax checks pass. `verify-click.sh` correctly rejects a negative package containing an
x86-64 `/bin/true` executable in place of the ARM64 launcher.

No Android regression suite was run because no shared source or existing Android assembly was changed. Phone-based
`connectedAndroidTest` was not run; no Android device was used for this desktop/Ubuntu Touch entry point.

## Desktop runtime

The generated `build/install/app-ubuntu-touch/bin/app-ubuntu-touch` launcher runs with JDK 21 under Xvfb. Screenshots show
folders, the seven-message Inbox and the decoded welcome message. The visible preview label identifies sample data.
The Compose UI test independently verifies the Back button and text entry. Escape navigation was exercised with X11
window focus set explicitly.

The final screenshot run used `SKIKO_RENDER_API=SOFTWARE`, appropriate for Xvfb. An earlier default-renderer run reported
an unavailable GL context and fell back; this does not establish GPU rendering on Ubuntu Touch.

## ARM64 package

The packager was built with:

```sh
docker build --platform linux/arm64 -f app-ubuntu-touch/packaging/arm-packager.Dockerfile \
  -t local-mail-demo-packager .
```

The package was built with the host/JDK environment above and:

```sh
UT_ARM_PACKAGER_IMAGE=sha256:3a08b52420bd76280d8323db951a306e428fb17ac5e4cd60f53903e049a68563 \
UT_OUTPUT=/home/tom/own/thunderbird-android/app-ubuntu-touch/build/ut-candidate-1 \
  app-ubuntu-touch/packaging/build-click.sh
```

Gradle stages platform-independent bytecode and 101 dependency JARs with ARM64 Skiko. Target-native `jpackage` runs under
ARM64 emulation in a network-disabled container and bundles a JRE with its legal directory. The bundled ARM64
`lib/runtime/bin/java -version` also executes successfully under emulation.

Artifact, relative to repository root:

```text
app-ubuntu-touch/build/ut-candidate-1/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.0_arm64.click
```

- Size: **139,163,326 bytes** (about 132.7 MiB).
- SHA256: `6bdf6b20006522d85db1e814583d8ddc8119e0b34bb3b040ba09ae69e5a95d25`.
- Clickable's default review result:

```text
mail-demo.thomasredstone_0.1.0_arm64.click: pass
```

The complete review/build output is retained in the local evidence directory. No review findings were suppressed.
`verify-click.sh` successfully verifies AArch64 for the native launcher, bundled Java launcher, JVM, AWT libraries and
Skiko extracted from its JAR, and rejects a bundled host Skiko library.

The payload preserves original runtime JARs, JRE legal notices, dependency coordinates and ordered hashes, source commit
and local patch, port sources, and packaging image/package inventories. Full dependency source compliance remains a
release gate. The OS base digest is pinned; apt inputs are signed but not version-pinned. This was one successful build
using the existing host dependency cache, not an independent clean-cache or byte-for-byte reproducibility demonstration.

## Local evidence files

Generated evidence is under `app-ubuntu-touch/build/evidence/` and is ignored by Git:

- `gradle-checks.log`, `runtime-dependencies.log` and Gradle XML/HTML reports under `build/test-results` and `build/reports`.
- `packager-build.log`, `click-build.log`, `architecture-negative.log` and `bundled-arm64-jvm.log`.
- `desktop.log`, `folders.png`, `messages.png`, `reader.png` and `back-to-messages.png`.

The package and these generated files can be removed by cleaning the module; the commands and results above are the
persistent record.

## Phone deployment and fixes on 2026-10-26

The user authorised installation/testing using the shared `ut` knowledge base. The target is the existing Fairphone 4,
Ubuntu Touch 24.04-2.x build 708, reached as `phablet` on `100.87.156.48:2222`. Packages were transferred with resumable
rsync, checked against their host SHA256, then installed through phone-local `/snap/bin/phone-manager.pm push`.
No firmware, OS policy, lifecycle exemption or other application was changed. No reboot was performed.

| Version | Device finding or change |
| --- | --- |
| 0.1.0 | Installation succeeded; normal confined launch failed because external `mkdir` was forbidden. |
| 0.1.1 | Launcher uses builtins and Java creates the cache; JVM starts. |
| 0.1.2 | Preserves Lomiri's full `APP_ID` instead of overwriting it with the cache name. The user confirms the app opens and the filter cursor flashes, but reports a missing keyboard and a small canvas. |
| 0.1.3 | Explicit screen-sized canvas and FP4 density fix. Captured app buffer is 1080x2259 and fills the window with readable UI. The user subsequently confirms correct physical sizing. |
| 0.1.4 | Adds the Kotlin Maliit adapter, following the existing `ut-platform` protocol; installed; the user confirms a correctly sized window with working on-screen typing. |

Current installed artifact:

```text
app-ubuntu-touch/build/ut-candidate-5/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.4_arm64.click
SHA256 e0b4ef1dda9408653cd155f062bed93710fb8d23a89ec676ffbe111676faa455
142506106 bytes
```

This build passed Clickable default review and ARM64 verification. Its 108 runtime JARs include dbus-java 5.2.0 and
junixsocket 2.10.1. Target-native `jpackage` reused the previously verified ARM64 JRE; the runtime modules hash is
recorded in the payload. The app adapter handles focus/reset/hide, composing-word replacement, committed text,
backspace/Enter and keyboard geometry; it does not log typed text. It currently serves only the plain-text subject filter.

Eight tests pass, including exact D-Bus callback signatures (`commitString`, `updatePreedit` and the byte final argument
of `keyEvent`), repeated preedit replacement, selection preservation and Unicode backspace. Detekt passes. The restricted
PATH launcher regression fails on 0.1.0 and passes after removing external utilities. A second regression detects the
APP_ID overwrite in 0.1.1 and passes after separating the cache identifier.

Phone Manager's recorded launch tests see a live JVM, but their focused-PID assertion has not passed in the recorded
runs. Several runs occurred with the display off (panel brightness 0), and compositor screenshots were fully black;
these are not visual acceptance. A separate X11 probe captures only the named Mail demo window and demonstrated the
small-canvas defect and the corrected 1080x2259 canvas. These app-buffer captures establish rendering, not physical touch
or keyboard success. The probe was checked against a running desktop app and against its absence.

Local records are in `app-ubuntu-touch/build/evidence/phone/`: install and launch logs by version, regression logs,
`x11-0.1.3.png`, the initial black compositor capture, and IME test/build logs. All are local generated evidence, ignored
by Git. The user confirms on 2026-10-26: “I have a correctly sized window that types!” This establishes physical sizing and
on-screen typing for 0.1.4. Physical backspace/Enter and blur/refocus remain separate checks; their editing logic has
unit coverage.

Remaining device checks: physical backspace/Enter, keyboard avoidance and blur/refocus, touch scrolling/back, rotation,
suspend/resume and subsequent relaunch. Upgrade has been exercised through these preview versions; reboot and uninstall
remain untested. Clickable review is not store approval. Live accounts and background mail remain later milestones.
