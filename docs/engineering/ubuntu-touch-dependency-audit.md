# Ubuntu Touch dependency audit

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
