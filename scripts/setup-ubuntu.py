#!/usr/bin/env python3
"""Install an isolated Ubuntu/omp experiment into the current debug APK via ADB.

No credentials, session databases, or host node_modules are copied. Install the
branch's compat28 APK first. Ubuntu and Bun downloads are SHA256-pinned.
"""
import argparse
import copy
import hashlib
import ipaddress
import json
import pathlib
import shlex
import subprocess
import tarfile
import time
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKAGE = 'dev.zfold.assistant'
UBUNTU_NAME = 'ubuntu-base-24.04.5-base-arm64.tar.gz'
UBUNTU_URL = 'https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/' + UBUNTU_NAME
UBUNTU_SHA = 'a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2'
BUN_NAME = 'bun-linux-aarch64-1.4.3.zip'
BUN_URL = 'https://github.com/oven-sh/bun/releases/download/bun-v1.4.3/bun-linux-aarch64.zip'
BUN_SHA = 'efa9813da5ed72423bf847f916e8d2c47c0d776add972354026a75e10da9aa21'
OMP_VERSION = '18.10.0'


def download(cache, name, url, digest):
    target = cache / name
    if not target.exists():
        temporary = cache / (name + '.download')
        print('Downloading', url, flush=True)
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open('wb') as out:
            while chunk := source.read(1024 * 1024):
                out.write(chunk)
        temporary.rename(target)
    with target.open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest()
    if actual != digest:
        raise RuntimeError(f'Checksum mismatch: {target}; expected {digest}, got {actual}')
    print(f'Verified {name}: {target.stat().st_size / 1024 / 1024:.1f} MiB', flush=True)
    return target


class Device:
    def __init__(self, serial):
        self.adb = ['adb', '-s', serial]

    def shell(self, argv, *, app=True, data=None, capture=True, timeout=90, check=True):
        command = ['run-as', PACKAGE, *argv] if app else argv
        return subprocess.run([*self.adb, 'shell', '-T', shlex.join(command)],
                              input=data, stdout=subprocess.PIPE if capture else None,
                              stderr=subprocess.PIPE if capture else None, timeout=timeout, check=check)

    def write(self, path, data, mode='600'):
        command = f'cat > {shlex.quote(path)} && chmod {mode} {shlex.quote(path)}'
        self.shell(['/system/bin/sh', '-c', command], data=data)

    def exists(self, path):
        return self.shell(['/system/bin/test', '-f', path], check=False).returncode == 0

    def extract(self, archive, destination):
        # Android SELinux may deny hardlinks even inside this UID. Materialize
        # hardlinked members as files instead of ignoring tar extraction errors.
        command = ['run-as', PACKAGE, '/system/bin/tar', '-x', '--no-same-owner', '-C', destination]
        with subprocess.Popen([*self.adb, 'shell', '-T', shlex.join(command)], stdin=subprocess.PIPE) as proc:
            try:
                with tarfile.open(archive, 'r:gz') as source, tarfile.open(fileobj=proc.stdin, mode='w|') as out:
                    for member in source:
                        relative = pathlib.PurePosixPath(member.name)
                        if relative.is_absolute() or '..' in relative.parts:
                            raise RuntimeError('Unsafe rootfs archive member: ' + member.name)
                        if member.isdev():
                            continue  # Guest /dev is bound from Android at launch.
                        value = copy.copy(member)
                        if member.islnk():
                            stream = source.extractfile(member)
                            if stream is None:
                                raise RuntimeError('Unresolved hardlink: ' + member.name)
                            value.type = tarfile.REGTYPE
                            value.linkname = ''
                            value.size = source.getmember(member.linkname).size
                            with stream:
                                out.addfile(value, stream)
                        elif member.isfile():
                            with source.extractfile(member) as stream:
                                out.addfile(value, stream)
                        else:
                            out.addfile(value)
                proc.stdin.close()
                if proc.wait(timeout=180):
                    raise RuntimeError('Android rootfs extraction failed')
            except BaseException:
                proc.kill()
                proc.wait()
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit ADB device; no implicit production-device selection')
    parser.add_argument('--dns', required=True, help='DNS server IP reachable from this device; emulator normally 10.0.2.3')
    parser.add_argument('--no-omp', action='store_true', help='Install Ubuntu/Bun only, for independent phone-client checks')
    parser.add_argument('--cache', type=pathlib.Path, default=ROOT / '.build/ubuntu-downloads')
    args = parser.parse_args()
    dns = str(ipaddress.ip_address(args.dns))
    device = Device(args.serial)
    device.shell(['/system/bin/am', 'start', '-W', '-n', PACKAGE + '/.MainActivity'], app=False)
    # The environment file is written only after both servers are listening.
    # A previous process's file is not readiness for this process.
    deadline = time.monotonic() + 20
    environment = None
    while time.monotonic() < deadline:
        current = device.shell(['/system/bin/pidof', PACKAGE], app=False, check=False)
        result = device.shell(['/system/bin/cat', 'files/runtime/environment.json'], check=False)
        try:
            candidate = json.loads(result.stdout)
            if str(candidate.get('ZFOLD_RUNTIME_PID')) in current.stdout.decode().split():
                environment = candidate
                break
        except json.JSONDecodeError:
            pass
        time.sleep(0.1)
    if environment is None:
        raise RuntimeError('App did not publish current-process runtime readiness within 20s; install/open this branch APK and inspect its startup error')
    for key in ('PREFIX', 'TMPDIR', 'ZFOLD_PKG_SOCKET', 'ZFOLD_PHONE_SOCKET', 'ZFOLD_AGENT_ROOT'):
        if not environment.get(key):
            raise RuntimeError(f'Missing {key}; install/open this branch APK')
    prefix = environment['PREFIX']
    runtime = str(pathlib.PurePosixPath(prefix).parent.parent)
    rootfs = runtime + '/ubuntu'
    channel = json.loads(device.shell(['/system/bin/cat', runtime + '/runtime-channel.json']).stdout)
    if channel.get('targetSdk') != 28:
        raise RuntimeError('This writable Linux runtime requires the compat28 APK; no automatic SDK downgrade')
    device.shell(['/system/bin/env', 'ZFOLD_PKG_SOCKET=' + environment['ZFOLD_PKG_SOCKET'],
                  runtime + '/bin/pkg', 'install', '-y', 'proot'], capture=False, timeout=600)
    args.cache.mkdir(parents=True, exist_ok=True)
    if not device.exists(rootfs + '/etc/os-release'):
        archive = download(args.cache, UBUNTU_NAME, UBUNTU_URL, UBUNTU_SHA)
        stage = rootfs + '.installing'
        if device.shell(['/system/bin/test', '-e', stage], check=False).returncode == 0:
            raise RuntimeError('An interrupted rootfs installation exists: ' + stage + '; inspect it before removing it')
        if device.shell(['/system/bin/test', '-e', rootfs], check=False).returncode == 0:
            raise RuntimeError('An incomplete rootfs already exists: ' + rootfs + '; inspect it before replacing it')
        device.shell(['/system/bin/mkdir', '-m', '700', stage])
        device.extract(archive, stage)
        device.shell(['/system/bin/chmod', '700', stage])
        device.shell(['/system/bin/mv', stage, rootfs])
    else:
        print('Keeping existing Ubuntu rootfs; no sessions or configuration are replaced.', flush=True)
    bun_archive = download(args.cache, BUN_NAME, BUN_URL, BUN_SHA)
    device.shell(['/system/bin/mkdir', '-p', rootfs + '/usr/local/bin', rootfs + '/opt/omp'])
    with zipfile.ZipFile(bun_archive) as source:
        executable = source.read('bun-linux-aarch64/bun')
    existing = device.shell(['/system/bin/sha256sum', rootfs + '/usr/local/bin/bun'], check=False)
    if existing.returncode or existing.stdout.decode().split()[0] != hashlib.sha256(executable).hexdigest():
        device.write(rootfs + '/usr/local/bin/bun', executable, '700')
    device.write(rootfs + '/etc/resolv.conf', ('nameserver ' + dns + '\n').encode())
    device.write(rootfs + '/usr/local/bin/phone', b'#!/bin/sh\nexec /usr/local/bin/bun /opt/pi-phone/phone-cli.mjs "$@"\n', '700')
    device.write(rootfs + '/usr/local/bin/xdg-open', b'#!/bin/sh\nexec /usr/local/bin/bun /opt/pi-phone/phone-open.mjs -- "$@"\n', '700')
    host_env = ['/system/bin/env', *[key + '=' + str(value) for key, value in environment.items()]]
    launch = [*host_env, runtime + '/bin/ubuntu']
    for command in (['/bin/cat', '/etc/os-release'], ['bun', '--version'], ['phone', 'call', 'device_info', '{}']):
        device.shell([*launch, *command], capture=False)
    if not args.no_omp:
        # SELinux denies real hardlinks; PRoot's link emulation is not a safe
        # package-cache backend. Copy package files instead, preserving all deps.
        device.shell([*launch, 'bun', 'install', '--backend=copyfile', '--exact', '--cwd', '/opt/omp',
                      '@oh-my-pi/pi-coding-agent@' + OMP_VERSION], capture=False, timeout=1200)
        wrapper = b'#!/bin/sh\nexec /usr/local/bin/bun /opt/omp/node_modules/@oh-my-pi/pi-coding-agent/dist/cli.js -e /opt/pi-phone/omp-phone-extension.mjs "$@"\n'
        device.write(rootfs + '/usr/local/bin/omp', wrapper, '700')
        device.shell([*launch, 'omp', '--version'], capture=False)
    print("Ready: open a NEW App terminal and run ubuntu; inside it, phone call device_info '{}'.", flush=True)
    if not args.no_omp:
        print('Run omp from any Ubuntu directory; the Android tool extension is loaded automatically.', flush=True)
    print('Sensitive reads/actions require Android approval. No OAuth data was imported.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.SubprocessError, OSError, ValueError) as error:
        raise SystemExit('setup-ubuntu: ' + str(error))
