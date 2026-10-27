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
