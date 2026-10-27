# Ubuntu Touch port roadmap

Status: active roadmap for an unofficial port, updated 2026-10-27. This is not an upstream commitment.

The goal is a fully featured, reliable mail application on Ubuntu Touch, reusing Thunderbird's Kotlin mail engine,
shared Compose UI and design system. The implementation route is a Compose Desktop JVM application packaged as an ARM64
Click, following the sibling `mobileapp` approach. The installed offline demo proves the platform path; it is not the
mail application endpoint. Work continues through live mail, daily-use features, background delivery, feature parity
and a reproducible supported release.

## Current position and completion target

| Area | Verified position | Remaining work |
| --- | --- | --- |
| Platform | 0.1.27 installed on FP4; 0.1.4 sizing/typing confirmed; enforcing-profile dispatcher/schema/cancellation/recovery probes pass | Physical secure-input UI, rotation and lifecycle checks |
| Mail | Protected accounts, cached mailbox and composer/outbox UI; confined TLS IMAP/SMTP, model-driven cancellation and fresh-JVM recovery pass | Physical/provider journeys, authenticated interruption coverage and daily-use features |
| Engineering | 357 app tests, 102 SMTP tests; prior 863 shared mail tests; 108 Bolt JVM tests and prior Bolt Android compile; ARM64 package/Click review pass | Provider/physical-UI suites, CI and existing Bolt static-analysis baseline gap |
| Source delivery | Installed 0.1.27 embeds clean `84f80558a8`; OAuth setup and mail-session integration verified on host; large protected-payload native read fails | Further roadmap implementation and reproducible CI |
| Phone delivery | Phone Manager 0.1.27 install verified; local fleet `e1adc10` pins its exact artifact/hash | Physical foreground, broader cancellation and lifecycle verification |

The completion target includes account setup and secure credentials, IMAP/SMTP and agreed additional protocols,
multiple accounts and unified views, offline storage, folders and message operations, search and threading,
plain-text/HTML reading and composition, attachments, drafts, authentication, encryption/signing, background delivery,
notifications, accessibility and reliable upgrades. A feature checklist below turns this into release gates.
Experimental features and Android-specific integrations must be assessed against the pinned source baseline rather
than guessed. A feature is not removed from scope merely because its Android implementation cannot run on Linux.

## Implementation evidence

The preview builds as an ARM64 Click and passes Clickable review. Version 0.1.27 is installed on the FP4 with account
setup, cached mailbox and composer/outbox UI; the user previously confirmed full-screen sizing and on-screen typing for 0.1.4. New secure-input acceptance
remains open. Confined small-credential persistence and upgrade access pass through SignOn; larger payload reads time out and remain a release blocker. See the
[dependency audit](ubuntu-touch-dependency-audit.md) and [milestone 1 evidence](ubuntu-touch-m1-evidence.md)
for the initial scope, and [milestone 2 evidence](ubuntu-touch-m2-evidence.md) for current build/device results and gaps.
Controlled-server receiving/sending and fresh-process recovery pass under phone confinement; the physical
user-facing receiving/sending journey remains unfinished.

## Starting point

The repository already has useful foundations:

- The [KMP](../../build-plugin/src/main/kotlin/thunderbird.library.kmp.gradle.kts) and
  [KMP Compose](../../build-plugin/src/main/kotlin/thunderbird.library.kmp.compose.gradle.kts) conventions configure
  Android and JVM targets.
- The mail protocol and backend modules include JVM implementations for IMAP and SMTP.
- The [demo backend](../../backend/demo/README.md) provides offline folders and messages for an initial UI.
- Newer features contain shared Compose code, but legacy application wiring and some feature implementations still
  depend on Android. A JVM target on a module does not establish that a complete application can run on Linux.
- The [database architecture](../architecture/database.md) describes Android/JVM desktop driver support and shared
  repository boundaries as a proposed direction. There is no `core:database` implementation in this checkout.
  The port's app-local SQLite adapter and upstream integration gap are recorded in the
  [milestone 2 evidence](ubuntu-touch-m2-evidence.md).

Compose Desktop supports Linux ARM64; see the [platform compatibility documentation](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html).
Ubuntu Touch integration still requires device validation, especially Xwayland, input and confinement.

The local reference implementation is `mobileapp/ubuntuTouchApp`, particularly `README.md`, `build-hybrid.sh`,
`arm-packager.Dockerfile`, `coreapp-launch.sh` and `inventory-package.py`. Record its exact source commit when adapting
it. Its packaging and launcher are useful precedents; its Bluetooth permissions and lifecycle changes are specific
to that application and should not be copied into the mail client.

## Checkpoint A Preserve the working implementation

Before expanding the demo into live mail:

- Review and commit the current app, packaging, tests and evidence on the feature branch; push it to the project remote.
- Record source commit, package hash, installed version and test results together. Uncommitted local files are not a
  completed source-delivery checkpoint.
- Register the chosen preview in the phone-fleet manifest with matching version/path/hash after reviewing that entry.
  Verify desired and installed state agree without converging unrelated applications.
- Complete or explicitly retain the outstanding physical input and relaunch checks. Keep the installed working
  preview available while developing the next candidate.

Acceptance: source is recoverable from the remote, another checkout can locate the build instructions, and the
preview's source/package/fleet/device versions can be traced to one another. The source-preservation and alignment
checkpoint is evidenced for 0.1.5 in the milestone 2 record. Physical input/relaunch checks remain explicitly open
under the device gates; a clean-source package is not proof of those checks or cold-cache reproducibility.

## Application structure

The `app-ubuntu-touch` entry point uses Compose Desktop and Koin to assemble
shared features with Linux platform implementations. Keep portable behaviour in existing common/JVM modules and put
platform integration behind the relevant API contracts. Retain the existing design system and MVI conventions.

Follow the [API and internal dependency rules](adr/0009-api-internal-split.md) and
[white-label architecture](adr/0006-white-label-architecture.md). Before implementation, define how the new entry point
fits the permitted composition modules; update the relevant architecture decision and build enforcement if needed.
Do not route dependencies through legacy Android modules merely to reach a reusable implementation.

Use JVM bytecode plus target-native JVM, AWT and Skiko libraries. Kotlin/Native is not required for this route.
The initial target is Ubuntu Touch 24.04 ARM64, matching the reference packaging approach. Record a specific device
and OS image for acceptance; other architectures and images remain separate targets.

## Milestone 0 Dependency and integration audit

Establish the smallest viable JVM application dependency graph before broad changes.

- Trace the design system, navigation, message list, reader and demo backend through their JVM variants.
- Identify missing platform implementations and Android dependencies, including transitive dependencies.
- Assess shared storage, account configuration, credential handling and application composition.
- Confirm compatible Kotlin, Compose, Gradle, JDK and ARM64 native dependency versions.
- Record the target device/image, build host, package identifier and unofficial branding requirements.

Acceptance: a dependency map and bounded list of required changes, with a selected shared screen that can be compiled
and launched on desktop Linux. Decide whether the first mail flow can use existing shared features or needs focused
portability work. Keep the scope limited to that flow.

## Milestone 1 Installable offline demo

Build the new application shell, reuse the existing design system, and wire the demo backend to folder and message
views. Provide a basic reader using plain text initially if HTML would delay proving the application path.

Adapt the reference hybrid packaging pipeline:

1. Compile JVM bytecode on the native build host and stage the ARM64 runtime dependency set.
2. Run ARM64 JDK `jpackage` in an isolated ARM64 container, using emulation only for target packaging when necessary.
3. Verify the launcher, JVM, AWT and Skiko native libraries are ARM64; preserve dependency order and avoid JAR filename
   collisions when staging.
4. Bundle the app image and JRE in a Click with an app-specific desktop file, launcher and AppArmor manifest.
5. Use app-writable data/cache paths and validate Xwayland display discovery under confinement.

Acceptance: a clean build produces a Click that installs and launches on the named device, displays demo folders and
messages, supports scrolling/back navigation and normal on-screen keyboard input, and relaunches after reboot.
Run Clickable review and record unresolved findings. Preserve dependency notices and a build inventory.

This milestone proves application delivery. It does not establish live-mail support or store eligibility.

## Milestone 2 One account with foreground mail

Deliver a useful vertical slice: configure one IMAP/SMTP test account, synchronise folders and messages, read mail,
compose a plain-text message and send it while the application is active.

- Reuse the JVM mail engine and existing repository contracts.
- Integrate the shared database and attachment infrastructure where its JVM implementation is ready. Document any
  remaining gap before adding an alternative store.
- Implement Linux account settings and credential storage, including the available protected-storage mechanism.
- Keep TLS verification and certificate-error behaviour intact. Never log credentials, addresses or message content.
- Handle connection loss, cancellation and retries without duplicating sent messages.
- Persist account and message state across restarts, with explicit account removal behaviour.

Deliver this milestone as bounded work packages:

| Package | Deliverable | Acceptance evidence |
| --- | --- | --- |
| 2.1 Mail contracts and storage | Reuse shared account/mail repository APIs and JVM database drivers; add only necessary Linux adapters | Persistent account-scoped folders/messages; schema and restart tests; no Android runtime dependencies |
| 2.2 Account setup and secrets | Editable server settings, TLS configuration, protected credential storage and account removal | Controlled account can be configured, reopened and removed; unavailable secret storage fails clearly without a plaintext fallback |
| 2.3 Foreground receiving | Folder discovery, initial/incremental IMAP sync, body fetch, cancellation and error state | Controlled-server assertions compare actual server state with cached state across reconnect and app restart |
| 2.4 Composition and sending | Recipient/subject/body editor, persistent draft/outbox and SMTP delivery | Server receives intended message; offline queue survives restart; ambiguous SMTP outcomes do not trigger blind duplicate sends |
| 2.5 Phone vertical slice | Package and deploy the complete single-account journey | On FP4: configure account, sync, read, draft, send, restart and read cached mail offline |

Acceptance: the complete flow works against a controlled test server on the device, then an explicitly configured real
test account. Verify rejected credentials, invalid certificates, interrupted sync and send failure recovery. Do not
request or log production credentials to substitute for controlled tests. Measure package size, startup time and memory.
This milestone is in progress. Storage, protected setup and controlled-server protocol integration are implemented;
cached mailbox/composer UI, Cancel controls and persistent send history are implemented; required STARTTLS succeeds
on the confined phone with controlled fixtures. Host tests cover authenticated mid-body disconnect/cache recovery,
lost-APPEND-response reconciliation and bounded socket/worker cancellation;
blocked writes and the complete physical/provider vertical slice remain incomplete.

## Milestone 3 Daily use in the foreground

Expand from the vertical slice into the core mail experience:

- Safe HTML rendering with an explicit external-content policy and suitable Linux rendering integration. See the
  [HTML feasibility investigation](ubuntu-touch-html-feasibility.md) for reusable sanitisation, device evidence and required
  renderer/privacy experiments; no renderer has been selected yet.
- Attachment download/open/save through supported Ubuntu Touch file and content integration.
- OAuth browser handoff and callback handling for selected providers, with provider registration requirements resolved.
- Reply/reply-all/forward, quoting, signatures, sender identity, To/Cc/Bcc, autosaved drafts and a persistent outbox.
- Read/unread, star/flag, archive, trash/delete, move/copy, multi-select and folder refresh/management as supported by
  the upstream contracts. Reconcile offline changes after reconnect.
- Multiple accounts, unified inbox and account-scoped views, with strict storage/identity isolation.
- Local search, search within folders, server search where supported, sorting, threading and conversation navigation.
- Account settings, sync limits, cache retention, identities, import/export and removal/data-retention behaviour.
- Touch sizing, rotation, keyboard focus/selection, keyboard avoidance, links, accessibility and convergence behaviour.

To/Cc/Bcc editing, persistence and SMTP privacy are implemented in 0.1.12; host delivery/privacy and confined native
composer checks pass. Physical/provider delivery acceptance remains open.
Read/unread and star controls with durable offline assignments, UID-validity guards and explicit folder-sync replay are
implemented in 0.1.13. Host tests cover conflict/recovery and independent IMAP flags; confined phone reconciliation,
server-side flag assertions and fresh-JVM recovery pass. Other message actions, bulk operations and background replay remain open.
Reply/reply-all and explicit text-forward drafts are implemented in 0.1.14 for complete cached plain-text bodies. Actual
reader→composer navigation, recipient/privacy and threading tests pass; native reply editing/delivery and fresh-JVM
threading recovery pass. Full attachment/HTML forwarding, HTML-only responses, quote preferences and identities/signatures
remain open; text forwarding does not complete the full forwarding requirement. Reader/reply body extraction now uses
the engine's viewable-part classification so text attachments are not mistaken for the body.

Automatic Sent copies are implemented in 0.1.15, with per-account folder selection, separate delivery/copy state,
search before APPEND and explicit review before retrying an uncertain copy. Confined phone tests and independent
recipient/Sent mailbox assertions verify one delivery/copy; fresh-process recovery passes after server shutdown.
Host TLS APPEND-response-loss recovery also passes after database reopen; phone/provider interruption, blocked writes,
uncertain-copy retargeting and physical/provider acceptance remain open.

Cached folder search is deployed in 0.1.16: Unicode/case-insensitive literal terms span sender, subject
and cached viewable plain text; offline storage/model/UI tests pass. It excludes attachment bodies and HTML placeholders.
Confined native search and fresh-process recovery after server shutdown pass. Account/unified scopes, server search,
filters, HTML indexing and large-mailbox indexing/pagination/cancellation performance remain required; this slice does not complete the search gate.

Account-wide and unified-account cached search are deployed in 0.1.17, with contextual account/folder/UID results,
reader/flag/reply routing and Back restoring the original search account/folder. Confined model and reopened SQLite
checks pass, as does fresh-process offline search after server shutdown. Physical UI acceptance for these scopes,
server search, filters, HTML indexing and large-mailbox performance remain required.

Cached sorting is implemented in 0.1.20 development source: newest/oldest, subject/sender, unread-first and
starred-first modes apply to folder and account/unified search results without network access. Host model, SQLite
and rendered UI checks pass, including stable identity ties and resetting a scrolled list after changing order.
Selection survives navigation and restarts through an atomic private preference file, with explicit save-failure
feedback and retry. Locale-specific collation, native
phone acceptance and large-mailbox performance remain open; 0.1.20 ARM64 Click is built and reviewed, with phone installation/acceptance pending.

Version0.1.26 adds independent persistent sort choices for each account/folder, account-wide cached search and unified
search. Existing global preferences remain the default for views without a saved choice. Private atomic sidecar files
contain only the sort enum; account/folder identifiers are hashed in filenames. Host restart/navigation, legacy-default
and save-failure/retry checks pass, including a fail-before cross-folder regression. The reviewed ARM64 package is built;
confined native writes and fresh-JVM recovery pass on installed0.1.26. Physical acceptance and stale preference-file retention remain open.

Reader attachment names, MIME types and counts are implemented in 0.1.21 development source for complete cached
messages, including HTML-only bodies. Incomplete messages request full download; shared sanitization and classification,
account isolation and rendered reader checks pass. Content Hub Open/Save, outgoing attachments, orphan cleanup and
native/physical acceptance remain open. The 0.1.21 ARM64 Click is built and reviewed, with installation pending.
Listing metadata does not complete attachment handling.

Cached multi-select and bulk read/unread/star/unstar are implemented in 0.1.22 development source across folder,
account and unified cached results. Full account/folder/UID identities, a visible 1000-selection bound, atomic offline
queue updates and retry/close handling are covered by model/SQLite/rendered checks; controlled TLS IMAP reconciliation
passes after database reopen. Unified counts cover all accounts and discard review is per account. Other bulk actions,
server-wide selection, background replay and phone/physical/provider acceptance remain open. The 0.1.22 ARM64 Click
is built and reviewed; installation remains pending.

Explicit foreground queued-flag synchronization is implemented in 0.1.23 development source. It visits changed folders
in the selected account or all configured accounts in unified scope, isolates folder failures, retains unresolved work,
reports counts and supports owned-transport cancellation. Controlled IMAP replay after database reopen and mailbox/UI
checks cover this path. The 0.1.23 ARM64 Click is built and reviewed; installation remains pending. Background scheduling,
native/physical/provider acceptance and large-mailbox performance remain open.

OAuth scope metadata and configurable minimum-permission gates are implemented in 0.1.24 development source across
exchange, protected storage, account commit, cached access and refresh. Rotated refresh tokens persist before a narrowed
access token is denied. Legacy unknown scopes are not guessed. Real-provider scope policies/canonicalization, identity
verification, registration and phone/provider acceptance remain open; the production registry is still empty. The
0.1.24 ARM64 Click is built and reviewed, with installation pending.

A provider-pinned verified-email profile adapter and protected identity binding are implemented in 0.1.25 development
source across browser authorization, account commit, restart, refresh and both mail-token providers. Mismatched profile,
subject changes, credential-pair reuse and ordinary authenticated-address edits fail closed. Legacy identity stays
unknown. Real Google/Microsoft claim policies and any required OIDC validation remain open; no provider is enabled.
Native protected-format migration, address/alias normalization and physical/provider journeys remain required.

Cached message-source inspection is implemented in0.1.27 source. The reader opens bounded32KiB byte pages directly
from SQLite, with explicit binary/control escaping, total/range counters, incomplete-cache labels and previous/next
navigation across all cached bytes. It works offline with locked credentials, preserves account/folder/UID routing
from unified results and returns to reader before search. Host binary/paging/isolation/model/rendered tests pass;
ARM64 Click is built/reviewed and installed27; confined native binary paging, isolation and fresh-JVM recovery pass.
Physical source-reader acceptance, exact-byte export and large-MIME/native-memory behaviour remain open. Source inspection does not complete HTML/privacy, attachment or export gates.

Version0.1.28 adds the exact-byte cached message export core: complete messages are copied to private0600 `.eml`
leases in bounded chunks under a consistent SQLite transaction, preserving binary bytes without MIME reserialization.
Host tests cover replacement during export, cancellation, file failure, account/folder/UID isolation and lease lifetime;
ARM64 Click is built/reviewed. This core is not connected to a user Save flow. Content Hub adapter/picker, transfer
lifecycle/recovery, explicit permission approval and native/physical export acceptance remain required.

Split delivery into reader/privacy, composer/drafts, folder/message actions, multi-account/search, provider authentication
and platform integration work items. Each item needs its own server/fixture tests and phone journey; one large UI change
is not an acceptance strategy.

Acceptance: end-to-end journeys pass on the device, including HTML privacy, attachment handling, OAuth
success/cancellation/expiry, draft recovery, queued operations, unified inbox/search and account switching/removal.
Exercise large mailboxes, long/unbroken subjects, non-ASCII text, multipart messages and malformed fixtures.
Search and security features are part of the completion checklist; they cannot be silently deferred out of the final product.

A source-only cache resource fix now releases freshly parsed MIME body files after local summary/content/search/reply
projections, with a fail-before regression (42 retained files across three rounds) and 137 passing app tests. It awaits
deployment. Parser-failure/orphan-cache handling, shared transport ownership and large-session soak remain open.

## Milestone 4 Background sync and notifications

Ubuntu Touch suspends applications outside the foreground or when locked; see
[UBports app suspension guidance](https://github.com/ubports/docs.ubports.com/blob/master/userguide/dailyuse/location.rst).
An open IMAP connection in the UI process alone cannot provide reliable background delivery.

Compare supported push, permitted background execution and a separate synchronisation service against device policy,
battery use and deployment requirements. Select a mechanism in a technical design before implementing it. A privileged
companion or lifecycle exemption, if required, creates a distinct installation/support path that must be documented.

Define reconnection, suspend/resume, reboot recovery, notification permissions, lock-screen privacy and duplicate
suppression. If a separate process is selected, establish storage ownership and a narrow authenticated IPC contract;
avoid concurrent unsynchronised writes to the mail database.

Acceptance: controlled mail arrival tests pass while foregrounded, backgrounded and locked, after network changes,
suspend/resume and reboot. Record delivery latency, missed/duplicate notifications and battery impact over a defined
test period. Foreground-only builds remain clearly labelled until this gate passes.

## Milestone 5 Feature parity and security completion

The [reviewed feature inventory](ubuntu-touch-feature-inventory.md) maps current gaps to verified baseline source
anchors and adapter/test work. It remains incomplete until individual legacy commands/settings and all required
capabilities have coverage. Continue the source-backed feature inventory against Thunderbird base
`de8a03268415ce8e0c8b0327d3bbcb6b40a06a91`, updating it deliberately when rebasing. Map every user-visible capability
to a reusable implementation, an Ubuntu Touch adapter and its tests. Mark each row implemented, failing, not started,
experimental upstream or platform-specific, with evidence. Unresolved rows remain open work, not implicit exclusions.

| Capability | Required completion gate |
| --- | --- |
| Accounts and protocols | IMAP/SMTP complete; assess and implement POP3 parity, and explicitly classify the baseline's JMAP/experimental capabilities |
| Provider authentication | Supported provider matrix; password/app-password and applicable OAuth flows, refresh, expiry, revocation and cancellation verified |
| Message reading | Plain text, sanitised HTML, remote-content controls, inline images, MIME/charset handling, safe link handling and raw-message inspection/export |
| Composition | Reply/reply-all/forward, recipients, identities/signatures, attachments, drafts/outbox, send errors and supported HTML/plain-text modes |
| Organisation | Unified inbox, folders, flags, bulk actions, sorting, threads/conversations and offline operation reconciliation |
| Search | Cached search, folder/account/unified scope and supported server search with cancellation and large-mailbox behaviour |
| Security and privacy | TLS validation, protected secrets, safe rendering, notification privacy and no secret/message-content logging |
| Encryption and signing | Replace Android-only OpenPGP integration with a reviewed Linux-capable implementation; verify key management, signing/verification and encrypt/decrypt journeys |
| Storage and migration | Retention, attachment/cache limits, corruption/error handling, backup/export/import and versioned migrations; never silently delete mail on an error |
| Mobile usability | Maliit composition/deletion/selection, secure-field hints, blur/refocus, keyboard insets, touch/scroll/back, orientation, accessibility and supported external-display/input behaviour |
| Background delivery | Agreed supported mechanism, reconnect/reboot recovery, outbox processing, notification actions and measured battery/latency |
| Platform-specific integrations | Inventory Android widgets, shortcuts, sharing/contacts and any other baseline integrations; implement the UT equivalent or obtain an explicit scope decision |

For OpenPGP, establish the key-provider and crypto-engine boundary before implementation. Android intents/services do
not carry across to Linux, and the port must not substitute unreviewed cryptography. Use known test vectors and test
keys for tamper, wrong-key, missing-key and signature-failure cases. Resolve any other baseline security integration
through the same explicit inventory.

Acceptance: the feature matrix is backed by passing automated checks and named device journeys. No required capability
is marked not started or failing. Any experimental or platform-specific exclusion requires an explicit user scope
decision and a documented consequence. A smaller release may be labelled a preview; it is not the fully featured endpoint.

## Milestone 6 Reproducible builds and release readiness

- Automate host compilation, target-native packaging, architecture checks and Click review in CI.
- Pin the packaging base image and toolchain inputs; inventory dependencies, licences and required source materials.
- Demonstrate a clean-cache build without personal credentials or private build artefacts.
- Test fresh installation, upgrade from prior previews, database migration, rollback limitations and uninstall/data-retention behaviour.
- Keep source, CI artifact, signed/distributed package, fleet declaration and installed version aligned; verify each release from a fresh checkout.
- Run reliability/soak tests across large/multiple mailboxes, network transitions, disk pressure, interrupted writes and repeated suspend/resume.
- Set and measure startup, memory, idle CPU, storage, delivery-latency and battery budgets on the supported device/image.
- Publish supported device/image combinations, user documentation, known limitations and recovery instructions.
- Resolve distribution naming, branding and store review requirements for an unofficial port.

Acceptance: another developer can reproduce the documented build and installation. The milestone 5 feature matrix
and device/soak gates pass, required distribution review is satisfied, and a real release can be installed and upgraded
through the supported delivery path. A locally generated Click alone is not store approval or release completion.

## Delivery order and verification

The remaining route is **checkpoint A → milestone 2 → milestone 3 → milestone 4 → milestone 5 → milestone 6**.
The audit and core demo path are demonstrated; outstanding milestone 1 device checks remain visible alongside the next
work. Investigate background architecture during milestone 2 and security/provider integration during milestone 3 so
late platform constraints do not force a rewrite. CI, source backup and dependency inventories start now rather than
waiting until release.

For each bounded work item, record the source paths and dependency boundary, behaviour, acceptance criteria, tests,
exclusions, blocker/decision dependencies and final evidence. Completion requires implemented behaviour plus verified
results, and the commit/package/deployment references appropriate to the item. Keep one active implementation item
until it is completed or concretely blocked. Reporting the next milestone is not a substitute for doing authorised work.

Date estimates require the live-mail/storage audit, HTML-rendering choice, provider registrations, OpenPGP integration
choice and background-execution mechanism. These are planning risks with explicit investigation work, not permission
to drop features. Re-estimate after each gate using measured results.

For each implementation change, run the narrowest relevant Gradle build, tests and quality checks, plus Android
regression checks for changed shared code. Test boundaries with meaningful cases: persistence/recovery, protocol errors,
platform contracts and user journeys. Validate the packaged application on real hardware; desktop execution alone
does not cover Ubuntu Touch confinement or lifecycle behaviour.

Graceful-close draft flushing and immediate focus-loss saving are deployed in 0.1.11 and desktop/native-model verified;
physical suspend/lifecycle acceptance remains open (see milestone 2 evidence). Abrupt termination can still lose input
inside the debounce interval.

The immediate work is milestone 2: verify the cached mailbox and composer/outbox UI on device, complete
cancellation and recovery gaps, and verify receiving/sending on the phone. Keep outstanding source/package
alignment and physical checks visible as candidates advance; record concrete blockers and continue independent work.


The 0.1.18 enforcing-profile cache/resource/attachment, composer and synthetic socket/browser OAuth callback probes
pass. Browser callback delivery is established for the headless fixture; actual setup UI restoration/suspension and
real-provider OAuth remain open. Later host-tested token storage/exchange/refresh code is not part of this installed
artifact. See the latest milestone evidence for exact source, hashes and remaining integration work.
