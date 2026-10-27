#!/bin/bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
mkdir -p "$TEMP/app/mail-demo/lib/runtime/bin" "$TEMP/home with spaces"
cp "$HERE/launch.sh" "$TEMP/app/launch.sh"
cat > "$TEMP/app/mail-demo/lib/runtime/bin/java" <<'JAVA'
#!/bin/sh
set -eu
printf '%s\n' "$DISPLAY" "$TMPDIR" "$APP_ID" "$@" > "$HOME/launcher-result"
JAVA
chmod +x "$TEMP/app/mail-demo/lib/runtime/bin/java"
APP_ID=mail-demo.thomasredstone_mail-demo_test PATH=/nonexistent HOME="$TEMP/home with spaces" DISPLAY=:87 XDG_CACHE_HOME="$TEMP/cache with spaces" \
    "$TEMP/app/launch.sh" 'argument with spaces'
EXPECTED="$TEMP/cache with spaces/mail-demo.thomasredstone/tmp"
grep -Fx 'mail-demo.thomasredstone_mail-demo_test' "$TEMP/home with spaces/launcher-result"
grep -Fx ':87' "$TEMP/home with spaces/launcher-result"
grep -Fx -- "-Djava.io.tmpdir=$EXPECTED" "$TEMP/home with spaces/launcher-result"
grep -Fx -- "-Dskiko.data.path=$EXPECTED/skiko" "$TEMP/home with spaces/launcher-result"
grep -Fx 'argument with spaces' "$TEMP/home with spaces/launcher-result"
echo 'Launcher preserves DISPLAY, confined paths and argument boundaries'
