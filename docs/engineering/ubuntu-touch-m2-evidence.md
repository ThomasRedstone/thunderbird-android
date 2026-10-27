# Ubuntu Touch live-mail implementation

## Baseline and scope

The offline preview source is committed and pushed as `b715212616` on `feat/ubuntu-touch-demo`.
Fresh JVM tests, detekt, spotlessCheck and installDist passed on 2026-10-27 using JDK 21 and Xvfb;
the launcher shell test also passed. The installed FP4 version remains 0.1.4, with retained package SHA256
`e0b4ef1dda9408653cd155f062bed93710fb8d23a89ec676ffbe111676faa455` verified again over SSH.
That package predates the dependency-wiring extraction in the commit; it must not be described as a clean build
of the commit. A fresh package and fleet alignment remain checkpoint A work.

## Work package 2.1: persistent backend storage

Brief: implement account-scoped durable storage for the existing JVM `BackendStorage`/`BackendFolder` contracts.
Preserve folder metadata, raw MIME messages, flags and sync checkpoints across process restart. Use prepared
statements and transactional updates; fail on unknown schema versions or corrupt files without deleting mail.

The proposed Global Database architecture is not implemented in this checkout: `core:database` is absent and
legacy storage depends on Android. The port therefore needs an app-local adapter pending upstream integration.
It uses AndroidX's bundled SQLite driver, the same driver family recommended for the proposed Room architecture,
rather than adding a second database framework. This does **not** implement the proposed unified Room schema or
legacy import. See [database architecture](../architecture/database.md),
[AndroidX releases](https://developer.android.com/jetpack/androidx/releases/sqlite) and
[KMP database setup](https://developer.android.com/kotlin/multiplatform/room).

Acceptance: reopen a file and recover folder IDs/types, MIME, flags and sync checkpoints; isolate accounts sharing
server folder/message IDs; roll back interrupted updates; reject future schemas and corruption without resetting
the store. Exercise the real SQLite implementation in tests. Verify target ARM64 native loading separately before
phone acceptance. No SQL or filesystem paths escape into portable domain contracts.

Exclusions from this bounded item: passwords, account UI, network sync/send, the upstream Room migration and
background execution. These remain required later roadmap work, not product exclusions.

Implemented: `MailDatabase`, `PersistentBackendStorage` and `PersistentBackendFolder`. Folder batches and message/flag
writes use SQLite transactions. Foreign keys cascade message/flag removal. Account-scoped metadata stores sync
checkpoints; MIME is reparsed using the existing mail engine. Unsupported schema versions and corruption fail without
resetting the database. No password storage is included.

Verification on 2026-10-27:

- All 13 app tests pass (five new storage tests). These exercise MIME subject/body recovery, persistent folder identity,
  flags and checkpoints after reopen, account isolation, cascading deletes, rollback, future schema and corruption.
- `:app-ubuntu-touch:detekt`, `:app-ubuntu-touch:spotlessCheck` and `:app-ubuntu-touch:installDist` pass using JDK 21.
  Tests run under Xvfb, with `--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
- The test-only `PhoneStorageProbe` was copied over SSH with the compiled adapter and SQLite JVM JARs, and run on the
  FP4 using the installed preview's bundled JVM. Exit 0: `SQLite native load, persistent reopen and account isolation
  passed`. It creates/removes only its own temporary directory. Output retained in
  `app-ubuntu-touch/build/evidence/storage-probe/phone-result.txt`.
- The bundled JAR contains `natives/linux_arm64/libsqliteJni.so`. The Click verifier now checks this member's ELF
  architecture when SQLite is present, preserving the vendor JAR and licence.

The phone probe ran outside Click confinement. It proves target-native driver compatibility and disk persistence;
it does not prove confined application storage, account setup or live mail. The adapter is not yet wired into the UI.
Shared account/domain repositories and upstream Room integration remain open. Android application and instrumented
tests were not run: changes are confined to this new JVM app and its catalog dependency declaration.

## Account prerequisite: JVM TLS adapter

`JvmTrustedSocketFactory` implements the mail engine's socket contract for both unconnected direct-TLS sockets
and connected STARTTLS wrapping. It uses the JVM trust store and Apache's hostname verifier already used by
`mail:common`; TLS 1.2/1.3 remain enabled. Chain trust, leaf validity and the configured server hostname must all pass.
Certificate failures retain the engine's `CertificateChainException` so callers can classify them correctly. There
is no trust-all mode or certificate override. Client-certificate authentication is explicitly unavailable pending its
own platform implementation.

Verification: all 16 app tests pass, including the three TLS tests; detekt and spotlessCheck pass.
The unknown-certificate test also asserts that the engine can extract the rejected certificate chain from the
handshake exception. Evidence: `app-ubuntu-touch/build/test-results/test/TEST-net.thunderbird.app.ubuntutouch.JvmTrustedSocketFactoryTest.xml`.

Real loopback TLS tests generate an ephemeral localhost certificate using the JDK keytool. They test success for
trusted direct TLS and STARTTLS, rejection of trusted certificates for another hostname in both modes, and rejection
of an unknown self-signed certificate by the system trust store. Test credentials are temporary fixture values.
This is a prerequisite for account setup, not a completed account flow: Secret Service, server settings persistence,
secure input, UI and protocol wiring remain open.

## Work package 2.2: account persistence and protected secrets

Brief: implement the Linux adapters for shared `AccountManager<UtMailAccount>` and `AccountRepository`, with durable
server settings and protected passwords. Preserve existing cached mail when upgrading the schema. Account settings
must contain no passwords; unavailable or locked secret storage must never trigger a plaintext fallback. Test
restart, failed writes, replacement and selected-account removal before wiring the setup UI.

Implemented adapters:

- `UtAccountManager` provides shared API create/update/delete, ordering and observable accounts. `UtMailAccount` uses
  the shared `AccountId` and existing `ServerSettings`. Settings reject disabled TLS, embedded passwords and unsupported
  authentication modes; initial password/app-password authentication supports IMAP/SMTP. OAuth and client certificates
  remain later required work.
- Database schema 2 adds account/server configuration and a credential-cleanup journal. The schema-1 upgrade preserves
  cached folders and sync metadata. Credentials are written under an immutable reference before the SQL commit claims
  them. Failed/crashed saves leave journaled, unreferenced entries that can be cleaned on retry/restart. Password
  replacement preserves the old reference if the new write fails. Delayed cleanup is reported separately from the
  committed password change. Removal refuses locked/unavailable storage, then deletes only the selected account's
  credentials, settings and cached mail. A crash between secret deletion and the SQL removal can leave an account
  needing credentials again; it does not erase its cached mail. Removal is retryable.
- `SecretServiceCredentialStore` implements the
  [Secret Service protocol](https://specifications.freedesktop.org/secret-service/latest/) on the local session bus,
  following `../ut-platform/src/secrets.rs`. Sessions close after each operation. The library's bounded reply timeout
  is 20 seconds per method. These blocking adapters must be called on IO dispatchers; shared suspend create/update
  methods dispatch to IO, and setup UI wiring remains pending. Locked items, missing service,
  unsupported prompts and invalid replies fail clearly. No unlock UI is implemented yet. The `plain` session algorithm
  follows the local-service reference; per-app attributes namespace items but do not isolate them from unconfined
  processes running as the same user. Labels contain no account address or password.
- Maliit now accepts a hidden-text focus mode, resets composition before activation, and explicitly disables prediction,
  correction and capitalization for passwords. Normal-field hints reset the hidden-text mode. This API is unit-tested;
  password-field phone interaction is not yet verified.

Verification on 2026-10-27:

- All 26 app tests pass, including eight account tests and the protocol/hint tests. Cases cover restart with separate
  protected passwords, no plaintext password in SQLite, locked-store failure, interrupted SQL saves and credential
  cleanup after reopen, account-scoped deletion, password replacement/failure, migration and TLS/settings validation.
- Detekt, spotlessCheck and installDist pass. Command: JDK 21 with Xvfb,
  `./gradlew :app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:spotlessCheck :app-ubuntu-touch:installDist --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
- `app-ubuntu-touch/scripts/test-secret-service.sh` passes against a disposable session bus and real GNOME keyring:
  create/read/replace/delete, namespace isolation and actual locked-item failure. It deliberately locks only its
  disposable collection and never uses the user's existing session bus or keyring. Requires dbus-run-session,
  gnome-keyring-daemon, JDK 21 and the configured Android SDK for Gradle configuration.
- The test-only `SecretServiceProbe` ran on FP4 using the installed preview's bundled JVM: exit 0,
  `Secret Service create/read/replace/delete and namespace isolation passed`. It created and deleted only a random
  probe namespace/item. The phone collection was never locked or modified beyond those test items. Output retained in
  `app-ubuntu-touch/build/evidence/secret-probe/phone-result.txt`.

The real-keyring probe exposed a D-Bus path equality bug; comparing protocol paths instead of library source metadata
fixed it, and both real-service probes pass. The phone test was outside Click confinement. Confined access, setup UI,
secure-field interaction and live IMAP/SMTP are still open. The installed app remains the offline 0.1.4 preview.
These are unfinished roadmap requirements, not product exclusions. No production account credentials were requested.

## Account setup UI candidate 0.1.5

The application now has an Accounts entry with manual IMAP/SMTP server settings, direct TLS/required STARTTLS,
separate usernames/passwords, editing and explicit account-removal confirmation. It uses Bolt components, Koin and
an MVI controller. Database creation uses the app's XDG data directory with owner-only directory permissions.
Blocking storage/keyring work runs on IO; navigation is disabled while saves/removal are active. Password fields are
cleared after saves, failures and cancellation, and are never prepopulated from the keyring. A failed combined
settings/password replacement keeps the prior settings/reference; obsolete-key cleanup failures are reported after
the active account is committed. The UI explicitly states that receiving/sending are not yet connected.

The shared Bolt password field gained a `TextFieldValue` overload, preserving selection/cursor/preedit data while
retaining password masking, visibility controls and semantics. The existing String overloads and tests remain.
Keyboard edits apply to the controller's current state, avoiding lost input when several Maliit events arrive before
recomposition. Demo state is hoisted through the new application screen; the existing demo navigation test still passes.

Verification:

- All 30 app tests pass. Added journeys cover account create/edit/confirmed removal, locked-keyring error and password
  clearing, consecutive Maliit edits with selection, and scrolling the form to enter a masked password and cancel.
- All 108 Bolt JVM tests pass, including the new selected-password replacement test. `:components:ui:bolt:compileAndroidMain`
  passes for the shared component change. Android instrumentation/device tests remain unrun; no Android test device
  is configured for this port work.
- App detekt, spotlessCheck and installDist pass. Scoped Spotless IDE-hook checks report `IS CLEAN` for both changed
  Bolt Kotlin files. The standalone Bolt detekt task initially fails because its included-build config path is missing.
  Running it with the root repository detekt config reports 27 findings; running the same analysis over committed
  `HEAD` sources reports the same 27 rule/message pairs, with no new findings. This is a retained baseline quality gap,
  not a claimed passing whole-module static check. SARIF comparisons are retained under
  `app-ubuntu-touch/build/evidence/account-ui/bolt-{current,baseline}.sarif`.
- The normal desktop distribution launched under Xvfb with a separate test XDG data directory. Accounts and Add account
  navigated to the form. Rendered screenshots were inspected at
  `app-ubuntu-touch/build/evidence/account-ui/{accounts,form}.png`.

Version 0.1.5 is prepared in source and packaging metadata. Packaging, Click review, confined SQLite/Secret Service,
physical secure input and phone account setup acceptance remain pending. The phone still has 0.1.4 installed.
Live IMAP/SMTP, controlled-server integration and the rest of the roadmap remain open work.
