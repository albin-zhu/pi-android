// Runs before bundled CLIs. Android's only executable Node image ends in .so,
// so package managers and user scripts must dispatch child Node calls through
// /system/bin/sh instead of trying to exec that path.
'use strict';
const childProcess = require('node:child_process');
if (process.env.ZFOLD_NODE_EXEC_PATH === '/proc/self/exe') process.execPath = '/proc/self/exe';
const launcher = process.execPath;
const wrapper = process.env.ZFOLD_NODE_WRAPPER;
const isNodeCall = value => value === launcher || value === '/proc/self/exe' || /(^|\/)(node|libnodecli\.so)$/.test(String(value || ''));

if (wrapper && isNodeCall(launcher)) {
  const redirect = args => [wrapper, ...args];
  const originalSpawn = childProcess.spawn;
  childProcess.spawn = function spawn(file, args, options) {
    if (isNodeCall(file)) return originalSpawn('/system/bin/sh', redirect(args || []), options);
    return originalSpawn.apply(this, arguments);
  };
  const originalSpawnSync = childProcess.spawnSync;
  childProcess.spawnSync = function spawnSync(file, args, options) {
    if (isNodeCall(file)) return originalSpawnSync('/system/bin/sh', redirect(args || []), options);
    return originalSpawnSync.apply(this, arguments);
  };
}
