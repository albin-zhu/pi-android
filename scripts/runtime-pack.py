#!/usr/bin/env python3
"""Build a reproducible Pi Runtime Pack from pinned Android/bionic packages."""
import argparse, dataclasses, hashlib, io, json, os, pathlib, re, shutil, stat, sys, tarfile, tempfile, urllib.request

TERMUX_INDEX='https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages'
TERMUX_POOL='https://packages.termux.dev/apt/termux-main/'
TERMUX_PREFIX='/data/data/com.termux/files/usr'
RUNTIME_PREFIX='@@PI_RUNTIME_USR@@'

@dataclasses.dataclass
class Package:
  name:str; version:str; filename:str; depends:list; sha256:str; size:int

def parse_index(text):
  packages={}
  for record in text.split('\n\n'):
    values={}
    for line in record.splitlines():
      if ': ' in line:
        key,value=line.split(': ',1);values.setdefault(key,value)
    if not values.get('Package') or not values.get('Filename'):continue
    name=values['Package']
    depends=[]
    for item in values.get('Depends','').split(','):
      match=re.match(r'^([A-Za-z0-9][A-Za-z0-9.+-]*)',item.strip())
      if match:depends.append(match.group(1))
    packages[name]=Package(name,values.get('Version',''),values['Filename'],depends,values.get('SHA256',''),int(values.get('Size','0')))
  return packages

def resolve(packages,roots):
  selected=[];seen=set()
  def visit(name):
    if name in seen:return
    seen.add(name)
    if name not in packages:raise RuntimeError(f'Package not found: {name}')
    for dependency in packages[name].depends:visit(dependency)
    selected.append(packages[name])
  for name in roots:visit(name)
  return selected

def fetch(url,target=None):
  request=urllib.request.Request(url,headers={'User-Agent':'pi-runtime-pack/1'})
  with urllib.request.urlopen(request) as response:
    data=response.read()
  if target:target.write_bytes(data)
  return data

def deb_data_tar(data):
  if data[:8]!=b'!<arch>\n':raise RuntimeError('Not a Debian ar archive')
  offset=8
  while offset+60<=len(data):
    header=data[offset:offset+60];offset+=60
    name=header[:16].decode('ascii').strip().rstrip('/')
    size=int(header[48:58].decode('ascii').strip())
    if name=='data.tar.xz':return data[offset:offset+size]
    offset+=size+(size%2)
  raise RuntimeError('Debian package has no data.tar.xz')

def safe_extract(archive:tarfile.TarFile,target:pathlib.Path):
  target.mkdir(parents=True,exist_ok=True)
  for member in archive.getmembers():
    destination=target/member.name
    if member.isdir():
      destination.mkdir(parents=True,exist_ok=True);member_mode=member.mode;os.chmod(destination,member_mode)
    elif member.issym():
      destination.parent.mkdir(parents=True,exist_ok=True);link=member.linkname
      if link.startswith('/'):
        link='/'+link.lstrip('/')
        if not link.startswith(TERMUX_PREFIX+'/'):raise RuntimeError(f'Unsupported absolute symlink: {member.name} -> {link}')
        link=TERMUX_PREFIX+link[len(TERMUX_PREFIX):]
        if destination.is_symlink() or destination.exists():destination.unlink()
        os.symlink(link,destination);continue
      else:
        resolved=(destination.parent/os.path.normpath(link)).resolve()
        if not resolved.is_relative_to(target.resolve()):raise RuntimeError(f'Escaping symlink: {member.name} -> {member.linkname}')
        link=os.path.normpath(link)
      if destination.is_symlink() or destination.exists():destination.unlink()
      os.symlink(link,destination)
    elif member.islnk():
      destination.parent.mkdir(parents=True,exist_ok=True)
      if not member.linkname.startswith(TERMUX_PREFIX+'/'):raise RuntimeError(f'Unsupported hard link: {member.name} -> {member.linkname}')
      source=target/member.linkname.lstrip('/')
      if not source.is_file():raise RuntimeError(f'Hard link target missing: {member.linkname}')
      if destination.exists():destination.unlink()
      os.link(source,destination)
    elif member.isfile():
      if destination.is_symlink() or destination.exists():destination.unlink()
      destination.parent.mkdir(parents=True,exist_ok=True);source=archive.extractfile(member)
      if source is None:raise RuntimeError(f'Cannot read package file: {member.name}')
      with source,open(destination,'wb') as output:shutil.copyfileobj(source,output,1024*1024)
      os.chmod(destination,member.mode)
    else:raise RuntimeError(f'Unsupported package member type: {member.name}')

def install_package(package:Package,payload:bytes,staging:pathlib.Path,cache:pathlib.Path):
  cache.mkdir(parents=True,exist_ok=True)
  artifact=cache/pathlib.Path(package.filename).name
  if not artifact.exists():artifact.write_bytes(payload)
  actual=hashlib.sha256(artifact.read_bytes()).hexdigest()
  if package.sha256 and actual!=package.sha256:
    raise RuntimeError(f'Checksum mismatch for {package.name}: expected {package.sha256}, got {actual}')
  with tarfile.open(fileobj=io.BytesIO(deb_data_tar(payload)),mode='r:xz') as archive:
    with tempfile.TemporaryDirectory(prefix=f'pi-pack-{package.name}-') as temporary:
      root=pathlib.Path(temporary);safe_extract(archive,root)
      source=root/'data/data/com.termux/files/usr'
      if not source.is_dir():raise RuntimeError(f'{package.name} has no Termux usr tree')
      shutil.copytree(source,staging/'usr',dirs_exist_ok=True,symlinks=True)
      normalize_symlinks(staging/'usr')

def rewrite_text_file(path:pathlib.Path):
  try:
    if path.stat().st_size>2_000_000 or path.is_symlink():return False
    value=path.read_bytes()
    if not value.startswith(b'#!') or TERMUX_PREFIX.encode() not in value:return False
    path.write_text(value.decode('utf-8').replace(TERMUX_PREFIX,RUNTIME_PREFIX),newline='')
    path.chmod(path.stat().st_mode|stat.S_IXUSR|stat.S_IXGRP|stat.S_IXOTH)
    return True
  except (UnicodeDecodeError,OSError):return False

def normalize_symlinks(root:pathlib.Path):
  for path in root.rglob('*'):
    if not path.is_symlink():continue
    link=os.readlink(path)
    if not os.path.isabs(link):continue
    link='/'+link.lstrip('/')
    if not link.startswith(TERMUX_PREFIX+'/'):continue
    inside=link[len(TERMUX_PREFIX):].lstrip('/')
    target=root/inside
    relative=os.path.relpath(target,path.parent)
    path.unlink();os.symlink(relative,path)

def build(name,requested,index_url,output,cache):
  packages=resolve(parse_index(fetch(index_url).decode('utf-8')),requested)
  with tempfile.TemporaryDirectory(prefix='pi-runtime-pack-') as temporary:
    staging=pathlib.Path(temporary)/'pack';staging.mkdir()
    for package in packages:
      print(f'install {package.name} {package.version}')
      install_package(package,fetch(TERMUX_POOL+package.filename),staging,cache)
    if name=='build-toolchain':
      binDir=staging/'usr'/'bin'
      for source,alias in [('clang','gcc'),('clang++','g++'),('clang','cc'),('clang++','c++')]:
        target=binDir/alias
        if not target.exists() and (binDir/source).exists():os.symlink(source,target)
    rewritten=0
    for path in (staging/'usr').rglob('*'):
      if path.is_file() and rewrite_text_file(path):rewritten+=1
    binaries=sorted(path.name for path in (staging/'usr'/'bin').iterdir() if path.is_file() and not path.is_symlink())
    environment={
      'LD_LIBRARY_PATH':'{cache}/usr/lib','PATH':'{cache}/usr/bin:/system/bin:/system/xbin:/vendor/bin',
      'HOME':'{cache}/home','TMPDIR':'{cache}/tmp','GIT_EXEC_PATH':'{cache}/usr/libexec/git-core',
      'GIT_TEMPLATE_DIR':'{cache}/usr/share/git-core/templates','GIT_CONFIG_NOSYSTEM':'1',
    }
    if name=='go-toolchain':environment.update({'GOROOT':'{cache}/usr/lib/go','GOPATH':'{cache}/home/go','GOCACHE':'{cache}/home/go/cache','GOTMPDIR':'{cache}/tmp'})
    if name=='build-toolchain':environment.update({'CC':'{cache}/usr/bin/clang','CXX':'{cache}/usr/bin/clang++','CPPFLAGS':'-I{cache}/usr/include','LDFLAGS':'-L{cache}/usr/lib','CMAKE_PREFIX_PATH':'{cache}/usr'})
    manifest={
      'schema':1,'name':name,'architecture':'arm64','libc':'bionic','source':'termux-main',
      'packages':[{'name':item.name,'version':item.version,'sha256':item.sha256,'filename':pathlib.Path(item.filename).name} for item in packages],
      'rewrittenShebangs':rewritten,'binaries':binaries,
      'environment':environment,
      'limitations':['ELF RUNPATH may still point at Termux; Pi exec broker supplies LD_LIBRARY_PATH first','scripts are rewritten to the runtime placeholder and require install-time prefix substitution'],
    }
    (staging/'home').mkdir();(staging/'tmp').mkdir()
    (staging/'manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
    output.parent.mkdir(parents=True,exist_ok=True)
    with tarfile.open(output,'w:gz') as archive:archive.add(staging,arcname='.')
    digest=hashlib.sha256(output.read_bytes()).hexdigest()
    print(f'pack {output} ({output.stat().st_size/1024/1024:.1f} MiB, {len(packages)} packages, {rewritten} shebangs)')
    print(f'sha256 {digest}')

def main():
  parser=argparse.ArgumentParser()
  parser.add_argument('--name',required=True)
  parser.add_argument('--output',type=pathlib.Path,required=True)
  parser.add_argument('--index',default=TERMUX_INDEX)
  parser.add_argument('--cache',type=pathlib.Path,default=pathlib.Path('.build/runtime-pack-cache'))
  parser.add_argument('packages',nargs='+')
  args=parser.parse_args()
  build(args.name,args.packages,args.index,args.output,args.cache)

if __name__=='__main__':main()
