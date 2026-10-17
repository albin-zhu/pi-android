#!/usr/bin/env python3
"""Optional, pinned Wine WoW64/Box64 runtime for the app-owned ARM64 Ubuntu.

Installation never registers binfmt handlers or replaces system wine/box64.
Windows programs run with this Ubuntu user's permissions, not in a sandbox.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

VERSION = 'wine-10.0-box64-b99d443'
ASSETS = (
    ('wine-10.0-amd64-wow64.tar.xz',
     'https://github.com/Kron4ek/Wine-Builds/releases/download/10.0/wine-10.0-amd64-wow64.tar.xz',
     'aeebbbf239e548f0136f1cd72a2e109dd9a572a8f703da3c09fa40d74d5c255f'),
    ('box64-android.deb',
     'https://ryanfortner.github.io/box64-debs/debian/box64-android_0.4.5+20261016.b99d443-1_arm64.deb',
     '967539e7b72328eddabd8ade832e92d2b6804e911f57793c7b116afaf3500502'),
)
PACKAGES = (
    'ca-certificates', 'libx11-6', 'libxext6', 'libxrender1', 'libxrandr2',
    'libxi6', 'libxcursor1', 'libxfixes3', 'libxinerama1', 'libxxf86vm1',
    'libgl1', 'libgl1-mesa-dri', 'libglu1-mesa', 'libpulse0', 'libasound2t64',
    'libfreetype6', 'libfontconfig1', 'libgnutls30t64', 'libgstreamer1.0-0',
    'libgstreamer-plugins-base1.0-0', 'fonts-dejavu-core', 'xkb-data', 'x11-utils',
)


def root():
    return Path.home() / '.local/share/zfold/windows' / VERSION


def prefix_path(requested=None):
    return Path(requested or os.environ.get('WINEPREFIX') or
                Path.home() / '.local/share/zfold/wineprefixes/default').expanduser().absolute()


def binaries(directory):
    return directory / 'box64/usr/local/bin/box64', directory / 'wine/bin/wine'


def ready(directory):
    return (directory / 'installed.json').is_file() and all(p.is_file() and os.access(p, os.X_OK)
                                                           for p in binaries(directory))


def download(directory, asset):
    name, url, digest = asset
    target = directory / name
    if not target.exists():
        partial = directory / (name + '.partial')
        print('Downloading ' + url, flush=True)
        try:
            with urllib.request.urlopen(url, timeout=60) as source, partial.open('wb') as out:
                shutil.copyfileobj(source, out, 1024 * 1024)
            with partial.open('rb') as source:
                actual = hashlib.file_digest(source, 'sha256').hexdigest()
            if actual != digest:
                raise RuntimeError('SHA-256 mismatch: ' + name)
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    with target.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != digest:
            raise RuntimeError('Cached artifact SHA-256 mismatch; inspect/remove ' + str(target))
    return target


def install(confirmed):
    if platform.machine() not in ('aarch64', 'arm64'):
        raise RuntimeError('This Wine runtime requires ARM64 Ubuntu.')
    if ready(root()):
        print('Already installed: ' + str(root()))
        return
    print('Install isolated Wine 10.0 WoW64 + Box64 b99d443 from:')
    for _, url, _ in ASSETS:
        print('  ' + url)
    print('Also install Ubuntu client libraries: ' + ' '.join(PACKAGES))
    print('Programs have this Ubuntu user\'s file/network permissions. Game files are not included.')
    if not confirmed and input('Continue? [y/N] ').strip().lower() not in ('y', 'yes'):
        raise RuntimeError('Installation cancelled; nothing installed.')
    if os.geteuid() != 0:
        raise RuntimeError('Run installation as the Ubuntu guest root user; Android root is not required.')
    directory = root()
    directory.parent.mkdir(parents=True, exist_ok=True)
    with (directory.parent / '.install.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if ready(directory):
            print('Already installed: ' + str(directory))
            return
        if directory.exists():
            raise RuntimeError('Incomplete runtime exists; inspect before replacing: ' + str(directory))
        cache = Path.home() / '.cache/zfold/windows'
        cache.mkdir(parents=True, exist_ok=True)
        archives = [download(cache, asset) for asset in ASSETS]
        subprocess.run(['apt-get', 'update'], check=True)
        subprocess.run(['apt-get', 'install', '-y', '--no-install-recommends', *PACKAGES], check=True)
        with tempfile.TemporaryDirectory(prefix='.install-', dir=directory.parent) as temporary:
            stage = Path(temporary)
            with tarfile.open(archives[0]) as archive:
                archive.extractall(stage, filter='data')
            (stage / 'wine-10.0-amd64-wow64').rename(stage / 'wine')
            # GNU tar directory metadata restoration fails under some PRoot/SELinux
            # combinations. Let dpkg decode the ar member, then extract with Python.
            with tempfile.TemporaryFile() as payload:
                subprocess.run(['dpkg-deb', '--fsys-tarfile', str(archives[1])], stdout=payload, check=True)
                payload.seek(0)
                with tarfile.open(fileobj=payload) as archive:
                    archive.extractall(stage / 'box64', filter='data')
            for binary in binaries(stage):
                if not binary.is_file() or not os.access(binary, os.X_OK):
                    raise RuntimeError('Runtime artifact is missing executable ' + str(binary))
            manifest = {'version': VERSION, 'assets': [{'url': a[1], 'sha256': a[2]} for a in ASSETS]}
            (stage / 'installed.json').write_text(json.dumps(manifest, indent=2) + '\n')
            stage.rename(directory)
        print('Installed: ' + str(directory))
        print('Open X11 in the app, then run: windows winecfg')


def launch(command, arguments, prefix):
    directory = root()
    if not ready(directory):
        raise RuntimeError('Wine is not installed. Run: windows install')
    box64, wine = binaries(directory)
    environment = os.environ.copy()
    environment['WINEPREFIX'] = str(prefix)
    environment['WINEARCH'] = 'win64'  # WoW64 supports Win32 within this prefix.
    environment['BOX64_LD_LIBRARY_PATH'] = str(directory / 'box64/usr/lib/box64-x86_64-linux-gnu')
    environment['BOX64_PATH'] = str(wine.parent) + ':' + environment.get('PATH', '/usr/bin:/bin')
    environment['PATH'] = str(box64.parent) + ':' + str(wine.parent) + ':' + environment.get('PATH', '/usr/bin:/bin')
    environment['WINESERVER'] = str(wine.parent / 'wineserver')
    if command == 'stop':
        executable, parameters = wine.parent / 'wineserver', ['-k']
    elif command == 'winecfg':
        executable, parameters = wine, ['winecfg.exe']
    else:
        executable, parameters = wine, arguments
    if command != 'stop' and not environment.get('DISPLAY'):
        raise RuntimeError('No DISPLAY. Open the app X11 view and enter a new Ubuntu shell.')
    if command != 'stop':
        prefix.parent.mkdir(parents=True, exist_ok=True)
    print('Wine prefix: ' + str(prefix), flush=True)
    os.execve(box64, [str(box64), str(executable), *parameters], environment)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', help='Separate Wine prefix; defaults to WINEPREFIX or the app-specific prefix')
    commands = parser.add_subparsers(dest='command', required=True)
    installing = commands.add_parser('install', help='Download verified runtime and install Ubuntu libraries')
    installing.add_argument('--yes', action='store_true', help='Accept the printed installation plan without prompting')
    commands.add_parser('status', help='Show installation, display and prefix paths')
    commands.add_parser('winecfg', help='Open Wine configuration on the current X11 display')
    commands.add_parser('stop', help='Stop all Windows processes in the selected prefix only')
    running = commands.add_parser('run', help='Run a Windows program; following arguments are passed unchanged')
    running.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    prefix = prefix_path(args.prefix)
    if args.command == 'install':
        install(args.yes)
    elif args.command == 'status':
        print(json.dumps({'installed': ready(root()), 'version': VERSION, 'runtime': str(root()),
                          'prefix': str(prefix), 'display': os.environ.get('DISPLAY'),
                          'xauthority': os.environ.get('XAUTHORITY')}, indent=2))
    else:
        if args.command == 'run' and not args.arguments:
            parser.error('run requires a program, for example: windows run notepad.exe')
        launch(args.command, getattr(args, 'arguments', []), prefix)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError, EOFError, tarfile.TarError) as error:
        raise SystemExit('windows: ' + str(error))
