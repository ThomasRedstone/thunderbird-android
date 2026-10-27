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

## Installed 0.1.5 and confinement gate

The clean-source ARM64 build completed and passed Click review plus launcher/JVM/AWT/Skiko/SQLite architecture checks.
Package: `app-ubuntu-touch/build/ut-candidate-6/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.5_arm64.click`.
Size: 146,479,732 bytes. SHA256: `e01a00a6972aae676aec27ccb707800a6b0f6eef993ade0084f1c27812b407f2`.
Embedded source: `1ff8647b8042a5bebdcbc1beb2e71136867313fc`; `SOURCE-CHANGES.patch` is empty. This build reused the
previously verified ARM64 runtime, so it does not prove an independent cold-cache build.

The package was uploaded with rsync and its phone checksum verified before installation through Phone Manager.
The install hook emitted the previously observed user-bus/old-AppArmor-cache warnings. Independent reads confirmed
the installed current link is `0.1.5` and its embedded source matches. Phone-fleet commit `289394a` records the matching
version/path/hash while preserving unrelated edits; its whole-fleet hook was skipped to avoid unrelated convergence.

The test-only `ConfinedStorageProbe` ran via `aa-exec -p mail-demo.thomasredstone_mail-demo_0.1.5` using the installed
bundled JVM and runtime classpath. It reads `/proc/self/attr/current` and refuses to run unless the expected profile is
enforcing. **Confined SQLite native loading, persistence/reopen and account isolation passed.**

**Protected credentials fail under confinement.** The same probe's Secret Service operation returns UNAVAILABLE.
The phone journal confirms an AppArmor denial of `org.freedesktop.Secret.Service.OpenSession` at
`/org/freedesktop/secrets`. This is blocker `b-zxbi`; it blocks phone account-save acceptance, not independent JVM
protocol/composer work. There is no plaintext fallback and the app has not been unconfined. Evidence is retained in
`app-ubuntu-touch/build/evidence/account-ui/{confined-result,secret-service-denial}.txt`.

Next investigation: the sibling Lightning port uses SignOn/Online Accounts with the standard `accounts` policy group
(`../lightning/src/storage/SignonSecretStore.cpp`), rather than Secret Service, for confined credentials. The FP4's
SignOn AuthService interface was introspected and retained as `signon-service-interface.txt` in the same evidence
directory. Implement and test that supported adapter/permission path without weakening app confinement. Never call
the service's global clear operation or broaden another app's credential access.

The screen was off during installation/headless checks. An unlock request is pending; physical password entry,
foreground setup, rotation/lifecycle and reboot acceptance remain open. No visual phone acceptance is inferred from
these probes. Live receiving/sending and later roadmap requirements remain unimplemented.

## SignOn adapter candidate 0.1.6

The Ubuntu Touch composition now selects `SignonCredentialStore`; desktop Linux retains Secret Service. This
addresses the platform API mismatch behind `b-zxbi`, pending confined-device acceptance. The manifest adds the standard
`accounts` policy group: the installed Ubuntu Touch policy grants SignOn AuthService/Identity/AuthSession calls through
this group. This permission is necessary for the requested protected account credentials. No policy override, global
unconfinement or privileged companion is introduced, and no other app's permissions are changed.

The Kotlin D-Bus adapter follows the sibling Lightning integration and the official
[SignOn daemon source](https://gitlab.com/accounts-sso/signond), inspected at
`a08b05b79100bfd535100b8341df4e8bf1441802`, together with actual FP4 interface introspection. Secrets are read through
the password/password auth session, with `UiPolicy=NoUserInteraction`; identity metadata never supplies a password.
Daemon ACL/ownership checks remain intact. The adapter verifies its own namespace/key metadata before reads, writes
and deletes, and does not expose global enumeration or clear operations.

Schema 3 adds an index of our opaque credential references to SignOn identity IDs. Enumeration is restricted to
privileged keychain clients, so the app cannot use it to rediscover IDs. The adapter first stores an **empty** identity,
commits its ID to SQLite, then sends the secret. A crash before the index commit can leave only empty metadata, never
an unindexed password. Failed index commits attempt to remove the empty reservation; failed secret stores retain an
indexed entry that the account cleanup journal can remove. A failed deletion keeps its index for retry. Empty metadata
left by a crash between reservation and indexing remains a resource-cleanup limitation; do not broaden enumeration
permissions or clear other identities to address it. The existing account schema, mail and cleanup journal survive
migration. Password bytes are encoded only for transport inside the protected service secret; encoding is not
encryption and no password payload is written to the app database.

Verification so far:

- All 37 app tests pass, along with detekt, spotlessCheck and installDist. New cases cover commit-before-secret
  ordering, index failure without sending passwords, secret-write failure/restart cleanup, deletion retry, ownership
  mismatch, schema-2 migration and D-Bus signatures. Existing schema-1 migration and mail/account tests still pass.
- The bundled-JVM `SignonProbe` passed on FP4 outside confinement: indexed create/read/replace/delete and identity
  isolation. Only fresh synthetic probe identities were created/removed. Evidence:
  `app-ubuntu-touch/build/evidence/signon/unconfined-result.txt`.
- `AccountRestartProbe` is a test-only two-process executable for account creation, cold JVM restart/read, editing,
  password replacement and removal. Its synthetic database uses a marked private cache directory, not the real app DB.
  Failed operations retain that directory for scoped recovery instead of discarding its credential index.

0.1.6 packaging and the enforcing-profile round trip are next. Physical secure input, upgrade identity access and
daemon failure/prompt behaviour remain device gates. No encrypted-at-rest claim is inferred solely from using the
system service. The phone is still on 0.1.5 until the new candidate is installed.

## Cold-service activation fix and upgrade test in progress

0.1.6 was built clean from `51d9dcb4fbe8d65dcbd5178649c889d8b8cda766`, passed Click review and ARM64 checks, and was
installed after phone checksum verification. Package size: 146,491,952 bytes; SHA256:
`9ad17fc054193f877a9bdcc96dc2acdf2efc3aecff394e9d8fc4177a38b72e45`. Its source-change patch is empty.

The first enforcing-profile SignOn probe failed at `registerNewIdentity` before storing a secret. The policy file did
contain the expected `accounts` rules, but the daemon had no bus owner. Those rules match the daemon's unconfined peer
label; the cold first call was denied. Explicit `org.freedesktop.DBus.StartServiceByName` before the SignOn interface
call fixes this without changing policy. Failed activation closes the connection and returns a sanitised failure.

A prototype containing that fix ran under the installed **enforcing 0.1.6 profile**. The preflight reported no daemon
owner, then indexed create/read/replace/delete and selective identity isolation passed. This prototype overlays the
updated class for the test; it is not yet evidence that the installed 0.1.6 app contains the fix. Output:
`app-ubuntu-touch/build/evidence/signon/confined-activation-result.txt`.

The real account manager then created a synthetic account and protected credentials under that profile in
`account-probe-016-activation`, a marked private test cache directory. That JVM exited. The fixture is retained for
read/edit/password-replacement/removal by a separate JVM after the fixed 0.1.7 package is installed, checking identity
access across an app upgrade as well as process restart. No production account or the real app database was touched.
Preparation output: `app-ubuntu-touch/build/evidence/signon/account-prepare-016.txt`.

All 37 app tests, detekt, spotlessCheck and installDist pass after the activation change. Packaging and installed-class
confined verification for 0.1.7 are still required before closing `b-zxbi`. Physical input acceptance remains open.

## Installed 0.1.7: confined credentials and upgrade gate passed

0.1.7 was built clean from `d5764edd0f2519f1395f232704421371bf5adc07`, with an empty source-change patch.
Click review and ARM64 checks pass. Size: 146,492,050 bytes; SHA256:
`5fc26145e3ac496c81a049b23fa966a5a53952ef508f7d4d0bda69472b27c82f`. Host package:
`app-ubuntu-touch/build/ut-candidate-8/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.7_arm64.click`.
Phone checksum was verified before installation; the current link and installed source reference were read independently.
Phone-fleet commit `268c562` pins this version/path/hash, preserving unrelated work and skipping whole-fleet convergence.

The test JVM classpath contained **only test classes plus the installed application's production JARs**; no prototype
production class overlay was used. The bundled JVM ran under `mail-demo.thomasredstone_mail-demo_0.1.7 (enforce)`.

- An isolated cold-service check observed no SignOn bus owner, then created/read/replaced/deleted fresh credentials and
  verified selective identity isolation. The daemon activated through the normal bus API and app confinement remained
  enforcing. Output: `app-ubuntu-touch/build/evidence/signon/installed-017-cold-isolated.txt`.
- A separate JVM read the account/passwords created under 0.1.6 after upgrading to 0.1.7. Account editing, password
  replacement, obsolete-credential cleanup and account removal passed. The identity index was empty after removal,
  and the marked probe directory was removed. Outputs: `account-prepare-016.txt` and `account-verify-017.txt` in the
  same evidence directory. Only a synthetic account in that test directory was touched.

Blocker `b-zxbi` is resolved by the supported SignOn adapter and explicit activation. This proves the confined backend
account/secret path, persistence and identity access across this upgrade. It does not prove physical secure-field
typing or every daemon failure/prompt condition. The phone's screen remained off; the unlock request is still pending.
Those gaps remain open alongside live-mail implementation, not blockers to independent protocol/composer work.


## Work package 2.3: foreground protocol engine foundation

Brief: connect the existing JVM IMAP backend and SMTP transport to protected account settings and persistent
account-scoped storage. Expose foreground operations for folder discovery, sync, explicit full-body fetch and sending.
Keep all blocking operations on IO callers, retain TLS validation, and disable the legacy engine's sensitive logging.
This bounded foundation does not complete receiving UI, cancellation, composition/outbox or device acceptance.

Implemented: `ForegroundMailSessionFactory`, `ForegroundMailSession`, `ForegroundSyncListener` and `MailRuntime`.
Sessions resolve secrets only when opened; settings in SQLite retain no passwords. Sync callback failures propagate
to callers. The preview window is 100 messages with a 1 MiB automatic body limit; explicit fetching uses the engine's
complete-message API. Shared sync cancellation remains an upstream integration gap; closing pooled connections is
not claimed to cancel an active sync. Background/push platform methods fail explicitly and no push API is exposed.

The legacy logger is initialised with a disabled, non-controllable wrapper before engine use. Its message lambdas
are not evaluated, and debug/sensitive flags cannot be enabled. Folder status stores only a generic failure marker,
not raw exception text that can reflect server input or credentials.

Verification on 2026-10-27: 45 app tests pass, zero failures/errors/skips. GreenMail 2.1.14 is test-only and absent from
the runtime distribution. Controlled loopback TLS servers with an ephemeral localhost certificate verify real folder
discovery, initial/incremental sync, remote flags/deletions, reopened SQLite cache and SMTP delivery including a
leading-dot body. An independent Jakarta Mail client observes server state. Negative tests verify typed untrusted
certificate and authentication errors, refused-connection cache retention, and no emitted engine log events.
The large-message test failed before changing from partial-download to complete-message API and passes afterwards.
The status privacy test likewise failed before replacing persisted raw errors with a generic marker.

GreenMail does not support STARTTLS ([upstream issue](https://github.com/greenmail-mail-test/greenmail/issues/135)).
The fixture therefore verifies that required STARTTLS rejects unsupported servers for both receiving and sending,
with no delivered message; it does not establish a successful STARTTLS handshake. Successful server interoperability
and phone networking remain open gates. Default production trust is unchanged; fixture trust is injected only in tests.

Command: JDK 21 and Android SDK configured, `xvfb-run -a ./gradlew :app-ubuntu-touch:spotlessApply
:app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:spotlessCheck :app-ubuntu-touch:installDist
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Result: BUILD SUCCESSFUL; log `/tmp/thunderbird-live-engine-final.log` and module XML/HTML test reports.
Android app/instrumentation checks were not run for these JVM-app-only changes. Existing shared Bolt checks and
limitations above remain unchanged. Version 0.1.8 is a development version, not installed; FP4 remains on 0.1.7.

Next acceptance work: cached mailbox UI and explicit sync/error states, persistent draft/outbox and compose/send,
active cancellation and interrupted-operation recovery, successful STARTTLS against a supporting server, then the
complete confined FP4 journey. A direct SMTP transport test is not evidence of an outbox or a usable mail app.


## Work package 2.3: cached mailbox UI

Brief: connect the protected account manager, persistent backend and tested foreground protocol engine to usable
account/folder/message navigation. Cached reads must not require secrets or network availability. Refresh and full-body
fetch must be explicit; failure must retain existing cached mail and must not display raw server exception text.

Implemented: `LiveMailRepository` and its persistent adapter, `LiveMailViewModel` and `LiveMailScreen`, wired through
Koin and application navigation. Mailboxes lists configured accounts, opens cached folders and messages, discovers
folders on Refresh, syncs a selected folder and renders cached plain text. It shows unread state and provides an
explicit complete-body download for partial messages. No HTML is rendered or remote content fetched by the reader.
Navigation and repeated operations are disabled while busy; this is not an implementation of active cancellation.
The demo and secure account setup remain available. Leaving setup disposes its model and clears input state.

The Click `networking` policy group is added for foreground connections to the user's configured IMAP/SMTP servers;
`accounts` remains for protected SignOn storage. This is required by the requested live-mail journey and does not add
an execution exemption or background service. The new package policy still needs actual confined device verification.

Verification: four new tests cover offline navigation with zero refresh calls, readable cache after network failure,
non-disclosure of a synthetic sensitive exception, explicit complete-body download, busy-state navigation exclusion,
and Compose reader/disabled controls. The real controlled-server integration test additionally asserts repository
folder discovery, unread state and body recovery after database reopen. The previous 45 tests are retained.
Final verification: 49 app tests, zero failures/errors/skips; detekt, spotlessCheck and installDist pass.
Command is the same scoped JDK 21/Xvfb Gradle invocation recorded for the engine foundation above, with final log
`/tmp/thunderbird-mailbox-final.log`. Android/instrumentation checks were not run for this JVM-app-only change;
shared Bolt checks and their existing baseline limitation remain unchanged. Version 0.1.8 has not yet been installed;
FP4 remains on 0.1.7.

Remaining gates: phone build/install and controlled-server journey, meaningful typed account/TLS error UI, successful
STARTTLS interoperability, active cancellation, subject filtering/search and efficient envelope-only list reads.
Current lists parse cached MIME and are suitable for the bounded preview window, not a measured large-mailbox solution.
Message reading does not yet mark mail seen. Composition/drafts/outbox, sending UI and all later roadmap features stay open.


### 0.1.8 package and installation

Built clean from `6958418eaca1a98c07f47694ba482a15e6ce3a63` using the pinned packager image recorded above.
`SOURCE-CHANGES.patch` is empty. Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite checks pass.
Artifact: `app-ubuntu-touch/build/ut-candidate-9/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.8_arm64.click`.
Size: 146,967,938 bytes. SHA-256: `2493682cb9e04ab87526a5da828f5d03a2e5b660eb02647ed2e4deb3340f6f3b`.

Rsync completed before the independent phone checksum check. Phone Manager installed the exact file on FP4.
Post-install reads confirm `current` is 0.1.8, the embedded source matches the commit and the generated app profile
exists with accounts/networking permissions. The installer reports unsigned-development-package, user-bus hook and
old AppArmor-cache removal warnings; independent installation checks passed. No launch or live-mail UI acceptance
is inferred from those results. Logs are retained under `app-ubuntu-touch/build/evidence/mailbox/`.
Phone-fleet commit `a07d3ad` pins only this app's version/path/hash; unrelated work is preserved and no full-fleet
convergence was run. Source commits are pushed to `feat/ubuntu-touch-demo`.

Next: verify actual confined protocol operations on FP4 with controlled fixtures, complete the phone mailbox journey,
and implement persistent composition/drafts/outbox. Physical secure-field input, rotation and lifecycle checks remain
open. Existing installed 0.1.7 credential proofs are historical evidence, not a substitute for new 0.1.8 device checks.


## Work package 2.4: persistent drafts and guarded SMTP attempts

Brief: preserve editable drafts and immutable queued MIME across restart, then allow exactly one foreground attempt
per queued row. An interrupted or ambiguous attempt must remain visible and must not be silently retried. Keep all
outgoing records account-scoped, cascade selected account removal, and reject header injection without overwriting
the previous draft. Composer/outbox UI and phone acceptance are separate unfinished parts of this work package.

Implemented: schema 4 adds `outgoing` with draft fields, a stable generated Message-ID, state and frozen MIME.
`OutgoingMailStore` stores drafts, freezes validated recipients and UTF-8 quoted-printable MIME when queued, atomically
claims queued rows, and commits results. Queued messages cannot be edited in place. `OutgoingMailSender` uses the
existing foreground session/SMTP transport. SENT means SMTP acceptance, not final recipient delivery or successful
Sent-folder filing. No automatic send/retry loop exists. These blocking APIs require IO callers.

After a process restart, SENDING becomes UNCERTAIN before UI access. SMTP negative replies and failures explicitly
classified by the engine as safe become FAILED; possibly accepted or unclassified errors conservatively become
UNCERTAIN. No raw error text is stored. A failed row can be explicitly requeued; an uncertain row additionally requires
an explicit duplicate-risk acknowledgement. Its Message-ID is retained, but that is not a promise of server-side
deduplication. A second send call on SENDING/SENT/UNCERTAIN cannot claim another attempt.

Verification: 57 app tests pass, zero failures/errors/skips; detekt, spotlessCheck and installDist pass with the scoped
JDK 21/Xvfb command above. Log: `/tmp/thunderbird-outbox-final.log`. Six new persistence tests verify draft/queue reopen,
stable Message-ID, Unicode MIME/body, immutable queue, interrupted-send recovery and explicit retry gate, single claim,
account isolation/deletion, safe versus uncertain failures, injection rollback and schema-3 upgrade preserving account
and secret-index data. A new real SMTP test observes exactly one server-received queued message with intended content.
A second real-server test injects loss of the local result after actual SMTP acceptance: state remains UNCERTAIN and
another send call produces no second attempt/delivery. This fault injection is not a wire-level lost-ack fixture.
The suite also exposed a stale global MIME temporary path in test isolation; tests now restore that path after use.

Schema 4 is forward-only for older previews: 0.1.8 rejects it rather than deleting/resetting data. Installation rollback
requires a documented compatible backup/migration strategy; it has not been tested. Single application database
ownership remains required; a concurrent second process opening the same store is not a supported send coordinator.
Version 0.1.9 is development only. FP4 and fleet remain at 0.1.8/source `6958418eac`; no new phone result is claimed.

Still required: composer/outbox UI, autosave and recovery, send/retry/uncertain-decision journeys, recipients/Cc/Bcc and
identities, attachments, Sent-folder filing/reconciliation, cancellation, wire-level interruptions and full confined
phone verification. These are continuing roadmap requirements, not exclusions from the final application.


## Work package 2.4: composer and outbox UI

Brief: connect the protected account manager and persistent outgoing store/sender to a foreground plain-text composer
and recoverable outbox. Queueing must work offline, network sending must be explicit, and uncertain delivery must
require a duplicate-risk decision. Preserve selection/preedit and multiline input with Bolt and Maliit.

Implemented: `ComposerState`, `ComposerViewModel` and `ComposerScreen`, Koin bindings and Write navigation.
The account-scoped draft list opens/newly creates drafts with To, subject and body editing. Autosave waits 400 ms after
an edit; a mutex serialises writes. Newer edits cannot be overwritten by an older save response. Save draft and Back
explicitly flush current input; failed saves keep the form/input open. Top-level navigation is disabled while dirty
or busy. Queueing persists frozen MIME without network access; Send now makes a foreground attempt. Outbox details
show queued, failed, SMTP-accepted and uncertain states. Uncertain retry requires a review/confirmation step, with an
option to keep the uncertain record. SMTP acceptance is not labelled as final recipient delivery or Sent-folder filing.
Maliit Enter inserts a newline in the body and dismisses input in header fields; hidden-text hints are reset normally.

Verification: 63 app tests pass with zero failures/errors/skips; detekt, spotlessCheck and installDist pass using the
scoped JDK 21/Xvfb command above. Log: `/tmp/thunderbird-composer-runtime-final.log`. Three new model tests cover
latest-edit autosave, save-before-Back/reopen, queue-without-send, explicit sending, uncertain review/cancel/confirmation
and failed-save input retention. Two Compose tests cover editable recipients, offline queue action and duplicate-risk
confirmation controls. Existing real SMTP/store tests remain in the suite. Android/instrumented checks were not run:
these changes are JVM-app-only; shared Bolt source and its recorded checks/limitations are unchanged.

### Runtime dispatcher correction

An actual standalone 420×800 software-rendered desktop journey exposed a missing Main dispatcher: after selecting an
account, the default model scope could not launch and remained busy. Injected test scopes had hidden this issue.
This also affects the previously installed account/mailbox models; their UI acceptance remains unproven.
`e19062da14` adds the standard `kotlinx-coroutines-swing` artifact at the existing coroutines version and a production
Main-dispatcher test without a test override. See the [official dispatcher documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-swing/).
The new test failed before the dependency was added (`/tmp/thunderbird-main-dispatcher-before.log`) and passes afterwards.

The standalone application was then launched against a private temporary SQLite database created by the production
schema adapter, with synthetic account/draft rows and no passwords. Actual application navigation opened the draft
editor. A typed subject change was independently read back from SQLite as `Runtime autosave verified`, state DRAFT.
The application log was empty after the fix. Inspected screenshots are retained under
`app-ubuntu-touch/build/evidence/composer/desktop-{editor,autosave}.png`; the earlier busy-state screenshot/log is retained
as failure evidence. No network server was contacted for this visual/input check.

Remaining gates: actual confined phone networking, composer/secure-input acceptance and the full configure/sync/read/
draft/send/offline-reopen journey. Autosave has a 400 ms uncommitted window and no lifecycle flush yet; unexpected
termination during that window can lose the latest input. Draft deletion/discard, Cc/Bcc, attachments, reply/forward,
identity/signature controls, typed error guidance, Sent-folder filing/reconciliation and active cancellation remain
later requirements. Large drafts/outboxes still require measured layout/performance work. Version 0.1.9 has not yet
been installed; FP4 remains on 0.1.8 until the next verified deployment.


### 0.1.9 deployment and confined runtime proof

Built clean from `131b9b801200410095c5267e0a8aa9c2568d826c`, including dispatcher fix `e19062da14`.
`SOURCE-CHANGES.patch` is empty. Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite verification pass.
Artifact: `app-ubuntu-touch/build/ut-candidate-10/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.9_arm64.click`.
Size: 147,062,004 bytes. SHA-256: `2749e44b000c6d8ab9965bd97c0a95658c24e49f62d00add00373878d41b15c8`.
The pinned packager and reused ARM64 runtime are unchanged from the previous candidates.

Rsync reached exit 0 before the phone checksum guard and installation. Phone Manager installed the matching package;
independent reads confirm current 0.1.9, embedded source `131b9b8012` and the generated profile. No application database
existed at the standard data path, so no user database backup/migration was needed. The guarded installer would create
and quick-check a private SQLite backup before upgrading an existing database. Installer warnings remain the same
unsigned-development/user-bus/old-profile-cache warnings documented above; no GUI acceptance is inferred from installation.

The test-only `ConfinedComposerProbe` ran with the installed bundled JVM and installed production JARs, plus a JAR
containing only its own test classes. It verified `/proc/self/attr/current` exactly matches the 0.1.9 enforcing profile,
then exercised the production Swing Main dispatcher in headless mode. A newly created private cache fixture verifies
schema-3 to schema-4 migration preserving synthetic account and local secret-index data, UTF-8 draft/queue storage,
single claim, reopen after SENDING to UNCERTAIN, refusal to retry without acknowledgement, and explicit requeue.
It makes no network requests, touches no real SignOn identities and emits no engine log events. Fixture deletion is
checked before success. Result/exit 0: `Enforcing profile, Swing Main dispatcher, SQLite draft/queue and uncertain
recovery: PASS`. This does not verify a GUI window, Maliit or protected-service lifecycle on 0.1.9.

All 63 app tests and detekt/spotlessCheck pass again after adding the probe; log
`/tmp/thunderbird-composer-probe-build.log`. Package/install/probe logs and screenshots are retained under
`app-ubuntu-touch/build/evidence/composer/`. Phone-fleet commit `62bc1a1` pins the matching version/path/hash with
unrelated changes preserved and no full-fleet convergence. Source is pushed on `feat/ubuntu-touch-demo`.

Next gate: controlled real IMAP/SMTP through this installed enforcing profile, then the actual phone configure/sync/
read/draft/send/offline-reopen journey. The physical unlock/input question remains unanswered; independent engine,
recovery and later roadmap work can continue. No claim is made that milestone 2 or the full mail application is complete.


## Work package 2.5: confined controlled receiving/sending and fresh-process recovery

The installed 0.1.9 production engine now passes actual controlled TLS IMAP/SMTP networking under
`mail-demo.thomasredstone_mail-demo_0.1.9 (enforce)`. `ConfinedProtocolProbe` verifies the profile itself and uses the
installed JVM/JARs plus only its test classes. The host-only `PhoneProtocolServer` is a GreenMail fixture bound to the
host's tailnet interface, with generated IP-SAN TLS material, dynamic ports and no mail forwarding.

The phone creates a new private cache database and uniquely referenced real SignOn credential. It rejects the unknown
fixture certificate through default production trust, then uses explicit fixture-only trust to check IMAP/SMTP
settings, discover folders, sync/read the intended inbox body, queue frozen outgoing MIME and receive SMTP acceptance.
A second send call on the same row makes no second attempt. The independent server observes exactly one expected
outgoing message with the intended recipient and Unicode/leading-dot body. No production trust configuration changes,
AppArmor exemptions, real provider accounts or raw mail/credential logging are involved.

Two controlled runs pass. The second retains the marked fixture across process exit; the host then verifies delivery,
stops both mail servers and exits 0. A separate new phone JVM reopens the same account/index, reads its protected
credentials, cached body and SENT row with the servers stopped, then removes the disposable account/credential and
fixture. Independent SSH confirms that fixture directory is absent. No real app database/account is touched.
All phone and server processes exit 0. The host's temporary private-key material is removed during teardown.

The [device protocol guide](ubuntu-touch-device-protocol.md) records the self-contained workflow, argument contracts,
restart/cleanup obligations and evidence paths. After these test-only changes, all 63 app tests and detekt/spotlessCheck
pass again (`/tmp/thunderbird-device-protocol-build.log`). Installed source/package/fleet references remain unchanged:
0.1.9/source `131b9b8012`, package hash `2749e44b000c6d8ab9965bd97c0a95658c24e49f62d00add00373878d41b15c8`,
fleet `62bc1a1`. No new production package is needed for test executables.

This proves the headless controlled receive/send/restart chain on FP4, not the complete milestone-2 phone screen
journey. Physical setup/input, an explicitly configured real test account, successful STARTTLS interoperability,
active cancellation/interruption recovery and startup/memory measurement remain open. Later roadmap features remain
required; the tested engine path does not establish daily-use or release completion.


## Milestone 2 cancellation: session-owned sockets

Brief: make closing a foreground session interrupt its own active sockets, including direct TLS handshakes and the
plain TCP phase before required STARTTLS. Closing one session must not affect another. Keep existing mail-client
behaviour unless a socket factory explicitly opts into ownership. This layer does not implement the user Cancel flow.

Implemented: optional `PlainSocketFactory` plus the `TrustedSocketFactory.createPlainSocket()` extension in mail:common.
IMAP and SMTP use that hook for the initial non-TLS socket; factories without the optional interface still create the
same standard Java Socket. The original TrustedSocketFactory interface has no changed/removed members.
The Ubuntu Touch factory now creates a fresh `CancellableSocketFactory` per session. It tracks plain and TLS sockets
before engine connect/read operations, rejects new sockets after close and closes tracked resources without holding
its owner lock during socket IO. Underlying plain sockets are closed before their TLS wrappers. Session close then
clears the IMAP pool; it no longer depends on pooled connections to find an active connection.

Fail-before-fix evidence: four real loopback cases (IMAP/SMTP × direct TLS/required STARTTLS) accepted TCP and then
withheld handshake/greeting responses. All four timed out after session close with the old implementation.
`app-ubuntu-touch/build/evidence/cancellation/before.xml` and `/tmp/thunderbird-cancellation-before.log` retain this.
After the fix, all cases complete promptly with failure. The strengthened direct-TLS test observes ClientHello before
closing, and SMTP pre-DATA failures are asserted not to be possible-acceptance failures. A separate real TCP test
verifies owner isolation, EOF on closed sockets and rejection of socket creation after close.

Verification: 68 app tests, 331 mail:common tests, 433 IMAP tests and 99 SMTP tests pass; zero failures/errors/skips.
App detekt, spotlessCheck and installDist pass. Each changed shared mail module's lint, detekt and spotlessCheck pass.
Command: JDK 21/SDK configured, Xvfb Gradle invocation with those scoped tasks and the established configuration flags;
full command/result is retained in `/tmp/thunderbird-cancellation-shared-checks.log` (BUILD SUCCESSFUL).
Android app assembly and instrumentation were not run. The shared JVM mail suites and standalone lint cover the changed
engine paths; no Android-specific implementation or UI source changed.

Development version is 0.1.10. No candidate has been packaged/deployed for this change: phone/fleet remain at verified
0.1.9/source `131b9b8012`. The earlier confined protocol/restart evidence applies to 0.1.9 and is not evidence of this
new socket interruption behaviour on ARM64.

Next required work: per-operation cancellation ownership and UI state for refresh/body download/sending, cancellation
before a session becomes available, and interrupted-send handling that preserves SMTP uncertainty. DNS resolution and
protected-service calls have no session socket to close; do not claim prompt cancellation of those waits. Successful
STARTTLS interoperability, cancellation during authenticated sync/body fetch/SMTP DATA, device cancellation and the
complete physical journey remain open gates. Existing controlled tests for uncertain SMTP outcomes remain in the suite.

## Milestone 2 cancellation: operation ownership, UI and retry history

Brief: connect session-owned socket interruption to refresh, body download and foreground sending. A cancellation
request must prevent later connection attempts even if credential preparation has not finished. Keep the UI busy
until the worker settles, preserve completed changes, and never relabel a possibly accepted message as safely failed.

Implemented: `MailOperation` registers a session's socket owner before protected credentials are read. Cancellation
marks the request immediately and tears down sockets on IO, including model disposal; late preparation checks refuse
new transport work. Mailbox and composer models expose canCancel/cancelling state and Bolt Cancel controls. Navigation
and repeat clicks remain disabled until the worker finishes. Local draft/database writes are not exposed as cancellable
network operations. Completed sync changes are not rolled back. Resolver/protected-service waits have no active
socket to interrupt; the UI honestly remains cancelling until they settle, then prevents any subsequent connection.

SMTP still uses its phase-aware failure classification. Cancellation before submission does not imply acceptance;
after the DATA terminator without the final response it remains UNCERTAIN. An already accepted result remains SENT
if cancellation arrives while that result is being committed/displayed. No implicit retry loop is added.

### Preserve uncertainty across later attempts

Two new regressions failed before the fix: an uncertain message retried into a known rejection became FAILED, losing
the earlier risk; cancelling its queued retry before claim left a plain QUEUED row. Evidence:
`/tmp/thunderbird-uncertain-history-before.log` and `build/evidence/cancel-ui/uncertain-history-before.xml`.
Schema 5 adds `outgoing.uncertain_attempt`. Failed or cancelled retries cannot erase earlier uncertainty. Claims with
that history require explicit duplicate-risk acknowledgement for each attempt, including after reopen/requeue.
A newly accepted attempt keeps its history for display. Old schema-4 pending rows conservatively require review because
the older preview did not preserve complete attempt history; MIME and Message-ID are retained. Drafts are not flagged.
Older previews reject schema 5 rather than resetting data. Rollback remains a separate unverified recovery procedure.

### Verification

79 app tests pass, zero failures/errors/skips; detekt, spotlessCheck and installDist pass with the scoped JDK 21/Xvfb
command above. Latest log: `/tmp/thunderbird-cancel-probe-build.log`. This includes real socket/model cancellation,
blocked credential preparation (busy remains true and no later TCP connection occurs), accepted-result preservation,
Cancel button/repeat-click/navigation states, earlier-attempt retry guards and schema-4 migration.

`SmtpDataCancellationTest` uses a real TLS SMTP peer with an ephemeral certificate explicitly trusted only in that
test. It reads the full DATA terminator but withholds the final acknowledgement. The real composer/sender/socket path
cancels the wait within three seconds and persists UNCERTAIN/history; another bare Send does not resubmit it.
The fixture neither forwards nor logs mail. Prior shared-mail tests/lint/detekt (863 tests at `3234a3b049`) remain
applicable: this operation/UI work changes no shared mail source. Android assembly/instrumentation were not run.

The source bundle now includes the modified shared socket/protocol source files as well as the app sources; shell
syntax verification passes. The complete source/materials licensing and reproducible release gate remains open.

`ConfinedCancellationProbe` is an opt-in test executable for the next installed candidate: it checks the enforcing
profile/schema 5, creates a disposable protected account, drives the default Swing-dispatched mailbox model into a
controlled TCP greeting wait, requests Cancel and checks settled state within five seconds. A test-only socket observer
signals TCP establishment without replacing production cancellation code. Protected credential/index/cache cleanup is
checked. It has not yet been run on the phone at this source checkpoint; do not run it against installed 0.1.9.

Remaining gates: build/install 0.1.10, native cancellation and schema-5 checks, authenticated IMAP sync/body interruption,
blocked payload-write cancellation, successful STARTTLS interoperability and the complete physical/provider journey.
These remain explicit requirements; 0.1.9's earlier confined protocol proof does not verify this new code.

### 0.1.10 package, installation and confined verification

Built clean from `080d86f30cbb32e9010747fd2933686d58d4bb07`; `SOURCE-CHANGES.patch` is empty.
Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite verification pass. The port source archive also contains
`PlainSocketFactory.kt`, `RealImapConnection.kt` and `SmtpTransport.kt` as intended.
Artifact: `app-ubuntu-touch/build/ut-candidate-11/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.10_arm64.click`.
Size: 147,111,350 bytes. SHA-256: `618eb03410da299851b61a8aa11bcc7b91231a0e2bcd1572e5c4e73674e11d19`.
Pinned packager/runtime inputs are unchanged from previous candidates.

Rsync completed before phone checksum verification and installation. Phone Manager installed the matching artifact;
independent reads confirm current 0.1.10, embedded source `080d86f30c` and the generated profile. No database existed at
the checked standard/doubled application data paths, so the guarded backup step had no user data to back up.
The installer retains the previously documented unsigned-development, user-bus hook and old-profile-cache warnings.
No GUI acceptance is inferred from installation. Phone-fleet `9e08d03` pins only this app's version/path/hash; unrelated
changes were preserved and no full-fleet convergence ran.

`ConfinedCancellationProbe` ran using the installed bundled JVM/production JARs, test-only probe classes, and the exact
0.1.10 enforcing profile. It verified schema 5 and a real disposable SignOn account, drove the default Swing-dispatched
mailbox model into a controlled TCP wait before STARTTLS, requested Cancel and observed settled state within five
seconds. The independent host peer saw EOF with zero protocol/credential bytes. Both processes exit 0; cleanup is
checked. The test-only plain-socket observer signals establishment while preserving production model/token/socket-owner
logic. No alternate production implementation, trust bypass or confinement exemption is used.

The updated confined composer probe also passes dispatcher, schema-3 to schema-5 migration, draft/queue single claim,
uncertain history and explicit requeue checks. The controlled TLS IMAP/SMTP/protected-account flow was replayed on
0.1.10: the phone passes and the host independently observes exactly one intended outgoing message. After host server
teardown/exit 0, a fresh phone JVM passes protected credential, cached-body and SENT-row reopen, then cleans its
account/credential/index/fixture. Independent SSH confirms the retained protocol fixture is absent. All involved phone
and server handles reached exit 0; no fixture listeners remain running.

Evidence: `app-ubuntu-touch/build/evidence/cancel-ui/native/`, including installed-source, cancellation, independent
peer, schema recovery and `protocol-run-010` phone/server/cleanup results. This is a headless model/protocol proof,
not a physical Compose/Maliit journey or a suspend/background-delivery proof. Native cancellation during authenticated
IMAP body fetch or SMTP DATA/blocked writes is not claimed; the DATA response-wait cancellation proof above is a host
JVM test. Successful STARTTLS/provider interoperability and the physical journey remain open.


## Draft recovery: graceful close and focus loss

A focused follow-up fixes the 400 ms autosave gap for normal window close. `MailWindowLifecycle` registers the live
composer's save callback and waits before exiting; failed saves keep the same editor and input alive for correction
and retry. Duplicate close requests share the pending close. Window focus loss requests an immediate local draft save.
`ComposerViewModel.flushForClose()` cancels/joins the debounce and serialises the latest snapshot through the existing
write mutex. It checks persisted outgoing state before saving, so a concurrent queue operation cannot be rewritten as
a draft. It does not initiate SMTP or silently retry an outgoing message.

Bounded acceptance: edits typed immediately before normal close survive process exit; invalid draft headers prevent
close without losing editable input; background save does not wait for the debounce; disposed editors do not
participate. Exclusions: SIGKILL/power-loss durability of the final unsaved keystrokes, Ubuntu Touch suspend acceptance,
server-side Drafts/Sent filing, attachments and unrelated composition features remain open.

Verification on 2026-10-27:

- The opt-in `desktopDraftCloseProbe` launches the actual app under isolated Xvfb/X11 and a synthetic-only private
  SQLite fixture. Java Robot opens Write/account/draft, replaces the subject and immediately dispatches WINDOW_CLOSING.
  No credentials or network access are used. The task removes inherited Wayland/session-bus variables to avoid routing
  Robot input into the host desktop portal. It requires an explicit synthetic marker under `/tmp/mail-composer-visual-*`.
- With the immediate-exit close handler restored temporarily, the process exits 0 but independent SQLite inspection
  finds the old subject `Controlled composer fixture`. With the fix restored, the same probe exits 0 and independent
  SQLite assertion finds exactly `draft survives close`. Source was restored in a `finally` block before final checks.
- 83 app tests pass with zero failures/errors/skips, including immediate flush, invalid-header failure/retry,
  background flush, pending-save close ordering, duplicate close and participant disposal. Detekt and installDist pass.
  Exact combined command: `./gradlew :app-ubuntu-touch:desktopDraftCloseProbe :app-ubuntu-touch:spotlessApply
  :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist --no-configuration-cache
  -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`, with JDK21/Android SDK and synthetic/Xvfb environment.
  Final `spotlessCheck` is run separately. This JVM application has no Android lint/connected-test task; no shared
  Android module changed, so Android assembly/device tests were not rerun for this local lifecycle change.
- Before/after logs: `app-ubuntu-touch/build/evidence/draft-lifecycle/{before,after}.log`.
  The earlier Robot run inherited host Wayland and stalled before clicks; it is excluded from bug evidence.

This change is source/desktop verified; the installed phone remains 0.1.10 without this fix until a new candidate is
packaged and deployed. Native focus-loss/suspend/rotation acceptance remains open. A 400 ms uncommitted window still
exists for abrupt termination, and focus-loss saving cannot guarantee completion before OS suspension.


### 0.1.11 package and confined lifecycle verification

The draft-recovery fix (`db5a48eb71`) and release/probe preparation are committed and pushed as clean production
source `57294380c158ad0791ffa1e6853bb5e3235c6e89`. Candidate 12 contains empty SOURCE-CHANGES.patch and that exact
SOURCE-COMMIT.txt. Pinned builder and verified ARM64 runtime reuse are unchanged from 0.1.10. Click review and
launcher/JVM/AWT/Skiko/SQLite architecture checks pass. Package:
`app-ubuntu-touch/build/ut-candidate-12/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.11_arm64.click`,
147127116 bytes, SHA-256 `608d985a82b822d1e4610c196706f72d128abca06c3dddb7a33ff342a118face`.

Rsync completed with exit 0 before phone-side exact checksum verification and Phone Manager installation (exit 0).
No existing database was present at either checked standard/doubled application path. The guarded install retained
private backup handling for any existing database. Known unsigned-development/user-bus-hook/old-profile-cache warnings
occurred; independent current symlink, source/empty patch and new enforcing profile checks passed. The device reports
FP4, Ubuntu Touch 24.04-2.x/arm64/android9plus/daily, build 708.

The expanded test-only ConfinedComposerProbe ran with installed production JARs and bundled JVM, no production
overlays, under `mail-demo.thomasredstone_mail-demo_0.1.11 (enforce)`. It passes Swing Main dispatch, schema 3→5,
draft/queue/single-claim/uncertain recovery, immediate native model close flush, invalid-save preservation and immediate
background flush. Its new lifecycle fixture uses synthetic local settings and no credential operations/network/send;
the probe removes its own private fixture. This verifies native model/storage behaviour, not a physical window or OS
suspend. The actual window regression remains desktop evidence.

ConfinedCancellationProbe also passes on installed 0.1.11 with a disposable protected SignOn account and the default
Swing mailbox model. Both phone and host exit 0; the independent host observes EOF with zero protocol/credential bytes.
No protocol receive/send replay is claimed for this version; the previous 0.1.10 proof remains recorded above.

83 app tests, detekt and installDist pass (`/tmp/thunderbird-011-native-checks.log`); the version-only first check also
passed spotlessCheck. Final documentation/probe formatting check follows. Native/package/install/cancellation logs and
test-only JAR are retained under `app-ubuntu-touch/build/evidence/draft-lifecycle/`. Phone-fleet scoped commit `183ea07`
pins only this app's version/path/hash; unrelated dirty fleet work was preserved and no whole-fleet converge was run.
Physical secure-input/foreground provider journey, suspend/rotation, successful STARTTLS and broader authenticated
interruption checks remain open. The full daily-use/background/parity/release roadmap remains required.


## Required STARTTLS: positive host and confined phone interoperability

Bounded work item: verify successful IMAP/SMTP STARTTLS through the production ForegroundMailSessionFactory,
including authentication only after upgrade, received body caching and SMTP delivery; preserve certificate validation,
required-upgrade rejection and restart recovery. Exclusions: public provider acceptance, physical UI, authenticated
interruption/blocked-write coverage, background delivery and unrelated mail features remain open.

GreenMail's lack of STARTTLS support previously left only downgrade-rejection coverage. The test-only
`StartTlsGateway` now supplies an unauthenticated greeting, requires the exact upgrade exchange, completes a real JSSE
server handshake and then forwards to a loopback-only GreenMail backend. It never forwards client credentials/mail
before TLS. Host fixture trust is scoped to injected test sessions; no production TLS settings or trust store changed.
The gateway collects worker failures and joins/closes all owned listeners/sockets on teardown. The opt-in phone server
can tolerate expected client-rejected handshakes for its negative certificate check; automated positive tests cannot.

The automated integration test passes receiving a seeded UTF-8 body through IMAP STARTTLS into persistent storage,
folder refresh/sync, SMTP STARTTLS sending and independent recipient/body assertions. It checks both protocols complete
multiple upgrades. Existing required-STARTTLS downgrade rejection and socket cancellation tests remain in the full suite.
No production code changed: this closes an evidence gap rather than claiming a newly implemented protocol.

Native verification on installed 0.1.11/clean production `57294380c158ad0791ffa1e6853bb5e3235c6e89`:

- Updated test-only ConfinedProtocolProbe selects STARTTLS_REQUIRED explicitly for both servers. Its JAR contains only
  three probe classes and uses the installed production JARs and ARM64 bundled JVM under the enforcing app profile.
- A disposable protected SignOn account rejects the unknown IMAP fixture certificate under default trust, then succeeds
  with injected fixture trust. Settings, folder sync, cached body, queued SMTP acceptance and repeat-send suppression pass.
- Host independently observes exactly one outgoing message, intended recipient, Unicode/dot-stuffed body and Message-ID.
  Both upgraded protocol counters pass. Host process exits 0 before the restart probe begins.
- A fresh phone JVM with servers stopped reopens protected credentials, cached body and SENT outbox state, then removes
  its account, protected identity and private directory. Independent SSH confirms the fixture directory is absent.
- Phone preparation, host server, fresh JVM and cleanup commands all exit 0. Results are retained under
  `app-ubuntu-touch/build/evidence/starttls/native-run-2/`; the test-only JAR is in the parent directory.

The first host launch failed before any phone connection because plain backend listeners were missing from its setup;
that fixture omission was fixed before the successful run. No protocol or production changes were required. Physical
provider journeys, authenticated IMAP interruption and blocked payload writes are still required; this does not prove them.
84 app tests pass with zero failures/errors/skips; detekt and installDist pass. Exact command:
`./gradlew :app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt
:app-ubuntu-touch:installDist --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`,
with JDK21 and configured Android SDK. Final focused integration/spotlessCheck also pass. Logs:
`/tmp/thunderbird-starttls-final.log` and `/tmp/thunderbird-starttls-format.log`. No shared Android source changed; this JVM
application has no Android lint/connected-test task, so no Android assembly/device suite was rerun for test-only work.
No new phone package is required.


## Daily-use composition: To/Cc/Bcc

Bounded work item: expose To/Cc/Bcc in the Bolt editor, preserve all fields through autosave/close/reopen and freeze them
in queued messages. Deliver to the three recipient classes through the existing shared SMTP engine while stripping
Bcc from transmitted headers. Allow a Bcc-only draft to queue. Preserve migration, retry-history and stable-MIME rules.
Exclusions: reply/reply-all/forward, attachments, Sent filing, contacts/autocomplete, HTML composition and physical
phone keyboard/provider acceptance remain separate requirements.

Implementation adds schema 6 cc/bcc columns with empty defaults. Schema 5 queued MIME and uncertain-attempt history
are preserved byte-for-byte; schema 4 history migration still requires duplicate review. Draft save validates length
and CR/LF restrictions for all recipient headers, queue validates each list and requires at least one recipient across
the lists. Queued private MIME retains Bcc for the SMTP envelope; the existing shared SmtpTransport gathers all envelope
recipients and removes Bcc before DATA. The outbox details display Cc/Bcc locally. No recipient/content logging is added.

Verification before packaging: 88 app tests pass, detekt and installDist pass. Tests cover schema5→6 preserving frozen
MIME/risk, schema4→6 history, recipient restart persistence, Bcc-only queueing, injected/invalid Cc/Bcc rejection, Bolt
editor actions, Maliit Bcc edits and immediate close flush of Cc/Bcc. A real TLS SMTP test independently opens each
To/Cc/Bcc destination mailbox through Jakarta Mail and verifies delivery, visible To/Cc and absence of Bcc/blind address
in every delivered header. The actual desktop app window-close probe still preserves the exact new subject with the
two added editor fields. Logs: `/tmp/thunderbird-recipients-final.log`; final spotlessCheck follows.

The candidate version is 0.1.12. Packaging/deployment/native schema6 acceptance is pending at this checkpoint; the phone
still has 0.1.11/schema5 production. Existing schema6 test probes must not be run against the older installed package.


### 0.1.12 package and native recipient persistence

Production `3892a38fed732b401af0ece30dc11381c0d75c94` is committed/pushed and packaged as clean candidate 13, with
empty SOURCE-CHANGES.patch. Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite checks pass using the same pinned
builder/runtime. Artifact: `app-ubuntu-touch/build/ut-candidate-13/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.12_arm64.click`,
147127442 bytes, SHA-256 `f9695164c35d600aa07431b43251f9b3a098e1294bd8ce0399c81b3cc9727dba`.

Rsync exited 0 before phone-side exact checksum guard and Phone Manager installation (exit 0). No existing database
was present at the two checked application data paths; the guarded install retains private backup logic if one exists.
Known development-signature/user-bus-hook/old-profile-cache warnings occurred. Independent installed current symlink,
embedded source, empty patch and new profile checks pass.

Expanded ConfinedComposerProbe uses installed production JARs/bundled JVM plus test-only classes under
`mail-demo.thomasredstone_mail-demo_0.1.12 (enforce)`. Native schema3→6, SQLite draft/queue/uncertainty and Swing Main
checks pass. Its model flush saves Cc and Maliit Bcc commit values, verifies both persisted values, preserves input on
invalid save, and saves on background immediately. Its private fixture is removed. This is model event injection,
not physical Maliit keyboard acceptance. Native SMTP recipient/privacy delivery was not exercised in this checkpoint;
the real TLS host test provides that proof. Previous native STARTTLS evidence remains scoped to 0.1.11.

88 app tests pass with zero failures/errors/skips, detekt/installDist pass, and final spotlessCheck passes. Exact command
was the prior draft-close Xvfb fixture command with `:app-ubuntu-touch:desktopDraftCloseProbe :app-ubuntu-touch:spotlessApply
:app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist`, followed by `:app-ubuntu-touch:spotlessCheck`,
using JDK21/Android SDK and existing no-configuration-cache/isolated-projects=false/max-workers=4 options. No shared Android
code changed; this JVM app has no Android lint/device task, and Android assembly/device suites were not rerun.
Evidence (including 88-test XML) is under `app-ubuntu-touch/build/evidence/recipients/`.

Fleet commit `6482bb5` changes only this app's pin; unrelated dirty fleet work is preserved, with no full-fleet converge.
Reply/forward, Sent filing, message actions, attachments, HTML/privacy, account/search/provider and background/security/
release requirements remain on the full roadmap, alongside outstanding physical/provider and interruption checks.


## Daily-use message actions: offline read/unread and star

Bounded work item: reader controls queue explicit read/unread and starred/unstarred assignments without credentials or
network access. Show the local flags immediately, preserve them across process restart, and reconcile during explicit
folder sync. Scope assignments to account/folder/UID/UIDVALIDITY. Never replay an old action onto a reused message UID.
Keep failed actions durable, allow explicit local discard, and preserve acknowledged flags even if later sync fails.
Exclusions: archive/trash/delete/move/copy, bulk actions, automatic mark-read, background replay, cross-folder automatic
processing, physical UI/provider acceptance and broader interruption coverage remain open requirements.

Schema 7 adds pending_flags with account/folder cascading ownership and monotonically allocated action IDs. Repeated
assignments replace an action with a new ID, so an old acknowledgement cannot erase a newer choice. Local summaries
overlay assignments only while cached UID validity matches. Explicit folder refresh first syncs, then applies each
assignment with UIDVALIDITY checked on the same selected read-write IMAP folder used for STORE. Unknown or changed
mailbox identity is not accepted. On acknowledgement, cache update and pending-row deletion share a local transaction;
repeating an interrupted flag assignment is idempotent. A follow-up sync reads server flags. Pending changes in other
folders remain visibly queued until those folders are synced. Stale/conflicting changes remain for review/discard.

The reader uses Bolt buttons and MVI events for read/star toggles, disables them during work, displays pending counts
and offers a confirmed local discard path. Network errors reload readable cache/pending state without exposing raw
server errors. Source uses the existing ImapStore/ImapFolder APIs; the backend's generic setFlag does not accept a UID
validity guard, so the session checks identity on its selected folder before calling the shared folder's setFlags.

96 app tests pass with zero failures/errors/skips; detekt and installDist pass. Real TLS IMAP tests independently read
server flags via Jakarta Mail, verify offline-only updates before sync, restart persistence, both toggle directions,
UID-validity rejection with unchanged server state, safe discard, account isolation/cascade and stale acknowledgement.
A separate durability test proves acknowledged cache flags survive reopen before any later sync. Migration6→7 retains
Cc/Bcc/frozen queue data without inventing uncertainty. Model and Bolt tests cover local event wiring, busy controls and
explicit discard confirmation. Exact command: `./gradlew :app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test
:app-ubuntu-touch:detekt :app-ubuntu-touch:installDist --no-configuration-cache
-Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`, JDK21/Android SDK. Log: `/tmp/thunderbird-flags-final.log`.
Final spotlessCheck follows. No shared Android source changed; Android assembly/device tests were not rerun, and the
JVM application has no Android lint/device task.

0.1.13 is the candidate version; packaging/native acceptance is pending at this source checkpoint. The phone still has
0.1.12/schema6. Do not run schema7 probes against the older installed production JARs.


Before deployment, the mailbox close path was tightened to await any just-started local action write, using the same
window lifecycle registry as composer drafts. A failed local write holds the first close open to expose the error;
a subsequent explicit close may leave the unchanged cache. Local write identity is rechecked after waiting, so a
newer tap cannot be lost between completion and exit. Network operations retain their existing cancellation path.
The model regression fails with immediate-close behaviour substituted (assertion failure retained in
`build/evidence/flags/immediate-close-before.xml`), then passes with the wait restored. This is a model close-contract
regression; no claim is made that it drove the phone window. Candidate14 was built but not deployed and is superseded
by the forthcoming clean candidate containing this close fix.


### 0.1.13 deployment and independent native flag verification

Feature commit `d01a2719c1` and close fix are committed/pushed as clean production
`dafce5b2070af025db3eea1929ab5c6f00d57613`. Candidate 15 (superseding undeployed candidate14) has an empty source patch,
passes Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite checks, and uses the same pinned builder/runtime.
Artifact: `app-ubuntu-touch/build/ut-candidate-15/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.13_arm64.click`,
147158890 bytes, SHA-256 `fcc45f1128ef2e5bf72f824a274fa876821f4c6d813568b5af1f1c5b8bcc813b`.

Rsync exited 0 before the exact phone checksum guard and Phone Manager installation (exit 0). No existing database was
present at standard/doubled application data paths; private backup handling remains in the guarded install. Known
unsigned-development/user-bus-hook/old-profile-cache warnings occurred. Independent current version/source/empty-patch/
new-profile checks pass for 0.1.13. Fleet scoped commit `f4e49d2` pins this artifact while preserving unrelated dirty
work, without running whole-fleet converge.

Confined tests use the installed bundled ARM64 JVM and production JARs, with only 33 test probe classes added:

- ConfinedComposerProbe passes Swing Main, schema3→7, draft/queue/uncertainty, Cc/Bcc and lifecycle checks.
- ConfinedProtocolProbe uses a disposable protected SignOn account and required STARTTLS. Default trust rejects the
  unknown IMAP fixture certificate; injected test trust then permits settings/folders/sync/body and guarded SMTP delivery.
  Local read/star actions show pending state/flags, explicit folder refresh applies them and pending count becomes zero.
- The host independently asserts exactly one intended SMTP message and its Unicode/dot-stuffed body. A separate
  Jakarta IMAP connection asserts the remote INBOX message is both SEEN and FLAGGED. Both protocol upgrade counters pass.
- The host exits 0 before a fresh phone JVM verifies cached read/star flags, protected passwords, cached body and SENT
  outbox state with servers stopped. Account/credential/private-directory cleanup passes; independent SSH confirms absence.
- ConfinedCancellationProbe replays the refactored mailbox model under schema7. Phone and host exit 0; Cancel settles and
  the independent stalled peer sees EOF without any protocol/credential bytes. No fixture listener is left running.

Native logs are in `app-ubuntu-touch/build/evidence/flags/`, including `native-run-1/`. All preparation/server/restart/
cleanup/composer/cancellation commands exit 0. These headless/model checks do not prove physical reader/keyboard/window
close/suspend or provider acceptance. UID-reset/conflict/discard cases are host integration tests, not native reset tests.

Final verification after close wiring: 96 app tests pass, detekt/installDist/spotlessCheck pass, and actual Xvfb composer
window close still persists the exact typed subject. Logs: `/tmp/thunderbird-flags-verified.log` and
`/tmp/thunderbird-flags-close-format.log`; XML is retained in the evidence directory. The full command adds
`:app-ubuntu-touch:desktopDraftCloseProbe` with the isolated synthetic/Xvfb environment to the command above.
No shared Android changes were introduced; prior shared-mail verification remains unchanged.


## Daily-use response drafts: reply, reply-all and text forwarding

Bounded work item: create a saved, account-scoped draft from a complete cached plain-text message; open it directly in
the existing composer without sending or fetching credentials; preserve threading through edits/reopen/queue/send.
Respect Reply-To and List-Post, exclude configured own primary addresses and deduplicate recipients. Never copy source
Bcc. Replies quote decoded text; text forwards include visible source context with no reply threading or recipients.
Exclusions remain explicit: attachment/HTML forwarding, HTML-only response conversion, rich quote/signature preferences,
additional identities, provider and physical UI acceptance remain unfinished requirements. The action is labelled
“Forward text” and the composer warns that attachments are not included; full forwarding is not claimed complete.

The Android ReplyToParser depends on LegacyAccount/android.net.Uri, so the app adds a JVM adapter over the existing
Message/Address/MIME APIs rather than importing Android runtime code. Reply targets prefer Reply-To, then List-Post
mailto path (query recipients ignored), then From. Reply-all follows the baseline's addition of From/To/Cc with self
filtering and case-insensitive deduplication. Source subjects cannot inject CR/LF. Only syntactically bounded Message-ID
tokens enter threading; References retain the root and recent ancestors, capped at ten IDs of at most 512 characters.
Schema 8 adds empty-default in_reply_to/thread_references columns while preserving schema7 pending flags and frozen
outgoing records. A response seed is inserted atomically; normal draft edits preserve its threading fields.

Reader MVI creates a durable local compose request before navigation. Composer Resume opens that saved account/draft
and does not replace dirty input. Existing lifecycle close waiting also covers response creation. Partial or HTML-only
sources cannot create a response in this preview; rejection happens before any draft insertion.

While implementing replies, a real MIME selection bug was found: first-text/plain selection could display/quote a text
attachment instead of the message body, and lose later inline body parts. PlainTextBody now uses the shared engine's
viewable/attachment classifier and alternative handling, preserving inline text order. Two regressions fail with the
old first-match selector and pass restored, with retained before XML at
`app-ubuntu-touch/build/evidence/responses/mime-before.xml`. No actual private message was used; fixtures are synthetic.

110 app tests pass before native-probe expansion, including actual reader→saved composer UI navigation without send,
all response mode events/busy/partial controls, recipient/list/self/Bcc rules, threading bounds, composer Resume/edit
persistence, schema7→8 preserving pending flags, and a real TLS SMTP reply after DB reopen. A separate Jakarta IMAP
oracle verifies delivered In-Reply-To/References and quoted UTF-8 body. Final checks/native packaging follow below.


### 0.1.14 package, native reply delivery and recovery

Production `9c1cf6b8242e1fe94f542a7e03b79a2bc88bda0c` is committed/pushed and installed on FP4. Clean candidate 16 has
empty SOURCE-CHANGES.patch and passes Click review plus ARM64 launcher/JVM/AWT/Skiko/SQLite checks, with the same pinned
builder/runtime. Artifact: `app-ubuntu-touch/build/ut-candidate-16/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.14_arm64.click`,
147193416 bytes, SHA-256 `0880f83966b8ec991b22a1e15089bc30aade07b440951954421639e73fb084dc`.

Rsync completed with exit 0 before the exact phone checksum guard and Phone Manager installation (exit 0). The link
was slow; TCP byte counters confirmed forward progress on the same process, which was not restarted. No database was
present at standard/doubled application data paths; private backup handling remains available. Known unsigned-development/
user-bus-hook/old-profile-cache warnings occurred. Independent installed current version/source/empty-patch/new-profile
checks pass. Fleet scoped commit `5573cd0` pins this artifact, preserving unrelated dirty work without whole-fleet converge.

Native probes use the installed bundled JVM and production JARs, plus 36 test-only classes under enforcing profile
`mail-demo.thomasredstone_mail-demo_0.1.14`:

- ConfinedComposerProbe passes schema3→8, Swing Main, draft/queue/history, Cc/Bcc and lifecycle checks. It resumes a saved
  response, applies a Maliit body-edit event, flushes and verifies retained In-Reply-To/References and Unicode text.
- ConfinedProtocolProbe rejects the unknown IMAP fixture certificate under default trust, then uses injected fixture
  trust for required STARTTLS receiving/sending and protected SignOn account persistence. It replays queued read/star
  reconciliation, creates a real reply from cached source MIME, verifies the quote/threading, edits the body and sends.
  A second send call does not submit another message.
- Host independently verifies exactly one intended SMTP message, Unicode/dot-stuffed body, a new Message-ID and the
  expected In-Reply-To/References. A separate Jakarta IMAP oracle verifies source SEEN/FLAGGED state. Both upgrade counters pass.
- Host exits 0 before a fresh phone JVM checks cached flags, protected credentials, body, SENT state and threading with
  servers stopped. Account/identity/private-directory cleanup passes; independent SSH confirms absence.
- Model-driven cancellation passes again under schema8; the independent stalled peer sees EOF with zero protocol/
  credential bytes. Phone and host processes exit 0, with no fixture listener left running.

All native prepare/server/restart/cleanup/composer/cancellation commands exit 0. Evidence is in
`app-ubuntu-touch/build/evidence/responses/`, including the full 110-test XML, regression-before XML and `native-run-1/`.
These checks do not verify physical keyboard/window/suspend or public provider flows. Native delivery exercises Reply;
Reply-all and Forward text recipient/body behaviour are host tests. Attachment/HTML forwarding remains unfinished.

110 app tests, detekt, installDist and spotlessCheck pass (`/tmp/thunderbird-response-final-checks.log`). A final focused
8-test recipient/threading run plus quality checks passes after tightening List-Post preference and duplicate-parent
ordering (`/tmp/thunderbird-response-thread-final.log`). Actual Xvfb composer close still preserves the exact typed subject.
Exact full command: `./gradlew :app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt
:app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck --no-configuration-cache
-Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`, JDK21/Android SDK; the window probe uses the existing
isolated synthetic Xvfb command. No shared Android source or dependencies changed; the JVM app has no Android lint/device
task, and Android assembly/device suites were not rerun. Final documentation formatting is checked separately.


## Daily-use Sent copies: independent SMTP and IMAP recovery

Bounded work item: automatically save accepted messages in a configured (or uniquely identified special-use) Sent
folder; preserve owner-side Bcc/threading; show copy status independently of SMTP delivery. Search for provider-filed
copies before APPEND. A lost copy result must never cause SMTP retransmission or an unreviewed repeated APPEND. Persist
claim/outcome, recover interrupted claims, allow search-only reconciliation and explicit duplicate-risk acknowledgement.
Exclusions remain open: background filing, automatic recovery loops, retargeting an already uncertain copy after folder
rename/removal, public-provider/physical-UI acceptance and true blocked-write/APPEND-response-loss transport fixtures.

Schema 9 adds account-scoped sent_settings and sent_copies, with outgoing/account cascading ownership. Explicit null
settings keep local copies only; absent settings use a single cached SENT-role folder, never a guessed English name.
The drafts/outbox list offers a folded Sent-folder setting. Each copy keeps its target snapshot even if future settings
change. Only durable SMTP-SENT records can be claimed. WORKING claims recover UNKNOWN after restart; repeated claims
are refused. Unknown attempts search only unless the user explicitly acknowledges copy-duplication risk. Acknowledged
APPEND without UIDPLUS is successful even with no returned UID. Earlier uncertainty remains visible after later success.

OutgoingMailSender persists SMTP acceptance first and updates the composer before filing. Copy failure cannot rewrite
that delivery state, trigger a send retry or roll back acceptance. Separate search/retry-copy controls cannot invoke
SMTP. Local settings and queue writes are awaited on graceful close; without that join, the model regression fails
with the old Sent setting still visible. Its before XML is retained in `build/evidence/sent-copies/local-close-before.xml`.

The real integration test exposed the engine's one-shot parsed body contract: APPEND counts then writes a message,
consuming/deleting BinaryTempFileBody during size calculation. A private FrozenSentMessage adapter now writes the
immutable queued MIME repeatedly for count and APPEND, preserving the owner's Bcc. It is private to IMAP Sent filing;
SMTP keeps its existing Bcc-stripped path. The failing real-APPEND trace is retained in append-body-before.xml.

120 app tests pass with detekt/installDist/spotlessCheck before the final window-probe run. Tests cover successful
filing/no UIDPLUS, duplicate claim/repeated send suppression, unknown search-only/explicit retry/history, restart,
account/local-only scoping, schema8→9 preserving frozen MIME/threading, SMTP-accepted progress, close waiting and Bolt
controls. Real TLS IMAP/SMTP tests independently inspect recipient and Sent mailboxes for one delivery/copy, private
Bcc/threading, read flag before body fetch, provider-existing-copy deduplication and reconciliation after injected
local result loss following a real successful APPEND. This last case is not a claim of actual TCP acknowledgement loss.

0.1.15 is the candidate version. Packaging/native validation follows; the installed phone remains 0.1.14 until the
verified deployment below. Matching schema9 probes must not be run against older installed production JARs.

### 0.1.15 deployed Sent-copy evidence

Production `d38c691ea9b78af4e67841d54593b63c9bb56947` is committed/pushed. Candidate 17 embeds that source with an
empty SOURCE-CHANGES.patch. Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite checks pass. Artifact:
`app-ubuntu-touch/build/ut-candidate-17/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.15_arm64.click`,
147242292 bytes, SHA256 `6729ba1f8af624ffca0a2684085bce67f65cfbba7f2f4f21e746f62d57ef703f`.
Transfer completed before the exact phone checksum guard and successful Phone Manager installation. Current symlink,
embedded source, empty patch and enforcing profile were independently checked. Fleet scoped commit `06a59d2` pins the
matching package; unrelated dirty work is preserved and no whole-fleet converge or fleet push was performed.

The installed production JVM/JARs plus a test-only 36-class probe JAR passed under
`mail-demo.thomasredstone_mail-demo_0.1.15`:

- Composer schema3→9, Swing Main, drafts/queue/history, response/Maliit edit and close flushing, including Sent settings.
- Required STARTTLS IMAP/SMTP, protected SignOn account, default unknown-certificate rejection and injected fixture trust.
- Queued read/star reconciliation, actual reply seeding/threading, automatic Sent filing and repeated-send/copy suppression.
- Independent host Jakarta recipient mailbox: exactly one correct delivery/body/threading. Owner Sent mailbox: exactly
  one matching copy/body/threading, with SEEN asserted before fetching its body. Independent source read/star assertions pass.
- Host server exits 0 before a fresh phone JVM verifies protected credentials, cache, reply metadata, SENT and SAVED
  copy state offline. Phone exits 0 and independent SSH confirms the private fixture directory was removed.

Logs: `/tmp/thunderbird-015-native-result.log`, `/tmp/thunderbird-015-sent-phone.log`,
`/tmp/thunderbird-015-sent-server.log`, `/tmp/thunderbird-015-restart.log`.
Controlled host output: `app-ubuntu-touch/build/evidence/sent-copies/native-run-1/`.
All prepare/server/restart/cleanup commands completed successfully. Physical UI/provider journeys remain unverified.
This run does not add an actual TCP APPEND-response-loss test or repeat the stalled-peer cancellation probe.

Final full 120 app tests, detekt, installDist, spotlessCheck and actual Xvfb composer-close exact-subject preservation
pass (`/tmp/thunderbird-sent-verified.log`); XML is retained under `build/evidence/sent-copies/`.
No shared Android code/dependencies/permissions changed. This JVM module has no Android lint/device task; Android
assembly and connected-device suites were not rerun. Documentation formatting is checked separately.

Repository-wide spotlessCheck also ran and failed on existing Markdown formatting in the dependency audit.
The four changed documentation files pass focused spotlessApply/spotlessCheck using `-PspotlessIdeHook`
(`/tmp/thunderbird-015-doc-focused.log`); unrelated baseline formatting was preserved.

### Transport-level lost APPEND acknowledgement

A host integration fixture now closes the actual TLS client connection after the real IMAP server returns tagged
successful APPEND, withholding that acknowledgement from the mail engine. The gateway counts the dropped response;
the independent owner mailbox proves the copy was committed. Production filing reports UNKNOWN while SMTP remains
SENT. After closing/reopening the SQLite database, a new filer/session searches by Message-ID and reports SAVED,
retaining uncertainty history. Independent recipient and owner mailboxes each contain exactly one message, and the
owner body survives unchanged. This is actual transport response loss, unlike the earlier injected local exception.

The gateway's line-oriented response interception is restricted to this bounded APPEND/search fixture, which has no
body literals in server replies. Other gateway tests retain byte-stream forwarding. A fault-disabled control fails
at the dropped-response assertion; its XML is retained as `build/evidence/sent-copies/wire-fault-disabled.xml`.
The positive focused run passes (`/tmp/thunderbird-wire-append.log`). This is host TLS protocol coverage, not confined
phone or public-provider loss coverage. Authenticated body-fetch interruption, blocked writes, physical lifecycle and
provider recovery remain open. Production source/package remains the deployed 0.1.15; these changes are test-only.

All 121 app tests pass with zero failures/errors/skips, alongside app detekt and spotlessCheck
(`/tmp/thunderbird-wire-full.log`). Changed documentation passes focused formatting checks.
Only tests and evidence changed; Android assembly/device suites were not rerun.

### Authenticated body-fetch interruption and recovery

The controlled STARTTLS gateway now has a bounded full-body truncation fault. After real authentication and folder
selection, it forwards the BODY[] literal announcement and its first 16 bytes, then closes the TLS connection. Its
counter proves the body-transfer fault occurred. A real sync reports failure; an already cached complete message
retains its original subject/body and full-download flag, while any partial new record is not marked fully downloaded.
After database close/reopen and reconnect, sync recovers both messages and the entire Unicode body. The test log sink
receives no events. This covers a host authenticated mid-body disconnect, not just handshake interruption.

TLS material generation is shared with the existing fixture helper instead of duplicating keytool setup. Fault-mode
response parsing is limited to controlled fixtures; normal gateway traffic retains byte-stream forwarding. These are
test-only changes, so installed 0.1.15 remains unchanged. Phone/provider interruption, active authenticated cancellation,
blocked-write recovery and physical lifecycle testing remain open.

All 122 app tests pass with zero failures/errors/skips, plus app detekt and spotlessCheck
(`/tmp/thunderbird-fetch-full.log`). Final XML is retained in `build/evidence/sent-copies/`.
No production/shared Android changes or new permissions; Android build/device suites were not rerun.

## Cached folder text search

Bounded work item: make cached mail searchable offline through the live mailbox UI, using the existing repository,
MVI and Bolt field/button contracts and Maliit input. Acceptance: literal case-insensitive AND terms match decoded
sender, subject and cached viewable plain text; input and clear controls work; opening/back preserves the applied
query; leaving the folder clears it; search does not connect, request credentials or log its query/content. Keep
account/folder boundaries, Unicode normalisation, attachment classification and cooperative cancellation explicit.

Implementation: `CachedMailSearch.kt` scans summaries in their existing order, normalises NFC/case with Locale.ROOT,
and checks missing terms against the reader's decoded plain text. Literal percent/underscore characters are not SQL
wildcards. HTML placeholder text and MIME attachments are excluded. Queries are bounded to 512 characters. Explicit
Search/Clear controls use the existing Maliit editor and keyboard inset. Query events/state redact toString output.
Search runs on IO with cancellation checkpoints before/after the scan and between messages. Initial summary-list
materialisation is not interruptible; large-mailbox indexing/pagination and cancellation latency remain required work.

Tests cover Unicode decomposed/composed input, AND matches across fields, literal punctuation, empty/missing terms,
oversized input, cooperative cancellation, model offline navigation/reset and actual Compose submission/clear controls.
Real SQLite MIME storage is reopened without configured accounts/credentials: search finds the decoded body while
excluding attachment text, another account's same UID and another folder. The native protocol probe also checks
positive/negative/clear search during preparation and fresh-process offline recovery; phone execution follows packaging.

This is a folder-scoped cached plain-text search slice. Account-wide/unified and server search, recipients/date/flag
filters, HTML indexing, attachment search policy and large-mailbox performance remain on the full roadmap. No search
scope is silently excluded. Candidate 0.1.16 is not installed until deployment evidence is recorded; FP4 remains 0.1.15.

Final 127 app tests, detekt, installDist and spotlessCheck pass (`/tmp/thunderbird-search-final.log`); XML is retained
under `build/evidence/search/`. No shared Android changes, new dependencies or permissions; Android assembly/device
suites were not rerun. This JVM module has no Android lint/device task. Documentation formatting is checked separately.

## Authenticated in-flight body cancellation

The host STARTTLS fixture can now pause after forwarding the full-body literal header and 16 body bytes. The test
waits for that actual authenticated transfer (not a guessed delay), confirms the engine worker is still running, then
closes its production MailOperation. The worker must finish with failure within three seconds, and the independent
gateway must observe closure of the client connection. The previously cached complete message retains its original
subject/body/full-download flag, and the engine emits no log events. Finally blocks close the operation/executor and
assert worker termination. Normal gateway traffic remains byte-stream forwarded.

This verifies production session/socket cancellation after authentication and body transfer. Existing model tests
cover Cancel/busy state, but this new host case does not establish the combined physical phone UI/provider journey.
Blocked client writes, authenticated native phone cancellation and lifecycle/provider acceptance remain open.
The candidate 0.1.16 package was built before these test-only changes; its production source remains `b24e4b3054`.

The cancellation-disabled control fails with TimeoutException at the bounded worker wait; its XML is retained as
`build/evidence/interruptions/authenticated-cancel-disabled.xml`. Restoring production cancellation passes.
All 128 app tests pass with zero failures/errors/skips, plus app detekt/spotlessCheck
(`/tmp/thunderbird-auth-cancel-full.log`); final XML is retained under `build/evidence/interruptions/`.
No production code or Android changes; Android assembly/device suites were not rerun.

## Installed 0.1.16 and offline search verification

Clean candidate 18 embeds production `b24e4b30545db01bc847e384f955ce37f2555b8c` with an empty SOURCE-CHANGES.patch.
The ARM64 Click passed review and launcher/JVM/AWT/Skiko/SQLite checks using the pinned packager and verified reused
runtime. Artifact: `app-ubuntu-touch/build/ut-candidate-18/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.16_arm64.click`,
147258216 bytes, SHA256 `5f521db7c055562a9524035be14d6c4e5a511ede2547b929a59c5dd011001377`.
Rsync completed after about eight minutes without restarting the advancing transfer. The phone checksum guard passed
before Phone Manager installation. No existing application database was present at the standard data paths, so no
backup was needed. Known development-signature/user-bus/old-profile-cache warnings occurred; independent checks
confirmed current 0.1.16, embedded source, empty patch and new enforcing profile. Fleet scoped commit `c0f73ef` pins
only this app's version/path/hash, preserving unrelated dirty work; no full-fleet converge or fleet push was run.

Installed production JVM/JARs plus the matching test-only 36-class probe JAR passed under
`mail-demo.thomasredstone_mail-demo_0.1.16`:

- Composer schema3→9, Swing Main, draft/queue/history, response editing and graceful-close/Sent settings checks.
- Protected SignOn credentials, unknown-certificate rejection under default trust, then controlled required STARTTLS
  IMAP/SMTP with fixture-only trust injection. Cached sender/subject/body search checks positive, negative and empty queries.
- Durable read/star reconciliation, real reply creation/threading, one SMTP delivery and automatic Sent filing;
  repeated send/copy calls do not create another submission/copy.
- Independent host Jakarta recipient and owner Sent mailboxes assert exactly one matching message each, correct
  body/threading, and owner SEEN before body fetch. Independent source SEEN/FLAGGED assertions pass.
- Host exits 0 after server shutdown before a fresh phone JVM reopens protected credentials, cache, SMTP SENT and copy
  SAVED state, threading and offline search. Account/private-fixture cleanup passes; independent SSH confirms absence.

All composer/prepare/server/restart/cleanup commands exit 0. Logs: `/tmp/thunderbird-016-native-composer.log`,
`/tmp/thunderbird-016-phone.log`, `/tmp/thunderbird-016-server.log`, `/tmp/thunderbird-016-restart.log`.
Host output: `app-ubuntu-touch/build/evidence/search/native-run-1/`. The package's 127-test implementation gate and later
128-test host authenticated-cancellation gate are recorded above. Test-only later commits do not change installed
production source. Physical Maliit/search UI, provider journeys, window/rotation/suspend, performance/large-mailbox
search and native authenticated interruption remain open; no physical acceptance is inferred from these model probes.

## Account-wide cached search

Bounded work item: expand the cached folder search to all cached folders in the selected account, retaining mailbox
identity through results, reading, flags/replies and Back. Acceptance: identical UIDs in separate folders remain distinct;
results identify their folder; opening a result targets its exact account/folder/UID; local actions target that folder;
Back restores the query/scope/origin folder; returning to folder-only scope restores that folder. Search stays offline,
account-isolated, cancellable between folders/messages and redacts query/result toString output.

`CachedMailHit` carries account, MailFolder and MailSummary. `searchAccount` enumerates only the requested account's
cached folders and uses the existing literal decoded-text search in each. The mailbox model validates a clicked result
against the current result set and account; it never interprets a bare UID as a cross-folder location. The reader uses
the result folder for existing download/flag/response paths. Returning from the reader restores the origin folder and
search list. Scope controls and lazy-list keys retain folder/UID identity; result labels show the folder name.

Tests add reopened SQLite fixtures with duplicate UIDs in two folders and an unrelated account, verify contextual
results and account exclusion, and exercise model result opening, rejection of forged foreign-account hits, correct
flag target and Back/scope reset. Compose tests render both duplicate-UID rows and verify the clicked folder result.
Unified-account/server search, filtering, HTML indexing, large-mailbox indexing/pagination and cancellation latency
remain required work. This source change is not yet the installed phone candidate; 0.1.16 remains deployed.

All 130 app tests pass with zero failures/errors/skips, alongside app detekt, installDist and spotlessCheck
(`/tmp/thunderbird-account-search-full.log`). XML is retained under `build/evidence/account-search/`.
No new dependencies/permissions or shared Android changes; Android assembly/device suites were not rerun.

## Unified-account cached search

Bounded work item: extend contextual cached results to all configured accounts, with explicit folder/account/all-account
scopes. Acceptance: same folder names and UIDs across accounts remain distinct, result rows identify the account,
reader/flag/reply actions use the selected account, and Back restores the original account/folder/query/scope. Only
current configured accounts may be enumerated; clicked hits must belong to the current result set and a current account.

`CachedSearchScope` replaces the account-only Boolean with FOLDER/ACCOUNT/ALL_ACCOUNTS. Account enumeration is deduplicated
by ID. The model keeps the origin account/folder separately while reading another account's result; Back reloads the
origin account's folder list. Result keys retain account/folder/UID and unified rows show account/folder names. The
reader also identifies its account. Results are grouped by account/folder using existing ordering; global chronology,
threading and sorting remain required work. This is unified cached search, not a completed unified inbox.

Tests cover duplicate IDs in real reopened SQLite and model/UI routing across two accounts. The shared runtime fixture
uses a CredentialStore that fails on every access and a logger that fails on every event. It opens the secondary
account's cached body, queues only that account's flag and creates only that account's reply draft, then restores the
origin on Back. A deliberately wrong-account route fails this fixture at the body assertion; restored production
routing passes. Before XML: `build/evidence/unified-search/wrong-account-control.xml`.

The same fixture is available as opt-in `ConfinedSearchProbe`, which requires the expected enforcing profile and uses
only its own newly created private directory. Its synthetic SQL account records have no protected secrets; it verifies
that this offline journey does not request them. It does not replace the separate SignOn/account-setup tests. Native
execution follows the next package; the currently installed 0.1.16 lacks these source increments.

All 133 app tests pass with zero failures/errors/skips, plus app detekt/installDist/spotlessCheck
(`/tmp/thunderbird-unified-final.log`); XML is retained in `build/evidence/unified-search/`. No new dependencies,
permissions or shared Android changes; Android assembly/device suites were not rerun. Server search, filters, HTML
indexing, indexed/paged large-mailbox search and measured cancellation latency remain open, as do physical/provider
acceptance and the rest of the full-app roadmap.

### Installed 0.1.16 launch diagnostic

An additional Phone Manager apptest reports launched=true, alive=true, focused=false, locked=false, with no passing
screenshot (`/tmp/thunderbird-016-apptest.log`). The app initially appeared stopped; a diagnostic SIGCONT was sent only
to its identified Java PID, without changing suspension policy. The GL-context warning is followed by a Skiko fallback;
it alone is not proof of a rendering failure. X11 inspection by `_NET_WM_PID` finds the Mail demo window at 1080×2259.
The X11 input-focus child is a descendant of that window, despite Lomiri's PID focus predicate reporting false.
The title-only X11 query missed the UTF-8 `_NET_WM_NAME`; the PID/property/ancestry query corrected that weak evidence.

Logs: `/tmp/thunderbird-016-window-diagnosis.log`, `/tmp/thunderbird-016-x11-focus.log` and
`/tmp/thunderbird-016-threads-later.log`. This is a concrete focus-verifier discrepancy to investigate in the phone
integration. Neither an app-dead claim nor physical launch/keyboard acceptance follows from the current evidence.
No lifecycle exemption, AppArmor expansion or global display change was made. Physical UI and normal suspend/resume
acceptance remain open; the existing 0.1.16 protocol/search confinement evidence is unchanged.

## 0.1.17 candidate and targeted X11 rendering evidence

Production candidate source `a938b0424efadb2c9753ef80e58ee4b11fd5992f` is committed/pushed. Full 133 app tests,
detekt/installDist/spotlessCheck pass for version 0.1.17 (`/tmp/thunderbird-017-checks.log`). Protocol probes now also
check account-wide search and deduplicated account enumeration during preparation and fresh-process offline recovery.
The matching test-only JAR contains 52 probe/fixture classes, including ConfinedSearchProbe; it has been uploaded.
No installed production classes are overlaid by that JAR. Native checks await installation.

Clean candidate 19 passed Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite checks using the pinned packager and
verified reused runtime. Embedded SOURCE-COMMIT matches, SOURCE-CHANGES.patch is empty. Artifact:
`app-ubuntu-touch/build/ut-candidate-19/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.17_arm64.click`,
147276968 bytes, SHA256 `508c662427ea49404581ace649ef21413d75adc41324eb84ae1f7a3a1c4e659d`.
Transfer is in progress; the phone remains on 0.1.16 until the checksum-guarded installation is recorded.

A read-only XGetImage capture of only the identified 0.1.16 Mail window (verified _NET_WM_PID) produced
`app-ubuntu-touch/build/evidence/search/window-016.png`, 1080×2259. Visual inspection confirms the demo UI fills that
window and renders its navigation/folder list. This follows the earlier diagnostic resume, so it does not establish
normal focus/suspension behaviour. It is an app-window capture, not a Mir foreground screenshot, and does not complete
physical input, secure keyboard or live-mail UI acceptance. No display policy/permission change was made.

## Installed 0.1.17: confined account/unified search

The advancing transfer completed successfully without restarting. Phone checksum matched the candidate hash above
before stopping only the old 0.1.16 app service. A private pre-upgrade SQLite backup passed quick_check (schema 9), then
Phone Manager installed 0.1.17. Expected development-signature/user-bus/old-profile-cache warnings occurred; independent
checks confirm current 0.1.17, embedded `a938b0424efadb2c9753ef80e58ee4b11fd5992f`, empty source patch and the new profile.
Fleet scoped commit `41ac3db` pins candidate 19/version/hash, preserving unrelated dirty work without whole-fleet
convergence or fleet push. Logs: `/tmp/thunderbird-transfer-017.log` and `/tmp/thunderbird-install-017.log`.

Installed production JARs/JVM plus the matching 52-class test-only JAR pass under the enforcing 0.1.17 profile:

- ConfinedSearchProbe reopens real SQLite with two accounts sharing INBOX/UID 1. Reading, pending flag ownership and
  saved reply ownership target only the selected secondary account; Back restores the primary account and search.
  The failing-on-access CredentialStore confirms the local journey requires no secrets. Fixture cleanup passes.
- ConfinedComposerProbe passes schema3→9, Swing Main, drafts/queue/history, response editing and close/Sent settings.
- ConfinedProtocolProbe passes default unknown-certificate rejection, controlled required STARTTLS receiving/sending,
  protected SignOn persistence, queued read/star reconciliation, reply threading, automatic Sent filing and repeated
  send/copy suppression. Cached search includes account scope and deduplicated account enumeration.
- Independent host recipient/owner Sent mailboxes assert exactly one matching delivery/copy, body/threading and Sent
  SEEN before body fetch. Source SEEN/FLAGGED and both TLS-upgrade counters pass. Host exits 0 after server shutdown.
- Only then a fresh phone JVM verifies protected credentials, cached body/flags, SENT/SAVED/threading and local search
  with no servers. Phone exits 0; independent SSH confirms the protocol fixture is absent and no search-runtime fixture remains.

All prepare/server/restart/cleanup/composer/search commands exit 0. Logs: `/tmp/thunderbird-017-native-search.log`,
`/tmp/thunderbird-017-native-composer.log`, `/tmp/thunderbird-017-phone.log`, `/tmp/thunderbird-017-server.log` and
`/tmp/thunderbird-017-restart.log`; controlled host output is `build/evidence/unified-search/native-run-1/`.
Physical UI/provider, normal focus/suspend, native authenticated interruption, server search, filters, indexed/paged
large-mailbox search and the remaining full-app milestones remain open. No normal-launch acceptance is inferred from
the earlier targeted 0.1.16 window capture or this model/protocol verification.

### Attachment integration trace for the next work item

The engine's MessageExtractor.findViewablesAndAttachments supplies non-viewable parts with MIME classification.
Legacy AttachmentInfoExtractor requires Android Context/Uri/LocalPart and is not directly portable. Shared
`feature:mail:message:api` contains attachment value types, but adding its adapter still needs a bounded dependency review.
Use MimeUtility.decodeBody for transfer decoding; raw Body.getInputStream must not be treated as decoded bytes.
BinaryTempFileBody explicitly has one-use/disposal semantics, and some decoder closes intentionally retain its temp
file. Repeated cache projections therefore need a resource-lifetime regression before attachment export is added;
this is a source-derived follow-up, not yet a proven fixed leak.

A read-only device query finds `lomiri-content-hub`/`liblomiri-content-hub1` and its Qt6 library at 2.2.3, plus
`/usr/bin/lomiri-content-hub-send` owned by that package. The old non-Lomiri package names are not evidence of absence.
CLI arguments, transfer acknowledgements, receiver lifetime and confinement policy remain unverified; invoking help
produced no useful interface documentation. Do not invent a broker protocol or assume that launching a CLI completes
Open/Save acceptance. No attachment sharing, new permission or system package change was performed.

## Cached MIME resource ownership

Bounded bug fix: repeated local mailbox projections must dispose newly parsed MIME temp bodies after returning detached
summary/content/response data, on both success and failure. The initial real-SQLite regression performs three rounds
of summaries, body reads, account search and reply-draft creation with a base64 text body and binary attachment. It
fails before the fix: zero initial body files becomes 42 retained files. Before XML is retained in
`build/evidence/mime-resources/before.xml` (`/tmp/thunderbird-mime-resources-before.log`). This confirms the earlier
source-derived lifetime hypothesis for those local read paths.

An app-local `withCachedMessage` scope owns only the fresh result of PersistentBackendFolder.readMessage. Its private
owner walks multipart and nested message bodies iteratively, tracking Part identity, and removes BinaryTempFileBody
files after consumers finish. Missing/already-consumed files are harmless. IO deletion failures are collected while
other owned files are still released; AutoCloseable.use preserves the original projection/cancellation exception and
suppresses cleanup failure. It never sweeps a directory or recursively removes an unexpected nonempty path.
The callback contract requires detached data, not a Part/Body/stream. No shared engine ownership semantics changed.

Mailbox summaries, content and ResponseDraftFactory.create now use that scope. Content derives its summary from the
same parse rather than parsing the message twice. Stored MIME, external/caller-owned parses and transport/session
resources are unchanged. These methods never return parsed parts beyond the scope.

Tests prove no new retained files after the repeated read/search/reply journey; nested-message cleanup after a thrown
projection or CancellationException; a separate caller-owned parse remaining readable (including decoded binary bytes);
and preserving the primary exception while a deliberately nonempty temp-path directory causes cleanup failure and
other files are still deleted. The runtime search fixture now contains base64 bodies/attachments and checks its temp
file set before/after the cross-account reader/flag/reply journey. Host execution passes; native execution awaits the
next package and must not use the new test fixture against old installed production code.

All 137 app tests pass with zero failures/errors/skips, plus app detekt/installDist/spotlessCheck
(`/tmp/thunderbird-mime-resources-full.log`); XML is retained under `build/evidence/mime-resources/`. No new dependencies,
permissions or shared Android changes; Android assembly/device suites were not rerun. The JVM app has no Android
lint/device task. Installed phone remains 0.1.17; this fix is not yet deployed.

Exclusions remain required follow-ups: parser failures before a Message is returned, pre-existing orphan temp files,
shared backend/SMTP transport ownership, cache retention/limits and long-session memory/disk soak. This local ownership
fix does not complete storage or attachment acceptance. Attachment export/open/save through Content Hub and the full
HTML/background/provider/security roadmap remain open.


## Cached attachment preparation (source only)

CachedAttachmentStore lists MIME attachments from a complete cached message, scoped by account/folder/UID.
References include a SHA-256 source fingerprint and part ordinal; changed or cross-account selections are rejected.
Preparation decodes into a fresh private directory (0700) and file (0600), with 96 MiB raw and 64 MiB decoded
limits. Limits apply to actual bytes, irrespective of declared sizes. The caller owns an AutoCloseable lease and
must retain it until any future receiver has finished. Cancellation and failures release provisional exports.
Filename normalisation removes traversal separators, control/bidi formatting and excessive UTF-8 length. Unknown
transfer encodings are rejected without logging their input. Attached messages are serialised as valid MIME;
byte-for-byte preservation of their original wire representation is not promised.

Twelve new tests cover exact base64/quoted-printable/default decoding, empty and attached-message files,
account/folder/UID and stale-reference rejection, raw/decoded limits, cancellation after partial writes,
filename attacks, symlink rejection and independent simultaneous leases. Two regressions failed before fixes:
message/rfc822 requires serialisation instead of getInputStream, and RFC2231 newline filenames require control
filtering. Evidence is retained in build/evidence/attachments/{message-before,filename-before}.xml.
The confined-search fixture now exercises attachment preparation too, but native execution awaits a matching package.

All 149 app tests pass (zero failures/errors/skips), plus detekt/installDist/spotlessCheck; log:
/tmp/thunderbird-attachments-final.log, XML: build/evidence/attachments/. Command, with JDK 21 and Android SDK set:

```sh
./gradlew :app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt \
  :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
```

No new dependencies, permissions or shared Android changes. Android assembly/device suites were not rerun;
this JVM module has no Android lint/device task. Installed 0.1.17 does not contain this foundation or the MIME
resource fix. The next package must use a new version and a matching native test fixture.

Attachment UI and Content Hub Open/Save are still outstanding, as are crash-orphan cleanup, partial-message
fetching, outgoing attachments and retention/soak tests. Preparation alone does not meet attachment acceptance.
Upstream Content Hub 2.2.3 [exporter source](https://gitlab.com/ubports/development/core/lomiri-content-hub/-/blob/6cb421c0788f71abc3f1237f19229b7015ee957c/tools/send/exporter.cpp)
shows its send CLI rejects file URLs; it cannot be assumed to export arbitrary private attachments.
A broker adapter still needs live API/confinement and terminal-transfer lifetime verification. No guard was bypassed.


## OAuth readiness audit

Ubuntu Touch OAuth remains unimplemented. The shared core/common OAuthConfiguration is platform-neutral, but
feature/account/oauth uses the Android Compose plugin, AppAuth and Android Intent (including FinishOAuthSignIn).
It cannot be added unchanged to this JVM application. mail/common OAuth2TokenProvider provides the existing
transport contract: timed token retrieval, invalidation and provider-derived usernames. The latter matters for
Microsoft primary-account SMTP authentication and must not be replaced with the typed address unconditionally.

Next bounded implementation: browser authorization-code flow with PKCE and one-use state; a verified confined
callback route; protected per-account token persistence; serialized refresh and invalidation; IMAP/SMTP wiring;
and provider-specific setup/error UI. Test callback mismatch/replay, cancellation/timeout, refresh rotation,
expiry/revocation, account isolation and log redaction before real-provider phone journeys. Do not embed browser
credentials or reuse Android registration assumptions. Provider registrations and production approval remain unresolved;
no upstream OAuth configuration was edited during this audit.

Google documents [loopback support by client type](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration):
desktop clients retain support while Android/iOS client types do not. This does not establish eligibility of this
port or its current registration. Microsoft requires a [registered matching redirect URI](https://learn.microsoft.com/entra/identity-platform/reply-url).
Thus a loopback receiver is only a candidate pending registration, confinement, browser and suspend/resume tests.
OAuth can proceed independently of HTML and attachment UI; neither provider is accepted until actual login,
refresh and authenticated receive/send succeed on the phone.


### OAuth platform probe on installed 0.1.17

ConfinedOAuthPlatformProbe is test-only and uses no new production classes, credentials or provider requests.
It verifies /proc/self/attr/current against the expected enforcing profile, then binds IPv4 127.0.0.1 on an
OS-selected port, connects, accepts and checks one synthetic byte. This passes using the installed bundled JVM.
It does not prove Morph redirect delivery, callback HTTP parsing, PKCE/state, foreground restoration or suspend survival.

Live introspection exposes com.lomiri.URLDispatcher at /com/lomiri/URLDispatcher with DispatchURL(s,s) and
TestURL(as)->as. The installed app profile permits DispatchURL only. The probe's read-only TestURL call therefore
fails with org.freedesktop.dbus.errors.AccessDenied, confirmed on the real session bus. This is a measured API
constraint, not evidence that browser dispatch itself is denied. Do not add permissions merely for preflight lookup;
use the permitted dispatch operation and handle its outcome. No browser was launched in this experiment.
Installed dispatcher packages report 0.1.4+0~20260705190008.3+ubports24.04.2~1.gbp309775.

Build/testClasses/detekt/spotlessCheck pass (/tmp/thunderbird-oauth-platform-build.log). Native diagnostic exits 1
at TestURL after printing its loopback PASS (/tmp/thunderbird-oauth-platform-phone.log); it is not an overall green
authentication test. The initial diagnostic omitted the existing required private java.io.tmpdir and failed loading
junixsocket; rerunning with the normal private cache path reached the actual dispatcher policy denial.

To reproduce, package only ConfinedOAuthPlatformProbe*.class and OAuthDispatcherProbe*.class from the test output
into a separate test-only JAR. Run the installed bundled JVM with that JAR plus installed lib/app/*, the installed
profile via aa-exec, user-session bus variables and java.io.tmpdir pointing into the app private cache, as described
in the device protocol guide. Supply the exact installed profile as the sole argument. The probe intentionally
fails when capability resolution is unavailable; do not weaken confinement or mistake this for a provider test.
Next: implement/test one-use PKCE/state and bounded callback handling, then test DispatchURL/browser return and
lifecycle under the same profile before connecting any real provider account.


### OAuth authorization-attempt implementation (source only)

OAuthAuthorizationAttempt now generates independent 32-byte SecureRandom state and verifier values, URL-safe
unpadded encoding and an S256 challenge. It builds an authorization-code request from a supplied configuration;
no provider registration or upstream configuration was changed. It currently accepts only an already allocated
IPv4 literal loopback redirect with an explicit port and path, and HTTPS authorization endpoints without embedded
credentials/query/fragment. This is a candidate route, not a declaration that either provider registration supports it.

Callback acceptance bounds URI length (8192), parameter count (32) and code size (4096), matches the exact redirect
scheme/authority/raw path, rejects fragments and duplicate decoded parameter names, and compares state using
MessageDigest.isEqual. Invalid callbacks do not consume the attempt. Matching success or denial consumes it;
cancellation and five-minute monotonic expiry clear its mutable verifier/state fields.
The authorization URI still contains the spent state and must not be logged. Synchronized acceptance
allows only one winner during replay. Provider error descriptions are not returned. Attempt and success-result
string representations redact their contents. Immutable JVM strings cannot promise physical secret-memory erasure.

The tests include the [RFC 7636 Appendix B](https://www.rfc-editor.org/rfc/rfc7636#appendix-B) S256 vector,
request-value round trips, code/verifier binding, independent attempts, concurrent replay, cancellation/expiry,
matching denial, wrong host/port/path/scheme/userinfo, fragments, duplicate/encoded parameter names, malformed and
oversized input and invalid configuration. Native browser delivery remains unverified. The component does not yet
bind to an account-setup owner, listen for HTTP, dispatch a browser, exchange/refresh/store tokens or authenticate
IMAP/SMTP. Those remain required OAuth acceptance steps; no Gmail or Outlook sign-in is claimed.


All 156 app tests pass with zero failures/errors/skips, plus detekt/installDist/spotlessCheck
(/tmp/thunderbird-oauth-attempt-full.log); XML retained in build/evidence/oauth-attempt/.
Commands use the existing JDK 21/Android SDK environment and app-scoped tasks with
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4.
No dependencies, permissions or shared Android code changed. Android assembly/device suites were not rerun;
this JVM module has no Android lint/device task. This authorization component is not in installed 0.1.17.


### Bounded OAuth loopback HTTP receiver (source only)

OAuthLoopbackReceiver allocates a listener on IPv4 127.0.0.1 and an ephemeral port. The caller must explicitly
supply configuration matching its allocated /oauth2/callback redirect. Construction failures close the listener.
It owns one OAuthAuthorizationAttempt, permits one waiter and closes on completion, explicit setup-owner close or
coroutine cancellation. It uses the existing coroutines IO dispatcher and creates no independent global worker.

The listener has an absolute overall deadline (default five minutes from construction), a separate absolute request
read deadline (two seconds), a 16 KiB header cap and a 32-connection cap. Per-byte read timeouts use the remaining
request budget so slow input cannot renew its deadline. HTTP requires GET, HTTP/1.1, an origin-form target, exact
127.0.0.1:port Host and unique valid ASCII header fields. Transfer-Encoding and nonzero Content-Length are rejected.
The existing attempt validator then checks the callback path/state/parameters. Invalid clients do not consume state.
Responses are fixed plain text with no-store, no-referrer and default-src none; request/code/state/error text is never
reflected. A browser disconnect while writing the small fixed response does not discard an already validated code.
A subsequent watchdog closes both sockets at the overall deadline, including during response writes;
cancellation also closes the active peer and listener. OS scheduling and socket teardown latency remain measured-runtime concerns. IO failures exposed to the owner use a generic token-free exception.

Required follow-ups remain browser dispatch/return on the phone, foreground/suspend behavior, account-setup ownership
and UI, token exchange/refresh/persistence and real-provider registration/login/receive/send. This HTTP component alone
does not establish Gmail or Outlook OAuth acceptance and is not yet included in installed 0.1.17.


Receiver socket tests cover success and listener closure, token-free responses, valid denial, malformed HTTP,
wrong/duplicate Host, body framing, folded/oversized headers, wrong state/path, absolute slow-client timeout,
cancellation during a partial request, total expiry, expiry before await, configuration-failure cleanup,
repeated close and rejecting a second waiter while preserving the first. These are host loopback tests,
not substitutes for the upcoming confined phone/browser and real-provider journeys.


All 166 app tests pass (zero failures/errors/skips), plus detekt/installDist/spotlessCheck:
/tmp/thunderbird-oauth-receiver-full.log; XML in build/evidence/oauth-receiver/.
The app-scoped Gradle command used JDK 21/Android SDK and the standard
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4 options.
No new dependencies, permissions or shared Android changes. Android assembly/device suites were not rerun;
the JVM app has no Android lint/device task. Installed 0.1.17 remains unchanged.


### 0.1.18 browser handoff candidate

LomiriOAuthBrowser sends HTTPS authorization targets through the permitted DispatchURL method without TestURL.
It validates scheme/host/userinfo/fragment, uses the existing session-bus UID/SASL setup and waits for the reply
before releasing its private connection. Dispatcher/transport errors become a generic exception without the remote
message/cause/suppressed data. Three tests cover exact encoded request preservation, rejecting unsafe targets before
any dispatch and error redaction. The adapter is blocking and must be called on IO; cancellation during the D-Bus
call and real UI integration remain unverified. Dispatch acknowledgement is not authentication completion.

Upstream [platform client source](https://gitlab.com/ubports/development/core/lomiri-url-dispatcher/-/blob/309775964bc1ecbebf4d52cdc5add2b9b2c0910f/liblomiri-url-dispatcher/lomiri-url-dispatcher.c)
uses an empty second argument for normal routing. Its header explains that the argument restricts the destination
package and that app-ID lookup is unavailable to confined callers. Commit 309775964bc1ecbebf4d52cdc5add2b9b2c0910f
matches the source suffix of the installed dispatcher package, rather than assuming current main's API.
No caller identity is spoofed and no permission is added.

The receiver now starts an independent coroutine watchdog while awaiting a callback. At the overall deadline it
closes both listening and active peer sockets, including a response write. Its worker cancels the watchdog on exit.
The deadline still starts at construction; setup ownership must close a receiver that is never awaited.

ConfinedOAuthCallbackProbe is a new test-only driver for matching installed production code. Socket mode sends a
synthetic HTTP callback and verifies its status, code and PKCE verifier binding. Browser mode dispatches the same
local callback directly through the platform interface, solely to test browser-to-listener delivery. This diagnostic
HTTP URL is not accepted by the production HTTPS-only authorization-browser adapter and does not exercise a provider.
Both modes must run under the exact enforcing app profile with the installed JVM/app JARs and private temp directory.
A headless probe cannot prove actual account-setup foreground restoration or survival of app UI suspension.

Version 0.1.18 is reserved in Gradle, manifest and packager for these changes, attachment preparation and the MIME
resource fix. Installed phone/fleet still remain 0.1.17 until a new artifact is built and independently verified.


The browser adapter's host tests do not prove real dispatcher delivery or connection cancellation. The matching
native callback probe is prepared for 0.1.18; results must be recorded after deployment, without treating a successful
headless/browser callback as normal app UI lifecycle acceptance. No real OAuth provider has been contacted.


Candidate host verification: all 169 app tests pass (zero failures/errors/skips), plus detekt/installDist/spotlessCheck
(/tmp/thunderbird-018-checks.log; XML build/evidence/oauth-browser/). JDK 21/SDK, app-scoped Gradle tasks with the
standard no-configuration-cache/isolated-projects=false/max-workers=4 options. No dependencies, permissions or shared
Android changes. Android assembly/device suites were not rerun; the JVM module has no Android lint/device task.


### 0.1.18 artifact built; deployment pending

Candidate 20 completed successfully with Click review and ARM64 launcher/JVM/AWT/Skiko checks:
app-ubuntu-touch/build/ut-candidate-20/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.18_arm64.click.
Size 147360976 bytes; SHA-256 8601068f17ebc5793897fbfaac1115955ea19d01c40dd709bbbb23274288ca47.
It embeds source f18314918dca741bed2c3add7cbfaae25857752e with an empty SOURCE-CHANGES.patch.
Build log: /tmp/thunderbird-018-click.log. The pinned builder image and verified candidate-5 runtime are the same
as the previous candidate; this reused-runtime iteration is not a cold-build reproducibility result.

A separate 57-class test-only JAR is uploaded as probes/candidate-018-probes.jar. It includes updated search/resource/
attachment checks, protocol/composer drivers and ConfinedOAuthCallbackProbe, without replacement production classes.
Do not execute it against installed 0.1.17. At this checkpoint the Click transfer is still running; no installation,
native 0.1.18 test, browser callback result or fleet update is claimed. A prepared upgrade script checks the exact
artifact hash, stops only the old app service and makes a private SQLite quick_check backup before Phone Manager install.


### Protected OAuth token persistence (later source, not candidate 0.1.18)

OAuthTokenStore now uses the existing CredentialStore boundary for opaque token data; it has no SQLite dependency.
Its per-account key and protected payload bind the account UUID, client ID, authorization/token endpoints and
canonical scope set. A callback's ephemeral port is deliberately excluded so a new login port does not invalidate
saved refresh credentials. Copying a payload to another account or changing provider configuration fails closed.
The binding digest is a configuration guard, not a MAC or replacement for the protected service's access controls.

A versioned bounded binary envelope stores access/optional refresh token and absolute expiry. Token values reject
control/non-ASCII input and lengths over 16384 characters; decode caps total size at 40000 bytes and rejects unknown
versions, invalid markers, truncation and trailing data. Temporary encoded/read arrays and the encoding buffer are
cleared, including on credential-service failure. Retained immutable JVM strings are not physically erasable;
OAuthTokens.toString is redacted. Locked/unavailable service errors propagate without treating them as missing data.
No plaintext fallback, logging, provider configuration change, dependency or permission is introduced.

Eight tests use a recording credential-service fake to verify reopen, field preservation, clearing owned arrays,
account/configuration isolation, scope-order/redirect-port equivalence, absent refresh/expired access state,
malformed payloads, lock/write-failure behavior, selected-account deletion and invalid token values.
These host tests do not establish actual SignOn OAuth payload round trips or atomic refresh rotation.
Setup ownership, account deletion/credential cleanup integration, migration, token exchange/refresh/revocation and
IMAP/SMTP token-provider wiring remain required. This component is not yet called by the app UI and is not in the
already built f18314918d 0.1.18 artifact; any later package containing it must use a new version.


All 177 host app tests pass (zero failures/errors/skips), plus detekt/installDist/spotlessCheck:
/tmp/thunderbird-oauth-storage-full.log; XML build/evidence/oauth-storage/.
JDK 21/SDK with the standard app-scoped Gradle tasks and no-configuration-cache/isolated-projects=false/max-workers=4.
No shared Android change; Android assembly/device suites were not rerun and the JVM module has no Android lint/device task.
The frozen 0.1.18 test-driver JAR uploaded earlier remains matched to f18314918d, not these new token-store classes.

Provider prerequisites were checked against official documentation. Google's
[IMAP/SMTP OAuth mechanism](https://developers.google.cn/workspace/gmail/imap/xoauth2-protocol?hl=en)
uses the full mail.google.com scope and describes public-app verification requirements. Microsoft's
[mail protocol OAuth guide](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth/)
requires an app registration and Outlook-resource delegated scopes for IMAP and SMTP, with offline_access for
refresh tokens. A Graph-only token must not be assumed suitable for IMAP/SMTP. These constraints are recorded for
implementation; no production registration, tenant policy or upstream OAuth configuration has been changed.


### OAuth token exchange/refresh client (later source, not candidate 0.1.18)

OAuthTokenClient implements public-client code/PKCE exchange and refresh grants. It sends a matching redirect,
verifier, code and client ID; refresh retains the previous refresh token only when the response omits a replacement.
It requires HTTPS token endpoints without credentials/query/fragment. Bearer responses require valid bounded token
text and positive expiry capped at seven days, calculated conservatively from request start with overflow checks.
Invalid grants require reauthentication; invalid client/scope errors are configuration failures; rate limits and
server failures are unavailable-service outcomes. No error description or remote exception cause is exposed.
No client secret, provider registration or ID-token identity verification is implemented.

The app now directly declares the existing catalog kotlinx-serialization-json 1.11.0 dependency, without a new
version, plugin or shared-module change. A manual serializer rejects duplicate known token fields, including escaped
names, while ignoring extensions. Input is strict UTF-8, at most 64 KiB; malformed fields/expiry/type/content type fail
closed and response byte arrays are cleared. Parser debug input is disabled. Version 1.11's unknown-element skip
uses an iterative stack; a deeply nested extension is exercised by a regression. This does not assert the behavior
of the newer version shown by the current online API docs.

JdkOAuthTokenHttp uses JVM trust/hostname verification, no redirects/cookies/authenticator, form encoding and explicit
connect/request deadlines. A coroutine deadline also covers a body stalled after headers; cancellation cancels the
HTTP future and closes its response stream. Actual response bytes are capped independently of headers. Its owner
must close the client. Controlled TLS tests inject only their own fixture certificate; default trust is unchanged.
Mutable response bytes are cleared, but immutable JVM request/token strings cannot promise secret-memory erasure.

Tests cover exact grant parameters, refresh rotation/omission, expiry, duplicate/escaped fields, malformed/oversized
and deep-extension JSON, error classification/redaction and cancellation. Real local HTTPS tests cover form encoding,
certificate rejection, hostname mismatch, redirect refusal, chunked response limits and stalled-body cancellation/
deadline; an integration test exchanges a synthetic code through the real client and transport. No real provider
or production credentials are used. Granted-scope handling, provider-specific registration/client requirements,
validated Microsoft account identity, serialized refresh/persistence and account/transport/UI wiring remain open.


All 191 host app tests pass with zero failures/errors/skips, plus detekt/installDist/spotlessCheck
(/tmp/thunderbird-oauth-http-full.log; XML build/evidence/oauth-http/). JDK 21/SDK, app-scoped Gradle tasks with
no-configuration-cache/isolated-projects=false/max-workers=4. Shared Android code is unchanged; Android assembly/
device suites were not rerun and this JVM module has no Android lint/device task. This token HTTP code and its
new direct dependency are not included in the frozen 0.1.18 candidate and need a later version for native acceptance.


### 0.1.18 deployment and synthetic browser callback verified

The original transfer completed successfully before installation (log /tmp/thunderbird-018-transfer.log).
The exact phone-side hash matched 8601068f17ebc5793897fbfaac1115955ea19d01c40dd709bbbb23274288ca47.
The guarded install stopped only the old 0.1.17 app service and created a private pre-upgrade SQLite backup with
quick_check and schema 9, then Phone Manager installed successfully (/tmp/thunderbird-018-install.log).
Development signature/user-bus/old-profile-cache warnings remain recorded; an independent SSH check confirms current
0.1.18, embedded f18314918dca741bed2c3add7cbfaae25857752e, empty patch and new profile
(/tmp/thunderbird-018-installed.log). Installed candidate bytes/source/hash are unchanged from the build evidence.

The frozen 57-class test-only JAR ran with installed production JVM/app JARs and the exact enforcing 0.1.18 profile.
ConfinedSearchProbe passes account-safe search/reader/flags/reply/Back, MIME resource cleanup and private decoded
attachment preparation/cleanup. ConfinedComposerProbe passes Swing Main, schema migration, draft/outbox lifecycle
flush and uncertain-send recovery. Logs: /tmp/thunderbird-018-native-{search,composer}.log.

ConfinedOAuthCallbackProbe passes both socket and browser modes (/tmp/thunderbird-018-oauth-{socket,browser}.log).
Browser mode used the permitted real DispatchURL call to open only a synthetic local callback URL. The installed
production receiver accepted its HTTP callback/state and returned a code whose verifier matches the request challenge.
No provider URL, account or token endpoint was used. This proves the headless enforcing-profile browser-to-listener
path; it does not prove account-setup UI foreground restoration, suspend/relaunch survival or Gmail/Outlook login.
No AppArmor permission/lifecycle exemption was added. Generic SLF4J no-provider warnings are retained in the browser log.

Current remaining delivery work: repeat the controlled TLS receive/reply/Sent and fresh-JVM offline journey on 0.1.18,
then refresh only the owned fleet entry (currently still pins 0.1.17). Native token storage/HTTPS acceptance needs a
later package version; do not rebuild changed source as another 0.1.18 or replace its frozen matching driver JAR.


### 0.1.18 controlled live-mail/restart and fleet pin verified

A new controlled server fixture ran at build/evidence/oauth-browser/native-mail-018 with STARTTLS, flags, reply and
Sent-copy oracles. The installed 0.1.18 engine and frozen matching 57-class driver passed under its enforcing profile:
protected credentials, default certificate rejection then fixture-only trust, IMAP cache/search, queued read/star
reconciliation, threaded reply, SMTP acceptance and guarded Sent-copy handling (/tmp/thunderbird-018-phone.log).
Only synthetic fixture credentials/mail were used; no provider account was contacted.

The host server was stopped and completed successfully before the offline restart probe. Its independent oracles
confirmed a single intended-recipient SMTP delivery with the expected body/threading, read/star flags and exactly one
matching read Sent copy. STARTTLS gateway upgrade-count assertions passed too (/tmp/thunderbird-018-server.log).
A fresh installed phone JVM then reopened the retained protected credentials, cached body/search and outbox state
with that server stopped, and cleaned its fixture (/tmp/thunderbird-018-restart.log). An independent SSH check confirms
the exact retained protocol-runtime-9498098442069196560 directory is absent (/tmp/thunderbird-018-mail-cleanup.log).
No controlled server or probe remains running.

Fleet commit 9333240 now pins 0.1.18/candidate-20/source f18314918d and SHA-256
8601068f17ebc5793897fbfaac1115955ea19d01c40dd709bbbb23274288ca47. YAML parsing and an independent local artifact hash
check passed. Only the owned mail entry was staged/committed; unrelated manifest/payload/artifact changes were
preserved. The fleet-wide hook was bypassed for this scoped commit; no whole-fleet converge or fleet remote push ran.
This closes the local fleet-pin gap, not physical UI/provider acceptance or the later token-client deployment gap.


### Serialized OAuth token coordination (later source, not installed 0.1.18)

OAuthTokenCoordinator composes the protected store and HTTP token client. One shared instance serializes operations
per account while allowing different accounts to refresh independently. Every request checks protected storage;
there is no plaintext disk fallback or independent background worker. It reuses access outside a 60-second safety
margin and persists a refresh replacement before returning it. An invalid grant removes unusable saved state;
transient failures retain refresh state without returning expired access. An already-expired replacement is saved
for its rotated refresh credential but is not returned for authentication.

A protected-write failure retains the observed replacement in memory for a write retry rather than refreshing the
old credential again. Pending data remains bound to an immutable provider-configuration snapshot. Invalidation names
the actual rejected access token, so a delayed rejection cannot expire a newer token. It also retains a failed
invalidation write, preventing reuse of the rejected saved access token. Once a replacement/invalidation is observed,
its protected write completes in NonCancellable; ordinary queued/network cancellation remains cancellable. This
critical section still depends on the credential service's call/timeout behavior; bounded cancellation latency of
an unresponsive SignOn service is not established here.

Registry references include queued waiters; idle entries are removed once no pending protected write remains,
preventing two live mutexes for one account. Only failed pending writes retain token references between operations.
Deletion clears both protected data and pending state, but must still be integrated with actual account-removal and
setup-generation/credential-cleanup transactions. A setup owner must validate its account/generation before storeGrant.
The app must inject the same coordinator across operations; cross-process or independent-coordinator locking is not
implemented. A crash or response loss before rotation is observed/persisted can still require provider reauthentication;
this is not an exactly-once remote refresh guarantee or physical secret-memory erasure.

Thirteen tests exercise safety-margin expiry, one refresh across concurrent same-account operations, independent
accounts, failed persistence/retry without another refresh, provider binding, stale-token rejection, invalid grant,
queued cancellation, cancellation during the protected rotation commit, deletion/locked service, already-expired
replacement and transient failure. Meaningful fail-before regressions caught rejected-token reuse after a failed
invalidation write and returning an already-expired replacement; XML retained in
build/evidence/oauth-coordinator/{invalidation-before,expiry-before}.xml. The protected fake is thread-safe and uses
synthetic tokens only. These host tests do not establish native SignOn round trips or account/IMAP/SMTP/UI integration.


All 204 host app tests pass (zero failures/errors/skips), plus detekt/installDist/spotlessCheck:
/tmp/thunderbird-oauth-coordinator-full.log, XML build/evidence/oauth-coordinator/.
The app-scoped tasks used JDK 21/SDK and standard no-configuration-cache/isolated-projects=false/max-workers=4 options.
No new dependencies/permissions/shared Android code; Android assembly/device suites were not rerun and this JVM module
has no Android lint/device task. This coordinator is not included in installed 0.1.18/f18314918d; native acceptance
requires a later version together with token store/HTTP client, without rebuilding changed source as another 0.1.18.


### Engine OAuth token-provider bridge (later source, not installed 0.1.18)

CoordinatedOAuth2TokenProvider implements the existing mail engine OAuth2TokenProvider contract. IMAP can share a provider across its connection pool, so the bridge correlates issued access tokens with each
calling authentication thread using weak thread keys. The pinned engine's blocking auth/get/invalidate calls stay
on that thread. All providers share the coordinator; late rejection on one thread cannot invalidate a newer token
issued on another. Connection/store ownership must close the provider to clear all retained references. This is not
a contract for an authentication flow that migrates between threads. An owned
SupervisorJob supports close/interruption cancellation without a transient token failure poisoning subsequent calls.
Blocking calls reject the Swing UI thread and invalid time budgets; the budget includes waiting for the provider's
lock. Interrupt status is restored. Errors become generic AuthenticationFailedException messages without remote
cause/server text or suppressed response data. No password fallback is introduced.

Caller-supplied verified primary identities are bounded, validated and defensively copied. This bridge does not
parse or trust ID-token claims and does not infer a Microsoft primary identity from the typed address. Empty
alternatives mean no extra identities, not that Microsoft's identity requirements are complete. Providers must be
owned/closed by their protocol/store lifecycle and shared coordinator binding still needs app composition.
Synchronous credential-service calls and NonCancellable rotation persistence can delay cancellation; this does not
establish a hard wall-clock bound for an unresponsive SignOn service.

Nine bridge tests cover separate-connection invalidation ownership, no-token invalidation, timeout/close, actual
thread interruption, UI-thread/invalid-timeout/closed/missing-state rejection, sanitized locked-service errors and
retry, copied identities, lock-wait budget and shared-provider thread correlation. Two integration tests use the real IMAP and SMTP engines against
GreenMail behind the existing controlled STARTTLS gateway. Both engines reject a stale cached bearer, refresh it
and authenticate using the replacement; the same coordinator's protected state records the rotation. A separate
SMTP test proves supplied verified-primary fallback when the typed alias fails with an already fresh token.
Only synthetic mail identities/tokens and fixture certificate trust are used; production trust and shared engine
source are unchanged. These tests prove engine compatibility, not app account-setup or provider acceptance.

Still required: ForegroundMailSessionFactory currently supplies null token providers and UtMailAccount still limits
authentication to PLAIN/CRAM_MD5. Their credential/authentication selection, MailOperation ownership, Koin composition,
account setup/removal generation/journal integration, native acceptance and real provider registrations remain open.
The combined alias-plus-rejected-token retry scenario subsequently exposed a shared SMTP bug; see the regression and fix below.
No upstream OAuth factory/configuration, dependency or permission was changed in this slice.


All 215 host app tests pass with zero failures/errors/skips, plus detekt/installDist/spotlessCheck
(/tmp/thunderbird-oauth-provider-full.log; XML build/evidence/oauth-provider/). The app-scoped Gradle tasks used
JDK 21/SDK and the standard no-configuration-cache/isolated-projects=false/max-workers=4 options.
No shared Android/engine code changed; Android assembly/device suites were not rerun and this JVM module has no
Android lint/device task. The bridge and later OAuth client/store/coordinator remain outside installed 0.1.18;
any candidate containing them must bump all three version locations before packaging.


A dedicated two-thread regression failed before correcting the shared-provider correlation: an older authentication
thread invalidated the newer thread's token, causing a second refresh (access-2 instead of access-1).
The source audit confirmed RealImapStore passes the same provider to its RealImapConnection instances. Weak-keyed
per-thread issued references fix that failure without leaving ThreadLocal tokens on shared executor threads;
provider close clears every reference. Before XML: build/evidence/oauth-provider/shared-thread-before.xml;
log /tmp/thunderbird-oauth-provider-before.log. The later shared SMTP regression below closes the stale-token alias-retry gap, while real-provider identity and login acceptance remain open.


### SMTP verified-identity retry after refresh

The new STARTTLS integration regression reproduced a shared SMTP bug: a rejected cached token failed for both the
typed alias and verified primary identity, then the refreshed token was tried only with the alias. Authentication
failed even though the verified primary would accept the replacement. The regression failed before the fix with
AuthenticationFailedException (build/evidence/smtp-alias/engine-before.xml;
/tmp/thunderbird-smtp-alias-before.log). Three new protocol-module regressions also failed before the fix
(build/evidence/smtp-alias/module-before.xml; /tmp/thunderbird-smtp-alias-module-before.log).

SmtpTransport now retries the same discovered identity set with the refreshed token, stopping on success. If every
identity rejects that token, it invalidates once more and reports permanent failure without another refresh wave.
A non-authentication temporary response propagates without invalidating the replacement. The configured username
remains preferred for failure reporting. This changes a private shared implementation only; no public interface,
provider registration, scope or dependency changed.

The STARTTLS test now authenticates the primary with exactly one refresh and persists the rotated refresh token.
Module tests use a stateful fake provider plus scripted wire exchanges to verify primary recovery, all-identities
failure without a third wave, and temporary failure preserving the new token. Existing SMTP tests still pass.
All 216 UT app tests and 102 SMTP tests pass with zero failures/errors/skips, plus app detekt/installDist/spotlessCheck
and SMTP lint/detekt/spotlessCheck (/tmp/thunderbird-smtp-alias-checks.log). XML is retained under
build/evidence/smtp-alias/{app,smtp}/. Both Thunderbird and K-9 Mail FOSS debug Android consumer assemblies pass as well
(/tmp/thunderbird-smtp-alias-android.log, 2316 tasks, 4m 8s). Android instrumentation was not run: this private shared
JVM logic is covered by module/protocol/STARTTLS tests and consumer assemblies; real-device/provider journeys remain
separate acceptance gates. No Android UI or permission changed.

The change is source-only: installed 0.1.18 and its pinned f18314918d artifact do not contain this fix. Factory/UI
OAuth wiring, native later-version acceptance, validated provider identity claims, configuration/registration and
actual Gmail/Outlook login remain required. This resolves the measured alias-retry defect, not full Outlook acceptance.


Shared-fix verification used JDK 21/Android SDK and these scoped tasks, all with
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4:

```sh
./gradlew :mail:protocols:smtp:test :mail:protocols:smtp:lint \
  :mail:protocols:smtp:detekt :mail:protocols:smtp:spotlessCheck \
  :app-ubuntu-touch:test :app-ubuntu-touch:detekt \
  :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck
./gradlew :app-thunderbird:assembleFossDebug :app-k9mail:assembleFossDebug
```

The raw check/Android/before-fix logs are retained under build/evidence/smtp-alias/ as well as the /tmp paths above.
Both Android consumer builds completed before finalizing this change; no background build/server/probe remains running.


### OAuth grant generations before account-manager wiring

OAuthGrant now separates a stable account ID from a particular authorization's protected credential reference.
Each new grant gets a random UUID generation in an account-bound ut-oauth-v2 key. Restoring a reference validates
its exact account prefix, bounded length and canonical generation UUID; it cannot target a legacy password key or
another account's key. Its string representation is redacted.

Token store, coordinator and engine bridge now take that explicit grant reference. The protected envelope's binding
includes both account ID and credential key, so copying a payload to another generation of the same account fails.
Coordinator locks and pending writes are keyed by grant, allowing a replacement to proceed while an older generation's
network request is still in flight. The old result writes only its own key; late invalidation cannot affect a replacement,
even if both contain identical access-token text. Deleting one generation leaves the other intact.

The token envelope is version 2. Version 1 was host-only prototype code and was never included in installed 0.1.18;
there is no deployed token-store migration to perform. Old envelope versions fail closed. Password encoding, existing
account SQL schema and the phone's installed credentials are unchanged.

Five new tests cover generation creation/restoration and malformed/cross-account/password references; same-account
store/deletion/copy isolation; replacement progress while an older refresh is pending; and engine invalidation with
identical token text in different generations. Existing OAuth tests now use explicit generation references.
This is preparation for ownership integration, not its completion: an old result can still write its old key when
using the raw CredentialStore. Account-manager current-generation/mode checks must fence protected access under the
same lock as replacement/deletion, so deleted/retired keys cannot be recreated. Creation/replacement cleanup journaling,
provider-host allowlisting, session cancellation and setup-generation validation remain required before UI enablement.
No provider registration, configuration factory, dependency or permission changed in this slice.


All 221 host app tests pass with zero failures/errors/skips, plus detekt/installDist/spotlessCheck
(/tmp/thunderbird-oauth-grant-full.log; XML build/evidence/oauth-grant/). App-scoped tasks used JDK 21/SDK and the
standard no-configuration-cache/isolated-projects=false/max-workers=4 options. No shared engine/Android code changed;
the prior SMTP/Android consumer checks remain applicable. Android instrumentation was not run for this JVM change.
Installed phone/fleet remain verified 0.1.18/f18314918d; a later package must use a new version for these grant APIs.


### OAuth account ownership and schema 10 (source only)

OAuth account metadata now persists a provider reference, with both mail protocols required to use XOAUTH2.
Schema 9 upgrades to 10 without changing password accounts, cached messages or drafts. Tokens remain outside SQLite.
An injected provider registry validates the incoming and outgoing host, port and TLS mode before protected token
access. The production default registry is empty; no Gmail or Microsoft registration has been enabled.

The account manager journals initial and replacement credential writes and serializes protected OAuth access with
account replacement/deletion. ManagedOAuthCredentialStore rejects retired generations, and the refresh coordinator
discards pending state when ownership is rejected. Nine SQLite-backed tests cover removal/replacement races, failed
SQL commits, deferred cleanup, stale setup snapshots, locked storage, host restrictions and schema migration.
These checks do not cancel already issued network operations or revoke credentials remotely. Mail-session routing,
owned provider cancellation, setup UI and real provider verification remain required.

Installed 0.1.18 still uses schema 9 and does not contain this implementation. The next package must bump all three
version locations to 0.1.19; current compiled schema-10 probes must not replace the frozen 0.1.18 probe JAR.

Full verification passes: 230 app tests with zero failures/errors/skips, detekt, installDist and spotlessCheck.
Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist
:app-ubuntu-touch:spotlessCheck --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Log: /tmp/thunderbird-oauth-ownership-full.log; test XML: build/evidence/oauth-ownership/.
No shared engine or Android source changed; prior SMTP and Android consumer checks remain applicable.
Android instrumentation was not run for this JVM-only slice, and native deployment acceptance remains open.


### Foreground OAuth session routing (source only)

ForegroundMailSessionFactory now selects OAuth before password access. Its injected OAuthMailAuthentication validates
the provider's approved server settings and constructs separate IMAP/SMTP token providers sharing one coordinator.
The application composition binds that coordinator to OAuthTokenStore over ManagedOAuthCredentialStore, while
retaining the raw protected store for the account manager's journaled initial writes. The provider registry remains
empty in production; this wiring does not enable a Gmail or Microsoft registration or expose sign-in UI.

Mail operations can now own closeable token providers alongside sockets. Session resources reject and immediately
close resources added after cancellation; session close and construction failure release owned resources. Teardown
attempts every resource before propagating a failure. OAuth HTTP client lifetime is owned by Koin.

Account removal/replacement still needs operation-generation cancellation: the existing ownership fence prevents
retired credential writes but cannot revoke an already issued token or stop every existing authenticated connection.
Provider identity validation, setup UI, real-provider journeys and native acceptance remain open.

A new controlled STARTTLS factory-level test creates an OAuth account through the manager, refreshes once through
managed protected storage, checks both protocol settings, receives/caches an Inbox message, sends MIME through SMTP
and verifies the rotated refresh token. Since the password API rejects OAuth accounts, this also exercises the
authentication-mode selection. Missing OAuth composition fails closed. Three resource tests cover teardown failure,
idempotent close/late registration and cancellation interrupting a pending refresh.

Verification: all 234 app tests pass with zero failures/errors/skips, plus detekt/installDist/spotlessCheck.
Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Full log: /tmp/thunderbird-oauth-session-full.log; XML: build/evidence/oauth-session/.
Focused tests and checks also pass (/tmp/thunderbird-oauth-session-focused.log). No shared engine/Android changes;
prior shared SMTP and Android assemblies remain applicable. No Android instrumentation or phone deployment was run
for this JVM slice. Installed phone/fleet remain 0.1.18; the next package must use 0.1.19.


### Account-scoped session retirement (source only)

The account manager now registers each foreground session against its current in-memory account snapshot.
Registration shares the mutation monitor; a snapshot from before replacement, removal or identical recreation
cannot register a new session. Credential, provider, server or address changes retire existing sessions for that
account; display-name changes preserve them. Explicit session close releases its registration.

Mutation marks obsolete resource bundles retired under the manager lock, preventing new session operations, then
closes socket/provider resources after releasing that lock. Reentrant account mutations from an update transform
are rejected before nested teardown can occur under the outer lock. Locked credential deletion and failed SQL
replacement preserve the current sessions. If credential deletion succeeds but the subsequent SQL removal fails,
sessions are retired even though account metadata remains, since the protected credentials are already gone.

This cancellation does not revoke provider-side tokens or undo mail already accepted by a server. Outbox/Sent
uncertainty handling remains necessary for interrupted network operations. Real provider setup and phone acceptance
remain open, and this source is not in installed 0.1.18.

The authenticated-transfer regression was checked with account-session registration temporarily disabled: the
credential-replacement case failed with TimeoutException because the existing read was not interrupted
(/tmp/thunderbird-account-session-before.log; build/evidence/account-session/authenticated-before.xml).
Registration was restored before verification. The focused ownership/OAuth/real-engine suite passes
(/tmp/thunderbird-account-session-focused.log). Six new manager tests cover account isolation, display-name
changes, failed/locked mutations, post-secret-delete SQL failure, teardown outside the manager lock, released
sessions, stale construction after identical recreation and nested mutation rejection. Additional real-engine
cases verify replacement interrupts an authenticated body transfer and an owned provider awaiting OAuth refresh.

Full verification: 242 app tests, zero failures/errors/skips; detekt, installDist and spotlessCheck pass.
Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist
:app-ubuntu-touch:spotlessCheck --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Full log: /tmp/thunderbird-account-session-full.log; test XML: build/evidence/account-session/.
No shared engine or Android changes; prior shared SMTP and Android consumer checks remain applicable.
Android instrumentation was not run for this JVM slice. Phone/fleet remain 0.1.18; no native or real-provider
acceptance is claimed. Next package must bump to 0.1.19 in all three version locations.


### OAuth account authorization orchestration (source only)

OAuthAccountAuthorization connects approved provider configuration, an allocated loopback receiver, browser
dispatch, validated callback/PKCE code exchange and the manager's journaled account creation/replacement. Each
prepared attempt is single-use and returns only account identity and a pending-cleanup flag. It checks the account
snapshot before browser dispatch and the manager checks it again when committing a replacement. Tokens, callback
codes and verifiers are not put in observable setup state or SQLite. Token configuration binding already excludes
the ephemeral redirect port; exchange uses the exact URI allocated for that attempt.

An explicit cancellation phase prevents a cancelled attempt from starting its protected commit. Cancellation closes
the listener even while browser dispatch is blocked and cancels pending callback/HTTPS work. The future setup model
must call attempt.cancel() before cancelling its coroutine or leaving setup. Once the atomic transition to protected
commit has occurred, cancel returns false: the journaled write runs non-cancellably and the UI must wait for its
outcome. Scope disposal can still suppress delivery of that outcome, so a later setup must reload account state.
Cancellation does not revoke provider-side credentials or undo an already started account commit.

Fourteen tests use a real loopback receiver with controlled browser and token responses. They cover exact
redirect/code/PKCE binding, protected persistence without database token/code leakage, denial, browser failure,
timeout, cancellation before start/during callback/blocked dispatch/exchange, stale account changes, SQL failure
cleanup, disallowed hosts, successful reauthorization and the protected-write cancellation boundary.

All 256 app tests pass with zero failures/errors/skips, plus detekt/installDist/spotlessCheck.
Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist
:app-ubuntu-touch:spotlessCheck --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Logs: /tmp/thunderbird-oauth-authorization-{focused,full}.log; XML: build/evidence/oauth-authorization/.
No shared engine or Android changes; prior SMTP/Android consumer verification remains applicable. Android
instrumentation was not run for this JVM-only slice.

This service is not yet wired into account setup UI or production DI. Dedicated OAuth editing is still needed; the
password form currently fails closed for OAuth accounts rather than converting them. Production provider registry
remains empty. Real Gmail/Microsoft registrations, required scopes, verified Microsoft identity, provider journeys
and native focus/suspend/keyring acceptance remain open. No OAuth configuration factory, client registration,
dependency or permission was changed. Installed phone/fleet remain 0.1.18; next package must bump all three version
locations to 0.1.19, and schema-10 probes must not overwrite the frozen schema-9 installed-package driver.


### OAuth account setup integration (source only)

Account setup now exposes only configured provider choices. OAuth forms collect name/address and build mail settings
from the registry's approved host/port/TLS values; password and manual server fields are hidden, cleared on provider
selection and reject late hidden-field edits. Registry choices carry a validated display name, without exposing
client IDs or endpoints. A missing provider disables browser sign-in while allowing the user to return to account
removal. The production registry remains empty: Gmail/Outlook buttons are not advertised without registration.

AccountSetupViewModel owns the single-use authorization attempt. Browser-wait cancellation remains busy until
listener/exchange cleanup completes; once protected commit starts the form waits for the saved outcome. Disposal
cancels the attempt before the model scope and suppresses late state publication. Reauthorization retains the old
protected grant until successful replacement; the snapshot captured when editing is checked before dispatch and
at commit. Changing providers clears passwords while preserving name/address. Successful manual-save text now
points to the already connected Mailboxes/Write flows.

Main Koin composition now binds OAuthAccountAuthorization and injects it into account setup. Ubuntu Touch uses
Lomiri browser dispatch; desktop uses a separate HTTPS-only Desktop browser adapter with generic, redacted errors.
No real provider registration, OAuth configuration factory, dependency or permission changed.

Typed address is used as the configured protocol username; it is not asserted to be a provider-verified Microsoft
identity, and no unverified JWT-derived alternative names are supplied. Real provider registration/scopes/identity
validation and end-to-end acceptance remain gates. Physical phone focus restoration, keyboard and suspension are
not proven by host model or Compose tests. This code is not installed in phone 0.1.18; next package must use 0.1.19.

Window-close lifecycle integration now registers account setup alongside the composer/mailbox owners. Closing the
window cancels a browser wait and joins its cleanup, or waits for a protected commit already in progress before
disposal. Dedicated model tests exercise both paths. Abrupt process termination still relies on the existing
credential cleanup journal. A dedicated pending-credential-cleanup retry control remains a follow-up; current OAuth
setup reports that condition and the manager's durable cleanup API remains available.

Twelve model tests exercise provider selection/secret clearing, approved server construction, denial/retry,
cancellation/cleanup, disposal, retained grants on cancelled reauthorization, stale editing snapshots, protected
commit cancellation, empty provider choices, both window-close paths and immediate cancellation before a queued
authorization coroutine starts. Four rendered Compose tests verify hidden password/server fields, browser sign-in
events, cancellation availability and missing-provider behaviour. Two desktop-browser adapter tests cover HTTPS
validation and error redaction. All use synthetic data; no provider login or real message delivery occurs here.

The immediate-cancel regression initially failed with a generic failure instead of “Sign-in cancelled.”
(/tmp/thunderbird-oauth-setup-cancel-before.log; build/evidence/oauth-setup/cancel-before-start-before.xml).
OAuthAccountAuthorizationAttempt now reports pre-start cancellation as CancellationException; the model handles it
consistently with cancellation during browser or exchange work.

Final verification: all 274 app tests pass, zero failures/errors/skips, plus detekt/installDist/spotlessCheck.
Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist
:app-ubuntu-touch:spotlessCheck --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Log: /tmp/thunderbird-oauth-setup-full.log; XML: build/evidence/oauth-setup/. The initial focused model/Compose checks
also passed (/tmp/thunderbird-oauth-setup-focused.log); final full verification includes subsequent lifecycle and
immediate-cancel regressions. Initial virtual-clock timeouts in socket tests were corrected with bounded real-time
waits; they were not treated as application evidence. No shared engine/Android changes; prior SMTP/Android consumer
checks remain applicable. Android instrumentation and native deployment were not run for this JVM-only slice.


### Provider registration proposal (2026-10-27; not enabled)

The existing Thunderbird debug OAuth factory supplies Android custom-scheme redirects (including an Android
package/signature-bound Microsoft redirect). Those public client IDs are not evidence that this unofficial port
is authorized to use a different callback. No existing ID or production configuration has been changed.

Proposed first test setup: dedicated, owner-controlled registrations named “Mail demo for Ubuntu Touch,” with
explicit test accounts and no client secret bundled into the app. Enable entries only after registration details,
callback behaviour and grants have been verified. Separate Microsoft 365 and Outlook.com mail-server pairs can
share an approved registration while keeping their account-specific server allowlists distinct.

| Provider | Proposed registration and grant | Candidate mail-server pair from the existing factory | Open acceptance gate |
| --- | --- | --- | --- |
| Gmail | Google Desktop client, code + S256 PKCE, `https://mail.google.com/` | `imap.gmail.com:993` direct TLS; `smtp.gmail.com:587` required STARTTLS | Owner-approved client ID; consent/test-user setup; actual loopback exchange/refresh; public-distribution verification |
| Microsoft 365 | Entra mobile/desktop public client; Outlook IMAP/SMTP delegated scopes plus `offline_access`; audience chosen by owner | `outlook.office365.com:993` direct TLS; `smtp.office365.com:587` required STARTTLS | Registered redirect and port policy; tenant/user consent; protocol availability; granted scopes and validated identity strategy |
| Outlook.com | Approved public-client registration supporting personal accounts; same Outlook resource permissions | `outlook.office365.com:993` direct TLS; `smtp-mail.outlook.com:587` required STARTTLS | Personal-account login, matching SMTP identity and actual receive/send/refresh acceptance |

Google documents loopback support for Desktop client types, while Android/iOS client loopback support was removed.
Its native-app guide lists a client secret as optional for code exchange/refresh. The current secretless PKCE client
therefore has a documented path to test, not proof that a particular registration will accept it.
Sources: [desktop OAuth](https://developers.google.com/identity/protocols/oauth2/native-app),
[loopback migration](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration).
Gmail IMAP/SMTP requires the full mail scope and public apps using it have verification/policy requirements; a
controlled test login does not establish release approval.
Source: [Gmail XOAUTH2](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol).

Microsoft specifies the Outlook resource scopes `https://outlook.office.com/IMAP.AccessAsUser.All` and
`https://outlook.office.com/SMTP.Send`; `offline_access` requests refresh capability.
Source: [Exchange OAuth](https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth/).
Its redirect guidance documents ephemeral-port matching for localhost and a manifest-based way to register HTTP
127.0.0.1 callbacks. The current receiver always uses numeric IPv4 with an allocated port, so numeric-address port
matching must be demonstrated against the chosen registration. Do not infer it from the localhost example. If a
fixed registered port or localhost callback is necessary, adapt and test the receiver explicitly; never silently
fall back to a different redirect after binding fails.
Source: [Entra redirect rules](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url).

Microsoft's code-flow guidance treats access tokens as opaque for APIs the client does not own. This port must
not derive trusted mailbox identities by decoding an unverified access token. Granted-scope handling and an
approved identity-validation strategy remain implementation work before enabling provider-specific identity
alternatives. Source: [code flow and token handling](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow).

Required owner input: use approved existing Desktop/public-client registrations, or authorize setting up dedicated
ones and identify their owning Google project/Entra tenant. No passwords or client secrets should be pasted into
chat. Repository AGENTS.md prohibits OAuth configuration changes without explicit instruction. This is a bounded
provider-enablement decision, not a blocker on remaining UI/platform/roadmap work.


### Pending credential cleanup retry UI (source only)

Accounts now displays pending credential cleanup from the durable journal and offers an explicit Retry credential
cleanup action. Model construction checks SQL status (the production model factory runs on IO), without contacting
the keyring; manual/OAuth outcomes refresh that status. A failed account insert therefore exposes its unclaimed key
for cleanup after leaving the form. Locked protected storage keeps the retry visible. Cleanup rechecks live account
references before deleting anything, so a journal entry still used by an account is not treated as obsolete.

Retry runs on IO, disables repeated retry and account navigation while busy, and does not discard an account form.
It does not initiate another OAuth browser flow. OAuth setup directs users to the new action when obsolete keys
could not be removed. If cleanup-status refresh fails after authorization, setup keeps an attention/retry indicator
rather than falsely declaring cleanup complete or discarding a successful authorization outcome.

Five new model/SQLite tests cover journal discovery after reopen, retained active credentials, locked retry then
unlock, active-key journal exclusion, cleanup after failed insert and protection of an in-progress form. Two rendered
Compose tests verify the retry event and disabled busy controls. The focused suite passes
(/tmp/thunderbird-credential-cleanup-focused.log).

Full verification: 281 app tests, zero failures/errors/skips; detekt/installDist/spotlessCheck pass.
Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Log: /tmp/thunderbird-credential-cleanup-full.log; XML: build/evidence/credential-cleanup/. No shared engine or Android
changes; prior SMTP/Android consumer checks remain applicable. Android instrumentation/native deployment were not
run for this JVM-only slice. The phone's last verified package remains 0.1.18; next package must use 0.1.19.

Provider registration direction is parked in Desk q-ar74, with halted=false. No provider configuration is enabled
while that decision is pending. Native controlled acceptance and independent later roadmap requirements can proceed.


### 0.1.19 built from clean source; transfer in progress

Candidate 21 builds from clean commit 3266863128050ad98218caed4645c217599d0472, with an empty
SOURCE-CHANGES.patch. Gradle, Click manifest and ARM packager versions are all 0.1.19.
Artifact: app-ubuntu-touch/build/ut-candidate-21/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.19_arm64.click.
Size: 147562550 bytes. SHA-256: 571fde133180cd3764a23fecb9bdcbb0132c467b209dffedb66f144380bbd28a.
Clickable review and ARM64 launcher/JVM/AWT/Skiko validation pass (/tmp/thunderbird-019-click.log).
The pinned builder and verified candidate-5 ARM runtime were reused; this is not cold-build reproducibility proof.
All 281 host app tests pass, plus detekt/installDist/spotlessCheck (/tmp/thunderbird-019-checks.log).

A new 62-class test-only driver is compiled against this source, without replacement production classes:
build/evidence/oauth-native-019/candidate-019-probes.jar, SHA-256
159c3996bea1b3878f89f5ac70fe3397c34b576b93ef09ffb1469c07db1caa63. Its uploaded phone copy matches.
The frozen 0.1.18 driver is unchanged (52ddbbdfad8bdb8d2f38ea25f2d7ee91ba4c61e2f71e453921ec0d106289b825).

ConfinedOAuthTokenProbe is prepared to verify 16384-character access and refresh token payloads through real
SignOn storage, default-trust rejection of a self-signed controlled endpoint, fixture-scoped trusted HTTPS refresh,
synthetic loopback/PKCE code exchange and protected account commit, then identity/index cleanup. It uses a private
temporary database and retains its directory if protected cleanup fails; cleanup mode is restricted to this probe's
private directory prefix. PhoneOAuthTokenServer is a bounded host-only synthetic fixture that logs request counts
and validity, never request bodies or credentials. No real provider is contacted and no system trust is modified.

The 147 MB Click transfer is still running over the phone's mobile-data connection; no USB device or Wi-Fi address
was available. A local rsync comparison found 143351358 bytes of literal data, so a compressed-Click delta was
rejected as ineffective and the original transfer was kept. No restart or second Click transfer was launched.
The unused controlled HTTPS server was stopped before any native probe, to restart only when installation is ready.

At this checkpoint the last observed phone version is still 0.1.18. No installation, schema migration, native
0.1.19 result or fleet update is claimed. The prepared upgrade script requires the complete Click size/hash, the
expected old source and no running Mail demo profile, then makes a private quick_check-verified SQLite backup
before Phone Manager installation. Future tests must use the matching 0.1.19 profile and driver, never the frozen
0.1.18 driver. Actual setup UI focus/suspend and real provider acceptance remain open.

The final remote size observation was 121420800 bytes. A subsequent SSH connection timed out and a Tailscale ping
also timed out. The original SCP initially remained live, then authoritatively terminated with exit 1 and
“Connection timed out / Broken pipe / lost connection” in /tmp/thunderbird-019-transfer.log. Partial deployment
blocker b-nnxn records this gap with halted=false; other roadmap work can continue. Once connectivity returns,
inspect the staged size/hash first: if incomplete, available rsync can resume with append verification instead of
retransmitting the full Click. Verify the complete SHA-256 before installation. No modem/OS/network configuration
change or installation was attempted.

A host-only fixture JAR is also frozen as build/evidence/oauth-native-019/host-server-019.jar, SHA-256
700328fd1d4eab0a0d28e6487fd76167e59a112a242e5863d8d492155b4b4507. On recovery, run it against candidate-21's
immutable production lib/app JARs, not a later rebuilt installDist. Start a fresh bounded server output directory;
the old endpoint is stopped and stale. The matching public certificate is already on the phone as
probes/candidate-019-certificate.der. Preserve both frozen 0.1.18 and 0.1.19 test drivers as source advances.


## Cached message sorting (0.1.20 development source, 2026-10-27)

Folder lists and account/unified cached search now offer newest, oldest, subject, sender, unread-first and
starred-first ordering. Effective dates come from existing SQLite metadata without a schema migration. Sorting runs
on the cache reader's IO dispatcher and requires neither network access nor an unlocked credential store. Unknown
dates remain last in either date direction; timestamp comparisons avoid subtraction overflow. Header keys use NFC
normalization and locale-independent case conversion, with deterministic UID/account/folder ties that retain distinct
results sharing a UID. The chosen order survives folder/account navigation within the current model lifetime.

Changing order resets the viewport to the first result after the cache operation completes. A rendered regression
failed before this fix because Compose retained the previously visible UID; the retained failure is
`app-ubuntu-touch/build/evidence/mail-sort/viewport-before.xml` and
`/tmp/thunderbird-sort-viewport-before.log`. Additional tests cover all six orders, extreme/missing dates, Unicode and
Turkish default locale, duplicate identities, locked-keyring operation, navigation, busy controls and real SQLite
list/reader date projection. The existing account-search regression now asserts the deterministic folder tie order
and selects its Archive result by identity rather than list position.

Version locations advance to 0.1.20 for changed production source. No 0.1.20 Click has been built or installed.
The immutable 0.1.19 package and frozen 0.1.18/0.1.19 native probe JARs remain unchanged; both probe hashes were
rechecked. Phone installation/native sorting and physical UI acceptance remain pending. Durable sort preferences,
locale-specific collation, pagination and large-mailbox performance remain open. There are no new dependencies,
permissions, shared Android changes or OAuth registration changes in this slice.

Final verification: all 291 app tests pass with zero failures, errors or skips; `detekt`, `installDist` and
`spotlessCheck` pass. Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ANDROID_HOME=/home/tom/Android/Sdk ./gradlew :app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test
:app-ubuntu-touch:detekt :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck --no-configuration-cache
-Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Log: `/tmp/thunderbird-sort-full-fixed.log`; XML retained under `build/evidence/mail-sort/after/`.
This JVM application has no Android lint/instrumentation target; shared Android sources were unchanged and the prior
shared-engine Android assembly evidence still applies. Native checks were not run because the FP4 remains unreachable
(blocker b-nnxn). Provider registration decision q-ar74 remains pending.


### Durable sort selection (0.1.20 development source)

The selected sort order now survives mailbox recreation and app restart. Production Koin injects
`MailSortPreferences` using a `mail-sort` file beside the existing private mail database; model creation already runs
on IO. Saves run on the mailbox's IO worker and register as local writes so window close joins the pending operation.
The file contains only the enum name, with owner-only permissions. A flushed temporary file is atomically renamed
within the same directory; failed replacement removes the provisional file. Missing, unknown and overlong values
fall back to newest-first, with bounded reads. Other read failures surface through the existing storage-opening error.

A failed save retains the selected order in the current mailbox, shows an explicit persistence failure and supports
retry. It also reports a failed local write through close-flush instead of silently declaring the preference saved.
Tests exercise every mode after reopening the store, private permissions, failed replacement/cleanup, a fresh model
and reopened mail database, plus a failed model save followed by successful retry without network access.
No database migration or new permission/dependency is required. The global order applies to folder/account/unified
cached lists. Per-folder preferences and locale-specific collation remain follow-up work.

The phone reachability recheck again timed out (`ssh` port 2222, ConnectTimeout10); no transfer or installation was
started. Frozen 0.1.19 remains the built deployment candidate; no changed 0.1.19 artifact is produced and current
0.1.20 source is not yet packaged. OAuth provider decision q-ar74 and phone blocker b-nnxn remain partial gaps.

Verification: full 295 app tests, zero failures/errors/skips; `detekt`, `installDist` and `spotlessCheck` pass using the
same full app command recorded above. Log `/tmp/thunderbird-sort-preferences-full.log`; XML
`app-ubuntu-touch/build/evidence/mail-sort-preferences/`. Scoped documentation formatting also checked. Shared/Android
source is unchanged; Android instrumentation and native preference acceptance were not run. Packaging follows from
this clean committed source; the current source remains 0.1.20 and no earlier artifact is overwritten.


### Verified 0.1.20 ARM64 package

Candidate22 built successfully from clean `aea2d6affa2f013abd27f89b1c02967607800fa6`; embedded
`SOURCE-CHANGES.patch` is empty. Artifact:
`app-ubuntu-touch/build/ut-candidate-22/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.20_arm64.click`.
Size: 147595130 bytes. SHA-256: `804329d5d5bc984ef35d237e09333ecf3d4f48a788b9767ed63b499f20af08a5`.
Clickable review passes and package verification confirms ARM64 launcher, JVM, AWT and Skiko.
Log: `/tmp/thunderbird-020-click.log`. The same pinned builder
`sha256:3a08b52420bd76280d8323db951a306e428fb17ac5e4cd60f53903e049a68563` and verified candidate5 ARM runtime
were reused; this is not a cold reproducibility result.

Matching current test classes are frozen in `build/evidence/oauth-native-020/candidate-020-probes.jar` (62 classes,
SHA-256 `9aa1d5b696f5db52191e6c9c292f26322e49742c9785370786785c3824c16a12`) and
`host-server-020.jar` (2 classes, SHA-256 `8e34156d75cdbc0a8f78a070e904c468a400633d4fb0148ba3f939402c268a02`).
Use these only with installed 0.1.20 production classes. Earlier frozen drivers and packages remain preserved.
The package is not transferred or installed; latest verified phone version remains 0.1.18. The partial 0.1.19 transfer
is retained and has not been overwritten. Choosing the next phone candidate must explicitly distinguish versions,
back up the production database, verify the complete artifact hash and then use Phone Manager. Native 0.1.20 UI,
preference persistence and provider authentication remain unverified.


## Reader attachment metadata (0.1.21 development source)

The actual mail reader now displays attachment count, sanitized filename and MIME type from complete cached MIME.
Classification and display metadata are shared with `CachedAttachmentStore`, preserving its export/reference rules.
The reader uses its existing parsed message and IO cache projection rather than reparsing or exporting files, and
HTML-only messages can still list attachments. Incomplete messages show a complete-download prompt and do not expose
provisional attachment metadata. Metadata diagnostic strings are redacted, and no remote resources are fetched.

A real SQLite regression checks names/types, body preservation, distinct accounts sharing folder/UID, HTML-only
messages, incomplete-cache suppression and MIME temporary-file cleanup with a locked credential store. Two rendered
reader tests verify complete metadata and incomplete-message messaging. The existing decoding, fingerprint, lease,
size-limit and filename-attack attachment tests also pass after extracting shared metadata helpers.
Focused command: standard JDK21/SDK environment and Gradle flags, `:app-ubuntu-touch:spotlessApply
:app-ubuntu-touch:test --tests '*CachedAttachmentStoreTest' --tests '*MailReaderAttachmentsTest'
:app-ubuntu-touch:detekt :app-ubuntu-touch:spotlessCheck`.
Log: `/tmp/thunderbird-attachment-reader-focused.log`.

This completes reader metadata presentation only. Open/Save through Content Hub, crash-orphan cleanup, outgoing
attachments, partial fetching, very large attachment counts and physical/native UI acceptance remain open. The reader
explicitly labels Open/Save unavailable; no file-launch shortcut or permission expansion was introduced. No new
library, mail schema, shared-engine or Android source changes. Source advances to 0.1.21 because immutable 0.1.20
already exists. No 0.1.21 Click is built or installed yet; matching 0.1.20 drivers remain frozen.

Final host verification: 298 app tests, zero failures/errors/skips; `detekt`, `installDist` and `spotlessCheck` pass.
Full command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Log `/tmp/thunderbird-attachment-reader-full.log`; XML `app-ubuntu-touch/build/evidence/attachment-reader/`.
Scoped docs formatting/checks pass. No Android device/instrumentation target exists for this JVM app; no shared source
changed and previous Android assembly evidence is unchanged. Phone/native checks were not run. Frozen 0.1.20 probe
SHA-256 was independently rechecked unchanged before source delivery.


### Verified 0.1.21 ARM64 package

Candidate23 built from clean `5dc68ccddbc51f11c08478856cd65cdab6bcadd4`; embedded SOURCE-CHANGES is empty.
Artifact: `app-ubuntu-touch/build/ut-candidate-23/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.21_arm64.click`.
Size 147607604 bytes; SHA-256 `3255df53fae96163fbcd4be235bf6c21625084ab01780a32d1bc14e5ca2fc87d`.
Click review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-021-click.log`). The same pinned builder
and candidate5 runtime were reused, so this does not establish cold reproducibility.

Matching drivers frozen from the tested source: `build/evidence/oauth-native-021/candidate-021-probes.jar`, 62 classes,
SHA-256 `507d11f9c142e16c529234b7f413ffa9f1260c369f2b796f07f3d820d56c86db`;
`host-server-021.jar`, 2 classes, SHA-256 `703e1b87073239af7f1d768c6333260323dbab7e519e45dac70b7f824ba988f2`.
MailContent constructor changed, so use the matching driver with installed 0.1.21 classes rather than any previous
probe JAR. Older packages/drivers remain preserved.

The bounded phone SSH reachability check again timed out. No transfer, Phone Manager installation or native 0.1.21
verification was attempted. Latest verified installed version remains 0.1.18. The existing upgrade script is specific
to 0.1.19 and must be updated and reviewed for an explicitly chosen newer version/hash before use. Phone b-nnxn and
provider decision q-ar74 remain partial gaps; neither blocks independent later roadmap requirements.


## Multi-select and offline bulk flags (0.1.22 development source)

Cached folder lists and account/unified search support explicit selection mode, row toggling, bounded select-listed,
and bulk read/unread/star/unstar assignments. Keys contain account, folder and UID; same-UID results remain distinct.
The selection limit is 1000 with a visible limit notice and labelled select-listed action. Selection clears when
changing search, scope, sort or navigation; disappeared rows are pruned on cache refresh. Busy controls disable and
empty selections cannot apply actions. This is a bounded cached selection, not a claim to select uncached server mail.

`PendingFlagStore.enqueueAll` validates unique non-empty bounded targets and applies every queue replacement in one
SQLite transaction. Failure rolls back the whole batch, preserving earlier assignments and their acknowledgement IDs.
Existing UID-validity fencing, durable local overlays and per-folder replay still apply. Batch writes run on IO and
use existing local-write close joining. Failed writes retain selection for retry; success clears selection only after
cache reload. Unified pending counts include every configured account; discard review remains explicitly per account.

Tests include complete identities across accounts/folders, stale-row pruning, the selection bound, busy/empty UI,
model queue failure/retry and unified pending counts, atomic rollback of an invalid final target, plus actual controlled
TLS IMAP reconciliation of selected read/star changes after closing/reopening SQLite. No new dependencies, permissions,
mail schema or shared Android code. Source advances to 0.1.22 after the immutable 0.1.21 build. Archive/trash/delete,
move/copy, server-wide selection, automatic background replay and phone/provider/physical acceptance remain required.

A unified-discard regression was caught before delivery: the new all-account pending count initially exposed an
origin-account-only discard control. Both rendered and model regressions failed before the fix (`/tmp/thunderbird-bulk-discard-before.log`,
`build/evidence/mail-bulk-flags/discard-screen-before.xml` and `discard-model-before.xml`). Unified scope now directs
the user to open an account for review/discard, and the model rejects a stale unified confirmation. Per-account discard
semantics are unchanged. This is not a new cross-account discard operation.

Final verification: full 307 app tests, zero failures/errors/skips; `detekt`, `installDist` and `spotlessCheck` pass.
Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew
:app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist
:app-ubuntu-touch:spotlessCheck --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Log `/tmp/thunderbird-bulk-full-final.log`; XML `app-ubuntu-touch/build/evidence/mail-bulk-flags/after/`.
Scoped docs checks pass. This JVM app has no Android lint or instrumentation task; shared Android sources were
unchanged, so prior shared-engine Android assembly evidence remains applicable. Native 0.1.22 checks have not run.


### Verified 0.1.22 ARM64 package

Candidate24 built from clean `d9eea90a34e144a4a5b588769c6ba9931fcaa6f0`; embedded SOURCE-CHANGES is empty.
Artifact: `app-ubuntu-touch/build/ut-candidate-24/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.22_arm64.click`.
Size 147643952 bytes; SHA-256 `d1f1971053917f64986b1e2f1113c8300459dd02157534805eae8350af20abf1`.
Click review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-022-click.log`). The same pinned builder
and verified candidate5 ARM runtime were reused, so this is not a cold reproducibility result.

Frozen matching drivers: `build/evidence/oauth-native-022/candidate-022-probes.jar` (62 classes),
SHA-256 `728cd4ca4da57ef3119cebf2f8d9d35ee7a1ce820b586eb06038267f7baa9c95`;
`host-server-022.jar` (2 classes), SHA-256 `6b44994cad1a65bb087d7b874ec6e65e54f07bc84ba4c6e343b01ad0775be0e5`.
LiveMailState ABI changed; use this driver with 0.1.22 production classes, preserving older matching driver/package
pairs. These existing native fixtures do not prove physical bulk-selection UI acceptance.

No 0.1.22 transfer or installation has started. Latest verified phone version remains 0.1.18; partial 0.1.19 is
preserved. Do not run the old hardcoded 0.1.19 upgrade script against a newer package. Phone blocker b-nnxn and provider
decision q-ar74 remain partial gaps. The full roadmap remains active with independent later work still available.


## Foreground queued-flag synchronization (0.1.23 development source)

The mailbox now offers **Sync queued changes** when local read/star assignments remain. It processes a snapshot of
changed folders in the current account, or all configured accounts in unified scope. Duplicate account/folder targets
run once. Each folder uses the existing trusted foreground session, UID-validity checks, acknowledgement identities
and readback. A failed folder or account retains unacknowledged work while later independent folders continue; a
remaining queue after refresh also counts as unresolved. The completion notice reports successful and unresolved
folder counts without exposing server errors. No SMTP outbox submission is triggered by this action.

The action runs on IO with an owned cancellable MailOperation even when the current view is not a search. Cancel
closes the active owned transport and prevents starting another folder. Coroutine cancellation propagates unchanged.
It follows foreground network-close behaviour rather than being registered as an uncancellable local-write flush.
A zero-work race reports that nothing remains queued. This remains an explicit foreground action, not a background
scheduler or lifecycle exemption.

Tests exercise mixed failures across accounts/folders, duplicate targets, empty queues, pre-cancellation and transport/
coroutine cancellation; actual controlled TLS IMAP flags replay after SQLite reopen now runs through this API.
Mailbox tests check unified account scope, remaining queue/count/notice updates and cancellation of a blocked owned
transport; rendered tests cover the action and its busy guard. Existing per-folder sync and offline queue tests remain
in scope. No new dependencies, permissions, schema or shared Android changes. Source advances to 0.1.23 after the
immutable 0.1.22 build. Native/physical/provider acceptance, background replay and large-mailbox performance remain open.

A fresh bounded SSH check to the FP4 again timed out. No phone transfer or installation was started. Provider
registration decision q-ar74 and phone blocker b-nnxn remain partial gaps; independent roadmap implementation continues.

Verification: full 313 app tests, zero failures/errors/skips; `detekt`, `installDist` and `spotlessCheck` pass.
Focused log `/tmp/thunderbird-flag-sync-focused.log`; full log `/tmp/thunderbird-flag-sync-full.log`; XML
`app-ubuntu-touch/build/evidence/queued-flag-sync/`. Full command: JDK21/Android SDK environment, `./gradlew
:app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Scoped documentation checks pass. No Android lint or device target exists in this JVM app; Android/shared source was
unchanged and prior shared-engine Android assembly evidence is unchanged. No native queued-sync test was run.


### Verified 0.1.23 ARM64 package

Candidate25 built from clean `7373dbe8bb9a27fd9920f3b1d1cfa7e91cce5d16`; embedded SOURCE-CHANGES is empty.
Artifact: `app-ubuntu-touch/build/ut-candidate-25/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.23_arm64.click`.
Size 147645634 bytes; SHA-256 `bd20e537af42e93c30a248968d75ea3268678560e2ad4f6efdeb8f3cc9186a4b`.
Click review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-023-click.log`). The same pinned builder
and verified candidate5 ARM runtime were reused; cold reproducibility remains unproven.

Matching frozen drivers: `build/evidence/oauth-native-023/candidate-023-probes.jar` (62 classes),
SHA-256 `c8a7f2262ddc66d0fa513793647e537979100f66feef98add970997718b496a3`;
`host-server-023.jar` (2 classes), SHA-256 `a0834d9c72ec14c9f593463deab61f1d11e1aaaaca3b1765e40452d7243ab769`.
Use these with installed 0.1.23 classes; prior driver/package pairs remain preserved. Existing native fixtures do not
prove the new queued-sync button's physical UI acceptance.

No transfer or installation of 0.1.23 was attempted. Latest verified phone version remains 0.1.18 and partial 0.1.19
is retained. The old upgrade script is hardcoded to 0.1.19; adapt and validate the selected newer version, source/hash
and database backup before using Phone Manager. Phone b-nnxn and OAuth registration decision q-ar74 remain partial
gaps. Provider scope/identity validation code gates also remain required independently of registration approval.


## OAuth granted scopes and permission gates (0.1.24 development source)

The token client now retains exact case-sensitive scope sets, rejects malformed/duplicate-key scope responses and
applies bounded syntax/count/size validation. Initial exchange uses an explicit returned scope, or the requested set
when omitted. It separately tracks the original grant and current access-token scopes: refresh omission inherits the
original grant, not a previously narrowed access token. Unknown legacy grants stay unknown. This follows
[RFC 6749 sections 3.3, 5.1 and 6](https://www.rfc-editor.org/rfc/rfc6749.html#section-3.3); access and identity tokens
remain opaque and no JWT claims are trusted.

Protected token format v3 appends current/original scope metadata, with backward reading of v2 as unknown scopes.
No SQL migration or plaintext scope store was introduced. Scope sets are immutable snapshots; up to 64 entries,
512 ASCII characters each and 3072 aggregate scope characters per set keep both maximum 16 KiB tokens plus metadata
within the existing 40000-byte protected envelope. Invalid stored counts, duplicates and syntax fail closed. Legacy
v2 tokens cannot satisfy a configured minimum until scope metadata is established; old app versions cannot read v3,
so rollback may require reauthorization. Protected-state downgrade/native migration acceptance remains open.

Provider specifications can declare required access scopes separately from requested consent/helper scopes. Policy
must be explicitly chosen and verified for each real provider before enablement; the default empty policy in existing
synthetic fixtures is not a real-provider acceptance result. Real Google/Microsoft policies, canonical scope spelling,
identity validation and registrations are still unresolved; the production registry remains empty.

Create/replace validates the required scopes before journaling or changing protected credentials, preserving an old
account/grant if permission checks fail. Both protocol providers pass an immutable policy snapshot to the shared
coordinator. Fresh cached access must meet that policy. After refresh, rotated secrets are persisted before checking
access permissions; an insufficient token is denied without discarding its replacement refresh token. Invalidation
preserves scope metadata. Caller mutation during a suspended refresh cannot weaken the check.

Tests cover explicit/omitted/narrowed scopes, original-grant inheritance, case sensitivity, invalid JSON/type/syntax/
escaped duplicate keys and response-byte clearing, v2 read/v3 rewrite, immutable snapshots, maximum protected payload,
malformed stored metadata, pre-commit rejection, cached denial, post-rotation denial and recovery, policy mutation and
both actual IMAP/SMTP token-provider bindings. No real provider registration, client ID, permission, dependency or
shared Android source was changed. Source advances to 0.1.24 after immutable 0.1.23. Real-provider/phone acceptance and
verified identity binding remain required; scope checking alone does not complete either provider flow.

Verification: full 324 app tests, zero failures/errors/skips; `detekt`, `installDist` and `spotlessCheck` pass.
Focused log `/tmp/thunderbird-oauth-scopes-focused.log`; full log `/tmp/thunderbird-oauth-scopes-full.log`; XML
`app-ubuntu-touch/build/evidence/oauth-scopes/`. Command: JDK21/SDK environment, `./gradlew
:app-ubuntu-touch:spotlessApply :app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist
:app-ubuntu-touch:spotlessCheck --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Scoped documentation checks pass. No Android lint/instrumentation target exists for this JVM app; shared Android
source is unchanged and prior shared-engine Android assembly evidence remains applicable. Native protected-format
migration, real provider flows and physical permission/error handling were not tested.


### Verified 0.1.24 ARM64 package

Candidate26 built from clean `a5e21f5529354ac1f3fcd4882bd18041039919b9`; embedded SOURCE-CHANGES is empty.
Artifact: `app-ubuntu-touch/build/ut-candidate-26/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.24_arm64.click`.
Size 147659328 bytes; SHA-256 `7a955401bc7c21ab81974b4b9b89e123eec16ef68a8782a7064be8403146709c`.
Click review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-024-click.log`). The same pinned builder
and verified candidate5 runtime were reused; cold reproducibility remains unproven.

Matching frozen drivers: `build/evidence/oauth-native-024/candidate-024-probes.jar` (62 classes),
SHA-256 `eecf254563bc5c12a708c766196fa90308893b4827c55fe93f0a5bd8061506a4`;
`host-server-024.jar` (2 classes), SHA-256 `80b7536baa8de683925bccd5e30f90b83f476713a4f68fde2ab7763a2a251efc`.
OAuthTokens and token-provider constructor ABIs changed, so use the matching driver/production classes. Existing native
fixtures do not establish the new format migration or every scope-policy branch; explicit native acceptance remains.

No 0.1.24 transfer or installation was attempted. Latest verified phone version remains 0.1.18, and partial 0.1.19 is
preserved. The old upgrade script still targets 0.1.19; adapt and verify the selected version/source/hash and database
backup before Phone Manager. Phone b-nnxn and registration decision q-ar74 remain partial gaps. Identity verification
and real provider policy selection remain open independently of scope metadata support. No real registration is enabled.


## Verified-email profile identity binding (0.1.25 development source)

A provider-approved HTTPS profile policy can now require a non-empty stable `sub`, an `email` exactly matching the
configured account address and an actual JSON boolean `email_verified: true`. This is an OAuth resource-profile
adapter, not general ID-token verification or a claim of complete OpenID Connect compliance. No JWT claims are decoded
or trusted. Real Google/Microsoft integration still needs approved endpoint/claim/scope semantics and its own acceptance;
Microsoft Graph mail/UPN fields are not assumed to satisfy this verified-email contract. Reference contracts reviewed:
[OpenID standard claims/UserInfo](https://openid.net/specs/openid-connect-core-1_0.html#UserInfo) and
[Google identity documentation](https://developers.google.com/identity/openid-connect/openid-connect).

The registry owns the endpoint; account fields cannot supply it. Main Koin injects a verifier using the existing JDK
HTTPS client, with default trust/hostname checks, no redirects, bounded response/deadline/cancellation and a bearer
header rather than a token URL. Missing verifier wiring fails closed when a profile policy is configured. The parser
rejects missing, duplicate/escaped duplicate, false, mistyped, malformed or oversized claims, and clears reply bytes.
The profile adapter currently requires exact address spelling, printable ASCII subjects up to 255 characters and valid
UTF-8 email up to 320 bytes. Case/alias normalization and other provider identity contracts require explicit follow-up.

Verification runs after PKCE/code exchange and scope checks, before the protected-commit phase. Transient proof binds
the pinned endpoint, address and exact access/refresh credential pair. Account creation/replacement checks it before
journaling or writing secrets. A previous subject under the same approved endpoint cannot silently change during
reauthorization. Ordinary settings edits cannot change a verified address or separate IMAP/SMTP usernames from it;
renaming remains possible. Cancellation during profile retrieval prevents protected commit, preserving prior state.

Protected token format v4 stores endpoint digest, subject and email beside token/scope data. Both v2 and v3 remain
readable with unknown identity; no identity is inferred from an account address. Cached mail-token access under an
identity policy requires the protected binding, and refresh/invalidation preserve it within the owned grant. Both
protocol-provider bindings enforce this requirement. Subject data is absent from mail SQLite. The maximum token/scope/
identity fixture is 39997 bytes, within the existing 40000-byte envelope. Older apps cannot read v4; protected-state
rollback/native migration and actual service acceptance remain open.

Two pre-delivery regressions were retained: Json's ordinary Boolean decoder accepts quoted `"true"`, and proof that
bound only the access token could be reused with a different refresh token. Both dedicated tests failed before fixes
(`/tmp/thunderbird-identity-regressions-before.log`, `build/evidence/oauth-identity/proof-types-before.xml`). Strict typed
claim decoding and length-framed credential-pair hashing fix these cases. Earlier profile-type failure evidence is
`profile-types-before.xml`. Additional tests cover HTTPS transport/redirects/default-trust rejection, mismatched account
and token proofs, protected restart/refresh/invalidation, legacy formats, maximum payload, subject pinning, settings
mutation, full browser/exchange/profile/commit and cancellation while profile retrieval is blocked.

No real provider registration, client ID, dependency, permission, SQL schema or shared Android source was changed.
The production provider registry remains empty; synthetic providers without an identity policy do not establish real
provider acceptance. Provider-specific policies, full OIDC requirements where applicable and physical/native/provider
journeys remain open. Source advances to 0.1.25 after immutable 0.1.24; older native drivers must not be mixed with the
changed token/provider/manager ABIs.

Verification: full 338 app tests, zero failures/errors/skips; `detekt`, `installDist` and `spotlessCheck` pass.
Focused log `/tmp/thunderbird-identity-integration.log`; full log `/tmp/thunderbird-identity-full.log`; XML
`app-ubuntu-touch/build/evidence/oauth-identity/after/`. Command: JDK21/SDK environment, `./gradlew
:app-ubuntu-touch:test :app-ubuntu-touch:detekt :app-ubuntu-touch:installDist :app-ubuntu-touch:spotlessCheck
--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.
Scoped docs checks pass. Shared Android sources are unchanged; this JVM app has no Android lint/instrumentation task,
and prior shared-engine Android assembly evidence remains applicable. Native/physical/provider acceptance remains open.
A bounded SSH recheck succeeded and reports installed 0.1.18; delivery recovery is being investigated before claiming
any transfer or installation. No 0.1.25 phone installation has run at this checkpoint.


### Verified 0.1.25 ARM64 package and recovered delivery

Candidate27 built from clean `a1948b0768d12899c18ae8ff04852e29e769ab44`; embedded SOURCE-CHANGES is empty.
Artifact: `app-ubuntu-touch/build/ut-candidate-27/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.25_arm64.click`.
Size 147708118 bytes; SHA-256 `b5d5f9b8edd3d5c5d5d0b2939bff88fa5dc7e4a6e528178917ab57437776613f`.
Click review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-025-click.log`). The same pinned builder
and candidate5 runtime were reused; cold reproducibility remains open.

Matching frozen drivers: `build/evidence/oauth-native-025/candidate-025-probes.jar` (62 classes),
SHA-256 `68f186d55f3ada455cf756e85bd26cbef7d1ede3e69368477d9cd7c043e04dd3`;
`host-server-025.jar` (2 classes), SHA-256 `f8f11d99934b26256d96df63ead470ea1aec01b2a00fa2ae5aac0660b22a9958`.
Use matching 0.1.25 production classes; token/provider/manager ABIs changed. These existing native fixtures do not
establish all new profile-identity paths or protected-format migration acceptance.

Phone SSH has recovered: UID32011 and installed 0.1.18/f18314918d were independently rechecked. The phone remains on
mobile data with no Wi-Fi IPv4 and has20GB free. Its old 0.1.19 partial is125337600bytes and remains preserved. A new,
separate 0.1.25 rsync upload is running, with partial/resume verification and keepalives; log
`/tmp/thunderbird-025-transfer.log`. Transfer speed is currently about40–50kB/s. No second copy is started while this
transfer is live. Connectivity blocker b-nnxn is resolved; transfer, full remote hash, private SQLite backup, installation
and native acceptance remain pending. Main0.1.18 is still running; a normal close will be requested after staging,
without force-killing or bypassing its draft/save close handler. No OS/modem/network or whole-fleet changes were made.
