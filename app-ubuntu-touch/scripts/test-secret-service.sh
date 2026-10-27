#!/bin/bash
# Never use the user's session bus or keyring: this test deliberately locks its collection.
set -euo pipefail
MAIL_TEST_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MAIL_TEST_DIR=$(mktemp -d)
trap 'rm -rf "$MAIL_TEST_DIR"' EXIT
mkdir -m 700 "$MAIL_TEST_DIR/data" "$MAIL_TEST_DIR/config" "$MAIL_TEST_DIR/runtime"
export XDG_DATA_HOME="$MAIL_TEST_DIR/data" XDG_CONFIG_HOME="$MAIL_TEST_DIR/config" XDG_RUNTIME_DIR="$MAIL_TEST_DIR/runtime"
export MAIL_KEYRING_TEST_DISPOSABLE=1 MAIL_TEST_ROOT
unset DBUS_SESSION_BUS_ADDRESS GNOME_KEYRING_CONTROL
/usr/bin/dbus-run-session -- bash -eu -c '
  printf "%s" fixture-keyring-password | /usr/bin/gnome-keyring-daemon --unlock --components=secrets --control-directory="$XDG_RUNTIME_DIR/keyring"
  cd "$MAIL_TEST_ROOT"
  ./gradlew :app-ubuntu-touch:secretServiceProbe --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
  ./gradlew :app-ubuntu-touch:secretServiceProbe -PutSecretProbeMode=--disposable-lock --no-configuration-cache -Dorg.gradle.unsafe.isolated-projects=false --max-workers=4
'
