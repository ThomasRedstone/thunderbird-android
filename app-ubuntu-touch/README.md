# Ubuntu Touch mail preview

An unofficial Compose Desktop preview using Thunderbird's JVM mail engine and Bolt components. The demo mailbox
contains bundled samples. Accounts supports manual TLS IMAP/SMTP settings and protected credentials. Mailboxes opens
cached folders/messages offline, discovers folders with Refresh, syncs a selected folder and reads plain-text mail.
Partial messages offer an explicit full download. Networking is foreground-only. Persistent draft/outbox storage
and guarded SMTP attempts are connected to Write: choose an account, create/reopen a draft, edit To/subject/body,
save or autosave locally, queue offline, then explicitly Send now. Uncertain delivery requires a duplicate-risk
confirmation before retrying. Background delivery, HTML, attachments and broader daily-use features remain roadmap work. The current development
version is 0.1.9; device acceptance is recorded separately in the milestone evidence.

## Desktop build

Use Linux, JDK 21, the repository Gradle wrapper and Android SDK (needed while configuring shared modules). Set
`JAVA_HOME` and `ANDROID_HOME` for your installation. The project compiles JVM bytecode targeting Java 17.

```sh
./gradlew :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:spotlessCheck :app-ubuntu-touch:installDist \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
app-ubuntu-touch/build/install/app-ubuntu-touch/bin/app-ubuntu-touch
```

UI tests require an X11 display; on a headless Linux host run the Gradle command under `xvfb-run -a`.
Use Escape or the Back button to return from a message or folder. The subject filter is also a typing/input check.

## ARM64 Click build

Requires Docker with ARM64 binfmt support, Clickable 8.9 or newer, `unzip`, `readelf` and `dpkg-deb`.
Compile JVM bytecode on the host; run only JDK packaging in the ARM64 container. The base OS is pinned by digest;
apt packages are signed but not version-pinned, so retain the image ID and package inventory for a release.

```sh
docker build --platform linux/arm64 -f app-ubuntu-touch/packaging/arm-packager.Dockerfile -t local-mail-demo-packager .
docker image inspect local-mail-demo-packager --format '{{.Id}}'
UT_ARM_PACKAGER_IMAGE=sha256:YOUR_IMAGE_ID UT_OUTPUT=/absolute/new/output \
  app-ubuntu-touch/packaging/build-click.sh
```

The output directory must be absent. Failed staging is retained. The script runs Clickable's default review, propagates
failure, and verifies ARM64 launcher/JVM/AWT/Skiko libraries. The package bundles the JRE, its legal directory, original
runtime JARs with embedded notices, dependency coordinates/hashes, packaging image/package inventories and port sources.
This inventory is a starting point for release licensing review, not a claim that all required dependency sources are bundled.

The launcher uses the session DISPLAY or discovers an existing Xwayland socket, and passes an app-owned writable
cache directory to Java/AWT/Skiko before JVM startup. The `accounts` policy group accesses protected SignOn storage, and `networking` permits the requested
foreground IMAP/SMTP connections to configured servers. No background/lifecycle exemption is requested.

## Phone deployment

The shared `../ut/ut-phone-manager.md` workflow uses Phone Manager, with app launch/focus and screenshot checks from
`../ut/ut-fleet-ops.md`. For the existing FP4 SSH alias, a resumable transfer followed by phone-local installation avoids
re-uploading the file through the remote HTTP proxy:

```sh
rsync --partial --append-verify package.click 100.87.156.48:/home/phablet/package.click
ssh 100.87.156.48 'sha256sum /home/phablet/package.click'
# Compare with the local package hash before installing.
ssh 100.87.156.48 '/snap/bin/phone-manager.pm push /home/phablet/package.click'
../phone-manager/dist/pm --ssh 100.87.156.48 apptest --keep --shots ./phone-shots mail-demo.thomasredstone
```

Use the exact candidate path emitted by `build-click.sh` instead of `package.click`. Phone Manager's test must confirm
an alive, focused app while the phone is unlocked; a launcher exit message or live JVM alone is insufficient. Inspect
the actual screenshot. Touch scrolling and the on-screen keyboard also need device interaction.

The launcher preserves Lomiri's exported `APP_ID`, which identifies the full package/hook/version launch. Its separate
cache identifier must not overwrite that variable. Click forbids external host utilities such as `mkdir` and `dirname`;
the launcher uses shell builtins, and Java creates its confined cache before loading AWT.

## Touch layout and keyboard

Click launches preserve the full `APP_ID` and use an explicit screen-sized Compose canvas. The FP4 preview applies
2.75x content density, following the existing `mobileapp` port; other devices may need density calibration.

The JVM Maliit adapter follows the working `../ut-platform/src/ime.rs` protocol: discover the private peer address over
the session bus, export the input-context callbacks, activate a focused text widget and request the keyboard. It handles
`updatePreedit`, `commitString`, byte-typed control-key events and keyboard geometry. Back/blur resets and hides the
context. Window-focus changes suspend and restore input activation. These operations run off the UI thread and do not
log typed text. The filter is a plain-text field; secure password hints must be added before using this adapter for credentials.

Eight tests cover the mailbox/navigation UI, cache setup, Maliit wire signatures and preedit/commit/backspace editing.
`packaging/test-launch.sh` also checks a restricted PATH and preservation of Lomiri's APP_ID. Actual device typing,
keyboard placement, rotation and focus recovery still require the phone acceptance checks in the evidence record.

For build iteration, `UT_RUNTIME_IMAGE=/absolute/path/to/a/previous/mail-demo/lib/runtime` can reuse a verified ARM64
runtime. The script checks its JVM architecture, still runs target-native `jpackage`, and records the reused modules
hash. Omit the variable to generate a fresh runtime.

## Acceptance

See the [dependency audit](../docs/engineering/ubuntu-touch-dependency-audit.md),
[roadmap](../docs/engineering/ubuntu-touch-roadmap.md) and [milestone evidence](../docs/engineering/ubuntu-touch-m1-evidence.md).
A package build is not proof of on-device input, confinement, reboot behaviour or store approval. Device installation
and system changes are separate actions; no phone deployment is included in this build workflow.
