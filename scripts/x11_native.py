#!/usr/bin/env python3
"""Build modified arm64 Xlorie; --prepare-only downloads inputs without compiling.

X11_TOOLCHAIN_ROOT selects the relocatable Ubuntu-aarch64/Android cross toolchain.
Other hosts use ANDROID_NDK_HOME plus host cmake, ninja, bison, patch and gcc.
X11_SOURCE_ROOT optionally selects a pristine materialized upstream source tree.
"""
import argparse
import hashlib
import json
import os
import pathlib
import platform
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
UPSTREAM = ROOT / 'third_party/termux-x11'
CPP = pathlib.Path('lorie/src/main/cpp')


def digest(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def check(path, expected):
    if digest(path) != expected:
        raise RuntimeError(f'Checksum mismatch: {path}; refusing changed build input')


def fetch(cache, name, record):
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / name
    if not target.exists():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
            temporary = pathlib.Path(output.name)
            try:
                with urllib.request.urlopen(record['url'], timeout=180) as response:
                    shutil.copyfileobj(response, output)
                output.flush()
                check(temporary, record['sha256'])
                temporary.replace(target)
            finally:
                temporary.unlink(missing_ok=True)
    check(target, record['sha256'])
    return target


def unpack_source(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            parts = pathlib.PurePosixPath(member.name).parts
            if len(parts) < 2:
                continue
            member.name = str(pathlib.PurePosixPath(*parts[1:]))
            source.extract(member, destination, filter='data')


def archive_manifest(archive, prefix=pathlib.PurePosixPath()):
    """Derive trusted contents from checksum-verified archives, never a cache marker."""
    result = {}
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            parts = pathlib.PurePosixPath(member.name).parts
            if len(parts) < 2 or member.isdir():
                continue
            relative = prefix.joinpath(*parts[1:])
            if relative.is_absolute() or '..' in relative.parts:
                raise RuntimeError('Unsafe source archive path: ' + str(relative))
            if member.issym():
                entry = {'symlink': member.linkname}
            elif member.isfile() or member.islnk():
                with source.extractfile(member) as stream:
                    entry = {'sha256': hashlib.file_digest(stream, 'sha256').hexdigest(),
                             'executable': bool(member.mode & 0o111)}
            else:
                raise RuntimeError('Unsupported source archive entry: ' + str(relative))
            result[str(relative)] = entry
    return result


def tree_manifest(source):
    result = {}
    for path in source.rglob('*'):
        relative = str(path.relative_to(source))
        if relative == '.x11-source-inputs.json':
            continue
        if path.is_symlink():
            result[relative] = {'symlink': os.readlink(path)}
        elif path.is_file():
            result[relative] = {'sha256': digest(path),
                                'executable': bool(path.stat().st_mode & 0o111)}
        elif not path.is_dir():
            raise RuntimeError('Unsupported materialized source entry: ' + relative)
    return result


def materialize(cache, source, pins, inputs):
    expected = {'source': pins['source_archive'], 'commit': pins['commit'],
                'submodules': inputs['submodule_archives']}
    marker = source / '.x11-source-inputs.json'
    if inputs['source_commit'] != pins['commit']:
        raise RuntimeError('Source and build-input commit pins disagree')
    modules = {m['path']: m for m in pins['submodules']}
    if set(modules) != {m['path'] for m in inputs['submodule_archives']}:
        raise RuntimeError('Incomplete pinned submodule archives')
    archive = fetch(cache, 'termux-x11-' + pins['commit'] + '.tar.gz', pins['source_archive'])
    archives = [(archive, pathlib.PurePosixPath())]
    contents = archive_manifest(archive)
    for record in inputs['submodule_archives']:
        if record['commit'] != modules[record['path']]['commit']:
            raise RuntimeError('Submodule archive commit mismatch: ' + record['path'])
        name = pathlib.Path(record['path']).name + '-' + record['commit'] + '.tar.gz'
        archive = fetch(cache, name, record)
        prefix = pathlib.PurePosixPath(record['path'])
        archives.append((archive, prefix))
        contents.update(archive_manifest(archive, prefix))
    if not source.exists() or not any(source.iterdir()):
        for archive, prefix in archives:
            unpack_source(archive, source / prefix)
    actual = tree_manifest(source)
    if actual != contents:
        changed = sorted(name for name in actual.keys() | contents.keys()
                         if actual.get(name) != contents.get(name))
        raise RuntimeError('Materialized X11 source differs from pinned archives: ' +
                           ', '.join(changed[:12]) + '. Preserve local edits as overrides; '
                           'select a new empty X11_SOURCE_ROOT rather than trusting this cache.')
    expected['tree_sha256'] = hashlib.sha256(
        json.dumps(contents, sort_keys=True).encode()).hexdigest()
    marker.write_text(json.dumps(expected, sort_keys=True, indent=2) + '\n')
    return source


def platform_stubs(root, cache, record):
    destination = root / 'platform-stubs'
    destination.mkdir(parents=True, exist_ok=True)
    for member in record['members']:
        target = destination / pathlib.PurePosixPath(member['member']).name
        if not target.exists():
            start, size = member['offset'], member['compressed_size']
            request = urllib.request.Request(record['url'], headers={
                'Range': f'bytes={start}-{start + size - 1}'})
            with urllib.request.urlopen(request, timeout=180) as response:
                expected = f'bytes {start}-{start + size - 1}/{record["archive_size"]}'
                if response.status != 206 or response.headers.get('Content-Range') != expected:
                    raise RuntimeError('NDK server did not honor the pinned byte range')
                compressed = response.read()
            if len(compressed) != size:
                raise RuntimeError('Truncated NDK platform stub')
            data = zlib.decompress(compressed, -15) if member['compression'] == 8 else compressed
            if len(data) != member['size'] or hashlib.sha256(data).hexdigest() != member['sha256']:
                raise RuntimeError('NDK platform stub checksum mismatch')
            target.write_bytes(data)
        check(target, member['sha256'])
    return destination


def platform_headers(root, cache, record):
    destination = root / 'platform-include'
    marker = destination / '.input-sha256'
    if marker.exists() and marker.read_text().strip() == record['sha256']:
        return destination
    archive = cache / 'ndk-r29-platform-headers.bin'
    if not archive.exists():
        start, size = record['offset'], record['compressed_size']
        request = urllib.request.Request(record['url'], headers={
            'Range': f'bytes={start}-{start + size - 1}'})
        with urllib.request.urlopen(request, timeout=180) as response:
            expected = f'bytes {start}-{start + size - 1}/{record["archive_size"]}'
            if response.status != 206 or response.headers.get('Content-Range') != expected:
                raise RuntimeError('NDK server did not honor the platform header byte range')
            archive.write_bytes(response.read())
    check(archive, record['sha256'])
    data = archive.read_bytes()
    offset, count = 0, 0
    # This checksum-pinned ZIP span contains complete consecutive local entries.
    # The trailing partial entry belongs to a different include directory.
    while offset + 30 <= len(data) and data[offset:offset + 4] == b'PK\x03\x04':
        header = struct.unpack_from('<4s5H3L2H', data, offset)
        if header[2] & 8:
            raise RuntimeError('Unsupported NDK ZIP data descriptor')
        name = data[offset + 30:offset + 30 + header[-2]].decode()
        body = offset + 30 + header[-2] + header[-1]
        end = body + header[7]
        if end > len(data):
            break
        if name.startswith(record['member_prefix']) and not name.endswith('/'):
            relative = pathlib.PurePosixPath(name[len(record['member_prefix']):])
            if relative.is_absolute() or '..' in relative.parts:
                raise RuntimeError('Unsafe NDK header path')
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            raw = zlib.decompress(data[body:end], -15) if header[3] == 8 else data[body:end]
            if len(raw) != header[8] or zlib.crc32(raw) != header[6]:
                raise RuntimeError('Corrupt NDK platform header')
            target.write_bytes(raw)
            count += 1
        offset = end
    if count != record['file_count']:
        raise RuntimeError('Incomplete NDK platform headers')
    marker.write_text(record['sha256'] + '\n')
    return destination


def tool_identity(paths):
    return {str(pathlib.Path(p).resolve()): digest(p) for p in paths}


def cross_toolchain(root, inputs):
    """No package installation or global symlinks: all target files stay under root."""
    if platform.system() != 'Linux' or platform.machine() not in ('aarch64', 'arm64'):
        raise RuntimeError('The extracted toolchain needs Linux aarch64; use ANDROID_NDK_HOME on other hosts')
    root.mkdir(parents=True, exist_ok=True)
    cache = root / 'downloads'
    marker = root / 'packages.json'
    if not marker.exists() or json.loads(marker.read_text()) != inputs['packages']:
        for package in inputs['packages']:
            archive = fetch(cache, package['url'].rsplit('/', 1)[1], package)
            destination = root / ('host' if package['kind'] == 'host' else 'termux')
            subprocess.run(['dpkg-deb', '-x', str(archive), str(destination)], check=True)
        marker.write_text(json.dumps(inputs['packages'], indent=2) + '\n')
    stubs = platform_stubs(root, cache, inputs['platform_stubs'])
    include = platform_headers(root, cache, inputs['platform_headers'])
    cxx_include = include / 'c++/v1'
    prefix = root / 'termux/data/data/com.termux/files/usr'
    sysroot = root / 'sysroot'
    usr = sysroot / 'usr'
    # Replace the earlier Termux-prefix link: that package deliberately omits
    # zlib, EGL/GLES/KHR and other NDK platform headers supplied by Termux packages.
    if usr.is_symlink():
        usr.unlink()
    usr.mkdir(parents=True, exist_ok=True)
    for name, target in [('include', include), ('lib', prefix / 'lib')]:
        link = usr / name
        if not link.exists():
            link.symlink_to(target, target_is_directory=True)
    for stub in stubs.iterdir():
        shutil.copyfile(stub, prefix / 'lib' / stub.name)
    resource = root / 'clang-resource'
    resource.mkdir(exist_ok=True)
    clang_resource = pathlib.Path(subprocess.check_output(
        ['/usr/bin/clang-18', '-print-resource-dir'], text=True).strip())
    for name, target in [('include', clang_resource / 'include'), ('lib', prefix / 'lib/clang/21/lib')]:
        link = resource / name
        if not link.exists():
            link.symlink_to(target, target_is_directory=True)
    host = root / 'host/usr'
    env = os.environ.copy()
    env['PATH'] = str(host / 'bin') + os.pathsep + env.get('PATH', '')
    env['LD_LIBRARY_PATH'] = str(host / 'lib/aarch64-linux-gnu') + os.pathsep + env.get('LD_LIBRARY_PATH', '')
    env['BISON_PKGDATADIR'] = str(host / 'share/bison')
    env['M4'] = str(host / 'bin/m4')
    # NDK r29 android-legacy.toolchain.cmake splits functions/data and garbage
    # collects sections in RelWithDebInfo. Xlorie's intentionally partial Xlib
    # sources rely on this to discard unused client-connection functions.
    flags = (f'--target=aarch64-linux-android30 --sysroot={sysroot} -resource-dir={resource} '
             f'-B{prefix / "lib"} -DANDROID -fPIC -ffunction-sections -fdata-sections')
    toolchain = root / 'android-aarch64.cmake'
    toolchain.write_text(f'''# Generated by scripts/x11_native.py; Android target on an ARM64 Linux host.
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(ANDROID TRUE)
set(CMAKE_ANDROID_ARCH_ABI arm64-v8a)
set(CMAKE_C_COMPILER /usr/bin/clang-18)
set(CMAKE_CXX_COMPILER /usr/bin/clang++-18)
set(CMAKE_ASM_COMPILER /usr/bin/clang-18)
set(CMAKE_SYSROOT "{sysroot}")
set(CMAKE_C_FLAGS_INIT "{flags}")
set(CMAKE_CXX_FLAGS_INIT "{flags} -nostdinc++ -isystem {cxx_include}")
set(CMAKE_ASM_FLAGS_INIT "{flags}")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld -rtlib=compiler-rt -L{prefix / 'lib'} -Wl,-z,max-page-size=16384")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld -rtlib=compiler-rt -L{prefix / 'lib'} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS_RELWITHDEBINFO_INIT "-Wl,--gc-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELWITHDEBINFO_INIT "-Wl,--gc-sections")
set(CMAKE_AR /usr/bin/ar)
set(CMAKE_RANLIB /usr/bin/ranlib)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
set(X11_STATIC_CXX_LIBRARIES "{prefix / 'lib/libc++_static.a'};{prefix / 'lib/libc++abi.a'};{prefix / 'lib/libunwind.a'}")
''')
    tools = ['/usr/bin/clang-18', '/usr/bin/clang++-18', '/usr/bin/ld.lld-18',
             '/usr/bin/ar', '/usr/bin/ranlib', '/usr/bin/gcc',
             *[host / 'bin' / name for name in ('cmake', 'ninja', 'bison', 'patch', 'm4')]]
    return str(host / 'bin/cmake'), ['-DCMAKE_TOOLCHAIN_FILE=' + str(toolchain)], env, tool_identity(tools)


def select_toolchain(cache, inputs):
    custom = os.environ.get('X11_TOOLCHAIN_ROOT')
    ndk = os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT')
    if not custom and not ndk and not (platform.system() == 'Linux' and platform.machine() in ('aarch64', 'arm64')):
        sdk = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or
                           pathlib.Path.home() / 'Library/Android/sdk')
        for version in ('29.0.14206865', '28.0.13004108'):
            candidate = sdk / 'ndk' / version
            if candidate.is_dir():
                ndk = str(candidate)
                break
    if custom or not ndk:
        return cross_toolchain(pathlib.Path(custom).resolve() if custom else cache / 'toolchain', inputs)
    ndk = pathlib.Path(ndk).resolve()
    host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    compiler = ndk / 'toolchains/llvm/prebuilt' / host / 'bin/clang'
    tools = []
    for name in ('cmake', 'ninja', 'bison', 'patch', 'gcc'):
        tool = shutil.which(name)
        if not tool:
            raise RuntimeError(f'Missing host prerequisite: {name}')
        tools.append(tool)
    identity = tool_identity([compiler, *tools])
    identity['ndk_source_properties'] = digest(ndk / 'source.properties')
    return (tools[0], ['-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                       '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-30',
                       '-DANDROID_STL=c++_static', '-DCMAKE_ANDROID_STL_TYPE=c++_static'],
            os.environ.copy(), identity)


def build(native, cache=None, prepare_only=False, source_root=None):
    cache = pathlib.Path(cache or ROOT / 'vendor/x11-source').resolve()
    cache.mkdir(parents=True, exist_ok=True)
    pins = json.loads((UPSTREAM / 'sources.json').read_text())
    inputs = json.loads((UPSTREAM / 'build-inputs.json').read_text())
    source = pathlib.Path(source_root or os.environ.get('X11_SOURCE_ROOT') or
                          cache / ('termux-x11-' + pins['commit'])).resolve()
    materialize(cache / 'downloads', source, pins, inputs)
    cmake, toolchain, env, identity = select_toolchain(cache, inputs)
    if prepare_only:
        return source
    overrides = UPSTREAM / 'overrides'
    files = sorted(p for p in overrides.rglob('*') if p.is_file())
    relative = {str(p.relative_to(overrides)): digest(p) for p in files}
    for required in ('lorie/rootless.c', 'lorie/rootless_jni.cpp', 'lorie/rootless.h'):
        if required not in relative:
            raise RuntimeError(f'Missing native rootless override: {required}; old APK library is not a fallback')
    provenance = {'source_commit': pins['commit'], 'source_pins_sha256': digest(UPSTREAM / 'sources.json'),
                  'build_inputs_sha256': digest(UPSTREAM / 'build-inputs.json'),
                  'source_tree_sha256': json.loads((source / '.x11-source-inputs.json').read_text())['tree_sha256'],
                  'builder_sha256': digest(__file__), 'overrides': relative,
                  'target': 'aarch64-linux-android30', 'toolchain': identity}
    fingerprint = hashlib.sha256(json.dumps(provenance, sort_keys=True).encode()).hexdigest()
    work = cache / 'builds' / fingerprint
    library = work / 'build/libXlorie.so'
    receipt = work / 'provenance.json'
    if not receipt.exists() or not library.exists():
        work.mkdir(parents=True, exist_ok=True)
        tree = work / 'source'
        # An interrupted build may contain patched or manually edited inputs.
        # Never let stale objects from such a tree acquire a fresh receipt.
        for generated in (tree, work / 'build'):
            if generated.exists():
                shutil.rmtree(generated)
        shutil.copytree(source, tree, symlinks=True)
        for override in files:
            destination = tree / CPP / override.relative_to(overrides)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(override, destination)
        # Native overrides are relative to cpp; keep upstream recipes unchanged.
        cmakelists = tree / CPP / 'CMakeLists.txt'
        original = (source / CPP / 'CMakeLists.txt').read_text()
        if 'CMakeLists.txt' in relative:
            original = (overrides / 'CMakeLists.txt').read_text()
        cmakelists.write_text(original + '''
target_sources(Xlorie PRIVATE lorie/rootless.c lorie/rootless_jni.cpp)
if(X11_STATIC_CXX_LIBRARIES)
    # Suppress implicit shared libc++ but explicitly link its pinned static form.
    target_link_libraries(Xlorie "-Wl,--start-group" ${X11_STATIC_CXX_LIBRARIES} "-Wl,--end-group" dl)
else()
    # The standard NDK CMake toolchain supplies c++_static and its dependencies.
    get_target_property(rootless_link_options Xlorie LINK_OPTIONS)
    list(REMOVE_ITEM rootless_link_options "-nostdlib++")
    set_target_properties(Xlorie PROPERTIES LINK_OPTIONS "${rootless_link_options}")
endif()
''')
        configure = [cmake, '-S', str(tree / CPP), '-B', str(work / 'build'), '-G', 'Ninja',
                     *toolchain, '-DCMAKE_BUILD_TYPE=RelWithDebInfo', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
                     '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', '-DPython3_EXECUTABLE=' + sys.executable]
        subprocess.run(configure, check=True, env=env)
        subprocess.run([cmake, '--build', str(work / 'build'), '--target', 'Xlorie', '--parallel',
                        os.environ.get('X11_BUILD_JOBS', '4')], check=True, env=env)
        provenance.update({'fingerprint': fingerprint, 'library_sha256': digest(library), 'configure': configure})
        receipt.write_text(json.dumps(provenance, indent=2, sort_keys=True) + '\n')
    else:
        recorded = json.loads(receipt.read_text())
        if recorded.get('fingerprint') != fingerprint or any(
                recorded.get(key) != value for key, value in provenance.items()):
            raise RuntimeError('Cached Xlorie receipt does not match current build inputs')
        provenance = recorded
        check(library, provenance['library_sha256'])
    native = pathlib.Path(native)
    native.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(library, native / 'libXlorie.so')
    shutil.copyfile(receipt, native / 'libXlorie.provenance.json')
    return native / 'libXlorie.so'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=pathlib.Path, default=ROOT / '.build/apk/lib/arm64-v8a')
    parser.add_argument('--cache', type=pathlib.Path, default=ROOT / 'vendor/x11-source')
    parser.add_argument('--source-root', type=pathlib.Path)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    print(build(args.native, args.cache, args.prepare_only, args.source_root))


if __name__ == '__main__':
    main()
