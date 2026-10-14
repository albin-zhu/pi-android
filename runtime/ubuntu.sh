#!/system/bin/sh
# App-owned Linux user space; Android permissions stay in the phone tool service.
set -eu
fail() { printf 'ubuntu: %s\n' "$*" >&2; exit 1; }
[ -n "${PREFIX:-}" ] || fail '请从应用终端启动，以加载运行环境。'
runtime=$(CDPATH= cd -- "$PREFIX/../.." && pwd -P)
rootfs="$runtime/ubuntu"
proot="$PREFIX/bin/proot"
[ -x "$proot" ] || fail '缺少 PRoot；请先在应用终端运行 pkg install proot。'
[ -x "$rootfs/usr/bin/env" ] || fail 'Ubuntu 尚未安装；请使用此分支的 scripts/setup-ubuntu.py 安装实验环境。'
[ -d "$runtime/phone-tools" ] || fail '当前 APK 缺少手机工具扩展，请安装此分支构建的 APK。'
[ -n "${ZFOLD_PHONE_SOCKET:-}" ] || fail '手机工具服务未就绪，请重新打开应用终端。'
[ -n "${ZFOLD_AGENT_ROOT:-}" ] || fail '缺少工作区数据根，请重新打开应用终端。'
# All paths here are App-controlled, not shell text from the agent.
case "$ZFOLD_AGENT_ROOT" in *\"*|*\\*) fail '工作区路径不能包含 JSON 转义字符。';; esac
export PROOT_TMP_DIR="$TMPDIR"
export PROOT_LOADER="$PREFIX/libexec/proot/loader"
export PROOT_LOADER_32="$PREFIX/libexec/proot/loader32"
[ "$#" -gt 0 ] || set -- /bin/bash -l
exec "$proot" --link2symlink --kill-on-exit -0 -r "$rootfs" \
  -b /dev -b /proc \
  -b "$runtime/phone-tools:/opt/pi-phone" \
  -b "$ZFOLD_AGENT_ROOT:/workspace" -w /workspace \
  /usr/bin/env -i HOME=/root TMPDIR=/tmp PATH=/usr/local/bin:/usr/bin:/bin \
  LANG=C.UTF-8 TERM="${TERM:-xterm-256color}" COLORTERM=truecolor \
  ZFOLD_PHONE_SOCKET="$ZFOLD_PHONE_SOCKET" \
  ZFOLD_PHONE_PATH_MAP="{\"hostRoot\":\"$ZFOLD_AGENT_ROOT\",\"guestRoot\":\"/workspace\"}" \
  "$@"
