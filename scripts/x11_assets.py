#!/usr/bin/env python3
"""Prepare source-built rootless X11 native code and pinned keyboard data."""
import argparse
import hashlib
import io
import json
import pathlib
import posixpath
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

from x11_native import build as build_native

ROOT = pathlib.Path(__file__).resolve().parent.parent


def pinned(cache, name, artifact):
    """A mutable upstream URL is never trusted without its recorded digest."""
    target = cache / name
    if not target.exists():
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
                temporary = pathlib.Path(output.name)
                with urllib.request.urlopen(artifact['url'], timeout=120) as response:
                    shutil.copyfileobj(response, output)
            verify(temporary.read_bytes(), artifact['sha256'], name)
            temporary.replace(target)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    verify(target.read_bytes(), artifact['sha256'], name)
    return target


def verify(data, expected, name):
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise RuntimeError(f'Checksum mismatch for {name}: {actual}; expected {expected}. '
                           'Refusing changed upstream artifacts; do not update pins independently of source/JNI review.')


def deb_data(path):
    data = path.read_bytes()
    if data[:8] != b'!<arch>\n':
        raise RuntimeError(f'Invalid Debian archive: {path}')
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        name = header[:16].decode('ascii').strip().rstrip('/')
        size = int(header[48:58])
        offset += 60
        if header[58:60] != b'`\n' or size < 0 or offset + size > len(data):
            raise RuntimeError(f'Malformed Debian archive: {path}')
        if name == 'data.tar.xz':
            return data[offset:offset + size]
        offset += size + size % 2
    raise RuntimeError(f'Missing data.tar.xz in {path}')


def keyboard_zip(package, target):
    prefix = 'usr/share/X11/xkb/'
    files = {}
    with tarfile.open(fileobj=io.BytesIO(deb_data(package)), mode='r:xz') as archive:
        members = {m.name.removeprefix('./'): m for m in archive.getmembers()}
        for name, member in sorted(members.items()):
            if not name.startswith(prefix) or member.isdir():
                continue
            relative = name[len(prefix):]
            if not relative or '..' in pathlib.PurePosixPath(relative).parts:
                raise RuntimeError(f'Unsafe XKB path: {name}')
            source = member
            if member.issym():
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname))
                if not resolved.startswith(prefix) or resolved not in members:
                    raise RuntimeError(f'Unsafe XKB symlink: {name}')
                source = members[resolved]
            if not source.isfile():
                raise RuntimeError(f'Unsupported XKB entry: {name}')
            stream = archive.extractfile(source)
            if stream is None:
                raise RuntimeError(f'Missing XKB content: {name}')
            files[relative] = stream.read()
    for required in ('rules/evdev', 'rules/base', 'keycodes/evdev', 'symbols/us', 'types/complete', 'compat/complete', 'geometry/pc'):
        if required not in files:
            raise RuntimeError(f'Keyboard package missing {required}')
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, data, compresslevel=9)


def prepare(native, assets, cache=None):
    """Write native/libXlorie.so and assets/x11/{xkb.zip,licenses/*}."""
    native, assets = pathlib.Path(native), pathlib.Path(assets)
    cache = pathlib.Path(cache) if cache is not None else ROOT / 'vendor'
    for directory in (native, assets / 'x11', cache):
        directory.mkdir(parents=True, exist_ok=True)
    upstream = ROOT / 'third_party/termux-x11'
    keyboard = ROOT / 'third_party/xkeyboard-config'
    library = build_native(native, cache / 'x11-source')
    xkb = json.loads((keyboard / 'sources.json').read_text())
    package = pinned(cache, f'xkb-data_{xkb["version"]}_all.deb', xkb['package'])
    keyboard_zip(package, assets / 'x11/xkb.zip')
    for component, directory in (('termux-x11', upstream), ('xkeyboard-config', keyboard)):
        destination = assets / 'x11/licenses' / component
        destination.mkdir(parents=True, exist_ok=True)
        for filename in ('LICENSE.txt', 'sources.json', 'SOURCE.txt', 'build-inputs.json'):
            source = directory / filename
            if source.exists() and not (component == 'termux-x11' and filename == 'sources.json'):
                shutil.copyfile(source, destination / filename)
    receipt_path = native / 'libXlorie.provenance.json'
    receipt = json.loads(receipt_path.read_text())
    actual = hashlib.sha256(library.read_bytes()).hexdigest()
    if actual != receipt['library_sha256']:
        raise RuntimeError('Built Xlorie does not match its source-build provenance')
    metadata = json.loads((upstream / 'sources.json').read_text())
    metadata['upstream_artifacts'] = {'apk': metadata.pop('apk'), 'native': metadata.pop('native')}
    metadata['native'] = {
        'member': 'lib/arm64-v8a/libXlorie.so',
        'sha256': actual,
        'modified': True,
        'upstream_commit': receipt['source_commit'],
        'build_fingerprint': receipt['fingerprint'],
        'overrides': receipt['overrides'],
        'provenance': 'native-build.json',
    }
    destination = assets / 'x11/licenses/termux-x11'
    (destination / 'sources.json').write_text(json.dumps(metadata, indent=2, sort_keys=True) + '\n')
    shutil.copyfile(receipt_path, destination / 'native-build.json')
    return library, assets / 'x11/xkb.zip'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=pathlib.Path, default=ROOT / '.build/apk/lib/arm64-v8a')
    parser.add_argument('--assets', type=pathlib.Path, default=ROOT / '.build/assets')
    parser.add_argument('--cache', type=pathlib.Path, default=ROOT / 'vendor')
    args = parser.parse_args()
    for path in prepare(args.native, args.assets, args.cache):
        print(path)


if __name__ == '__main__':
    main()
