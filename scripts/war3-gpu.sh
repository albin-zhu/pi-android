#!/bin/sh
set -eu

gpu_lib=/root/.local/share/zfold/gpu/mesa-26.3.0-devel-20260824/usr/lib/aarch64-linux-gnu
export LD_LIBRARY_PATH="$gpu_lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LIBGL_DRIVERS_PATH="$gpu_lib/dri"
export MESA_LOADER_DRIVER_OVERRIDE=kgsl

exec sh "$(dirname "$0")/war3.sh"
