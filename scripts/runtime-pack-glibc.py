#!/usr/bin/env python3
"""Build the experimental GNU GCC pack for Pi's compat28 runtime."""
import argparse, hashlib, os, importlib.util, io, json, pathlib, re, shutil, stat, subprocess, sys, tarfile, tempfile, urllib.request

HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('runtime_pack',HERE/'runtime-pack.py')
rp=importlib.util.module_from_spec(spec);spec.loader.exec_module(rp)

INDEX='https://packages.termux.dev/apt/termux-glibc/dists/glibc/stable/binary-aarch64/Packages'
POOL='https://packages.termux.dev/apt/termux-glibc/'
TERMUX_SOURCE='/data/data/com.termux/files/usr'
# The build channel is sideload-only and currently pins the debug package/user path.
APP_CACHE='/data/user/0/dev.zfold.assistant/files/runtime/runtime-exec-test'
GCC_VERSION='14.2.1'
ROOTS=('gcc-glibc','binutils-glibc','make-glibc','patchelf-glibc')
LAUNCHER_SOURCE=HERE.parent/'native'/'gcc-glibc-launcher.c'
IGNORED_DEPENDENCIES={'resolv-conf'}

def packages(text):
  result={}
  for block in text.split('\n\n'):
    values={}
    for line in block.splitlines():
      if ': ' in line:
        key,value=line.split(': ',1);values.setdefault(key,value)
    name=values.get('Package')
    if not name or not values.get('Filename'):continue
    old=result.get(name)
    if old is None or values.get('Version','')>old.get('Version',''):result[name]=values
  return result

def dependency_name(value):return re.match(r'^[^<>= ]+',value.strip()).group(0)

def resolve(selected,index):
  seen=set();chosen=[]
  def visit(name):
    if name in seen:return
    seen.add(name)
    if name not in index:
      if name in IGNORED_DEPENDENCIES:return
      raise RuntimeError(f'package not found: {name}')
    for dependency in index[name].get('Depends','').split(','):
      if dependency.strip():visit(dependency_name(dependency))
    chosen.append(index[name])
  for name in ROOTS:visit(name)
  return chosen

def archive_payload(path):
  data=path.read_bytes();offset=8
  while offset+60<=len(data):
    header=data[offset:offset+60];offset+=60
    name=header[:16].decode('ascii').strip().rstrip('/')
    size=int(header[48:58].decode('ascii').strip())
    payload=data[offset:offset+size];offset+=size+(size%2)
    if name.startswith('data.tar.'):return payload
  raise RuntimeError(f'{path}: no data archive')

def install(index_item,data_archive,payload,staging):
  with tempfile.TemporaryDirectory(prefix=f"pi-glibc-{index_item['Package']}-") as temporary:
    root=pathlib.Path(temporary);rp.safe_extract(tarfile.open(fileobj=io.BytesIO(data_archive),mode='r:xz'),root)
    source=root/'data'/'data'/'com.termux'/'files'/'usr'
    if not source.is_dir():raise RuntimeError(f"{index_item['Package']}: no Termux usr tree")
    shutil.copytree(source,staging/'usr',dirs_exist_ok=True,symlinks=True)

def patch_elfs(staging):
  loader=(staging/'usr'/'glibc'/'lib'/'ld-linux-aarch64.so.1').resolve()
  helper=(staging/'usr'/'glibc'/'bin'/'patchelf').resolve()
  interpreter=f'{APP_CACHE}/usr/glibc/lib/ld-linux-aarch64.so.1'
  rpath=f'{APP_CACHE}/usr/glibc/lib'
  patched=0
  for path in sorted((staging/'usr'/'glibc').rglob('*')):
    if not path.is_file() or path.is_symlink() or path.resolve() in (loader,helper):continue
    if path.read_bytes()[:4]!=b'\x7fELF':continue
    first=subprocess.run(['patchelf','--set-interpreter',interpreter,str(path)],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    if first.returncode==0:patched+=1
  return patched

def android_clang():
  override=os.environ.get('LAUNCHER_CC')
  if override:return pathlib.Path(override)
  candidates=[]
  ndk=os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT')
  if ndk:candidates += pathlib.Path(ndk).glob('toolchains/llvm/prebuilt/*/bin/aarch64-linux-android24-clang')
  candidates += pathlib.Path('/Users/mt/Library/Android/sdk/ndk').glob('*/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android24-clang')
  for candidate in candidates:
    if candidate.is_file():return candidate
  raise RuntimeError('set LAUNCHER_CC to aarch64-linux-android24-clang')

def build_launcher(staging):
  compiler=android_clang();output=staging/'usr'/'bin'/'gcc-glibc-launcher'
  subprocess.run([str(compiler),'-O2','-static','-fuse-ld=lld',str(LAUNCHER_SOURCE),'-o',str(output)],check=True)
  output.chmod(output.stat().st_mode|stat.S_IXUSR|stat.S_IXGRP|stat.S_IXOTH)
  return output

def main():
  parser=argparse.ArgumentParser()
  parser.add_argument('--output',type=pathlib.Path,required=True)
  parser.add_argument('--cache',type=pathlib.Path,default=pathlib.Path('.build/runtime-pack-glibc-cache'))
  args=parser.parse_args();args.output.parent.mkdir(parents=True,exist_ok=True)
  index=packages(rp.fetch(INDEX).decode('utf-8'));selected=resolve(None,index)
  print(f"packages {len(selected)} ({sum(int(item.get('Size','0')) for item in selected)} bytes)")
  staging=pathlib.Path(tempfile.mkdtemp(prefix='pi-gcc-gnu-'))/'pack';staging.mkdir()
  for item in selected:
    artifact=args.cache/pathlib.Path(item['Filename']).name
    if not artifact.exists():
      print('download',item['Package'],item.get('Version',''));rp.fetch(POOL+item['Filename'],artifact)
    payload=artifact.read_bytes()
    if item.get('SHA256') and hashlib.sha256(payload).hexdigest()!=item['SHA256']:
      raise RuntimeError(f"checksum mismatch: {item['Package']}")
    print('install',item['Package'],item.get('Version',''));install(item,archive_payload(artifact),payload,staging)
  rp.normalize_symlinks(staging/'usr')
  bin_dir=staging/'usr'/'bin';glibc_bin=staging/'usr'/'glibc'/'bin';bin_dir.mkdir(parents=True,exist_ok=True)
  launcher=build_launcher(staging)
  for name in ('gcc','g++','cc','c++'):
    if not ((glibc_bin/name).exists() or (glibc_bin/name).is_symlink()):continue
    target=bin_dir/name;shutil.copy2(launcher,target);target.chmod(target.stat().st_mode|stat.S_IXUSR|stat.S_IXGRP|stat.S_IXOTH)
  patched=patch_elfs(staging)
  rewritten=sum(1 for path in (staging/'usr').rglob('*') if path.is_file() and not path.is_symlink() and rp.rewrite_text_file(path))
  environment={
    'PATH':'{cache}/usr/bin:{cache}/usr/glibc/bin:/system/bin:/system/xbin:/vendor/bin',
    'HOME':'{cache}/home','TMPDIR':'{cache}/tmp','LD_LIBRARY_PATH':'{cache}/usr/glibc/lib',
    'GCC_EXEC_PREFIX':'{cache}/usr/glibc/lib/gcc/','COMPILER_PATH':'{cache}/usr/glibc/bin',
    'LIBRARY_PATH':f'{{cache}}/usr/glibc/lib/gcc/aarch64-linux-gnu/{GCC_VERSION}:{{cache}}/usr/glibc/lib',
    'C_INCLUDE_PATH':'{cache}/usr/glibc/include',
    'CPLUS_INCLUDE_PATH':f'{{cache}}/usr/glibc/include/c++/{GCC_VERSION}:{{cache}}/usr/glibc/include',
  }
  manifest={
    'schema':1,'name':'gcc-gnu','architecture':'arm64','libc':'glibc','source':'termux-glibc',
    'packages':[{'name':item['Package'],'version':item.get('Version',''),'sha256':item.get('SHA256',''),'filename':pathlib.Path(item['Filename']).name} for item in selected],
    'rewrittenShebangs':rewritten,'patchedElfs':patched,
    'binaries':sorted(path.name for path in bin_dir.iterdir() if path.is_file() and not path.is_symlink()),
    'replacePrefix':'/data/data/com.termux/files/usr/glibc',
    'environment':environment,
    'environmentScope':{'roots':['usr/bin/gcc','usr/bin/g++','usr/bin/cc','usr/bin/c++','usr/glibc']},
    'limitations':[
      'GNU GCC targets glibc; bionic Clang remains available from build-toolchain pack',
      'compiler wrappers select the relocated glibc dynamic linker for user programs',
      'experimental sideload pack pins the compat28 app cache path at build time',
    ],
  }
  (staging/'home').mkdir();(staging/'tmp').mkdir()
  (staging/'manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
  import tarfile
  with tarfile.open(args.output,'w:gz') as archive:archive.add(staging,arcname='.')
  print('patched',patched,'shebangs',rewritten)
  print(args.output,args.output.stat().st_size)
  print('sha256',hashlib.sha256(args.output.read_bytes()).hexdigest())

if __name__=='__main__':main()
