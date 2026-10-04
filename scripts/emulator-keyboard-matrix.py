#!/usr/bin/env python3
"""Verify keyboard geometry at both orientations/font scales on an explicit emulator."""
import argparse, pathlib, subprocess, os
root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--serial',default='emulator-5554');args=parser.parse_args()
if not args.serial.startswith('emulator-'):raise SystemExit('Emulator only')
sdk=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT') or pathlib.Path.home()/'Library/Android/sdk')
adb=[str(sdk/'platform-tools/adb'),'-s',args.serial]
output=root/'artifacts/interaction-redesign';output.mkdir(parents=True,exist_ok=True)
def read(*command):return subprocess.check_output(adb+list(command),text=True).strip()
def setting(group,name,value):
    command=['shell','settings','delete',group,name] if value=='null' else ['shell','settings','put',group,name,value]
    subprocess.run(adb+command,check=True,stdout=subprocess.DEVNULL)
saved={(group,name):read('shell','settings','get',group,name) for group,name in [('system','font_scale'),('system','accelerometer_rotation'),('system','user_rotation'),('secure','show_ime_with_hard_keyboard')]}
try:
    setting('system','accelerometer_rotation','0');setting('secure','show_ime_with_hard_keyboard','1')
    for orientation,rotation in [('portrait','0'),('landscape','1')]:
        for scale in ['1.0','1.3']:
            name=f'keyboard-{orientation}-{scale}';print(name,flush=True)
            setting('system','user_rotation',rotation);setting('system','font_scale',scale)
            read('shell','am','start','-n','dev.zfold.fixture.screen/.Target','--es','page','blue')
            result=read('shell','am','instrument','-w','-e','orientation',orientation,'dev.zfold.assistant/.KeyboardInstrumentation')
            (output/f'{name}.txt').write_text(result+'\n')
            with (output/f'{name}.png').open('wb') as image:subprocess.run(adb+['exec-out','run-as','dev.zfold.assistant','cat','cache/keyboard-geometry.png'],stdout=image,check=True)
            if 'INSTRUMENTATION_CODE: -1' not in result:raise SystemExit(f'Failed: {name}; inspect its result and screenshot')
    print('All four keyboard layouts passed',flush=True)
finally:
    for (group,name),value in saved.items():setting(group,name,value)
