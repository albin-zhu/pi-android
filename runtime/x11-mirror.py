#!/usr/bin/env python3
"""Read-only frame relay for one X11 display used by the Android viewer.

The viewer starts this process inside the Ubuntu guest, connects to the Unix
socket, and receives length-prefixed JPEG frames. No input is ever sent to the
X server. The relay exits when the viewer closes the socket or the process is
terminated; the Xvfb server and its clients are never touched.

Protocol (big-endian):
  handshake: b"XMF1" + uint32 json-length + json
  frame:     uint32 jpeg-length + jpeg
"""
import glob
import json
import os
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time

MAGIC = b'XMF1'
MAX_FRAME = 8 * 1024 * 1024
MAX_WIDTH = max(240, min(1440, int(os.environ.get('X11_MIRROR_WIDTH', '720'))))
FPS = max(1, min(8, int(os.environ.get('X11_MIRROR_FPS', '3'))))
CAPTURE = os.environ.get('X11_MIRROR_CAPTURE', 'auto')
_processes = set()


def stop_process(process):
    try:
        process.terminate()
    except OSError:
        return
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        try:
            process.kill()
            process.wait(timeout=2)
        except (OSError, subprocess.TimeoutExpired):
            pass


def _terminate(*_):
    for process in list(_processes):
        stop_process(process)
    raise SystemExit(0)


signal.signal(signal.SIGTERM, _terminate)
signal.signal(signal.SIGINT, _terminate)


def auth_paths(display):
    """Candidate Xauthority files for a display, including xvfb-run and -auth."""
    paths = []
    number = display.lstrip(':')
    # The server's own -auth argument is the most specific candidate; trying it
    # first keeps dead or unrelated displays from delaying every probe.
    try:
        with open('/tmp/.X%s-lock' % number, encoding='ascii') as lock:
            pid = int(lock.read().strip())
        with open('/proc/%d/cmdline' % pid, 'rb') as cmdline:
            arguments = cmdline.read().split(b'\0')
        for index, argument in enumerate(arguments):
            if argument in (b'-auth', b'-authfile') and index + 1 < len(arguments):
                paths.append(arguments[index + 1].decode('utf-8', 'replace'))
    except (OSError, ValueError):
        pass
    paths.extend(sorted(glob.glob('/tmp/xvfb-run.*/Xauthority'), reverse=True)[:8])
    if os.environ.get('XAUTHORITY'):
        paths.append(os.environ['XAUTHORITY'])
    paths.append('/run/zfold-x11/Xauthority')
    seen = set()
    for path in paths:
        if path and path not in seen and os.path.isfile(path):
            seen.add(path)
            yield path


def probe(display):
    """Return (environment, width, height) for a display or None."""
    for auth in list(auth_paths(display)) + [None]:
        environment = dict(os.environ)
        if auth:
            environment['XAUTHORITY'] = auth
        else:
            environment.pop('XAUTHORITY', None)
        try:
            result = subprocess.run(
                ['xdpyinfo', '-display', display], env=environment,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                timeout=2, text=True)
        except (OSError, subprocess.SubprocessError):
            continue
        match = re.search(r'dimensions:\s+(\d+)x(\d+) pixels', result.stdout or '')
        if match:
            return environment, int(match.group(1)), int(match.group(2))
    return None


def ffmpeg_frames(display, environment, width, height):
    target = min(MAX_WIDTH, width)
    command = [
        'ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin',
        '-f', 'x11grab', '-framerate', str(FPS),
        '-video_size', '%dx%d' % (width, height), '-i', display,
        '-vf', 'scale=%d:-2' % target, '-c:v', 'mjpeg', '-q:v', '6',
        '-flush_packets', '1', '-f', 'mjpeg', 'pipe:1',
    ]
    process = subprocess.Popen(
        command, env=environment, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL)
    _processes.add(process)
    buffer = b''
    try:
        while True:
            # read1 returns as soon as one syscall yields data; read(n) would
            # wait for n bytes and delay small JPEG frames on static screens.
            chunk = process.stdout.read1(65536)
            if not chunk:
                return
            buffer += chunk
            while True:
                start = buffer.find(b'\xff\xd8')
                if start < 0:
                    break
                end = buffer.find(b'\xff\xd9', start + 2)
                if end < 0:
                    break
                yield buffer[start:end + 2]
                buffer = buffer[end + 2:]
            if len(buffer) > MAX_FRAME:
                buffer = b''
    finally:
        _processes.discard(process)
        stop_process(process)


def xwd_frames(display, environment, width, height):
    if not shutil.which('xwd') or not shutil.which('convert'):
        raise RuntimeError('需要 ffmpeg，或 xwd + ImageMagick（convert）')
    while True:
        started = time.monotonic()
        try:
            shot = subprocess.run(
                ['xwd', '-display', display, '-root', '-silent'],
                env=environment, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=5)
            if shot.returncode != 0 or not shot.stdout:
                return
            jpeg = subprocess.run(
                ['convert', 'xwd:-', '-resize', '%dx' % min(MAX_WIDTH, width),
                 '-quality', '72', 'jpg:-'],
                input=shot.stdout, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=5)
        except (OSError, subprocess.SubprocessError):
            return
        if jpeg.returncode != 0 or not jpeg.stdout:
            return
        yield jpeg.stdout
        delay = 1.0 / FPS - (time.monotonic() - started)
        if delay > 0:
            time.sleep(delay)


def frames(display, environment, width, height):
    if CAPTURE == 'xwd':
        return xwd_frames(display, environment, width, height)
    if CAPTURE == 'ffmpeg' or shutil.which('ffmpeg'):
        return ffmpeg_frames(display, environment, width, height)
    return xwd_frames(display, environment, width, height)


def send_json(client, payload):
    data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
    client.sendall(MAGIC + struct.pack('>I', len(data)) + data)


def main():
    if len(sys.argv) != 3 or not re.fullmatch(r':\d+', sys.argv[1]):
        raise SystemExit('usage: x11-mirror.py :DISPLAY /run/zfold-x11/mirror.sock')
    display, path = sys.argv[1], sys.argv[2]
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(path)
    os.chmod(path, 0o600)
    server.listen(1)
    server.settimeout(20)
    try:
        client, _ = server.accept()
    except socket.timeout:
        server.close()
        return
    sent = False
    try:
        client.settimeout(15)
        if not shutil.which('xdpyinfo'):
            send_json(client, {'error': '需要 xdpyinfo；请在 Ubuntu 安装 x11-utils'})
            return
        if CAPTURE == 'xwd':
            ready = shutil.which('xwd') and shutil.which('convert')
        else:
            ready = shutil.which('ffmpeg') or (shutil.which('xwd') and shutil.which('convert'))
        if not ready:
            send_json(client, {'error': '需要 ffmpeg，或 xwd + ImageMagick（convert）'})
            return
        result = probe(display)
        if result is None:
            send_json(client, {'error': '无法连接 %s：显示未运行或缺少可用的 Xauthority' % display})
            return
        environment, width, height = result
        send_json(client, {'display': display, 'width': width, 'height': height,
                           'fps': FPS, 'readOnly': True})
        sent = True
        source = frames(display, environment, width, height)
        try:
            for jpeg in source:
                if not jpeg or len(jpeg) > MAX_FRAME:
                    continue
                client.sendall(struct.pack('>I', len(jpeg)) + jpeg)
        finally:
            close = getattr(source, 'close', None)
            if close is not None:
                close()
    except (BrokenPipeError, ConnectionResetError, socket.timeout):
        pass
    except (OSError, RuntimeError) as error:
        print('x11-mirror: %s' % error, file=sys.stderr)
        if not sent:
            try:
                send_json(client, {'error': str(error)})
            except OSError:
                pass
    finally:
        try:
            client.close()
        except OSError:
            pass
        server.close()
        # Do not unlink here: a replacement relay may already have bound the
        # same path. The next start removes a stale socket before binding.


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError) as error:
        print('x11-mirror: %s' % error, file=sys.stderr)
        raise SystemExit(1)
