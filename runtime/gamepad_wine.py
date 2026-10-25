"""Prefix-local XInput integration; never changes the Wine installation.

Only absent files, positively marked Wine builtins, or hash-verified previous
Zfold bridges may be replaced. Originals remain in an app-owned backup store.
A durable undo journal recovers interrupted updates, with collision checks before
rollback. Each file replacement is atomic; a running Wine process may retain an
already loaded DLL until it exits. DirectInput and force feedback are unsupported.
"""
import fcntl
import hashlib
import json
import os
import stat
import struct
import tempfile

NAMES = ('xinput1_1', 'xinput1_2', 'xinput1_3', 'xinput1_4', 'xinput9_1_0')
TARGETS = {f'drive_c/windows/{folder}/{name}.dll': f'{arch}/{name}.dll'
           for folder, arch in (('system32', 'x86_64-windows'), ('syswow64', 'i386-windows'))
           for name in NAMES}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def atomic(path, data, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.zfold-input-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            os.fchmod(stream.fileno(), mode)
        os.replace(temporary, path)
    finally:
        if os.path.lexists(temporary):
            os.unlink(temporary)


def describe(path, store):
    if not os.path.lexists(path):
        return {'kind': 'absent'}
    if not path.is_file():
        raise RuntimeError(f'Gamepad DLL collision (not a regular file): {path}')
    data = path.read_bytes()
    checksum = sha(data)
    backup = store / checksum
    if backup.exists():
        if sha(backup.read_bytes()) != checksum:
            raise RuntimeError(f'Gamepad backup checksum mismatch: {backup}')
    else:
        atomic(backup, data)
    result = {'kind': 'symlink' if path.is_symlink() else 'file', 'sha256': checksum,
              'mode': stat.S_IMODE(path.stat().st_mode)}
    if path.is_symlink():
        result['link'] = os.readlink(path)
    return result


def current_hash(path):
    if not os.path.lexists(path):
        return None
    if not path.is_file():
        raise RuntimeError(f'Gamepad DLL collision (not a regular file): {path}')
    return sha(path.read_bytes())


def restore(path, description, store):
    kind = description['kind']
    if kind == 'absent':
        path.unlink(missing_ok=True)
        return
    data = (store / description['sha256']).read_bytes()
    if sha(data) != description['sha256']:
        raise RuntimeError(f'Gamepad backup checksum mismatch: {path}')
    if kind == 'symlink':
        fd, temporary = tempfile.mkstemp(prefix='.zfold-input-', dir=path.parent)
        os.close(fd)
        os.unlink(temporary)
        try:
            os.symlink(description['link'], temporary)
            os.replace(temporary, path)
        finally:
            if os.path.lexists(temporary):
                os.unlink(temporary)
    else:
        atomic(path, data, description['mode'])


def wine_builtin(data, relative):
    if len(data) < 96 or data[:2] != b'MZ' or not (
            data[64:].startswith(b'Wine builtin DLL\0') or data[64:].startswith(b'Wine placeholder DLL\0')):
        return False
    offset = struct.unpack_from('<I', data, 60)[0]
    machine = 0x8664 if '/system32/' in relative else 0x14c
    return offset + 6 <= len(data) and data[offset:offset + 4] == b'PE\0\0' and struct.unpack_from('<H', data, offset + 4)[0] == machine


def checked_target(prefix, relative):
    if relative not in TARGETS:
        raise RuntimeError(f'Invalid gamepad manifest path: {relative}')
    path = prefix / relative
    if not path.parent.resolve().is_relative_to(prefix.resolve()):
        raise RuntimeError(f'Gamepad DLL directory escapes Wine prefix: {path.parent}')
    return path


def recover(prefix, metadata, store):
    journal = metadata / 'transaction.json'
    if not journal.exists():
        return
    transaction = json.loads(journal.read_text())
    if transaction.get('committed'):
        journal.unlink()
        return
    # Preflight every target before restoring any: never overwrite an external edit.
    for relative, change in transaction['changes'].items():
        path = checked_target(prefix, relative)
        if current_hash(path) not in (change['before'].get('sha256'), change['after'].get('sha256')):
            raise RuntimeError(f'Interrupted gamepad update conflicts with external DLL: {path}')
    for relative, change in transaction['changes'].items():
        restore(checked_target(prefix, relative), change['before'], store)
    restore(metadata / 'installed.json', transaction['manifest_before'], store)
    journal.unlink()


def update_prefix(prefix, assets, enabled):
    metadata = prefix / '.zfold-gamepad'
    if not enabled and not metadata.exists():
        return
    metadata.mkdir(parents=True, exist_ok=True)
    if metadata.is_symlink() or not metadata.resolve().is_relative_to(prefix.resolve()):
        raise RuntimeError(f'Gamepad metadata directory escapes Wine prefix: {metadata}')
    store = metadata / 'originals'
    store.mkdir(exist_ok=True)
    if store.is_symlink():
        raise RuntimeError(f'Gamepad backup directory is a symlink: {store}')
    with (metadata / 'lock').open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        recover(prefix, metadata, store)
        manifest = metadata / 'installed.json'
        installed = json.loads(manifest.read_text()) if manifest.exists() else {}
        if any(relative not in TARGETS for relative in installed):
            raise RuntimeError(f'Invalid gamepad installation manifest: {manifest}')
        products = json.loads((assets / 'manifest.json').read_text())['sha256'] if enabled else {}
        changes, next_manifest = {}, {}
        # Preflight ALL targets and source hashes before replacing any DLL.
        for relative, product in TARGETS.items():
            if not enabled and relative not in installed:
                continue
            target = checked_target(prefix, relative)
            current = current_hash(target)
            old = installed.get(relative)
            ours = old is not None and current == old['installed_sha256']
            if not enabled:
                if not ours:
                    raise RuntimeError(f'Refusing to restore externally changed gamepad DLL: {target}')
                before = describe(target, store)
                after = old['original']
            else:
                data = (assets / product).read_bytes()
                expected = products.get(product)
                if expected != sha(data):
                    raise RuntimeError(f'Packaged gamepad DLL checksum mismatch: {assets / product}')
                if current is not None and not ours and not wine_builtin(target.read_bytes(), relative):
                    raise RuntimeError(f'Gamepad DLL collision; preserving custom DLL: {target}. '
                                       'Use ZFOLD_GAMEPAD=0 or a separate Wine prefix.')
                before = describe(target, store)
                original = old['original'] if ours else before
                after = {'kind': 'file', 'sha256': expected, 'mode': 0o600}
                if not (store / expected).exists():
                    atomic(store / expected, data)
                next_manifest[relative] = {'installed_sha256': expected, 'original': original}
            if before != after:
                changes[relative] = {'before': before, 'after': after}
        if installed == next_manifest and not changes:
            return
        transaction = {'changes': changes, 'manifest_before': describe(manifest, store), 'committed': False}
        journal = metadata / 'transaction.json'
        atomic(journal, json.dumps(transaction, sort_keys=True).encode())
        try:
            for relative, change in changes.items():
                target = checked_target(prefix, relative)
                target.parent.mkdir(parents=True, exist_ok=True)
                restore(target, change['after'], store)
            if next_manifest:
                atomic(manifest, json.dumps(next_manifest, sort_keys=True).encode())
            else:
                manifest.unlink(missing_ok=True)
            transaction['committed'] = True
            atomic(journal, json.dumps(transaction, sort_keys=True).encode())
        except BaseException:
            recover(prefix, metadata, store)
            raise
        journal.unlink()


def strip_linux_preload(environment, assets):
    # The ELF preload is for native arm64 Linux, NOT Box64's x86_64 loader.
    preload = environment.get('LD_PRELOAD', '')
    paths = preload.replace(':', ' ').split()
    paths = [path for path in paths if path != str(assets / 'linux-aarch64/libzfold-gamepad.so')]
    if paths:
        environment['LD_PRELOAD'] = ':'.join(paths)
    else:
        environment.pop('LD_PRELOAD', None)


def configure(prefix, environment, assets):
    enabled = environment.get('ZFOLD_GAMEPAD', '1') != '0'
    update_prefix(prefix, assets, enabled)
    if not enabled:
        return
    overrides = []
    for clause in environment.get('WINEDLLOVERRIDES', '').split(';'):
        if not clause:
            continue
        if '=' not in clause:
            overrides.append(clause)
            continue
        modules, order = clause.split('=', 1)
        remaining = [name for name in modules.split(',') if name.strip().lower().removesuffix('.dll').lstrip('*') not in NAMES]
        if remaining:
            overrides.append(','.join(remaining) + '=' + order)
    overrides.append(','.join(NAMES) + '=n')
    environment['WINEDLLOVERRIDES'] = ';'.join(overrides)
