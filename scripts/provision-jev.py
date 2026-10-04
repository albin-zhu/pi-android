#!/usr/bin/env python3
"""Copy an explicitly supplied Jev key to one debug app's private storage via stdin."""
import argparse, json, os, re, subprocess, sys

def key_from_fish():
    key=os.environ.get('JEV_API_KEY','').strip()
    if not key:
        result=subprocess.run(['fish','-ic','printf "%s" "$JEV_API_KEY"'],capture_output=True,text=True,check=True)
        key=result.stdout.strip()
    if not key or re.search(r'\s',key) or len(key)>4096:
        raise SystemExit('JEV_API_KEY is absent or invalid; no key was written')
    return key

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--test',action='store_true',help='Use only the isolated agent-smoke configuration')
    args=parser.parse_args()
    key=key_from_fish()
    directory='agent-smoke' if args.test else 'agent'
    payload=json.dumps({'enabled':True,'model':'jev-1.13.0','apiKey':key}).encode()
    # The secret is never a shell argument, host file, or stdout value.
    command=f'umask 077; mkdir -p no_backup/{directory}; cat > no_backup/{directory}/jev.json.tmp; chmod 600 no_backup/{directory}/jev.json.tmp; mv no_backup/{directory}/jev.json.tmp no_backup/{directory}/jev.json'
    result=subprocess.run(['adb','-s',args.serial,'shell','run-as','dev.zfold.assistant','sh','-c',"'"+command+"'"],input=payload,capture_output=True)
    if result.returncode:raise SystemExit('Private Jev provisioning failed; ensure the debug APK is installed and ADB is connected')
    print(f'Jev configuration written to private {directory} storage. Restart the app to load it. Key omitted.')

if __name__=='__main__':main()
