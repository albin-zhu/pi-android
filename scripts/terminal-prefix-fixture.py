#!/usr/bin/env python3
"""Create a protected foreign app directory for emulator-only Bash regression tests."""
import argparse, pathlib, subprocess, os

root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',default='emulator-5554')
parser.add_argument('--remove',action='store_true')
options=parser.parse_args()
if not options.serial.startswith('emulator-'):
    raise SystemExit('This fixture is restricted to an emulator')
adb=['adb','-s',options.serial]
installed=subprocess.run(adb+['shell','pm','path','com.termux'],capture_output=True,text=True).stdout.strip()
marker='zfold-private-prefix-fixture'
if installed:
    detail=subprocess.check_output(adb+['shell','dumpsys','package','com.termux'],text=True)
    owned=marker in detail
    if options.remove:
        if not owned:raise SystemExit('Existing package is not this fixture; no changes made')
        subprocess.run(adb+['uninstall','com.termux'],check=True)
    elif not owned:print('Existing foreign package left unchanged; it can exercise the same isolation boundary.')
    else:print('Protected-prefix fixture already installed')
    raise SystemExit(0)
if options.remove:raise SystemExit(0)
sdk=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT') or pathlib.Path.home()/'Library/Android/sdk')
tools=sdk/'build-tools/35.0.0'
work=root/'.build/terminal-prefix-fixture';work.mkdir(parents=True,exist_ok=True)
manifest=work/'AndroidManifest.xml'
manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.termux" android:versionCode="1" android:versionName="{marker}">
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35"/>
<application android:label="Protected prefix test fixture" android:hasCode="false" android:debuggable="true" android:allowBackup="false"/>
</manifest>''')
subprocess.run([str(tools/'aapt2'),'link','-o',str(work/'unsigned.apk'),'--manifest',str(manifest),'-I',str(sdk/'platforms/android-35/android.jar')],check=True)
subprocess.run([str(tools/'apksigner'),'sign','--ks',str(root/'.build/debug.keystore'),'--ks-key-alias','zfold','--ks-pass','pass:android','--key-pass','pass:android','--out',str(work/'fixture.apk'),str(work/'unsigned.apk')],check=True)
subprocess.run(adb+['install','--no-incremental',str(work/'fixture.apk')],check=True)
subprocess.run(adb+['shell','run-as','com.termux','mkdir','-p','files/usr/etc'],check=True)
