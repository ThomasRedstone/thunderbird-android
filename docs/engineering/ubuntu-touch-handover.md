# Ubuntu Touch mail port handover

Updated 2026-10-29. Resume the full roadmap from the current source and evidence, with the corrected shared mobile UI architecture.

## Fresh session starting point

This section and the 2026-10-29 update supersede historical prototype failures and next-action lists below.

- Branch `feat/ubuntu-touch-demo`; Crane's canonical list-page change was incorporated as `a75dd4890f` (from `crane/n-d6if` at `534dd660d0`). Shared composition binding is committed as `168e9b81f6`. These two commits are local and not yet pushed.
- Both the **API module** (`feature/mail/message/list/api`, commit `2e0595420f` + `25d8ed7d7f`) and the **internal list module** (`feature/mail/message/list/internal`, commit `008e3d111e`) are now fully migrated to KMP Compose.
- The `preview` package resolution failure in `MessageListScreenRendererTest.kt` was resolved by moving the Compose previews and preview helpers from `src/debug` into `src/androidMain` where KMP source-set conventions make them accessible to `androidHostTest`.
- Verification passed cleanly:
  - `api`: 15 JVM tests, 15 Android host tests pass.
  - `internal`: 25 JVM tests, 140 Android host tests pass (1 upstream test skipped with `@Ignore`).
  - Code quality: `detekt` and `spotlessCheck` pass.
  - Legacy UI consumer: `:legacy:ui:legacy:compileDebugKotlin` compiles cleanly.
- App-local search WIP remains intentionally dirty and separately preserved on `checkpoint/ut-search-ui-before-reuse-review` (`b9bd9c4d2da1ae15105411a7a08ba899ea25fd4f`). The current worktree has three modified app files and five untracked search files. Do not stage or reset them.
- **Hardware & Decisions Status (Updated 2026-10-28)**:
  - **FP4 Phone**: Online and ready for deployment/acceptance testing.
  - **Content Hub permission (`q-jk4j`)**: Approved / allowed by Tom.
  - **OAuth credentials & ownership (`q-ar74`)**: Clarified as registering client application credentials.
  - **Side quest resolution**: Keel v1 command/event facade and event subscription completed (PRs #25, #26 merged; documentation PR #27 opened; Journey 9 verified). No lingering items or dependencies on the mail port.
- **Next milestone**: Wire the canonical list renderer/state into the Ubuntu Touch app through the existing shared composition boundary, with controlled mailbox data and real event dispatch. Then package and run device acceptance. The current app still hosts its own preview UI and has not rendered the canonical page on FP4.

## 2026-10-29 canonical list and composition update

Crane completed Desk item `n-d6if` in its own clean worktree. Its approved plan, read-only review, and verify artifacts are under `/home/tom/.local/state/dock/desk/artifacts/n-d6if/`. The resulting commit was cherry-picked onto the feature branch without touching the app-local search WIP. The canonical `MessageListPage`, `MessageList`, renderer, footer, swipe item and accessibility state now live in `feature/mail/message/list/internal/src/commonMain`. Android retains resource and notification-scaffold adapters; JVM has English string, Bolt colour and scaffold adapters. The renderer is bound in `sharedMessageListModule`.

Crane verification passed `internal:jvmTest`, `internal:testAndroidHostTest`, `internal:detekt`, `internal:spotlessCheck` and `legacy:ui:legacy:compileDebugKotlin`. The review recorded 33 JVM tests passing, 139 Android host tests passing and one pre-existing ignored test. This is controlled renderer/UI and Android regression evidence, not a UT app integration or device result.

The next local step added `sharedMessageListModule` to `app-composition` and its internal-module dependency there, consistent with ADR-0009's composition exception. Verification passed with JDK 21 and the Android SDK: `:app-composition:compileKotlinJvm`, `:app-composition:compileAndroidMain`, both internal list test targets, internal detekt/Spotless, and the legacy Android UI compile. `app-composition:spotlessCheck` also passed; its detekt task reported `NO-SOURCE`. Logs: `/tmp/thunderbird-ut-composition-20261029.log` and `/tmp/thunderbird-ut-composition-quality-20261029.log`. An initial invocation failed only because `:app-composition:compileDebugKotlin` is not a task in this KMP module; it was corrected to `compileAndroidMain` and rerun successfully.

The remaining integration is substantive: `app-ubuntu-touch` does not yet depend on `app-composition`, supply the list's external repository/preference contracts, or translate its existing mailbox identities and events into `MessageListState`. The canonical page has therefore not replaced the preview mailbox. Build a controlled end-to-end state/event slice before switching any live UI. Preserve the app-local storage, credential and transaction safeguards until their shared replacements are proven equivalent. No Click was built or installed in this update.

## Resume update after internal list migration

Work resumed under the continuing goal. The internal list module migration is now committed as `008e3d111e`:
1. Converted `feature/mail/message/list/internal` to `thunderbird.library.kmp.compose`.
2. Extracted canonical state machine, side-effect handlers, `GetAccountFolders`, `GetMessageListPreferences`, `LocalMessageUidPrefixProvider`, and `MessageListItem` to `commonMain`.
3. Added `sharedMessageListModule` Koin module in `commonMain` and included it in Android's `featureMessageListModule`.
4. Moved Compose preview helpers and previews from `src/debug` into `src/androidMain`.
5. Moved state machine unit tests and `RecordingFunctions` to `commonTest`.
6. Verified: internal jvmTest (25 passed), internal testAndroidHostTest (140 passed, 1 pre-existing ignored), detekt passed, spotlessCheck passed, legacy UI consumer compiled.

Next action is adapting the canonical list page (`MessageListPage`, `MessageListScreenRenderer`) and its platform resources/accessibility strings for shared desktop/JVM rendering.

## Objective and user correction

The objective remains a fully featured Thunderbird mail app on Ubuntu Touch, following [the full roadmap](ubuntu-touch-roadmap.md). Ubuntu Touch is the product; Compose Desktop/JVM is the build/runtime target. **Reuse and adapt the existing mobile UI and behaviour**, as sibling `../mobileapp` does. Do not resume building a separate application UI by default.

The earlier work expanded an app-local preview shell too far without establishing the full reuse boundary. The user challenged that approach and the resulting schedule estimates. We acknowledged the mistake. Platform adapters and safety/protocol evidence remain valuable; custom application UI, storage and orchestration require review against existing implementations. Do not discard them blindly or treat their test count as full feature parity. Earlier completion percentages are not audited figures. Later 4–8 week usable-port plus 1–3 month hardening estimates were conditional planning ranges, not commitments.

Read [the dependency audit](ubuntu-touch-dependency-audit.md), especially its reuse reassessment and prototype steps. The next milestone is a meaningful canonical mobile message-list slice, including its actual UI and state/controller path consumed by both Android and JVM. Compiling one helper or rendering a button is insufficient.

## Repository and durable checkpoints

Repository: `/home/tom/own/thunderbird-android`. Current branch: `feat/ubuntu-touch-demo`. Remote: `git@github.com:ThomasRedstone/thunderbird-android.git`.

The last completed source commits before this handover are:

- `c687760336`: corrected shared mobile UI target and reuse audit.
- `d01befabb3`: extracted canonical state-machine view model to KMP, with Android compatibility wrapper.
- `8ad345f7cf`: recorded compilation of the concrete Android mobile-list consumer.

Two separately pushed checkpoint branches preserve unfinished work:

| Branch | Commit | Contents |
| --- | --- | --- |
| `checkpoint/ut-search-ui-before-reuse-review` | `b9bd9c4d2da1ae15105411a7a08ba899ea25fd4f` | Eight unfinished app-local search UI/model/test files, based on `2ea82daa59`; hold this work during reuse correction |
| `checkpoint/ut-mobile-list-api-kmp-handover` | `103ff074a9718ed4c54c53c42116cabad06dcfdf` | In-progress canonical list API KMP conversion, resources/source moves and failing UI regression, based on `8ad345f7cf` |

**The working tree is deliberately dirty.** The module conversion appears as many deleted `src/main` files and new `commonMain`/`androidMain`/`jvmMain` paths. They are moves, not lost code. The eight app-local search files are also still present. Do not reset, clean, switch branches or broadly stage the tree without reviewing these independent changes.

In a fresh clean checkout, read this handover from the main feature branch, then recover only the intended module paths from the relevant checkpoint. The new list checkpoint excludes the separate search UI changes. In this existing workspace, the files are already present—do not reapply a patch blindly.

## Verified reuse boundary

Read-only sibling reference `/home/tom/own/mobileapp` is at `0477ae19007767f9580f0308eb8f773815b6f6ca`. Its desktop Main and Android MainActivity call the same `commonMain/.../ui/App.kt`. Its unrelated dirty files were not touched. Do not inherit its telemetry, Bluetooth permissions or lifecycle exemptions.

Thunderbird has substantial portable code, but no complete shared mobile `App()` boundary yet:

- Existing IMAP/SMTP/MIME engine and account contracts are already reused by the preview.
- `app-composition` and the shared reader implementation compile for JVM. Shared EML export/configstore/file/account-profile JVM tests pass: 5 + 11 + 17 + 3 = 36, zero failures, `/tmp/thunderbird-reuse-audit-jvm.log`.
- Shared reader code currently provides supporting attachment/action/CSS logic. `MailPart.jvm.kt` is only an interface. This is not an entire JVM reader/HTML engine.
- JVM notification platform module is empty; composition compilation does not prove delivery.
- Existing `MessageListScreenRenderer`, `MessageListPage`, read/unread row composables and `MessageListStateMachine` are the canonical mobile extraction targets. Android module placement does not mean all of their code is Android-specific.
- Legacy Fragments/Activities, account models, storage/controller services and Android resource consumers still need real platform boundaries.
- GitNexus has no indexed Thunderbird repository. The exploring/refactoring skill was read; direct source searches and Gradle evidence are the fallback. Do not use another repo's graph or index this entire repo unnecessarily.

## Completed shared controller extraction

Canonical implementation now lives in:

`core/ui/contract/src/commonMain/kotlin/net/thunderbird/core/ui/contract/mvi/BaseStateMachineViewModel.kt`

The old Android FQN remains as a delegating subclass in `core/ui/compose/common/.../mvi/BaseStateMachineViewModel.kt`. Its constructor/call sites remain usable. This reuses the existing behaviour; no new dependency edge or replacement application controller was added.

`/tmp/thunderbird-mobile-state-extraction.log`: shared UI-contract JVM suite18 tests; Android UI-common host suite17 tests; all pass, including the same8 state-machine behaviour cases on each path. Detekt/spotless pass. The unchanged list API compiled. The concrete list internal consumer also compiles: `/tmp/thunderbird-mobile-state-list-consumer.log`, `:feature:mail:message:list:internal:compileDebugKotlin`, exit0. These are host tests/compilation, not physical Android or UT UI acceptance.

## Current prototype and exact failure

Working conversion in `feature/mail/message/list/api`:

1. Replaced the Android-only plugin with the established KMP Compose convention.
2. Moved canonical rows/state/events/contracts/preferences into `src/commonMain/kotlin`.
3. Kept the legacy sort mapper and FragmentManager factory in `androidMain`.
4. Removed a LegacyAccount import used only in delete-policy KDoc; the actual interface stays shared.
5. Changed MessageListContract to use the canonical shared state-machine base.
6. Used common `expect enum SortType` and Android/JVM actual enums. Android retains its original R-backed `labelResId` getter; JVM has no fake resource IDs. Existing Android strings/translations moved unchanged to `androidMain/res`.
7. Moved existing SortCriteria tests to commonTest and added canonical mobile row interaction tests.

`/tmp/thunderbird-mobile-list-api-kmp.log` passed: JVM compilation and existing sort tests, Android compilation, detekt/spotless, and the Android list internal consumer. No full mobile list/page has rendered on JVM yet.

The subsequent command stopped at a JVM UI failure:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/tom/Android/Sdk ./gradlew \
  :feature:mail:message:list:api:jvmTest \
  :feature:mail:message:list:api:testAndroidHostTest \
  :feature:mail:message:list:api:detekt :feature:mail:message:list:api:spotlessApply \
  :legacy:ui:legacy:compileDebugKotlin \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
```

Log: `/tmp/thunderbird-mobile-list-api-interactions.log`, terminal exit1. Latest JVM XML:14 tests,1 failure, no errors/skips (12 sort tests and two row tests). Android host tests have no result XML from this invocation; neither those nor the full legacy consumer should be claimed passed.

The actual shared read row renders and dispatches open on JVM. The unread row's star test fails at `PortableMessageItemTest.kt:57`: after clicking favourite, expected `state.starred=true`, actual false. No fix was applied before handover.

Likely pre-existing callback bug, supported by current source:

- `FavouriteButtonIcon` computes and emits `!favourite` correctly.
- `organism/MessageItem.kt` uses `onFavouriteChange = { onTrailingClick(element) }`, discarding that new Boolean and forwarding the old `FavouriteIconButton` value.
- `ReadMessageItem`/`UnreadMessageItem` then call their callback with the old `element.favourite`.
- `MessageItemTrailingElement.FavouriteIconButton` is a data class, so forwarding a copy containing the new Boolean is a candidate fix.
- The internal mobile list ignores the Boolean and dispatches a toggle event; the legacy callback consumes it. Verify both paths rather than assume identical behaviour.

Retain the failing XML/log before fixing. Keep the KMP conversion refactor and any callback bug fix reviewable as separate commits if practical. Do not weaken the test or create alternative UI to obtain a passing result.

## Immediate next actions

1. Read Desk (`desk_read`), this handover, AGENTS.md, corrected audit and current git status.
2. Confirm checkpoint/source files and that no jobs remain live. No source changes were made after the failing invocation except documentation/checkpointing.
3. Investigate/fix the canonical favourite callback with a failing-before/passing-after regression for read and unread states and toggling in both directions; validate selection/long-click separately after star passes.
4. Rerun the combined API JVM/Android host/legacy consumer command above. Check actual task/XML results and formatting after any fixes. Preserve Android R getter compatibility.
5. Commit/push the API conversion and fix with their exact evidence. Stage only intended paths; leave the separately checkpointed custom search UI alone.
6. Continue extracting the **existing** internal mobile list page/template/state machine and its platform side effects. Establish contracts/resource adapters and Android regression coverage; do not replace them with the preview's custom list/controller.
7. Render actual canonical mobile list UI with controlled read/unread/starred/selected data and prove events traverse its canonical state path on JVM. Then wire via shared composition, package and test on UT when reachable.

The current API conversion is not a completed full-screen prototype. No API/internal bypass, fake Android resource IDs, runtime TODO stubs, new permission or OAuth changes should be introduced just to make compilation green.

## Preview and phone state

The preview remains useful as a platform/engine reference, not the agreed final UI architecture. `app-ubuntu-touch` source version38 includes previously committed read-only server search and guarded selected-result caching. Last full app suite before the unfinished UI was485 tests, all passing. The checkpointed custom UI adds nine focused passing model/UI cases, but no full-suite or phone acceptance was claimed for it. Do not resume that work by default.

Latest frozen artifact is37/schema19, not installed:

- `app-ubuntu-touch/build/ut-candidate-39/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.37_arm64.click`
- Source `4c44c58c996f2dabb4545ea28496a76fb33f2032`, empty SOURCE-CHANGES patch.
- 148182292 bytes, SHA256 `9210ccb96dc2a9ec8d47fbbe634c9db64fadfa440116a81773213cdc9e917453`.
- Click review and ARM64 launcher/JVM/AWT/Skiko/SQLite checks pass, `/tmp/thunderbird-037-click.log`.
- Pinned builder `sha256:3a08b52420bd76280d8323db951a306e428fb17ac5e4cd60f53903e049a68563`, verified candidate5 ARM64 runtime reused. Not a cold/CI reproducibility proof.
- Frozen native driver `build/evidence/native-037/candidate-037-native-probes.jar`, SHA256 `3295a548eee77dac4c7a782b72f4b3e90cdff5b99e2b2594cf2341de3078cd87`. Includes exact37 migration, Copy/recovery and protected chunk probes; not run on phone.

Last verified FP4 installation remains34/schema15, clean `1f248250f6de4da9f8515f76aa01e435d3802177`. SSH `phablet@100.87.156.48:2222`, UID32011, repeatedly timed out. Last known main PID538669 was suspended; a normal close request was sent but completion was never verified. Revalidate all device state. Never assume it is stopped or repeat an install blindly.

Prepared but not run: `/tmp/thunderbird-037-upgrade.py` and `/tmp/thunderbird-037-probe.sh`. Upgrade requires exact old34/source, no app process, private SQLite backup/schema15, exact new artifact/hash/source. No37 transfer, install, backup or native migration happened.35 transfer ended with rsync error10, not a live transfer. The local phone-fleet pin remains34, `c1a763f`; unrelated fleet changes exist. Do not sync/prune/push the fleet or change device/network/OS policy.

## Outstanding decisions and safety boundaries

Desk main work item: `n-d6if`, parent `n-2md2`.

- `q-ar74`: Resolved for dedicated Google/Microsoft public-client application registrations. No client IDs are configured; generic OAuth plumbing and host tests do not prove Gmail/Outlook phone journeys.
- `q-jk4j`: Resolved; Tom approved `content_exchange_source`, and the manifest change is committed as `ba2fcc3ac8`. Save UI/adapter/lease/journal work still does not prove a real confined receiving-app transfer.
- FP4 was reported online on 2026-10-28. Recheck live device state before installation or native acceptance; the older offline observation in this document is historical.

Production SignOn on old installed builds fails large raw credential reads. Source37 adds protected chunks and ownership/deletion/uncertainty journals, with host capped-backend tests. Its native production-keyring probe is prepared but unrun. A separate experimental library repair succeeded only in private probes; it was not deployed. Never replace the system credential service, use a plaintext fallback, or claim the issue fixed on the phone.

Earlier Crane item `n-isti` is not a source of plan approval. Crane failed; user later authorized normal work. Do not self-approve it or instruct a worker through its pane. Current instructions prohibit spawning subagents unless explicitly requested.

Read repository AGENTS.md. Use Kotlin, Bolt Compose, Koin, MVI/Flow, existing API/internal boundaries and meaningful narrow tests. No telemetry, credentials/PII logging, new permissions or OAuth configuration without explicit authorization. Honour sibling/worktree dirty files. Do not edit source while a dependent build/test/package job is running. Keep the full roadmap active; it is far from complete. No completion claim is appropriate from these host checks.

For Gradle use JDK21 `/usr/lib/jvm/java-21-openjdk-amd64`, SDK `/home/tom/Android/Sdk`, `--no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4`.

## Remaining full application requirements

The corrected route preserves the complete scope: real-provider authentication and protected secrets; canonical mobile mailbox/reader/composer/settings; safe HTML and privacy controls; attachment opening/saving/composition; threading/search/paging; organisation/bulk/folder management and uncertain-outcome resolution; background delivery/notifications; encryption/signing; accessibility; data migration/retention; reproducible builds, upgrades and device/provider/soak acceptance. Consult the roadmap, feature inventory and M2 evidence for the detailed gates. The handover does not shrink the goal to the prototype.
