#!/bin/sh
set -eu
MAIL_DEMO_CACHE_ID=mail-demo.thomasredstone
# Click supplies HOME; use the application's confined cache, including AWT/Skiko extraction.
: "${HOME:?HOME is required}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$HOME/.cache}"
export XDG_DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
export TMPDIR="$XDG_CACHE_HOME/$MAIL_DEMO_CACHE_ID/tmp"
# Java creates this directory before loading AWT or parsing MIME bodies.
# Lomiri does not always set DISPLAY for Click-launched Xwayland clients.
if [ -z "${DISPLAY:-}" ]; then
    for socket in /tmp/.X11-unix/X[0-9]*; do
        [ -S "$socket" ] || continue
        number=${socket##*/X}
        case "$number" in
            *[!0-9]*|'') continue ;;
        esac
        export DISPLAY=":$number"
        break
    done
fi
if [ -z "${DISPLAY:-}" ]; then
    echo 'Mail demo: no Xwayland display available' >&2
    exit 1
fi
# Pass confined extraction paths before the JVM starts (AWT caches the temp path).
case "$0" in
    */*) cd "${0%/*}" ;;
esac
exec ./mail-demo/lib/runtime/bin/java "-Djava.io.tmpdir=$TMPDIR" \
    "-Dskiko.data.path=$TMPDIR/skiko" -jar ./mail-demo/lib/app/mail-demo.jar "$@"
