#!/bin/sh
set -eu
# Kept in the APK-owned /opt/pi-phone bind, not in the guest's system bin dir.
if ! command -v python3 >/dev/null 2>&1; then
    if [ "${1:-}" != install ]; then
        echo 'windows: Python 3 is missing; run windows install first.' >&2
        exit 1
    fi
    echo 'The optional Windows installer needs Ubuntu python3.'
    if [ "${2:-}" != --yes ]; then
        printf 'Install python3 using apt? [y/N] '
        read -r answer
        case "$answer" in y|Y|yes|YES) ;; *) exit 1 ;; esac
    fi
    apt-get update
    apt-get install -y --no-install-recommends python3
fi
exec python3 /opt/pi-phone/windows.py "$@"
