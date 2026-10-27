# Ubuntu Touch HTML reader feasibility

Investigation checkpoint, 2026-10-27. This records evidence and required experiments; it does not select a renderer,
approve a dependency/permission change or claim HTML support. The deployed preview still reads cached plain text.

## Reusable source and privacy boundary

`library:html-cleaner` is a JVM library (`library/html-cleaner/build.gradle.kts`) depending on the repository's jsoup
version, currently 1.19.1 in `gradle/libs.versions.toml`. Its public `HtmlProcessor.processForDisplay` can be investigated
without importing the Android-dependent legacy HtmlProcessorFactory. The current UT app does not depend on it.
The cleaner's own tests cover sanitisation; they are not a renderer/network privacy oracle.

Source inspection shows that BodyCleaner preserves style attributes/style tags, background attributes, remote image
URLs, cid/data image schemes and arbitrary anchor schemes. HeadCleaner preserves selected style/meta/base nodes while
removing meta-refresh. Therefore **sanitised output still requires a renderer resource and navigation boundary**. It
cannot be loaded into a generic browser and assumed private. Existing Android MessageWebView separately disables
JavaScript and blocks network loads initially; those Android controls do not carry into a Linux renderer automatically.

The shared reader's JVM MailPart is only an interface. It is not evidence of a working JVM HTML widget. Reuse the
engine's viewable-part selection so HTML alternatives, attachments and nested messages retain their intended roles.
No HTML-to-plain-text conversion or basic styled-text display will be counted as completing the HTML reader gate.

## Device and API evidence

A read-only package query on the FP4 reports GTK 3.24.41 and UBports Qt WebEngine 5.15.19 runtime packages. The query
found no installed OpenJFX package. This establishes available runtime candidates, not development headers, compatible
ABI, security maintenance, confinement compatibility or a working Compose embedding bridge. No package was installed.

The official [JavaFX 21 WebEngine API](https://openjfx.io/javadoc/21/javafx.web/javafx/scene/web/WebEngine.html) documents
in-memory HTML loading, a JavaScript enable switch and its own application-thread requirement. It also documents
HTTP/2 using HttpClient. A JavaFX trial must prove its actual resource/network boundary; intercepting only one assumed
URL-loading path is insufficient. ARM64 binaries, bundled native dependency inventory and patch/update policy remain
unverified for this app/runtime.

The official [Qt 6.8 request-interceptor API](https://doc.qt.io/qt-6.8/qwebengineurlrequestinterceptor.html) documents
blocking requests before Chromium networking. Qt's [security guidance](https://doc.qt.io/qt-6.8/qtwebengine-security.html)
also recommends restricting JavaScript and local-file access. These are Qt 6 references: the phone has Qt 5.15, so
version-matched API/behaviour and security-maintenance verification are still required. Do not infer compatibility or
change the phone's Qt installation from these references.

## Bounded experiments before an integration decision

1. Verify current renderer/dependency security support and ARM64 supply chain. Retain versions, licences, source and
   build inputs. Establish a viable update path; a renderer available on one phone is not a supported dependency.
2. Build a disposable native/desktop harness for each viable candidate. Prove a Compose-compatible surface/window,
   scrolling, selection, links, focus, Maliit/keyboard avoidance, rotation and suspend/resume under app confinement.
   Do not copy an unrelated application's lifecycle exemption or weaken AppArmor to force a pass.
3. Enforce an initial no-network/no-local-file policy before parsing/loading mail. Disable scripts, plugins, popups,
   form submission, downloads, web permissions, persistent cookies/storage and automatic navigation. Isolate each
   message/document and clear renderer state between accounts. Do not expose host objects or an untrusted script bridge.
4. Serve adversarial fixtures and an independent DNS/HTTP/TLS oracle: img/srcset, CSS url/import/fonts, body/table
   backgrounds, base-relative URLs, redirects, SVG, media, meta refresh, iframe/object, anchors, ping, data/cid URLs,
   file/custom schemes and malformed/oversized input. Default rendering must generate no remote requests. The oracle
   must inspect actual traffic; sanitiser snapshots alone cannot prove that property.
5. Design cid attachment access as exact account/message/part lookups, not arbitrary filesystem URLs. Bound decoded
   resources and lifetime, handle cancellation/errors, prevent traversal/foreign-account access and verify cleanup.
6. Define explicit per-message remote-image consent and safe external-link handoff with visible destination. Verify
   that consent permits only intended resource classes and cannot grant scripts, file access or persistent tracking.
7. Exercise realistic tables/styles, inline images, long/non-ASCII content, malformed MIME/HTML, dark mode, zoom and
   accessibility. Measure package size, startup, memory and large-message rendering. Keep a readable plain-text fallback.

Only after these experiments should the integration choose in-process embedding versus a confined helper and, if a
helper is needed, define narrow authenticated IPC, process lifecycle/storage ownership and the permitted composition
boundary. No renderer choice or platform-specific exclusion is approved by this investigation. HTML composition,
HTML-only replies, attachment forwarding and HTML search indexing remain separate required capabilities.
