#!/bin/sh
set -eu

export DISPLAY="${DISPLAY:-:9}"
export XAUTHORITY="${XAUTHORITY:-/run/zfold-x11/Xauthority}"

cd /root/.local/share/zfold/wineprefixes/default/drive_c/WarcraftIII
exec windows run explorer.exe /desktop=Warcraft,1024x768 'C:\WarcraftIII\war3.exe' -window -opengl -expansion
