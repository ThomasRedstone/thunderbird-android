# Ubuntu Touch mail port handover

Updated 2026-11-01. Resume the full roadmap from the current source and evidence, with the corrected shared mobile UI architecture.

## 2026-11-01 resumed device check and attachment fixture

Read-only recheck found the FP4 still running the installed 0.1.47 Click and its confined JVM. The live schema-19 SQLite database returned `quick_check=ok` with two accounts, ten folders, two cached messages, two outgoing records and no pending credential cleanup. These are counts only; the last verified UI send and reader results remain in the sections below.

A second-account self-addressed message with a small plain-text attachment was submitted directly to the disposable Ethereal server for the next reader/Content Hub check. Independent read-only IMAP search found exactly one matching Inbox copy and one `ut-attachment-fixture.txt` MIME part. The subject is `UT FP4 attachment save fixture 2026-11-01`; the decoded attachment is 43 bytes with SHA256 `60efa2481e2af54599d8f6ea682a852ca8ea8fd04a77349ec60e6dd279d337f7`. Both contain only synthetic test data. The phone's Mir physical capture was black because the display was off, and attempted Xwayland taps did not change the app view. **The fixture has not been synced into the app or opened through the phone UI.** After waking/unlocking the FP4, open the second account's INBOX, sync, inspect the attachment row, try Save with a real receiving app, and verify the decoded bytes and private lease cleanup. Do not submit the fixture again to make the test pass or claim Content Hub acceptance from the server-side copy alone.

## 2026-11-01 handover: next goal — daily-use foreground mail

**Goal:** Deliver the roadmap's [Milestone 3](ubuntu-touch-roadmap.md#milestone-3-daily-use-in-the-foreground) as a substantial, usable foreground mail experience on Ubuntu Touch. Reuse Thunderbird's shared mobile list, reader and state contracts with JVM/Linux adapters. Keep the existing protected account-scoped cache, mail engine and guarded outgoing/operation journals until a shared replacement proves equivalent. This goal ends with physical FP4 journeys and server assertions, not merely source compilation or a new screen.

**Current baseline.** The last phone-tested integration checkpoint on `feat/ut-search-reader-integration` is `31c777aef2`; the branch is pushed and this handover adds documentation. The installed FP4 Click is 0.1.47, built from `4d996d47635602d37eb4e11bd62914f273ddf9c3` with an empty source patch; the package hash and backup are below. The real mailbox starts without the demo preview. The canonical mobile list, shared plain-text reader, Bolt drawer, cache-backed folder/account routing, guarded search results and attachment metadata/Save entry point are integrated. On 2026-11-01 the FP4 UI created a second disposable IMAP/SMTP account, discovered folders, synced and read mail, saved/queued/sent a self-addressed draft, and reopened that account and its cached reader after restart. SMTP acceptance and one Inbox/one Sent Mail copy were checked independently. The first-account credential recovery and its limits are recorded below. This is a strong online foreground vertical slice; it is not yet Milestone 2's complete offline/error matrix or Milestone 3 acceptance.

**Finish the remaining vertical-slice gates first.** Demonstrate a cached reader body with network unavailable, then restore connectivity and check normal sync. With a private integrity-checked backup, test a versioned 0.1.47→0.1.48 Click upgrade: both app-created SignOn identities must remain accessible, both accounts and cached mail must survive, and sync/send must still work. The identities currently have a versioned 0.1.47 owner; do not assume a same-version restart proves upgrade safety. Exercise rejected credentials, invalid certificate, interrupted sync and uncertain send against controlled fixtures before extending the feature set. Keep the disposable mailbox credentials out of Git and logs; do not edit SignOn identities through metadata-only `store_info` (see recovery below). Phone network changes need a controlled way to reconnect and observe the UI; no offline pass has yet been recorded.

**Work packages for the next goal, in delivery order:**

| Package | Implement and reuse | Evidence required to close it |
| --- | --- | --- |
| Rich reader and privacy | Select a supported Linux HTML renderer only after the experiments in [HTML feasibility](ubuntu-touch-html-feasibility.md). Use the engine's MIME viewable-part classification, preserve a readable plain-text fallback, handle inline CID images and safe links, and make external content consent explicit per message. | Host malformed/large MIME tests and a network oracle showing zero remote requests by default; FP4 HTML, link, rotation, keyboard and offline reading with no cross-account resource access. HTML-to-text fallback alone does not close this. |
| Attachment lifecycle | Complete the shared reader's cache-scoped MIME-part actions. Prove real Content Hub Save and Open with a receiving app, private lease cleanup and interrupted/restarted transfer recovery. Add selected outgoing attachments and preserve them in drafts, send and forward where supported. | Exact-byte/decoded-file assertions, bounded large-file and cancellation tests, account-isolation checks, and an FP4 receive → open/save → attach → send journey. The existing metadata/Save button and fake receiver are only partial evidence. |
| Composition and message actions | Extend the current plain-text response/draft path to HTML-only replies, quote/identity/signature choices and attachment-aware forwarding. Finish offline read/star, archive/trash, move/copy and bulk-operation replay through the shared state path. | Controlled IMAP/SMTP assertions for recipient privacy, one delivery/Sent copy, UID-validity and conflict handling; reopen/reconnect tests and FP4 journeys across accounts. No blind retry after an uncertain SMTP result. |
| Multi-account mailbox and discovery | Provide a dedicated unified inbox, account-scoped and unified search with usable server-result routing, sorting, paging/cancellation and conversation navigation. Keep account removal and operations isolated while work is active. | Two-account FP4 switching/unified/search journeys, offline search and isolation checks, large-mailbox measurements, and server-side assertions for affected messages. Current unified cached search and server search are partial implementations. |
| Provider setup and interaction quality | Complete supported Google/Microsoft OAuth browser callback, verified identity/scope/refresh and cancellation flows once dedicated registrations and provider policies are configured. Improve touch/rotation, keyboard focus/avoidance, accessibility and error feedback. | Real-provider FP4 sign-in/restart/expiry/cancel journeys plus accessibility and device layout checks. Generic host OAuth plumbing and approved registration *intent* are not provider acceptance; no client IDs are currently configured. |

**Exit gate for this goal.** Build a clean-source ARM64 Click, preserve artifact/source hashes, back up the installed schema and account data, then run full journeys on the FP4 with at least two disposable accounts and controlled MIME fixtures: setup, online/offline read, HTML privacy, incoming and outgoing attachments, compose/reply/forward, mailbox actions, unified/search, restart and upgrade. Verify server outcomes independently and record failures without exposing message bodies or secrets. Each package needs focused JVM/Android regressions where it changes shared code, then confined and physical acceptance. Background sync/notifications, OpenPGP, complete import/export and release readiness remain later roadmap milestones; this goal must preserve their interfaces and avoid claiming them complete.

**Starting points and boundaries.** Work in `/home/tom/own/thunderbird-android-ut-search-reader` on `feat/ut-search-reader-integration`; inspect `git status` before editing. The separate `/home/tom/own/thunderbird-android` checkout has unrelated uncommitted server-search work and must not be reset or broadly staged. Start with `feature/mail/message/reader/api/.../MessageReaderPage.kt`, `app-ubuntu-touch/.../LiveMailScreen.kt`, `CachedAttachmentStore.kt`, `MessageSaveViewModel.kt`, `DbusContentExportBroker.kt`, `ComposerViewModel.kt`, `ForegroundMailSession.kt`, and the [feature inventory](ubuntu-touch-feature-inventory.md). Use the device protocol and current package/build evidence below before any install. The FP4 was reachable as `phablet@100.87.156.48:2222` during the 0.1.47 walkthrough; recheck its state, process and package. Screenshots under `/tmp/ut-screen-047-*` are private local evidence, not repository fixtures. Desk note `n-yxsx` tracks the versioned SignOn upgrade risk. Current goal status in the session tracker may lag this handover; use this evidence rather than inferring completion from its label.

## 2026-11-01 second disposable account created through the FP4 UI

Tom supplied a second disposable Ethereal mailbox. On the installed 0.1.47 Click, **Accounts → Add account** accepted its name, address, IMAP host/port with direct TLS, and SMTP host/port with required STARTTLS, with both passwords entered into masked fields. **Save account** confirmed success and showed two accounts. No credential or address was added to this repository.

For the new account, **Mailboxes → Refresh folders** discovered five server folders. **INBOX → Sync folder** fetched the mailbox's welcome message; the shared reader displayed its sender, subject, account, and cached body. **Write** selected the new account, saved an addressed draft locally, queued a self-addressed message, and **Send now** reported **Accepted by SMTP** and **Server copy saved in Sent Mail**. Independent read-only IMAP searches found exactly one matching message in INBOX and one in Sent Mail. After terminating and relaunching the app, the second account, folders, cached INBOX message, and reader body reopened successfully. Private local UI evidence is under `/tmp/ut-screen-047-second-*`.

This completes the online UI setup → sync → read → compose/send → restart → cached read journey for one account. The genuine network-offline reader check and attachment actions on a message with attachments remain open. Versioned SignOn credential access across a future Click upgrade also remains untested; the restart only tested the same installed version.

## 2026-11-01 FP4 0.1.47 UI acceptance

Tom unlocked the FP4. Launching the installed `mail-demo.thomasredstone_mail-demo_0.1.47` through Lomiri started the confined JVM, and a Mir physical screencast showed the real mailbox screen with the existing Ethereal test account. The greeter process name alone was not evidence that the screen was locked. Xwayland window captures then showed the real account's folder screen, the cached INBOX with five messages, and the shared reader opening the Ethereal welcome message and stepping to `Ubuntu Touch list fixture 1`. The reader displayed the cached plain-text body, sender, star/read controls, reply actions, and previous/next navigation. This confirms production startup and cached list/reader rendering on the phone; the account was provisioned earlier, so it does not prove the UI setup form.

The `Write` route opened the existing account's composer. Through the UI, an addressed message was saved as a draft, restored with its subject/body intact, queued, and submitted with `Send now`. The first submission showed **Uncertain delivery** and retained the outgoing row as `UNCERTAIN` with `uncertain_attempt=1`. The phone journal showed a SignOn permission failure: the original test credentials had been provisioned by an unconfined helper and were not readable by the confined app. A read-only IMAP subject search found no delivered copy at that point.

A metadata-only SignOn ACL repair attempted from the helper unexpectedly replaced those old identities' secrets with empty values. The old credentials were restored through the app's masked account-edit form, which created new, nonempty identities owned by the confined 0.1.47 app. This changed the account's cache binding and invalidated the five previously cached disposable messages; the private pre-upgrade backup remains available. The old empty helper identities and stale cleanup-index rows were removed after an integrity-checked SQLite backup. The UI reported **Obsolete credentials removed**. Do not use `store_info` on a SignOn identity reconstructed from metadata alone: its empty `Secret` field can replace the stored password.

A restart before the credential repair reopened the account, five-message cached INBOX, and reader successfully. A fresh self-addressed fixture was then deposited directly through the disposable mailbox for sync testing. The app's **Sync folder** fetched it, and the shared reader displayed its body. After confirming the original send subject was absent from IMAP, the UI's guarded **Review retry** → **Send again despite duplicate risk** path accepted the queued message. The app reported **Accepted by SMTP** and **Server copy saved in Sent Mail**. Independent read-only IMAP checks found exactly one matching copy in INBOX and one in Sent Mail. The outgoing row is now `SENT`; `uncertain_attempt=1` records the earlier uncertain attempt, not a current delivery failure.

At the end of this first-account recovery, the phone database had schema 19 `quick_check=ok`, one account, five folders, one freshly synced message, one `SENT` outgoing row, zero pending credential cleanup rows, and active app-owned SignOn identities with nonempty secrets. The second-account walkthrough above supersedes these counts. Screenshots under `/tmp/ut-screen-047-*` are private local evidence. Also verify that versioned SignOn ownership survives the next Click upgrade before treating the restored credentials as upgrade-safe. Capture screenshots and diagnostic logs under private permissions; never commit mailbox credentials or message contents. The initial phone lock and uncertain-send blockers are resolved.

## 2026-10-31 installed 0.1.47 real-mailbox candidate

Clean-source ARM64 Click 0.1.47 was built from `4d996d47635602d37eb4e11bd62914f273ddf9c3` with an empty
`SOURCE-CHANGES.patch`: `app-ubuntu-touch/build/ut-candidate-47/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.47_arm64.click`,
160,177,756 bytes, SHA256 `114f0536d6afca6c00821851b3747dc7b0fd734585b1f87f1fdb19af5738987c`.
Clickable review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-ut-click-47.log`). The verified 0.1.46
ARM64 runtime and pinned packager image were reused. The FP4 transfer hash matched; an integrity-checked private backup
was made at `/home/phablet/.cache/mail-demo.thomasredstone/upgrades/pre-reader-47-czwqp8t3`, then Phone Manager installed
0.1.47. The installed source marker and empty patch match the Click. The production cache remains schema 19 with
`quick_check=ok`, one account, five folders, and five messages at installation time (`/tmp/thunderbird-047-upgrade.log`). Phone Manager
reported launcher/user-bus and signature warnings, but the install command and post-install checks completed. The
2026-11-01 phone UI findings above supersede the earlier greeter-only observation.

## 2026-10-31 real mailbox startup without demo dependency

Commit `544cafa0b8` removes the preview destination from normal navigation. `Main` no longer loads demo messages or
requires a demo model before constructing the real protected mailbox; its global demo Escape handler was removed, so
mailbox Back handling can receive that key. The demo backend and its isolated tests remain available as Milestone 1
fixtures, but are not on the production startup path. The account-entry UI test now verifies the demo button is absent
on the account screen; it and the reader-response navigation test pass. App Detekt and Spotless pass
(`/tmp/thunderbird-ut-real-entry-without-demo-final.log`, `/tmp/thunderbird-ut-real-entry-without-demo-spotless.log`,
`/tmp/thunderbird-ut-no-demo-account-test.log`). This is included in the installed 0.1.47 Click and needs phone UI
acceptance.

## 2026-10-31 installed 0.1.46 reader candidate

Clean-source ARM64 Click 0.1.46 was built from `20f510a2610f1acc89e933a4d264af94672cfd5b` with an empty
`SOURCE-CHANGES.patch`: `app-ubuntu-touch/build/ut-candidate-46/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.46_arm64.click`,
160,184,608 bytes, SHA256 `f5169108b853b2ff0ded5b42c549fd4b0203bad4e14a7a409ceaf465bfc4f23f`.
Clickable review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-ut-click-46.log`). The verified 0.1.45
ARM64 runtime and pinned packager image were reused. The FP4 transfer hash matched; an integrity-checked private backup
was made at `/home/phablet/.cache/mail-demo.thomasredstone/upgrades/pre-reader-46-lhia9tg6`, then Phone Manager installed
0.1.46. The installed source marker and empty patch match the Click. The production cache remains schema 19 with
`quick_check=ok`, one account, five folders, and five messages (`/tmp/thunderbird-046-upgrade.log`). Phone Manager
reported launcher/user-bus and signature warnings, but the install command and post-install checks completed. The
device still runs `lomiri --mode=full-greeter`, with Xwayland `:28` owned by the greeter. No 0.1.46 UI walkthrough is
claimed.

## 2026-10-31 cached HTML-only text fallback

Commit `01a2acadf1` lets the cache-backed reader display inert text from an HTML-only MIME body instead of a placeholder.
It uses the existing MIME viewable classification, parses bounded HTML with Jsoup, removes scripts, styles, embeds and
forms, and renders text only; the parser makes no remote requests. A cached plain-text part remains preferred, and
reply/forward still require one. The HTML-only cache regression passes along with the five-test resource suite, app
Detekt and Spotless (`/tmp/thunderbird-ut-html-text-fallback-final.log`). This source is in the installed 0.1.46 Click
but has not been exercised through the FP4 UI. Rich HTML rendering, link handling, and attachment Open remain
future reader work.

## 2026-10-31 shared reader attachment Save action

Commit `8cc1ce586d` adds a Save control on each shared reader attachment row. The Ubuntu Touch host sends an account,
folder, and UID scoped request with the selected position and displayed metadata; `MessageSaveViewModel` reopens the
protected cached MIME message through `CachedAttachmentStore`, checks the current name and MIME type, then prepares the
existing Content Hub export using its fingerprinted `AttachmentReference`. A stale or missing part cannot be prepared,
and the reader retains only detached metadata. The existing attachment picker remains available. Server-search results
still show metadata without Save because that route has no export action.

Focused app tests pass 10/10, including a row-to-request UI test and current-cache metadata check; reader API JVM tests,
app and reader Detekt/Spotless pass (`/tmp/thunderbird-ut-reader-attachment-action-tests.log`,
`/tmp/thunderbird-ut-reader-attachment-action-final.log`). This commit is in the installed 0.1.46 Click but has not
been exercised through the FP4 UI. Open behavior and richer HTML/privacy handling remain separate reader work.

## 2026-10-31 real-mailbox entry and installed 0.1.45

The app now opens the protected mailbox on startup instead of the preview. With no accounts, that screen offers a
"Set up account" route into the existing account form; account, signature, and empty-composer Back routes return to the
mailbox. The empty-account navigation and reader → reply draft tests pass
(`/tmp/thunderbird-ut-real-entry-debug4.log`, `/tmp/thunderbird-ut-real-entry-tests.log`). App Detekt and Spotless pass
(`/tmp/thunderbird-ut-real-entry-quality.log`). A later combined verification run lost its Gradle daemon during host
configuration before producing a test assertion (`/tmp/thunderbird-ut-real-entry-final.log`).

The launcher now discovers numbered Xwayland sockets beyond `:9`; the FP4 greeter created `:28`. The launcher test
passes. Clean-source ARM64 Click 0.1.45 was built from `72902ba61e75cc1d61cf5a8caa735a87b32a516c` with an empty
`SOURCE-CHANGES.patch`: `app-ubuntu-touch/build/ut-candidate-45/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.45_arm64.click`,
159,748,148 bytes, SHA256 `535203e1624e916be98a6390f12123077dd912beaf8fbc99974605853899c031`.
Clickable review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-ut-click-45-retry.log`). The verified
0.1.44 ARM64 runtime and pinned packager image were reused. The first build attempt was killed during Gradle startup
under host load; the retry completed.

The FP4 transfer hash matched. An integrity-checked private backup was made at
`/home/phablet/.cache/mail-demo.thomasredstone/upgrades/pre-reader-45-c_6y3qw1`, then Phone Manager installed 0.1.45.
The installed source marker and empty patch match the Click. The production cache remains schema 19 with
`quick_check=ok`, one account, five folders, and five messages (`/tmp/thunderbird-045-upgrade.log`). Phone Manager
reported launcher/user-bus and signature warnings, but the install command and post-install checks completed. The
device is still running `lomiri --mode=full-greeter`; its Xwayland `:28` belongs to that greeter. No 0.1.45 UI
walkthrough is claimed. Unlock the FP4, then run the full setup → sync → read → compose/send → restart → offline read
journey through its UI.

## 2026-10-31 integrated 0.1.44 phone candidate

Clean-source ARM64 Click 0.1.44 was built from `54e75d47e2cd16715f4d6a97117653f742b03dcf` with an empty
`SOURCE-CHANGES.patch`:
`app-ubuntu-touch/build/ut-candidate-44/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.44_arm64.click`,
159,747,356 bytes, SHA256 `7e8a81a9fac6f9e96416152b57442774fc4cba575b28e03560079c3b164c90d2`.
Clickable review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-ut-click-44.log`). The verified
0.1.43 ARM64 runtime and pinned packager image were reused; this is not a cold runtime build. The changed reader API's
Android host tests pass (`/tmp/thunderbird-ut-reader44-android-host.log`).

The FP4 transfer hash matched. An integrity-checked private backup was made at
`/home/phablet/.cache/mail-demo.thomasredstone/upgrades/pre-reader-44-kbpck7b9`, then Phone Manager installed 0.1.44.
The installed source marker and empty patch match the Click. The production cache remains schema 19 with
`quick_check=ok`, one account, five folders, and five messages. Phone Manager reported a user-bus warning while
updating launcher metadata, but the installed package and cache checks completed. Xwayland is still absent; no 0.1.44
window, reader interaction, setup, send, restart, or offline UI acceptance is claimed. Unlock the FP4 and run the
complete one-account journey through its UI.

## 2026-10-31 shared reader attachment metadata

`MessageReaderPage` now renders attachment names and MIME types from its shared content model for both the cached
mailbox and server-search reader. The UT host maps only metadata already derived from the account-scoped protected
cache; incomplete messages still hide provisional attachment metadata. Save continues through the existing
`CachedAttachmentStore` reference and export safeguards. This does not yet connect the reader's MIME-part attachment
state to Open/Save actions or render HTML. `withCachedMessage` explicitly requires detached return values and releases
temporary MIME body resources when its callback ends; a JVM reader adapter must retain a protected cache reference and
reopen it for actions, rather than keep a parsed `Part` in UI state. The five focused cached-reader and server-search
UI tests pass; reader API
JVM tests and app compilation pass. Reader API and app Detekt/Spotless pass
(`/tmp/thunderbird-ut-shared-attachments-tests.log`, `/tmp/thunderbird-ut-shared-attachments-compile.log`,
`/tmp/thunderbird-ut-shared-attachments-app-tests.log`, `/tmp/thunderbird-ut-shared-attachments-quality.log`). The
combined test command was killed by the host memory limit after reader API tests; app UI tests passed separately.

## 2026-10-31 shared drawer navigation slice

The search-reader integration branch now uses Bolt's shared `ModalNavigationDrawer`, `ModalDrawerSheet`, and
`NavigationDrawerItem` in the live mailbox. Its account and folder entries are projections of `LiveMailState`, and taps
dispatch the existing guarded `OpenAccount` and `OpenFolder` events; the protected cache remains the only source of mail
state. The mailbox title row opens the drawer, and the preview subtitle was removed. Account and folder lists remain
available in the mailbox content. Android's dropdown drawer still depends on `AppCompatActivity`, Android settings,
legacy mailstore, and notification services; this is its portable UI component path, not a wholesale module relocation.

The Compose drawer event test and reader → reply draft navigation test pass, with no send on opening the draft.
App Detekt and Spotless pass (`/tmp/thunderbird-ut-drawer-check8.log`). A broader nearby test batch was killed by the
host workload memory limit before reaching app test execution (`/tmp/thunderbird-ut-drawer-relevant-tests.log`); no
additional app test result is claimed. An isolated `LiveMailScreenTest` retry also reached the test task before the
same host memory kill (`/tmp/thunderbird-ut-drawer-list-test.log`). The earlier 9/9 list UI tests passed before this
drawer edit; list behavior still needs a fresh run or device verification. This source is included in the installed
0.1.47 Click, and the 2026-11-01 FP4 walkthrough above verifies drawer navigation to the cached INBOX.

## Prior integration phase and source checkpoints

The integration branch established the shared single-account mail flow and accepted its online path on the FP4. It has the canonical list,
shared plain-text reader, Bolt drawer for cache-backed account/folder navigation, server search, and shared attachment
metadata presentation; the original eight search WIP files remain untouched. The remaining implementation work is
cache-backed MIME-part Open behavior, richer HTML/privacy handling, and broader on-device UI acceptance of
the installed clean integrated Click. The next-goal packages above supersede this earlier phase description.
The phone gate is one UI-driven setup → sync → read → compose/send → restart → offline read journey, including reader
navigation and attachment presentation. The 2026-11-01 walkthrough proved startup, cached read/navigation, new-account
creation and account editing with protected credential storage, live sync/read, draft persistence, SMTP send with
server-copy verification, and restart persistence. Network-offline reading and attachment actions remain open.

The JVM reader implementation now aliases `MailPart` to the shared mail engine's `Part` interface, matching Android's
MIME part type. A JVM reader test passes a real `MimeBodyPart` through attachment state; app JVM compilation, reader
implementation Detekt and Spotless pass (`/tmp/thunderbird-ut-mailpart-jvm-test.log`,
`/tmp/thunderbird-ut-mailpart-quality.log`). This establishes the type boundary, but does not yet map protected cached
attachments into reader state or complete their Open/Save behavior.

The separate `feat/ut-search-reader-integration` branch is rebased over that fix. It carries the original server-search
WIP in a clean integration worktree and uses the shared reader for search results; search results display the cached
body and attachments without offering actions the search route cannot dispatch. The original `feat/ubuntu-touch-demo`
worktree and its eight WIP files were not changed. Focused app tests pass 18/18, including the mailbox reader and both
search suites (`/tmp/thunderbird-ut-search-reader-rebased-focused.log`); app Detekt and Spotless pass
(`/tmp/thunderbird-ut-search-reader-quality-retry.log`). A full app test retry compiled and reached test execution,
then the Gradle daemon disappeared under host pressure without an assertion result
(`/tmp/thunderbird-ut-search-reader-app-test-retry.log`). At this earlier checkpoint the branch was source-only; the
later 0.1.47 install and UI acceptance are recorded above.

## 2026-10-31 shared reader slice (0.1.43 candidate)

Android's `MessageViewContainerFragment` pages through the current ordered message list and forwards reader actions to
`MessageViewFragment`; `MessageTopView` hosts its header, body and attachments. The existing KMP reader view model only
handles the actions bottom sheet and attachment state. Its JVM `MailPart` actual remains an interface, and Android's
HTML/WebView and attachment controllers are not portable by simple class relocation.

`feature/mail/message/reader/api` now contains a Compose `MessageReaderPage` with the mobile reader's plain-text
header, cached-body, download, read/star, reply/forward and adjacent-message controls. It accepts redacted content
state and action callbacks; it does not fetch, log or persist mail. `app-ubuntu-touch` maps its protected cache's
`MailContent` and existing `LiveMailEvent` actions into that page. The old app-local reader body and response/flag
controls were removed. Previous/next follows only the current folder's ordered cached list; search across folders
disables it. Existing `LiveMailViewModel` account, folder, flag journal, composer and cache safeguards remain in charge.

JVM reader tests and the UT app compile pass. App Detekt and Spotless pass. The full app run completed 497 tests with
one unrelated `AccountSetupScreenTest` coroutine timeout under host load; that exact test passed on an isolated rerun.
The new reader navigation Compose regression passed in the full run. Android reader API host tests and the legacy UI
consumer compile also pass (`/tmp/thunderbird-ut-reader-android.log`). Logs:
`/tmp/thunderbird-ut-reader-tests.log`, `/tmp/thunderbird-ut-account-ui-retry.log`,
`/tmp/thunderbird-ut-reader-quality-final.log`.

Clean-source ARM64 Click 0.1.43:
`app-ubuntu-touch/build/ut-candidate-43/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.43_arm64.click`,
159,647,280 bytes, SHA256 `0fc1bcf06b90a89dec0c369600fa8c2ca9f55af12f3da6089913dd3d5ad71978`.
The embedded source is `99e6ee1279a5aab8a9bc6d24f2ac3332d06f4511`, and `SOURCE-CHANGES.patch` is empty.
Clickable review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-ut-reader-click-43.log`). The
verified 0.1.42 ARM64 runtime and pinned packager were reused. The commit was pushed on `feat/ut-canonical-list`.

The FP4 package transfer hash matched; an integrity-checked private schema-19 database backup was made at
`/home/phablet/.cache/mail-demo.thomasredstone/upgrades/pre-reader-43-8hiu2csz`. Phone Manager installed 0.1.43,
and the installed source marker and empty patch match. The production cache remains schema 19, `quick_check=ok`,
with one account, five folders and five messages. The first launcher request printed `Started` but the JVM exited
before drawing: the phone was on `lomiri-full-greeter`, `lomiri-full-shell` was inactive, and no Xwayland server was
accepting `:0`; the user journal shows `java.awt.AWTError: Can't connect to X11 window server`. This is a display
availability gate, not reader UI acceptance. Unlock the phone and repeat the launch and UI checks.

Navigation trace: Android's `DropDownDrawer` wraps a Compose `DrawerView` in `AppCompatActivity`/`DrawerLayout`.
Its module is Android Compose and its view model depends on legacy account, mailstore, folder and notification services.
`core/ui/navigation` has shared `Route` types but its `Navigation` API still takes `NavGraphBuilder`; it is not a
ready-made desktop app host. The next source extraction should separate the drawer's account/folder presentation from
those Android services, then connect it to the same protected cache projection used by the current message list.

This is a bounded reader portability step, not a complete Android reader replacement: HTML rendering, MIME-part
attachment adaptation, translated reader strings and a shared whole-app navigation host remain open. The FP4 has not
yet displayed 0.1.43. The complete account setup → sync → read → compose/send → restart → offline read journey still needs
phone UI evidence; the prior Ethereal setup and sync used one-off helpers. Do not put test credentials in this handover.

## 2026-10-31 FP4 cached-list acceptance with disposable IMAP account

The owner supplied a disposable Ethereal account. Its address and password are deliberately omitted here. A one-off Java helper invoked the app's existing `UtAccountManager.createWithPasswords` path on the FP4, backed by `ChunkedCredentialStore`, `SignonCredentialStore` and `ProtectedPayloadJournal`; credentials entered over standard input and were not placed in a shell command, SQLite row or log. The helper class was removed after setup. Private integrity-checked SQLite backups were made before account setup at `/home/phablet/.cache/mail-demo.thomasredstone/provisioning/ethereal-y30_w9_1` and before sync at `/home/phablet/.cache/mail-demo.thomasredstone/provisioning/before-sync-02pqnjnn`.

Four synthetic messages were sent through Ethereal SMTP to `.invalid` recipients; Ethereal IMAP reported five INBOX messages including its welcome mail. A one-off helper then used the app's `ForegroundMailSessionFactory` and `PersistentLiveMailRepository` to refresh folders, sync INBOX and download bodies through the same protected account path as the UI; it too was removed. The phone's production SQLite now has schema 19, `quick_check=ok`, one account, five folders and five cached messages. The first reader body was verified complete (`/tmp/thunderbird-ethereal-phone-sync.log`). This proves real IMAP-to-cache operation, not an inserted fixture row.

The installed 0.1.42 launcher returned zero and printed `Started` (`/tmp/thunderbird-ethereal-phone-launch.log`); its exact-source confined JVM was observed. Xwayland screenshots of the actual FP4 app showed the shared mobile list rows for INBOX (`/tmp/ut-screen-after-escape.png`), later rows after remote pointer scrolling (`/tmp/ut-screen-after-scroll.png`), a selected cached message and body in the reader (`/tmp/ut-screen-after-click.png`), and the list after the on-screen Back control (`/tmp/ut-screen-after-back2.png`). The final screen was left on the list (`/tmp/ut-screen-final-list2.png`). Remote input used XTest pointer events; literal capacitive finger input was not separately measured. The temporary phone-side input/capture scripts and raw image were removed; local screenshots are mode 0600. The owner's visual feedback was that the shell looks basic; this slice does not establish full Thunderbird visual or feature parity. No code was changed or rebuilt for this acceptance pass. Ethereal test mail expires after a few hours, while the protected local cache remains until removed.

## 2026-10-29 folder-bound actions and FP4 0.1.42

A row callback could outlive a folder switch. Because message IDs can repeat across folders, a delayed click could otherwise act on the same ID in the new folder. Commit `16fceef543` binds canonical row callbacks to the folder that rendered them and checks both current reducer and mailbox folder before dispatching the action. The same-ID folder-switch regression passes. Commit `2113867b3b` versions the Click as `0.1.42`. Clean candidate full app tests: 496, zero failures/errors/skips, with app Detekt and Spotless passing (`/tmp/thunderbird-ut-folder-event-guard-full.log`). The original branch was fast forwarded while its eight search WIP files stayed uncommitted; the one overlapping file was backed up to `/tmp/thunderbird-MailApplicationScreen-search-wip-before-42.kt` (SHA256 `a59bb5fef78e42e0f48c0aadeb18cd5ef2083ba2d8a5a67323a7a6c2988c3c9d`) and restored byte-for-byte except for its required new callback call. A recovery stash remains at the top of the stash list. Combined original WIP app tests: 505, zero failures/errors/skips; Detekt and Spotless pass (`/tmp/thunderbird-ut-original-42-wip-check.log`).

Clean-source ARM64 Click: `app-ubuntu-touch/build/ut-candidate-42/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.42_arm64.click`, 159,617,138 bytes, SHA256 `518259853cf38e11863a24d68709dab7382f44df6ac66d73b325bf1d8f48cd91`. Embedded source `2113867b3b9f9d432393410858f26ad496406e2e`; empty `SOURCE-CHANGES.patch`. Clickable review and ARM64 launcher/JVM/AWT/Skiko checks pass (`/tmp/thunderbird-ut-canonical-click-42.log`). The verified candidate-11 runtime and pinned packager were reused.

The FP4 transfer completed. `/tmp/thunderbird-042-upgrade.py` checked the exact package hash, old 0.1.41 source, no app process, and schema-15 SQLite integrity, then made a checked private backup at `/home/phablet/.cache/mail-demo.thomasredstone/upgrades/0.1.42-gvptmf6e`. Phone Manager installed 0.1.42, and the installed clean-source marker matches. The normal launcher printed `Started` but exited 255 after losing its registry connection (`/tmp/thunderbird-042-launch.log`); independent inspection found exact-0.1.42 confined JVM PID `2471673`. Production SQLite remains schema 15, `quick_check=ok`, with zero accounts, folders, and messages. Physical rows, touch/scroll, reader and back navigation remain unaccepted until a disposable mailbox is populated through the phone UI.

## 2026-10-29 reducer-only row gate and FP4 0.1.41

The canonical renderer previously showed directly projected cache rows while its reducer was still warming up or on another folder. Commit `9c39302add` makes that transient frame show a loading message until the current folder's reducer state and event handler are both ready. The new Compose regression failed before the fix and passed after it (`/tmp/thunderbird-ut-reducer-ui-before.log`, `/tmp/thunderbird-ut-reducer-ui-after.log`). The clean candidate's full app suite passed 495 tests, zero failures/errors/skips (`/tmp/thunderbird-ut-reducer-ui-full.log`); focused app Detekt and Spotless passed. Commit `d1f260bb01` versions the Click as `0.1.41`. Both commits were fast forwarded into `feat/ubuntu-touch-demo` with its eight search WIP files still uncommitted. The combined original working tree passed 504 app tests, zero failures/errors/skips (`/tmp/thunderbird-ut-original-41-wip-test.log`).

Clean-source ARM64 Click: `app-ubuntu-touch/build/ut-candidate-41/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.41_arm64.click`, 159,618,986 bytes, SHA256 `83ed125a516ca100d0be7478ff65cc3a419580d3897ce6e49f81c3c3f61dba8f`. Its embedded source is `d1f260bb015d2e8ae2869a7abdecde33fe8e7b21`, and `SOURCE-CHANGES.patch` is empty. Clickable review plus ARM64 launcher/JVM/AWT/Skiko checks passed (`/tmp/thunderbird-ut-canonical-click-41.log`). The verified candidate-11 ARM64 runtime and pinned packager were reused; this is not cold-build proof.

The FP4 transfer completed and `/tmp/thunderbird-041-upgrade.py` checked the exact package hash, old 0.1.40 source and no app process. It made an integrity-checked private schema-15 SQLite backup at `/home/phablet/.cache/mail-demo.thomasredstone/upgrades/0.1.41-x762jdwl` before Phone Manager installed 0.1.41. The installed source marker and empty source patch match the Click (`/tmp/thunderbird-041-upgrade.log`). Normal `lomiri-app-launch` returned zero and printed `Started` (`/tmp/thunderbird-041-launch.log`); an independent check found exact-0.1.41 confined JVM PID `2457531`. Production SQLite remains schema 15 with `quick_check=ok` and zero accounts, folders or messages. Physical list rows, touch/scroll, reader and back navigation remain unaccepted. Populate a disposable mailbox through the app UI and repeat those checks before claiming the phone gate. Do not put credentials in this handover.

## 2026-10-29 reducer-backed list and FP4 0.1.40

The reducer and Click source commit `4657d7c094` was pushed to both `feat/ubuntu-touch-demo` and `feat/ut-canonical-list`. The original worktree is fast forwarded; its three modified and five untracked app-local search WIP files remain **uncommitted**. Before the fast forward they were saved in stash `4154cc53b0` and in `/tmp/thunderbird-search-wip-before-canonical.tar` (SHA256 `a1eefd7cd2310acbbb451d6977717078d8010a024ccce555629036b908bc572f`). The stash remains as recovery. The five untracked files compare byte-for-byte with that backup; the three tracked files retain their search changes alongside the canonical wiring. Do not stage or discard the WIP.

- `cdc87e1011`: fix the shared state machine's delayed initial callback when an immediate cache transition has already left its initial state. The new regression failed through the Ubuntu Touch controller before the fix and passes after it.
- `222d0c3a65`: expose the canonical state-machine factory through the list API and shared composition. Folder changes clear the previous rows, and `MessagesLoaded` updates an already loaded or selecting list. Two focused folder/selection regressions pass.
- `4404db2900`: the Ubuntu Touch mailbox now feeds its current cached folder and messages into the canonical reducer and renders the reducer's state through `MessageListPage`. Canonical row selection/open events enter the reducer before the existing `LiveMailViewModel` performs protected reader navigation and queued flag writes. Search remains in its separately preserved WIP. The controller integration test uses the actual `app-composition` factory and verifies folder isolation plus open dispatch.
- `4657d7c094`: version `0.1.40` and include the changed `core/common` source in Click provenance.

Verification on the clean candidate: full app suite 494 tests, zero failures/errors/skips (`/tmp/thunderbird-ut-state-controller-full.log`); core JVM 163 tests and internal list JVM 35 tests pass; API, internal and core Android host tests plus legacy Android UI compile pass (`/tmp/thunderbird-ut-state-controller-android.log`). App, API, internal and core Detekt/Spotless pass (`/tmp/thunderbird-ut-state-controller-quality2.log`). On the original branch with restored search WIP, the combined app suite passes 503 tests (`/tmp/thunderbird-ut-original-merged-wip-check.log`); its extra app routing needed a documented `LongMethod`/`CyclomaticComplexMethod` suppression in the still-uncommitted `MailApplicationScreen.kt`; combined Detekt and Spotless then pass (`/tmp/thunderbird-ut-original-merged-wip-quality2.log`).

Clean-source ARM64 Click: `app-ubuntu-touch/build/ut-candidate-40/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.40_arm64.click`, 159,619,304 bytes, SHA256 `bfa9870987f2fb61cc6c7f76d0a902466ad73de34b34b8e05618c6b1330c6c17`. Embedded source `4657d7c09430ca72fa00bfacd4d1146993165c2d`; `SOURCE-CHANGES.patch` is empty. Clickable review and launcher/JVM/AWT/Skiko ARM64 checks pass (`/tmp/thunderbird-ut-canonical-click-40.log`). The verified candidate-11 ARM64 runtime and the pinned packager were reused; this is not a cold-build proof.

The FP4 transfer hash matched. `/tmp/thunderbird-040-upgrade.py` verified the installed 0.1.39 source, absence of an app process, and schema-15 SQLite integrity, then made a private checked backup at `/home/phablet/.cache/mail-demo.thomasredstone/upgrades/0.1.40-llfjsoi5`. Phone Manager installed 0.1.40 and the installed clean-source marker matches the Click (`/tmp/thunderbird-040-upgrade.log`). A normal `lomiri-app-launch` returned zero and printed `Started` (`/tmp/thunderbird-040-launch.log`); independent inspection found exact-0.1.40 enforcing JVM PID `2434503` sleeping. Production SQLite remains schema 15 with `quick_check=ok`. The X window probe timed out while the display was inactive, and the database still contains zero accounts, folders and messages. Physical focus, list rows, touch/scroll and reader/back navigation are **not yet accepted**. Populate a disposable mailbox through the app UI before the real cached-list phone gate; do not provide credentials in this handover.

## 2026-10-29 initial canonical list integration candidate (historical)

The initial candidate was developed in the clean worktree `/home/tom/own/thunderbird-android-ut-canonical`, branch `feat/ut-canonical-list`, based on `6e1420c0c5`. At that stage the original `feat/ubuntu-touch-demo` worktree and its eight app-local search WIP files were not edited. The later update above records the fast forward and WIP restoration.

- `b1dd36fcbd`: project the existing protected cache's current folder into canonical `MessageListState` and provide an account/folder-isolated `LegacyMessageListBridge`. Three focused tests pass.
- `2349a9a9dc`: make an empty cached folder complete the canonical loading transition; the two changed Android host assertions failed before the fix and pass after it. The loader now logs only a message count, not message objects. Internal JVM and Android host suites, Detekt, Spotless and legacy Android UI compile pass; log `/tmp/thunderbird-ut-empty-folder-after.log`.
- `cd91117f8f`: resolve the canonical renderer from `app-composition` and show its mobile rows for a cached single-folder mailbox. Row open, selection, star, read and refresh events pass through the existing `LiveMailViewModel` and queued local flag journal. Eight focused adapter/action/Compose integration tests pass. Full app suite: 493 tests, zero failures/errors/skips; Detekt and Spotless pass; log `/tmp/thunderbird-ut-canonical-full-app-test.log`.
- `05adb9d955` and `bfa3e215fc`: version `0.1.39`, include shared composition/list source in the package inventory, and bound the packaging Gradle heap for this host.

The clean-source ARM64 Click built from `bfa3e215fc` with an empty `SOURCE-CHANGES.patch`: `app-ubuntu-touch/build/ut-candidate-39/build/aarch64-linux-gnu/app/mail-demo.thomasredstone_0.1.39_arm64.click`, 159,529,738 bytes, SHA256 `042eb37180e8d0af114194f5918a4fb4dc4ade2160c9638f9af391f337fb90fb`. Clickable default review and launcher/JVM/AWT/Skiko/SQLite ARM64 checks pass; log `/tmp/thunderbird-ut-canonical-click-39.log`. Builder `sha256:3a08b52420bd76280d8323db951a306e428fb17ac5e4cd60f53903e049a68563` and the previously verified candidate-11 ARM64 runtime were reused; this is not a cold-build proof.

FP4 transfer completed (`/tmp/thunderbird-ut-canonical-transfer-39.log`), and the remote package size and SHA256 matched the artifact above. `/tmp/thunderbird-039-upgrade.py` checked the old `0.1.34` source `1f248250f6`, absence of an app process, and the schema-15 database; it made an integrity-checked private backup at `/home/phablet/.cache/mail-demo.thomasredstone/upgrades/0.1.39-cqkqdu1i`. Phone Manager installed `0.1.39` and the installed clean-source marker matches `bfa3e215fc` (`/tmp/thunderbird-039-upgrade.log`). The installer printed its existing user-bus/profile-cache warnings, but the install command exited zero. A normal `lomiri-app-launch` printed `Started` before losing its registry connection (`/tmp/thunderbird-039-launch.log`); independent inspection found an exact-`0.1.39` enforcing JVM, PID `2395670`, sleeping. The production SQLite remains schema 15 with `quick_check=ok`. Window, focus and touch behavior are **not yet accepted**. The database currently has zero accounts, folders and messages, so a mailbox must be populated before the real cached-list phone gate can be exercised.

At this initial stage the app projected `LiveMailState` directly into `MessageListState`; the state-machine controller and feature-branch integration were still pending. The later update above records those changes and the remaining physical acceptance gate. The initial candidate branch was pushed at `430bd77f30`.

## Fresh session starting point

This section and the 2026-10-29 update supersede historical prototype failures and next-action lists below.

- Branch `feat/ubuntu-touch-demo`; Crane's canonical list-page change was incorporated as `a75dd4890f` (from `crane/n-d6if` at `534dd660d0`). Shared composition binding is committed as `168e9b81f6`. These and the handover commit `c720aa8c32` were pushed to `origin/feat/ubuntu-touch-demo` on 2026-10-29.
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

The remaining integration is substantive: `app-ubuntu-touch` does not yet depend on `app-composition`, supply the list's external repository/preference contracts, or translate its existing mailbox identities and events into `MessageListState`. The canonical `MessageListContract.ViewModel.Args` still requires a `LegacyMessageListBridge`; its state machine depends on preference, folder, debug and side-effect contracts. Identify and adapt these contracts explicitly for JVM, then build a controlled end-to-end state/event slice before switching any live UI. The canonical page has therefore not replaced the preview mailbox. Preserve the app-local storage, credential and transaction safeguards until their shared replacements are proven equivalent. No Click was built or installed in this update.

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
