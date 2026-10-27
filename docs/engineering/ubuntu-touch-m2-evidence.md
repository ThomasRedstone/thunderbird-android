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
