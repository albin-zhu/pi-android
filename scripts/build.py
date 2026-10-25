#!/usr/bin/env python3
"""Build a standalone arm64 APK with a pinned embedded Node/Pi runtime."""
import hashlib, io, json, os, pathlib, shutil, subprocess, sys, urllib.request, zipfile
import tarfile
from x11_assets import prepare as prepare_x11
from input_controls import prepare as prepare_input_controls
ROOT=pathlib.Path(__file__).resolve().parent.parent
SDK=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or pathlib.Path.home()/'Library/Android/sdk')
JAVA=pathlib.Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else pathlib.Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin')
BUILD=ROOT/'.build'; OUT=ROOT/'artifacts'; VENDOR=ROOT/'vendor'
VERSION='24.21.0-0'
URL=f'https://github.com/fogtape/nodejs-mobile/releases/download/v{VERSION}/nodejs-mobile-android-{VERSION}.zip'
SHA256='e3cd29a1be03405f11dd5c857af8cd3ad13f84f1409ea648f5328f0bada5bd76'
SHERPA_VERSION='1.13.8'
SHERPA_SHA256='633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
KOTLIN_SHA256='f9cdcdbff1f5de85380ae526977e683726c2aa42db1ed6e6e50ae89e496e95fd'
SILERO_SHA256='c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20'
KWS_VERSION='2024-01-01'
KWS_URL=f'https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-{KWS_VERSION}.tar.bz2'
KWS_SHA256='b2f7c89690dc8ce4c6ed6afeab7cd800c36ad1421fb6b6302b4a4b194cf7f35f'
XZ_VERSION='5.8.4'
XZ_SHA256='c26e35d5393d4ad59d7bfbac93570d418996e69bc160458bee0e165844ae56e8'
LIBLZMA_SHA256='44e95e6e60dddb3705e60a344a4f009a1085a210797342b721eaff41b44033c0'
APP_VERSION=json.loads((ROOT/'package.json').read_text())['version']
TARGET_SDK=int(os.environ.get('ZFOLD_TARGET_SDK','35'))
if TARGET_SDK not in (28,35):raise RuntimeError('ZFOLD_TARGET_SDK must be 28 or 35')
RUNTIME_CHANNEL='compat28' if TARGET_SDK==28 else 'modern'
APP_RELEASE=APP_VERSION+('' if TARGET_SDK==35 else '-compat28')
APP_VERSION_CODE=str(sum(int(n)*m for n,m in zip(APP_VERSION.split('.'),(10000,100,1))))
def run(*args):
    subprocess.run([str(a) for a in args],cwd=ROOT,check=True)
def pinned(file,url,digest):
    if not file.exists():urllib.request.urlretrieve(url,file)
    actual=hashlib.sha256(file.read_bytes()).hexdigest()
    if actual!=digest:raise RuntimeError(f'Checksum mismatch for {file.name}: {actual}')
    return file

def deb_data(path):
    data=path.read_bytes(); off=8
    if data[:8]!=b'!<arch>\n':raise RuntimeError(f'Invalid Debian archive: {path}')
    while off+60<=len(data):
        name=data[off:off+16].split(b'\0')[0].rstrip(b'/').decode()
        size=int(data[off+48:off+58])
        off+=60
        if name.startswith('data.tar.'):
            return data[off:off+size],name
        off+=size+(size%2)
    raise RuntimeError(f'Debian archive has no data tar: {path}')

def bootstrap_xz(native):
    xz=pinned(VENDOR/f'xz-utils-{XZ_VERSION}-aarch64.deb',
      f'https://packages.termux.dev/apt/termux-main/pool/main/x/xz-utils/xz-utils_{XZ_VERSION}_aarch64.deb',XZ_SHA256)
    lib=pinned(VENDOR/f'liblzma-{XZ_VERSION}-aarch64.deb',
      f'https://packages.termux.dev/apt/termux-main/pool/main/libl/liblzma/liblzma_{XZ_VERSION}_aarch64.deb',LIBLZMA_SHA256)
    extracted=[]
    for archive,wanted in ((xz,'usr/bin/xz'),(lib,'usr/lib/liblzma.so.5.8.4')):
        blob,name=deb_data(archive)
        with tarfile.open(fileobj=io.BytesIO(blob),mode='r:xz') as tar:
            members={member.name:member for member in tar.getmembers()}
            match=next((key for key in members if key.endswith('/'+wanted)),None)
            if not match:raise RuntimeError(f'{archive.name} is missing {wanted}')
            source=tar.extractfile(members[match])
            if source is None:raise RuntimeError(f'{archive.name} contains a non-file {wanted}')
            extracted.append(source.read())
    (native/'libzfold_xz.so').write_bytes(extracted[0])
    (native/'libzfold_lzma.so').write_bytes(extracted[1])
def main():
    key=BUILD/'debug.keystore'
    if not key.is_file():raise FileNotFoundError(f'Missing signing key: {key}. Restore the existing key as described in SIGNING.md; refusing to generate a replacement.')
    for d in [BUILD,OUT,VENDOR]:d.mkdir(exist_ok=True)
    archive=pinned(VENDOR/f'nodejs-mobile-android-{VERSION}.zip',URL,SHA256)
    aar=pinned(VENDOR/f'sherpa-onnx-{SHERPA_VERSION}.aar',f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v{SHERPA_VERSION}/sherpa-onnx-{SHERPA_VERSION}.aar',SHERPA_SHA256)
    kotlin=pinned(VENDOR/'kotlin-stdlib-1.9.25.jar','https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.25/kotlin-stdlib-1.9.25.jar',KOTLIN_SHA256)
    model=ROOT/'android/assets/models/silero_vad.int8.onnx'
    if hashlib.sha256(model.read_bytes()).hexdigest()!=SILERO_SHA256:raise RuntimeError('Silero VAD model checksum mismatch')
    kwsArchive=pinned(VENDOR/f'sherpa-onnx-kws-zipformer-wenetspeech-3.3M-{KWS_VERSION}.tar.bz2',KWS_URL,KWS_SHA256)
    kws_dir=ROOT/'android/assets/models/kws';kws_dir.mkdir(parents=True,exist_ok=True)
    with tarfile.open(kwsArchive,'r:bz2') as kwsTar:
      prefix=f'sherpa-onnx-kws-zipformer-wenetspeech-3.3M-{KWS_VERSION}'
      for source,target in [('encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx','encoder.int8.onnx'),('decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx','decoder.int8.onnx'),('joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx','joiner.int8.onnx'),('tokens.txt','tokens.txt')]:
        member=kwsTar.getmember(f'{prefix}/{source}');input=kwsTar.extractfile(member)
        if input is None:raise RuntimeError(f'Missing KWS model file: {source}')
        (kws_dir/target).write_bytes(input.read())
    wake=json.loads((ROOT/'android/assets/wake.json').read_text())
    vocabulary={line.split()[0] for line in (kws_dir/'tokens.txt').read_text().splitlines() if line.strip()}
    if not wake['word'] or any(c.isspace() or c=='@' for c in wake['word']) or wake['word'] not in wake['asrPrefixes'] or any(token not in vocabulary for token in wake['tokens'].split()):raise RuntimeError('Wake configuration has inconsistent name, aliases or model tokens')
    (kws_dir/'keywords.txt').write_text(wake['tokens']+' @'+wake['word']+'\n')
    native=BUILD/'apk/lib/arm64-v8a'
    if native.exists():shutil.rmtree(native)
    native.mkdir(parents=True)
    bootstrap_xz(native)
    prepare_x11(native, BUILD/'assets', VENDOR)
    prepare_input_controls()
    staleRuntime=BUILD/'assets/runtime'
    if staleRuntime.exists():
      shutil.rmtree(staleRuntime/'npm',ignore_errors=True)
      (staleRuntime/'node-worker-entry.cjs').unlink(missing_ok=True)
    with zipfile.ZipFile(archive) as z:
        candidates=[n for n in z.namelist() if n.endswith('arm64-v8a/libnode.so')]
        if len(candidates)!=1:raise RuntimeError('Unexpected Node archive layout: '+str(candidates))
        (native/'libnode.so').write_bytes(z.read(candidates[0]))
        headers=BUILD
        for name in z.namelist():
            if name.startswith('include/') and name.endswith('.h'):
                target=headers/name
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(z.read(name))
    sherpa_classes=BUILD/'sherpa-classes.jar'
    with zipfile.ZipFile(aar) as z:
        sherpa_classes.write_bytes(z.read('classes.jar'))
        for name in ('libonnxruntime.so','libsherpa-onnx-c-api.so','libsherpa-onnx-cxx-api.so','libsherpa-onnx-jni.so'):
            (native/name).write_bytes(z.read('jni/arm64-v8a/'+name))
    host='darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
    ndk=SDK/'ndk/28.0.13004108/toolchains/llvm/prebuilt'/host
    run(ndk/'bin/aarch64-linux-android30-clang++','-std=c++17','-fPIC','-shared',ROOT/'native/bridge.cpp','-L'+str(native),'-lnode','-llog','-Wl,-z,max-page-size=16384','-Wl,-soname,libzfold.so','-o',native/'libzfold.so')
    run(ndk/'bin/aarch64-linux-android30-clang','-std=c11','-D_GNU_SOURCE','-Wall','-Wextra','-Werror','-fPIE','-pie',ROOT/'native/package_cli.c','-Wl,-z,max-page-size=16384','-o',native/'libzfold_pkg.so')
    run(ndk/'bin/aarch64-linux-android30-clang','-std=c11','-O2','-Wall','-Wextra','-Werror','-fPIC','-shared','-nostdlib',ROOT/'native/x11-network.c','-Wl,-z,max-page-size=16384','-Wl,-soname,libzfold_x11_guard.so','-o',native/'libzfold_x11_guard.so')
    shutil.copy2(ndk/'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so',native/'libc++_shared.so')
    run('npm','run','bundle')
    channelAsset=BUILD/'assets/runtime/runtime-channel.json';channelAsset.parent.mkdir(parents=True,exist_ok=True)
    channelAsset.write_text(json.dumps({'channel':RUNTIME_CHANNEL,'targetSdk':TARGET_SDK,'writableExec':TARGET_SDK==28})+'\n')
    manifest=ROOT/'android/AndroidManifest.xml'
    if TARGET_SDK!=35:
      text=manifest.read_text()
      updated=text.replace('android:targetSdkVersion="35"',f'android:targetSdkVersion="{TARGET_SDK}"')
      if updated==text:raise RuntimeError('Expected targetSdkVersion not found')
      manifest=BUILD/f'AndroidManifest-{TARGET_SDK}.xml';manifest.write_text(updated)
    tools=SDK/'build-tools/35.0.0';android=SDK/'platforms/android-35/android.jar'
    generated=BUILD/'generated';classes=BUILD/'classes';dex=BUILD/'dex'
    for d in [generated,classes,dex]:
        if d.exists():shutil.rmtree(d)
        d.mkdir(exist_ok=True)
    run(tools/'aapt2','compile','--dir',ROOT/'android/res','-o',BUILD/'resources.zip')
    run(tools/'aapt2','link','-o',BUILD/'resources.apk','--manifest',manifest,'-I',android,'--java',generated,'-A',ROOT/'android/assets','-A',BUILD/'assets',BUILD/'resources.zip','--auto-add-overlay','--version-code',APP_VERSION_CODE,'--version-name',APP_RELEASE)
    sources=list((ROOT/'android/src').rglob('*.java'))+list((ROOT/'third_party/termux-terminal/src').rglob('*.java'))+list(generated.rglob('*.java'))
    run(JAVA/'javac','-encoding','UTF-8','-source','11','-target','11','-classpath',os.pathsep.join(map(str,(android,sherpa_classes,kotlin))),'-d',classes,*sources)
    classfiles=list(classes.rglob('*.class'))
    run(JAVA/'java','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','30','--output',dex,*classfiles,sherpa_classes,kotlin)
    unsigned=BUILD/'unsigned.apk';shutil.copy2(BUILD/'resources.apk',unsigned)
    with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as apk:
        for f in dex.glob('*.dex'):apk.write(f,f.name)
        for f in native.glob('*.so'):apk.write(f,'lib/arm64-v8a/'+f.name)
    run(tools/'zipalign','-P','16','-f','4',unsigned,BUILD/'aligned.apk')
    target=OUT/f"Zfold-{APP_RELEASE}-debug.apk"
    run(JAVA/'java','-jar',tools/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','zfold','--ks-pass','pass:android','--key-pass','pass:android','--out',target,BUILD/'aligned.apk')
    run(JAVA/'java','-jar',tools/'lib/apksigner.jar','verify',target)
    print(f'APK: {target} ({target.stat().st_size/1024/1024:.1f} MiB)')
if __name__=='__main__':main()
