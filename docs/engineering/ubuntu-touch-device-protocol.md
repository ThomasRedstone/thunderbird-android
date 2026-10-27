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
classes = list(root.rglob('ConfinedProtocolProbe*.class'))
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
mail_probe_profile=mail-demo.thomasredstone_mail-demo_0.1.9
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
