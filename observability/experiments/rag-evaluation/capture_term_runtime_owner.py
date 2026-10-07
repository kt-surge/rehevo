"""Check the listener's Java classpath through Gradle's manifest classpath jar."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import unquote
import zipfile
import psutil

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def main(pid):
    process = psutil.Process(pid)
    arguments = process.cmdline()
    if 'interview.guide.App' not in arguments or '--server.port=18080' not in arguments:
        raise ValueError('Different application listener')
    classpath = Path(arguments[arguments.index('-cp')+1])
    with zipfile.ZipFile(classpath) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8')
    unfolded = manifest.replace('\r\n ', '').replace('\n ', '')
    line = next(x for x in unfolded.splitlines() if x.startswith('Class-Path: '))
    entries = [unquote(x).replace('\\','/') for x in line[len('Class-Path: '):].split()]
    root = ROOT.as_posix().lower()
    matches = [x for x in entries if root in x.lower()]
    if not matches:
        raise ValueError('Manifest has no workspace classpath')
    value = dict(processId=pid,executable=process.exe(),appMain=True,port=18080,
        rawCommandWorkspaceLiteralMatched=any(root in x.replace('\\','/').lower() for x in arguments),
        classpathManifestWorkspaceMatched=True,workspaceEntries=matches,
        classpathJarSha256=hashlib.sha256(classpath.read_bytes()).hexdigest(),
        springAi201Present=any('spring-ai-client-chat/2.0.1/' in x for x in entries),
        note='Gradle uses a manifest classpath jar; no stop or restart performed.')
    output=HERE/'runs/term-actual-context-20261005-r1/runtime-owner.json'
    with output.open('x',encoding='utf-8') as stream:
        json.dump(value,stream,ensure_ascii=False,indent=2)
    print(json.dumps({k:value[k] for k in ['processId','classpathManifestWorkspaceMatched','springAi201Present']}))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--pid',type=int,required=True)
    main(parser.parse_args().pid)
