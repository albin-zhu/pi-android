#!/usr/bin/env python3
"""Build the small glibc/PE gamepad bridges without downloading a toolchain.

Requires an aarch64 glibc C compiler, clang, lld-link and llvm-dlltool. All source,
compiler identities, commands and output hashes are recorded under .build/input-controls.
No Android NDK/Bionic library is injected into the Ubuntu guest.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'native/input-controls'
BUILD = ROOT / '.build/input-controls'
DLL_NAMES = ('xinput1_1', 'xinput1_2', 'xinput1_3', 'xinput1_4', 'xinput9_1_0')
IMPORTS = {'CreateFileA': 28, 'ReadFile': 20, 'SetFilePointerEx': 20,
           'CloseHandle': 4, 'GetFileAttributesA': 4, 'GetLastError': 0, 'Sleep': 4,
           'LoadLibraryA': 4, 'GetProcAddress': 8, 'GetStdHandle': 4,
           'WriteFile': 20, 'ExitProcess': 4, 'GetSystemTimeAsFileTime': 4}
EXPORTS = (('XInputGetState', 8, 2), ('XInputSetState', 8, 3),
           ('XInputGetCapabilities', 12, 4), ('XInputEnable', 4, 5),
           ('XInputGetDSoundAudioDeviceGuids', 12, 6), ('XInputGetBatteryInformation', 12, 7),
           ('XInputGetKeystroke', 12, 8), ('XInputGetAudioDeviceIds', 20, 10),
           ('XInputGetStateEx', 8, 100), ('XInputWaitForGuideButton', 12, 101),
           ('XInputCancelGuideButtonWait', 4, 102), ('XInputPowerOffController', 4, 103),
           ('XInputGetCapabilitiesEx', 16, 108))


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def tool(variable, candidates):
    explicit = os.environ.get(variable)
    command = shlex.split(explicit) if explicit else None
    if command and shutil.which(command[0]):
        return command
    if not explicit:
        for candidate in candidates:
            if shutil.which(candidate):
                return [shutil.which(candidate)]
    raise RuntimeError(f'Missing {variable}: provide a compiler/tool command; tried {candidates}. '
                       'No packages are installed automatically.')


def prepare():
    tools = {
        'cc': tool('ZFOLD_INPUT_CC', ['aarch64-linux-gnu-gcc', 'gcc']),
        'clang': tool('ZFOLD_INPUT_CLANG', ['clang-18', 'clang']),
        'link': tool('ZFOLD_INPUT_LINK', ['lld-link-18', 'lld-link']),
        'dlltool': tool('ZFOLD_INPUT_DLLTOOL', ['llvm-dlltool-18', 'llvm-dlltool']),
    }
    machine = subprocess.check_output([*tools['cc'], '-dumpmachine'], text=True).strip()
    if not machine.startswith('aarch64') or 'linux' not in machine or 'android' in machine:
        raise RuntimeError(f'ZFOLD_INPUT_CC must target aarch64 Linux glibc, not {machine}')
    sources = [SOURCE / name for name in ('state.h', 'linux.c', 'xinput.c')]
    sources.extend((Path(__file__), ROOT / 'tests/xinput-probe.c'))
    provenance = {
        'abi': 1,
        'sources': {str(p.relative_to(ROOT)): digest(p) for p in sources},
        'tools': {name: {'command': command,
                         'version': subprocess.check_output([*command, '--version'], text=True).strip()
                                    if name != 'dlltool' else None,
                         'executable_sha256': digest(Path(shutil.which(command[0])).resolve())}
                  for name, command in tools.items()},
        'machine': machine,
    }
    output = BUILD / 'package'
    manifest = output / 'manifest.json'
    expected_products = {'linux-aarch64/libzfold-gamepad.so'} | {
        f'{arch}/{name}.dll' for arch in ('i386-windows', 'x86_64-windows') for name in DLL_NAMES
    }
    if manifest.is_file():
        previous = json.loads(manifest.read_text())
        if previous.get('provenance') == provenance and set(previous.get('sha256', {})) == expected_products and all(
                (output / name).is_file() and digest(output / name) == checksum
                for name, checksum in previous['sha256'].items()):
            return output
    BUILD.mkdir(parents=True, exist_ok=True)
    commands = []
    with tempfile.TemporaryDirectory(prefix='build-', dir=BUILD) as directory:
        work = Path(directory)
        package = work / 'package'
        linux = package / 'linux-aarch64'
        linux.mkdir(parents=True)

        def run(*command):
            command = [str(x) for x in command]
            commands.append([part.replace(str(work), '$BUILD') for part in command])
            subprocess.run(command, check=True, cwd=ROOT)

        run(*tools['cc'], '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
            '-fPIC', '-shared', '-pthread', '-Wl,-z,relro,-z,now',
            '-Wl,--build-id=sha1', '-Wl,-soname,libzfold-gamepad.so',
            SOURCE / 'linux.c', '-o', linux / 'libzfold-gamepad.so')
        for arch, target, machine, dll_machine in (
                ('x86_64-windows', 'x86_64-pc-windows-msvc', 'x64', 'i386:x86-64'),
                ('i386-windows', 'i686-pc-windows-msvc', 'x86', 'i386')):
            dest = package / arch
            dest.mkdir()
            obj = work / (arch + '.obj')
            import_def = work / (arch + '-kernel32.def')
            imports = work / (arch + '-kernel32.lib')
            suffix = machine == 'x86'
            import_def.write_text('LIBRARY KERNEL32.dll\nEXPORTS\n' + ''.join(
                f'  {name}' + (f'@{size}' if suffix else '') + '\n' for name, size in IMPORTS.items()))
            run(*tools['dlltool'], '-m', dll_machine, '-k', '-d', import_def, '-l', imports)
            run(*tools['clang'], '--target=' + target, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                '-ffreestanding', '-fno-builtin', '-fno-stack-protector', '-c', SOURCE / 'xinput.c', '-o', obj)
            for name in DLL_NAMES:
                definition = work / (arch + '-' + name + '.def')
                exports = EXPORTS if name != 'xinput9_1_0' else tuple(
                    item for item in EXPORTS if item[0] in ('XInputGetState', 'XInputSetState',
                                                          'XInputGetCapabilities', 'XInputGetDSoundAudioDeviceGuids'))
                definition.write_text('LIBRARY ' + name + '.dll\nEXPORTS\n' + ''.join(
                    '  ' + function + (f'=_{function}@{size}' if suffix else '') +
                    (f' @{ordinal}' if name != 'xinput9_1_0' else '') + '\n'
                    for function, size, ordinal in exports))
                run(*tools['link'], '/dll', '/noentry', '/nodefaultlib', '/machine:' + machine,
                    '/timestamp:0', '/opt:ref', '/opt:icf', '/dynamicbase', '/nxcompat',
                    '/def:' + str(definition), '/out:' + str(dest / (name + '.dll')),
                    '/implib:' + str(work / (arch + '-' + name + '.lib')), obj, imports)
            probe = work / (arch + '-probe.obj')
            probe_output = BUILD / 'probes' / arch
            probe_output.mkdir(parents=True, exist_ok=True)
            run(*tools['clang'], '--target=' + target, '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
                '-ffreestanding', '-fno-builtin', '-fno-stack-protector', '-c', ROOT / 'tests/xinput-probe.c', '-o', probe)
            run(*tools['link'], '/entry:mainCRTStartup', '/subsystem:console', '/nodefaultlib',
                '/machine:' + machine, '/timestamp:0', '/dynamicbase', '/nxcompat',
                '/out:' + str(probe_output / 'xinput-probe.exe'), probe, imports)
        hashes = {str(p.relative_to(package)): digest(p) for p in sorted(package.rglob('*')) if p.is_file()}
        (package / 'manifest.json').write_text(json.dumps(
            {'provenance': provenance, 'commands': commands, 'sha256': hashes}, indent=2, sort_keys=True) + '\n')
        # Keep the previous verified package until every new product has linked.
        if output.exists():
            shutil.rmtree(output)
        shutil.move(package, output)
    return output


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    print(prepare())


if __name__ == '__main__':
    main()
