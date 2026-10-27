#!/bin/bash
set -euo pipefail
[[ $# = 1 && -f "$1" ]] || { echo 'Usage: verify-click.sh package.click' >&2; exit 1; }
PACKAGE=$(realpath "$1")
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
dpkg-deb -x "$PACKAGE" "$TEMP"
APP="$TEMP/mail-demo"
check_arm64() {
    readelf -h "$1" | grep -q 'Machine:.*AArch64' || { echo "Not ARM64: $1" >&2; exit 1; }
}
check_arm64 "$APP/bin/mail-demo"
check_arm64 "$APP/lib/runtime/bin/java"
check_arm64 "$APP/lib/runtime/lib/server/libjvm.so"
for lib in "$APP"/lib/runtime/lib/libawt*.so; do check_arm64 "$lib"; done
found=0
for jar in "$APP"/lib/app/*skiko*.jar; do
    if unzip -Z1 "$jar" | grep '^libskiko-linux-arm64.so$' > /dev/null; then
        unzip -p "$jar" libskiko-linux-arm64.so > "$TEMP/skiko.so"
        check_arm64 "$TEMP/skiko.so"
        found=1
    fi
    if unzip -Z1 "$jar" | grep -E '^libskiko-linux-x64.so$' > /dev/null; then
        echo 'Unexpected host Skiko library in ARM64 package' >&2
        exit 1
    fi
done
[[ "$found" = 1 ]] || { echo 'ARM64 Skiko library missing' >&2; exit 1; }
# AndroidX ships a multi-platform JAR. Validate the ARM64 member that the
# driver's native loader selects; preserve the original JAR and licence.
for jar in "$APP"/lib/app/*sqlite-bundled-jvm*.jar; do
    [[ -f "$jar" ]] || continue
    unzip -p "$jar" natives/linux_arm64/libsqliteJni.so > "$TEMP/sqlite.so"
    check_arm64 "$TEMP/sqlite.so"
done
sha256sum "$PACKAGE"
stat --printf='Package bytes: %s\n' "$PACKAGE"
echo 'ARM64 launcher, JVM, AWT and Skiko verified'
