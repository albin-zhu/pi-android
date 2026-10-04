#!/usr/bin/env python3
"""Install a clearly marked, isolated screen target on an Android emulator."""
import argparse, os, pathlib, subprocess, zipfile
root=pathlib.Path(__file__).resolve().parent.parent
args=argparse.ArgumentParser(description=__doc__)
args.add_argument('--serial',default='emulator-5554')
args.add_argument('--remove',action='store_true')
options=args.parse_args()
if not options.serial.startswith('emulator-'):raise SystemExit('Emulator only')
sdk=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT') or pathlib.Path.home()/'Library/Android/sdk')
java=pathlib.Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin')
env={**os.environ,'JAVA_HOME':str(java.parent)}
adb=[str(sdk/'platform-tools/adb'),'-s',options.serial];package='dev.zfold.fixture.screen'
marker='zfold-screen-context-fixture'
installed=subprocess.run(adb+['shell','pm','path',package],capture_output=True,text=True).stdout.strip()
if installed:
    detail=subprocess.check_output(adb+['shell','dumpsys','package',package],text=True)
    if marker not in detail:raise SystemExit('Existing package is not this fixture; left unchanged')
if options.remove:
    if installed:subprocess.run(adb+['uninstall',package],check=True)
    raise SystemExit(0)
work=root/'.build/screen-context-fixture';work.mkdir(parents=True,exist_ok=True)
manifest=work/'AndroidManifest.xml'
manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="{marker}">
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35"/>
<application android:label="Screen QA Target" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:debuggable="true" android:allowBackup="false">
<activity android:name=".Target" android:exported="true" android:launchMode="singleTask"/></application></manifest>''')
source=work/'Target.java'
source.write_text('''package dev.zfold.fixture.screen;
public final class Target extends android.app.Activity {
  protected void onCreate(android.os.Bundle state){super.onCreate(state);show(getIntent());}
  protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);show(intent);}
  private void show(android.content.Intent intent){String page=intent.getStringExtra("page");boolean secure="secure".equals(page);if(secure)getWindow().addFlags(8192);else getWindow().clearFlags(8192);
    android.widget.TextView screen=new android.widget.TextView(this);screen.setText("EXTERNAL SCREEN QA\\n"+("blue".equals(page)?"PAGE B":"PAGE A"));screen.setTextSize(32);screen.setGravity(17);screen.setTextColor(-1);screen.setBackgroundColor("blue".equals(page)?0xff245dd6:0xffd64545);screen.setContentDescription("External fixture page "+page);setContentView(screen);
  }
}''')
tools=sdk/'build-tools/35.0.0';platform=sdk/'platforms/android-35/android.jar'
def run(*command):subprocess.run(list(map(str,command)),env=env,check=True,stdout=subprocess.DEVNULL)
run(java/'javac','-source','11','-target','11','-classpath',platform,'-d',work/'classes',source)
(work/'dex').mkdir(exist_ok=True)
run(tools/'d8','--min-api','30','--lib',platform,'--output',work/'dex',*sorted((work/'classes').rglob('*.class')))
run(tools/'aapt2','link','-o',work/'unsigned.apk','--manifest',manifest,'-I',platform)
with zipfile.ZipFile(work/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as apk:apk.write(work/'dex/classes.dex','classes.dex')
run(tools/'apksigner','sign','--ks',root/'.build/debug.keystore','--ks-key-alias','zfold','--ks-pass','pass:android','--key-pass','pass:android','--out',work/'fixture.apk',work/'unsigned.apk')
run(*adb,'install','--no-incremental','-r',work/'fixture.apk')
print('Installed emulator screen target: '+package)
