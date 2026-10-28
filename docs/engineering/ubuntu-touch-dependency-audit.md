# Ubuntu Touch dependency audit

The original sections below describe the offline preview. The reuse reassessment at the end supersedes any inference
that the preview shell is the intended full-application architecture. Ubuntu Touch is the product target; JVM/Compose
Desktop is the compilation/runtime target, with the existing mobile UI retained and adapted.

The first port target is an offline Compose Desktop JVM application, `app-ubuntu-touch`, packaged for Ubuntu Touch
24.04 ARM64. It reuses `backend:demo`, `backend:api`, `mail:common`, `core:common` and the Bolt design system from the
local `components` build. It does not depend on Android application assembly or legacy message-list UI.

## Reuse and remaining gaps

| Area | Existing implementation | First preview choice |
| --- | --- | --- |
| Mailbox | JVM `backend:demo` with bundled EML resources | Refresh folders and synchronise into app-owned memory |
| MIME | JVM `mail:common` | Decode `text/plain` with `MessageExtractor`; explicitly label HTML-only messages |
| Design system | KMP Bolt with JVM target | Existing theme, text, buttons, surfaces and text field |
| Folder and message screens | Legacy list and application wiring depend on Android | Small app-local Compose screens over demo data |
| Reader | KMP API/implementation, with `androidMain` depending on `legacy:core` | Plain-text reader; no WebView or HTML engine |
| Storage | Shared repository/Room desktop architecture exists | In-memory demo only; assess shared persistence for live mail |
| Authentication and sync | Android account, OAuth and lifecycle integration remain | No accounts, credentials or network requests |
| Runtime | Compose Desktop, AWT and Skiko | Host bytecode compilation, ARM64 JDK packaging and explicit ARM64 Skiko |

Both KMP convention plugins already configure Android and JVM targets. JVM mail modules resolve the JVM variants of
shared contracts; Android source sets do not need to be compiled for this application. The full settings/build-plugin
configuration still requires Android tooling, so the documented host setup includes an Android SDK.

## Composition boundary

The entry point binds its own demo repository through Koin and keeps implementations internal to the application.
It introduces no dependency on another area's `internal` or `impl` module. Existing API/internal dependency rules remain
unchanged. This preview does not need the wider composition exception discussed in the initial Crane plan.

## Reference and target

The packaging reference is sibling `mobileapp` commit `0477ae19007767f9580f0308eb8f773815b6f6ca`, especially
`ubuntuTouchApp/build-hybrid.sh` and its packager. Adaptations preserve original dependency JARs, avoid duplicate-basename
collisions using SHA256 prefixes, stage classpath order in the manifest, and run `jpackage` with a target-native JDK.
Bluetooth grants, notification permissions and lifecycle exemptions are not inherited.

The unofficial application ID is `mail-demo.thomasredstone`, introduced at version `0.1.0` (current phone preview `0.1.4`), with an envelope icon and a visible offline
preview label. It is not branded as an official Thunderbird release. The initial framework is
`ubuntu-touch-24.04-1.x`; device acceptance is a separate gate from generating a package.

## Milestone boundary

This establishes the application/runtime/package path. Live IMAP/SMTP, persistent mail storage, protected credentials,
OAuth callbacks, HTML privacy, attachments and reliable background delivery remain later roadmap milestones.
Build and runtime observations are recorded in [milestone evidence](ubuntu-touch-m1-evidence.md).

## Device integration follow-up

Phone testing exposed and fixed confined launcher utilities, APP_ID preservation and a small Compose canvas. The port
now has a Kotlin Maliit adapter using the protocol already proven in `ut-platform`/Flutter: private peer discovery,
focus/reset/hide, preedit/commit/control-key callbacks and geometry. Its D-Bus JVM dependencies are confined to the new
application. See the evidence record for software checks and the remaining physical input acceptance.


## Reuse reassessment: shared mobile UI on Ubuntu Touch (2026-10-28)

### Target and reference

The intended result is the mobile Thunderbird application on Ubuntu Touch, built using the JVM target and adapted
platform services. Desktop build tooling does not authorize designing a separate desktop UI or treating the mature
mobile application's behaviour as a new implementation specification.

The read-only reference is `../mobileapp` at `0477ae19007767f9580f0308eb8f773815b6f6ca`:
`composeApp/src/desktopMain/kotlin/Main.kt:145` and Android `MainActivity.kt:51` call the same
`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/App.kt`. Platform entry points initialize services and host
that shared application. The reference worktree has unrelated dirty files; none were changed or included here.
Bluetooth policy, lifecycle exemptions, telemetry and other app-specific integration are not inherited.

Thunderbird does not currently have that complete shared `App()` boundary. This is a missing extraction/assembly
boundary, not evidence that its Kotlin framework is unusable. Android module placement alone does not prove a class
needs replacement. The port must inspect and adapt the actual mobile feature implementation and its dependencies.

### Verified reuse map

| Area | Evidence in this checkout | Consequence |
| --- | --- | --- |
| Mail engine/MIME | `backend/imap`, `mail/common`, `mail/protocols/imap`, `mail/protocols/smtp`; already used by the UT session adapter | Retain mature protocol/domain behaviour and its tests |
| Shared application assembly | `app-composition/README.md`, `AppCompositionModule.kt`, `feature/mail/MailCompositionModule.kt` | Existing KMP composition is a concrete integration candidate; current UT Main does not use it |
| Shared core services | `core/file` JVM implementations, `core/configstore/impl-backend`, `feature/account/core` | Supply UT paths/platform dependencies and reuse shared contracts/implementations where suitable |
| Existing export | `feature/mail/message/export/impl-eml/.../EmlMessageExporter.kt` delegates to shared FileManager | Reuse export policy where compatible; it does not replace private snapshot/Content Hub lease/recovery ownership |
| Reader | `reader/impl` common view model, CSS/settings providers and bottom sheet compile on JVM; `MailPart.jvm.kt` is only an interface | Reuse these pieces with a real MIME adapter; this is not a complete JVM message reader/HTML renderer |
| Mobile message list | `list/internal/.../MessageListScreenRenderer.kt`, `component/page/MessageListPage.kt`, `MessageListStateMachine.kt`; API `ReadMessageItem.kt` and `UnreadMessageItem.kt` | Substantial existing Compose UI/state machinery is an extraction candidate even though its modules currently use the Android plugin |
| List dependencies | `BuildSwipeActions.kt` depends on LegacyAccountManager; the view model uses Android-generated R strings; list modules pull core/android and legacy/mailstore | Separate resource/platform/data adapters instead of automatically replacing the UI/state machine |
| Mobile legacy integration | `legacy/ui/legacy/.../MessageListFragment.kt` imports Android Views/Fragments; `activity/MessageCompose.java` is an Android Activity | These hosts cannot run unchanged in a JVM Click; extract/adapt their mobile behaviour and preserve Android consumers |
| Storage/controller | `legacy/core/.../LocalStore.java` uses ContentValues/Cursor/SQLiteDatabase; MessagingController uses Context/SystemClock | A real platform/storage boundary is needed; the proposed database architecture is not an implemented drop-in shared store |
| Notifications | JVM `feature/notification/impl/.../NotificationModule.jvm.kt` is an empty module | Shared composition compilation does not establish native notification delivery |
| Platform ownership | Click/JRE/Skiko staging, Maliit, confinement, SignOn, Lomiri browser/Content Hub/lifecycle | These UT adapters remain relevant whichever shared mobile UI is used |

The list page and read/unread rows inspected here contain Compose code rather than Android View code. Their transitive
resources, preferences, account models, notification scaffold and data side effects still need tracing. A superficial
import scan is not a portability proof. Similarly, the reader's common state currently covers attachment/actions
behaviour, not fetching, displaying and managing an entire message.

### Compilation and test proof

No shared source, module structure, dependency graph or platform configuration was changed for this check:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew \
  :app-composition:compileKotlinJvm \
  :feature:mail:message:reader:impl:compileKotlinJvm \
  :feature:mail:message:export:impl-eml:jvmTest \
  :core:configstore:impl-backend:jvmTest :core:file:jvmTest :feature:account:core:jvmTest \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
```

`/tmp/thunderbird-reuse-audit-jvm.log`: BUILD SUCCESSFUL, 169 tasks, exit0. Existing shared JVM tests: EML export5,
configuration backend11, file operations17, account profiles3: total36, zero failures/errors/skips. Composition and
reader compilation pass. This proves these JVM variants exist and build; it does not prove complete DI resolution,
a working mobile screen, confinement, device usability or full feature parity. GitNexus's indexed repositories do not
include Thunderbird; this audit used direct source and Gradle evidence rather than another repository's graph.

### Correction and preservation

The original audit justified a small offline preview. It did not justify growing the app-local preview UI, settings,
storage and orchestration into the final full application without a further reuse/extraction decision. The current
app-local implementation is a tested prototype and potential adapter/reference material; it is not an agreed long-term
architecture. Neither its test count nor its source size gives a valid project-completion percentage. Earlier rough
percentage/schedule statements are not validated forecasts.

Retain the working phone installation, frozen Clicks, platform adapters and protocol/recovery evidence. Review custom
application logic against existing mobile implementations before adding more. A replacement implementation should
have an explicit source-backed reason and preserve upstream behaviour; an Android module boundary alone is insufficient.
Do not discard safety journals or replace protected/transactional behaviour with a simpler shared helper merely to
increase a reuse count. Any migration must preserve stored data and existing recovery guarantees.

Unfinished search UI/model work is preserved on `checkpoint/ut-search-ui-before-reuse-review`, commit
`b9bd9c4d2da1ae15105411a7a08ba899ea25fd4f`, based on `2ea82daa597684ef00f0290d00479851e5571e4a`.
The working files remain in place. Focused nine UI/model tests and compile/detekt pass; no full-suite or phone acceptance
is claimed for this unfinished checkpoint. It is not being promoted as the final mobile-search implementation.

### Next bounded portability prototype

Use the existing mobile message-list feature as the first meaningful slice. Trace `MessageListScreenRenderer` →
`MessageListPage` → the existing read/unread row composables, together with `MessageListStateMachine` and its API
state/events. Inventory each blocking dependency (Android R/resources, legacy account/data source, navigation/effects,
contacts/avatars, preferences, notification scaffold and lifecycle). Define platform contracts and a module/source-set
change plan before editing shared architecture, following AGENTS.md and the API/internal ADR.

The prototype must demonstrate the same canonical mobile UI/state sources consumed by Android and the UT JVM host,
not copied lookalike screens or a new desktop design. Start with controlled data/adapters, then use the existing
engine/cache bridge only where its identity and transaction guarantees remain valid. Shared composition should own
internal implementation wiring; the UT entry point should provide platform services and host the shared mobile flow.

Acceptance: compile the selected canonical mobile UI and state path for JVM; render the actual mobile message list
with controlled read/unread/starred messages and selection; prove events traverse its existing state/controller path;
run relevant existing Android tests/compilation and new JVM interaction tests; compare behaviour/layout; then package
and test on FP4 when reachable. Record the precise source moved/adapted and every remaining gap. A plain shared button,
bottom sheet or composition-only compile is not acceptance of this prototype or of the full application.

Exclusions: wholesale replacement of the preview, data deletion/migration, unrelated feature additions, permission or
OAuth changes, system-service changes, release claims, and unreviewed API/internal boundary violations. The full mail
roadmap remains intact. The 1–3 day prototype and subsequent schedule ranges are planning allowances, not evidence of
completion or a delivery promise; revise them from the prototype's observed blockers.


### Prototype step 1: canonical state-machine view model extraction

The message-list contract's only `core:ui:compose:common` import is BaseStateMachineViewModel.
That implementation imports Lifecycle ViewModel/viewModelScope, flows/coroutines, shared Logger and shared StateMachine;
all are already used/supported by `core:ui:contract`'s KMP target. Move this implementation to the existing shared
UI-contract MVI package, retain the old Android class name as a delegating subclass, and run its existing behaviour
cases against the shared implementation as well as the Android compatibility wrapper. Keep existing message-list
callers unchanged in this first step. No new module or dependency edge is needed: the Android UI module already
exports core:ui:contract. Later list extraction can import the canonical shared class directly.

Acceptance for this step is shared JVM behaviour tests and Android compatibility tests/compilation, not the whole
mobile-list prototype. Preserve event ordering, effect delivery, side-effect handlers, one-time event handling and
logging semantics. Do not redesign the controller or replace it with the preview's app-local view models. This follows
the requested shared-mobile-UI adaptation within existing API/internal boundaries; the whole screen's remaining
resource, account and legacy-service dependencies remain open.


Step1 implemented: `core/ui/contract/src/commonMain/.../mvi/BaseStateMachineViewModel.kt` now owns the existing
state-machine view-model behaviour. Its code is extracted from the Android module; the old
`core/ui/compose/common/.../mvi/BaseStateMachineViewModel.kt` delegates through a compatibility subclass, preserving
its constructor and existing Android call sites. No module/dependency edge was added, and no app-local replacement
controller was introduced. The eight existing behaviour cases also run from commonTest against the canonical class.

Verification (`/tmp/thunderbird-mobile-state-extraction.log`, exit0, 334 tasks):

```sh
./gradlew :core:ui:contract:jvmTest :core:ui:contract:detekt :core:ui:contract:spotlessCheck \
  :core:ui:compose:common:testDebugUnitTest :core:ui:compose:common:detekt \
  :core:ui:compose:common:spotlessCheck :feature:mail:message:list:api:compileDebugKotlin \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
```

Same JDK21/Android SDK environment as the audit command above. Shared contract JVM suite:18 tests; Android UI-common
host suite:17 tests; zero failures/errors/skips. These include the same8 state-machine cases on each path, covering
initial state, transitions, effects, one-time handling and side effects. Existing Android message-list API compiles
unchanged. No physical Android/UT device test or entire application build is implied.

The eight checkpointed app/search files were compared byte-for-byte with the checkpoint commit and remain unchanged.
They are not included in this extraction. The meaningful mobile-list prototype is still incomplete: its API module
is Android-only, including Android resource-backed sort labels, legacy account mapping, a fragment factory and the
old view-model import; its internal module still includes data/side-effect/platform dependencies. Next separate these
platform contracts/resource consumers and adapt the existing mobile page/rows/state machine with Android regression
coverage. Do not claim success from the base view-model extraction alone.


Concrete consumer check after the extraction: `:feature:mail:message:list:internal:compileDebugKotlin` also passes
(`/tmp/thunderbird-mobile-state-list-consumer.log`, exit0,229 tasks), compiling the actual Android message-list view
model/renderer dependency path in addition to its API wrapper. This remains compile evidence, not physical UI acceptance.


### Prototype step 2 plan: preserve the canonical message-list API on Android/JVM

Convert the existing list API module to the established KMP Compose convention. Move its canonical Compose rows,
state/events/contracts and preferences into commonMain, keeping the legacy sort mapper and FragmentManager factory
in androidMain. Point the contract at the shared state-machine base. LocalDeleteOperationDecider's LegacyAccount
import is used only in KDoc; remove that documentation-only dependency rather than replacing the interface.

Use an expect enum SortType with platform actual declarations: Android retains its existing R-backed labelResId getter
and entries; JVM supplies the same enum identities without fake Android resource IDs. Keep all Android resource names/
translations unchanged. Move existing sort-criteria tests into commonTest with kotlin.test.Test so the same cases can
run on Android and JVM. Validate JVM compilation/tests, Android actual/API and concrete list consumers, plus quality.
The full internal page/state-machine/source-side-effect extraction remains the next step; module conversion alone
is not a completed mobile UI or a reason to copy it into the UT shell.


Step2 working state at handover: existing API sources are now divided into commonMain/androidMain/jvmMain and the
KMP build compiles for JVM and Android; existing sort tests pass on JVM and the concrete Android list consumer compiles
(`/tmp/thunderbird-mobile-list-api-kmp.log`, exit0). The next combined interaction/Android/legacy check stopped at a
JVM UI assertion:14 JVM tests,1 failure. The canonical read row renders and dispatches open; the unread-row star test
expects a new favourite=true but receives the old false value. No fix has been applied yet. MessageItem's existing
FavouriteButtonIcon callback discards its new Boolean and forwards the old FavouriteIconButton element; Read/Unread
then forward that old field. This is a source-backed hypothesis for a pre-existing mobile callback bug, to verify/fix
with the failing regression retained. The old internal caller ignores the Boolean and toggles through an event, while
the legacy callback consumes it. Preserve this distinction. Android host and full legacy consumer acceptance remain
unverified because the combined invocation stopped at JVM tests. This working conversion is checkpointed separately;
it is not a passed/merged portability milestone or the full mobile-list prototype.


### Prototype step 2 completed and canonical callback regression fixed

Refactor `2e0595420f` converts the existing list API to KMP while preserving the canonical mobile rows, contracts and
state sources. Android resource names/translations and the SortType.labelResId getter remain available. The JVM variant
has no fake resource IDs. No custom app/search checkpoint files were changed.

The failing canonical UI test identified a real existing callback defect: FavouriteButtonIcon emitted the new Boolean,
but MessageItem discarded it and forwarded the old element. Forwarding `element.copy(favourite = it)` preserves the
new value for both ReadMessageItem and UnreadMessageItem. This fix and interaction tests are separate from the refactor.
Before-fix XML is retained in `app-ubuntu-touch/build/evidence/mobile-list/favourite-before.xml` and the original failure
log is `/tmp/thunderbird-mobile-list-api-interactions.log`.

`/tmp/thunderbird-mobile-list-api-interactions-2.log` passes (exit0,848 tasks): JVM15 tests and Android host15 tests,
zero failures/errors/skips, detekt/spotlessApply, and `:legacy:ui:legacy:compileDebugKotlin`. These include existing sort
cases plus canonical read-row open, read/unread star toggles in both directions and unread-row long-press selection.
Final spotlessCheck passes in `/tmp/thunderbird-mobile-list-api-final-check.log`. The original mobile row code is now
rendered/interacted with on both targets, not replaced by a preview lookalike. The complete internal mobile list page,
state machine/data side effects, UT assembly and device acceptance remain required; this is not the full prototype.
