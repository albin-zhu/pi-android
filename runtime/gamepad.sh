#!/bin/sh
# Native arm64 glibc input adapter. Static binaries/direct syscalls and Wine
# DirectInput are not intercepted. ZFOLD_GAMEPAD=0 leaves guest input untouched.
set -eu
if [ "${ZFOLD_GAMEPAD:-1}" != 0 ]; then
    bridge=/opt/pi-phone/input-controls/linux-aarch64/libzfold-gamepad.so
    if [ ! -r "$bridge" ]; then
        printf 'gamepad: missing packaged guest adapter: %s\n' "$bridge" >&2
        exit 1
    fi
    case ":${LD_PRELOAD:-}:" in
        *":$bridge:"*) ;;
        *) LD_PRELOAD="$bridge${LD_PRELOAD:+:$LD_PRELOAD}"; export LD_PRELOAD ;;
    esac
    device=/run/zfold-x11/gamepad/event0
    case ":${SDL_JOYSTICK_DEVICE:-}:" in
        *":$device:"*) ;;
        *) SDL_JOYSTICK_DEVICE="${SDL_JOYSTICK_DEVICE:+$SDL_JOYSTICK_DEVICE:}$device"; export SDL_JOYSTICK_DEVICE ;;
    esac
fi
exec "$@"
