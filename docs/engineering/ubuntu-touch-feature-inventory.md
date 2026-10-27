# Ubuntu Touch feature inventory

Reviewed against Thunderbird baseline `de8a03268415ce8e0c8b0327d3bbcb6b40a06a91` on 2026-10-27.
Each source anchor below was checked to exist at that commit and to match the working tree byte-for-byte.
UT implementation checkpoint: `a938b0424efadb2c9753ef80e58ee4b11fd5992f`; phone deployment is recorded separately
in [milestone 2 evidence](ubuntu-touch-m2-evidence.md). A source/module anchor proves an implementation exists to
investigate, not that it can be linked into the UT application or that its feature is complete upstream.

This is the first reviewed inventory, not an exhaustive certification of every preference/action. Remaining inventory
work includes enumerating legacy settings and message-list/reader/composer commands, accessibility/localisation,
branding/funding integrations, and mapping each concrete command to an adapter and named tests. These are open work.
`Partial` means implemented behaviour with explicit missing scope or acceptance; no row is a silent exclusion.
Platform-specific rows require an equivalent or explicit user scope decision. Baseline-incomplete JMAP is an assessment
item, not automatic inclusion or exclusion. No architecture or provider configuration is approved by this inventory.

| Capability | Pinned source anchor | UT status | Existing evidence / integration boundary | Required next evidence |
| --- | --- | --- | --- | --- |
| IMAP / SMTP | [ImapBackend.kt](../../backend/imap/src/main/java/com/fsck/k9/backend/imap/ImapBackend.kt) | Partial | ForegroundMailSession; protected accounts and confined TLS flows pass | Physical/provider setup, lifecycle and interruption matrix |
| POP3 | [Pop3Backend.kt](../../backend/pop3/src/main/java/com/fsck/k9/backend/pop3/Pop3Backend.kt) | Not started | JVM backend exists; UT account validation currently accepts IMAP only | POP3 adapter/storage/UIDL retention, reconnect and provider journeys; respect unsupported flag/move capabilities |
| JMAP | [JmapBackend.kt](../../backend/jmap/src/main/java/com/fsck/k9/backend/jmap/JmapBackend.kt) | Incomplete baseline / not started on UT | Backend exists, but complete-message download throws UnsupportedOperationException and push is disabled | Assess baseline product availability and required scope explicitly; do not call a JVM module complete protocol support |
| Provider OAuth | [GetOAuthRequestIntent.kt](../../feature/account/oauth/src/main/kotlin/app/k9mail/feature/account/oauth/domain/usecase/GetOAuthRequestIntent.kt) | Partial, source only | PKCE/state, loopback HTTP receiver and dispatcher adapter; setup/token/provider integration absent | Browser/callback, registration, refresh/expiry/revocation/cancel; no provider config edits without explicit instruction |
| Plain-text / MIME reading | [MessageExtractor.java](../../mail/common/src/main/java/com/fsck/k9/mail/internet/MessageExtractor.java) | Partial | Engine extraction, cached reader, attachment classification tests and native cache recovery | Charset/malformed/large MIME suite and physical reader acceptance |
| HTML / remote content / links | [MessageWebView.kt](../../legacy/ui/legacy/src/main/java/com/fsck/k9/view/MessageWebView.kt) | Not started | Android WebView disables JavaScript and initially blocks network loads | Reviewed Linux renderer boundary, sanitisation, remote-content policy, inline images and safe links; privacy network oracle |
| Attachment open / save / share | [AttachmentProvider.java](../../legacy/core/src/main/java/com/fsck/k9/provider/AttachmentProvider.java) | Not started | Android provider is a portability boundary | UT Content Hub/file handling, filenames/MIME safety, cancellation, lifetime and confinement tests |
| Plain-text compose / drafts / recipients | [MessageCompose.java](../../legacy/ui/legacy/src/main/java/com/fsck/k9/activity/MessageCompose.java) | Partial | UT persistent drafts/outbox, To/Cc/Bcc, native editor and SMTP privacy tests | Physical editor/provider journeys, full composition parity and large-message handling |
| Reply / reply all / forward | [MessageCompose.java](../../legacy/ui/legacy/src/main/java/com/fsck/k9/activity/MessageCompose.java) | Partial | Cached plain-text response drafts and threading delivered; forward is explicitly text only | HTML/attachment forwarding, HTML-only responses, quoting preferences |
| Identities / signatures | [Identity.kt](../../core/android/account/src/main/kotlin/net/thunderbird/core/android/account/Identity.kt) | Not started | UT has primary account sender only | Multiple identities, reply identity selection, per-identity signatures and isolation tests |
| HTML compose / outgoing attachments | [DefaultMessageComposerHtmlSettingsProvider.kt](../../feature/mail/message/composer/internal/src/main/kotlin/net/thunderbird/feature/mail/message/composer/internal/html/DefaultMessageComposerHtmlSettingsProvider.kt) | Not started | Upstream composer settings are source evidence, not a portable complete UI | Editor and MIME builder integration, attachment lifecycle, multipart privacy/provider tests |
| Sent copies | [ImapBackend.kt](../../backend/imap/src/main/java/com/fsck/k9/backend/imap/ImapBackend.kt) | Partial | Independent SMTP/copy state, search-before-APPEND, explicit uncertain retry; native single-copy and host wire-loss tests | Uncertain target rename/removal, provider auto-file matrix, physical controls and background filing |
| Read / unread / star | [ImapBackend.kt](../../backend/imap/src/main/java/com/fsck/k9/backend/imap/ImapBackend.kt) | Partial | Durable offline assignments, UIDVALIDITY guards, native server reconciliation | Conflict/provider/physical journeys and background replay |
| Archive / trash / move / copy / bulk | [BuildSwipeActions.kt](../../feature/mail/message/list/internal/src/main/kotlin/net/thunderbird/feature/mail/message/list/internal/domain/usecase/BuildSwipeActions.kt) | Not started | Shared backend actions and Android list interaction need UT wiring | Durable offline operations, ambiguous outcomes, UIDVALIDITY, multi-select and independent mailbox assertions |
| Folder management / special folders | [GetAccountFolders.kt](../../feature/mail/message/list/internal/src/main/kotlin/net/thunderbird/feature/mail/message/list/internal/domain/usecase/GetAccountFolders.kt) | Partial | Discovery/cache and Sent target selection exist | Create/rename/delete/subscriptions as supported, special-folder settings and recovery |
| Multiple accounts / unified views | [MessageSearchSpecification.kt](../../feature/search/impl-legacy/src/commonMain/kotlin/net/thunderbird/feature/search/legacy/api/MessageSearchSpecification.kt) | Partial | Account-scoped storage/settings and account switching exist; no unified inbox | Unified navigation/results, identity isolation, removal while operations are active |
| Search / filters / threading / sorting | [MessageSearchSpecification.kt](../../feature/search/impl-legacy/src/commonMain/kotlin/net/thunderbird/feature/search/legacy/api/MessageSearchSpecification.kt) | Partial | Deployed 0.1.16 cached folder sender/subject/plain-text search; 137 host app tests | 0.1.17 adds account-wide and unified cached results/routing with confined model checks; physical UI/provider acceptance and server search, filters, thread navigation, sorting, indexing/pagination and measured cancellation latency |
| Background delivery / notification actions | [NotificationActionService.kt](../../legacy/core/src/main/java/com/fsck/k9/notification/NotificationActionService.kt) | Not started | UT session explicitly refuses wake-lock/push adapters | Supported background design, storage ownership/IPC if needed, notification privacy/actions and locked/reboot battery/latency tests |
| OpenPGP / signing / key management | [OpenPgpApiFactory.java](../../legacy/ui/legacy/src/main/java/com/fsck/k9/ui/crypto/OpenPgpApiFactory.java) | Not started | Android Context/OpenPGP service boundary is explicit | Reviewed Linux provider/crypto design; known vectors and key/encrypt/decrypt/sign/verify/tamper journeys |
| Export / import / retention | [SettingsExporter.kt](../../legacy/core/src/main/java/com/fsck/k9/preferences/SettingsExporter.kt) | Partial | UT versioned schema/recovery tests and private pre-upgrade backup exist | User export/import, retention/cache limits, disk pressure/corruption recovery, data-removal policy; Android ContentResolver cannot carry over |
| Contacts / recipient completion | [ContactRepository.kt](../../core/android/common/src/main/kotlin/app/k9mail/core/android/common/contact/ContactRepository.kt) | Platform-specific / not started | Android contact repository needs UT equivalent | Supported contacts interface, permission/privacy handling and recipient completion tests |
| Widgets / shortcuts | [BaseUnreadWidgetProvider.kt](../../feature/widget/unread/src/main/kotlin/app/k9mail/feature/widget/unread/BaseUnreadWidgetProvider.kt) | Platform-specific / not started | Android widget provider; unread/message-list/shortcut modules exist | Explicit UT equivalent or user-approved scope decision with documented consequence |

## Verification and release gates

Current implementation tests are recorded in the milestone evidence and `app-ubuntu-touch/src/test/`; do not treat
all 137 app tests as coverage for every row. Native probes establish engine/model/confinement behaviour, not physical
UI completion. Physical secure input, rotation, focus/keyboard avoidance, suspend/resume, accessibility and provider
journeys remain open even when host tests pass. Cached search still lacks large-mailbox acceptance.

Cross-cutting release work remains: fresh-cache/reproducible CI builds; architecture/Click review; dependency/source
and licence inventories; source/artifact/fleet/device alignment; upgrade/uninstall/backup/recovery; startup/memory/CPU/
storage budgets; network transitions, disk pressure, repeated lifecycle and battery/notification soak. These are the
roadmap's milestones 4–6 acceptance requirements and cannot be inferred from an installable preview.
