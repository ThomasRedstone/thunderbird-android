# Ubuntu Touch controlled device protocol checks

This opt-in harness verifies the installed production engine on the FP4 under its enforcing Click profile. It uses
only synthetic mail and newly created test credentials. It does not establish physical UI acceptance, a real provider
account, STARTTLS interoperability, cancellation or background delivery.

## Prerequisites

- Install the candidate matching the source being tested; record its source commit, hash and profile version.
- Use the documented JDK 21/SDK/Xvfb setup. Run the scoped app tests, detekt and spotlessCheck first.
- The phone must reach a local host interface, such as the host's tailnet address. Bind the fixture to that interface;
  do not use an unrelated mail server. GreenMail does not forward mail.
- Keep two host terminals: one for the fixture server and another for the phone commands. No phone unlock is needed
  for these headless probes. The full screen/input journey still requires it.

## Start the controlled host server

Choose a new absolute output directory; it must not contain a `stop` file from an earlier run. Substitute the host's
reachable local IP. The opt-in Gradle task uses only the test runtime classpath:

```sh
./gradlew :app-ubuntu-touch:phoneProtocolServer \
  -PutProtocolHost=HOST_LOCAL_IP -PutProtocolOutput=/absolute/new/evidence/run \
  --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
```

Wait for READY. Read `endpoints.properties` for the chosen IMAPS/SMTPS ports. The server generates an ephemeral
certificate whose IP SAN matches that interface. Only its public `fixture.cer` is copied to the phone; the private
key stays in a temporary host directory and is removed on normal server teardown. Default production trust is never
changed. The probe first asserts that default trust rejects the certificate, then explicitly injects fixture trust
into its own production socket-factory instance.

The server seeds a controlled inbox and accepts only synthetic test mail. It waits up to ten minutes for the output
`stop` marker. On stop, it asserts exactly one expected outgoing message with the intended recipient and Unicode/
leading-dot body. Retain its terminal exit status as well as its final assertion output.

## Build and upload the test-only probe

`testClasses` is built by the server task or the normal tests. Package only the probe's own classes, not replacement
production classes or the GreenMail server dependency:

```sh
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile
root = Path('app-ubuntu-touch/build/classes/kotlin/test')
patterns = ['ConfinedProtocolProbe*.class', 'ConfinedCancellationProbe*.class',
            'ObservedPlainSockets*.class', 'ConfinedComposerProbe*.class']
classes = sorted({path for pattern in patterns for path in root.rglob(pattern)})
assert classes
with ZipFile('/tmp/mail-protocol-probe.jar', 'w') as jar:
    for path in classes:
        jar.write(path, path.relative_to(root))
PY
```

Copy this JAR and the generated public certificate into the app's private cache `probes` directory, using the
established SSH/Phone Manager device workflow. Keep artifact names distinct from production JARs. The probe uses the
installed bundled JVM and the installed `mail-demo/lib/app/*` classpath. No modified engine, trust-store replacement
or confinement exemption is used.

## Receive, send and prepare restart state

On the phone, export the user session bus variables as in the credential checks. Substitute the exact installed
profile/version, the observed host/ports and the uploaded certificate path:

```sh
export XDG_RUNTIME_DIR=/run/user/32011
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/32011/bus
mail_probe_profile=mail-demo.thomasredstone_mail-demo_0.1.10
mail_probe_app=/opt/click.ubuntu.com/mail-demo.thomasredstone/current/mail-demo
mail_probe_cache=/home/phablet/.cache/mail-demo.thomasredstone/probes

aa-exec -p "$mail_probe_profile" -- "$mail_probe_app/lib/runtime/bin/java" \
  -Djava.awt.headless=true -Djava.io.tmpdir="$mail_probe_cache" \
  -cp "$mail_probe_cache/mail-protocol-probe.jar:$mail_probe_app/lib/app/*" \
  net.thunderbird.app.ubuntutouch.ConfinedProtocolProbe \
  "$mail_probe_profile" HOST_LOCAL_IP IMAPS_PORT SMTPS_PORT \
  "$mail_probe_cache/fixture.cer" retain-for-restart
```

The probe verifies its actual enforcing profile, creates a new private cache database and a uniquely referenced
SignOn credential, then checks certificate rejection, trusted TLS settings, IMAP folders/body, SMTP outbox acceptance
and duplicate-attempt prevention. No password or message content is printed. Success prints `PREPARED_FIXTURE=...`.
Record that exact path. This retained fixture includes a protected-service reference and must be cleaned by the
restart step; do not simply delete its index directory.

## Stop mail servers, then reopen in a new phone JVM

Create the host output `stop` marker and wait for the host Gradle task to exit successfully. This checks server-observed
delivery and closes both mail listeners. Keep that evidence before starting the fresh phone process.

Run the same `aa-exec`/JVM command with only the profile and the recorded fixture directory as application arguments:

```sh
aa-exec -p "$mail_probe_profile" -- "$mail_probe_app/lib/runtime/bin/java" \
  -Djava.awt.headless=true -Djava.io.tmpdir="$mail_probe_cache" \
  -cp "$mail_probe_cache/mail-protocol-probe.jar:$mail_probe_app/lib/app/*" \
  net.thunderbird.app.ubuntutouch.ConfinedProtocolProbe \
  "$mail_probe_profile" /recorded/private/protocol-runtime-directory
```

The new JVM reads the protected credentials, cached body and sent state while the mail servers are stopped. It then
removes its account/credential and checks fixture-directory deletion. It accepts only its marked directory directly
under this app's cache namespace. If this restart step’s credential cleanup fails, the index is deliberately retained for retry; use the
same restart command after resolving the service failure. Never clear unrelated system identities or delete an index
before its credentials have been removed. If preparation fails and leaves an unmarked fixture, retain it and
diagnose its specific credential/index state before removing anything.

The default five-argument mode (without `retain-for-restart`) performs the network checks and immediate cleanup in
one process. This is useful for a smoke check, but it does not prove fresh-process recovery.

## Recorded execution

2026-10-27, installed 0.1.9/source `131b9b801200410095c5267e0a8aa9c2568d826c`, FP4 Ubuntu Touch 24.04 build 708:

- First confined network run and immediate cleanup: exit 0; independent host observation: exit 0.
- Second confined network run retaining state: exit 0; independent host observation and server teardown: exit 0.
- Fresh phone JVM after server teardown: exit 0; protected credentials, cached body and outbox reopen pass.
- Fixture-directory removal independently checked over SSH. No real application database or existing account was used.
- 63 app tests and detekt/spotlessCheck pass after the harness changes. Evidence is retained under
  `app-ubuntu-touch/build/evidence/device-protocol/`, including server/phone results for both runs.

The expected negative-certificate handshake can produce GreenMail server-side closed-socket diagnostics. The positive
assertions, cleanup and process exit statuses still have to pass; diagnostic output alone is not a success signal.

## Model-driven cancellation fixture

For 0.1.10, the same test-only JAR also contains `ConfinedCancellationProbe`. Start a separate controlled host TCP
listener that accepts one connection but never supplies its greeting. Substitute the host's local test IP:

```sh
python3 - HOST_LOCAL_IP <<'PY'
import socket, sys
with socket.socket() as server:
    server.bind((sys.argv[1], 0))
    server.listen(1)
    server.settimeout(120)
    print('PORT=' + str(server.getsockname()[1]), flush=True)
    peer, _ = server.accept()
    with peer:
        peer.settimeout(15)
        assert peer.recv(1) == b''
    print('EOF with zero protocol/credential bytes: PASS')
PY
```

Run the installed enforcing-profile JVM exactly as above, replacing the entry point/arguments:

```sh
aa-exec -p "$mail_probe_profile" -- "$mail_probe_app/lib/runtime/bin/java" \
  -Djava.awt.headless=true -Djava.io.tmpdir="$mail_probe_cache" \
  -cp "$mail_probe_cache/mail-protocol-probe.jar:$mail_probe_app/lib/app/*" \
  net.thunderbird.app.ubuntutouch.ConfinedCancellationProbe \
  "$mail_probe_profile" HOST_LOCAL_IP STALL_PORT
```

The probe checks schema 5, creates a disposable protected account and drives the default Swing-dispatched mailbox
model. Once a passive test socket observer confirms real TCP establishment, it requests Cancel, checks that busy and
cancelling remain set initially, then requires settled state within five seconds. It removes its own account,
credential and private fixture. Both the phone and host checks must exit 0; host EOF alone could also result from a
failed/killed client. No real mail database/account or production TLS configuration is used.

2026-10-27: cancellation and schema recovery pass on installed 0.1.10/source `080d86f30cbb32e9010747fd2933686d58d4bb07`.
The complete controlled TLS/protected-account receive/send and fresh-JVM reopen after server teardown also pass again.
79 app tests/quality checks pass; prior 863 shared-mail tests/quality checks remain unchanged. Results are retained
under `app-ubuntu-touch/build/evidence/cancel-ui/native/`. Physical UI, successful STARTTLS, provider and broader
interruption/lifecycle gates remain open.


## Successful required STARTTLS

The host fixture now supports `-PutProtocolSecurity=starttls` on `:app-ubuntu-touch:phoneProtocolServer`. Supply
`utProtocolHost` and a new absent `utProtocolOutput` as above. Its front ends bind the supplied test interface and forward
to loopback-only plain GreenMail backends after a real TLS handshake. Pre-upgrade authentication is rejected.

Build the probe JAR using only `ConfinedProtocolProbe*.class`, upload it with this run's generated `fixture.cer`, and use
installed profile `mail-demo.thomasredstone_mail-demo_0.1.11` and the installed JVM/JAR classpath. Append
`retain-for-restart starttls` after the certificate argument in the preparation command. This chooses
STARTTLS_REQUIRED for both IMAP and SMTP without changing production settings or trust. The fixture still rejects the
unknown certificate under default trust before using its injected test trust.

After preparation exits 0, create `stop` in the host output directory and wait for the host process to exit 0, including
its independent recipient/body/single-delivery assertions. Only then run the existing two-argument fresh-JVM command
with the printed private fixture directory. Verify credential/account/directory cleanup independently over SSH.

2026-10-27: this complete STARTTLS flow passes under 0.1.11 confinement, including fresh-JVM recovery after server
teardown. Evidence is in `build/evidence/starttls/native-run-2/`. It uses installed production code without overlays;
the phone remains 0.1.11. Physical UI/provider acceptance and broader interruption coverage remain open.


## Native offline read/star reconciliation

On installed 0.1.13, start the host fixture with `-PutProtocolSecurity=starttls -PutProtocolFlags=flags` and a new output
directory. Build/upload only the matching `ConfinedProtocolProbe*.class` JAR and this run's certificate. Append
`retain-for-restart starttls flags` to the preparation arguments. The probe queues SEEN/FLAGGED locally and reconciles
on explicit folder refresh, then checks the pending queue is empty. No production class overlay or trust change is used.

Stop the host only after preparation exits 0. Host teardown independently reads flags through a separate Jakarta IMAP
connection and requires both SEEN and FLAGGED, alongside the existing independent SMTP assertions. Wait for host exit 0
before the two-argument fresh-JVM recovery probe. That probe also verifies cached flags and removes its protected account
and private fixture; check absence over SSH. The complete flow passes on 0.1.13, with evidence in
`build/evidence/flags/native-run-1/`. Physical UI/provider and broader authenticated interruption gates remain open.


## Native reply threading

On installed 0.1.14, add `-PutProtocolReply=reply` after the STARTTLS/flags host fixture properties. Its incoming fixture
has a fixed Message-ID/References and Reply-To. Append `retain-for-restart starttls flags reply` to phone preparation
arguments using the matching test-only probe JAR. The installed production factory creates a reply from cached MIME,
checks its quote/threading, edits the body, queues and sends it. Repeated send is suppressed.

Host stop now also requires a new reply Message-ID and exact In-Reply-To/References in the independently received SMTP
message. Wait for host exit 0 before fresh-JVM recovery, which checks persisted threading as well as cached flags/body,
protected credentials and SENT state, then cleans its fixture. This flow passes under 0.1.14 confinement; evidence is
`build/evidence/responses/native-run-1/`. Native composer Resume/Maliit edit/flush and cancellation regressions also pass.
This is not physical UI acceptance or complete attachment/HTML forwarding coverage.

## Automatic Sent filing fixture

On installed 0.1.15, add `-PutProtocolSent=sent` to the STARTTLS/flags/reply host fixture command and append `sent` as
the tenth phone probe argument. The host creates the owner's Sent folder before readiness. The phone configures that
cached folder and uses production automatic filing after durable SMTP acceptance. Repeated send/file calls must not
create another delivery or copy. The host independently inspects recipient Inbox and owner Sent mailboxes, including
matching Message-ID, body, threading and the read flag before body fetch. Never use the server's global message list
as a delivery count: IMAP APPEND legitimately adds an owner copy there.

Wait for phone preparation success, stop the host and require its final assertions/exit 0, then run the two-argument
fresh-JVM probe against the retained fixture directory. It must verify SAVED copy state alongside cached mail,
credentials and SENT state without servers, then remove its private account/fixture. Independently confirm cleanup.
This passes on 0.1.15; physical/provider and actual transport APPEND-response-loss acceptance remain open.

## Cached search on 0.1.16

The existing STARTTLS/flags/reply/Sent protocol fixture now also checks offline cached search using installed production
code: a positive subject/body query, a missing term and an empty query restoring the full folder. No extra probe
argument is needed. After stopping the host and requiring its final independent assertions/exit 0, the fresh-JVM
recovery probe repeats search with the servers absent. Both phases pass under the 0.1.16 enforcing profile.
This checks the local repository/model boundary; the physical Search field, Maliit keyboard and result navigation
still need the device UI journey. Account/unified/server scopes and large-mailbox performance remain open.
