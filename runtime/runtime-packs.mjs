export const runtimePackCatalog={
  schema:1,architecture:'arm64-v8a',libc:'bionic',sourceRepository:'termux-main',
  executionChannel:{modern:{writableExec:false},compat28:{writableExec:true,broker:'AndroidTools runtime_exec',cache:'files/runtime/runtime-exec-test'}},
  packs:[
    {
      id:'git-base',status:'built',priority:1,label:'Git',
      packages:['git'],provides:['git'],verified:'emulator Android 16 / targetSdk 28 / git 2.56.0',
      build:'python3 scripts/runtime-pack.py --name git-base --output artifacts/runtime-packs/git-base.tar.gz git',
    },
    {
      id:'gnu-userland',status:'built',priority:2,label:'GNU userland',
      packages:['coreutils','grep','sed','gawk','tar','gzip','xz-utils','findutils','diffutils','patch','less'],
      provides:['ls','cp','mv','rm','sort','awk','grep','sed','tar','gzip','xz','find','diff','patch'],
    },
    {
      id:'build-toolchain',status:'built',priority:3,label:'C/C++ build toolchain',
      packages:['make','binutils','clang','lld','llvm','libcompiler-rt','ndk-sysroot','libc++','pkg-config','cmake','ninja','python'],
      provides:['make','ar','ld','clang','clang++','c++','gcc-compatible','g++-compatible','cmake','ninja','pkg-config','python','python3'],
      policy:'gcc/g++ are user-facing aliases to Android/bionic Clang 21; GNU GCC itself is unavailable in current Termux main. Use Clang for bionic-correct binaries.',
    },
    {
      id:'go-toolchain',status:'built',priority:4,label:'Go toolchain',
      packages:['golang'],provides:['go','gofmt'],
      notes:'Termux Go is Android/bionic aware and uses the same runtime prefix as the other packs.',
    },
  ],
};
