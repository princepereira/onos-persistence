#!/usr/bin/env python
"""
 Copyright 2017-present Open Networking Foundation

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
"""

import re
import os
from zipfile import ZipFile
from tarfile import TarFile, TarInfo
import tarfile
import time
from cStringIO import StringIO
import subprocess


written_files = set()
now = time.time()

def addFile(tar, dest, file, file_size):
    if dest not in written_files:
        info = TarInfo(dest)
        info.size = file_size
        info.mtime = now
        info.mode = 0777
        tar.addfile(info, fileobj=file)
        written_files.add(dest)

def addString(tar, dest, string):
    if dest not in written_files:
        # print dest, string
        info = TarInfo(dest)
        info.size = len(string)
        info.mtime = now
        info.mode = 0777
        file = StringIO(string)
        tar.addfile(info, fileobj=file)
        file.close()
        written_files.add(dest)

def getHash():
    p = subprocess.Popen('git rev-parse --verify HEAD --short', stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)
    (output, err) = p.communicate()
    return output if p.wait() == 0 else '0000000000'

def stageOnos(output, version, files=[]):
    base = 'onos-%s/' % version

    runtimeVersion = version
    if version.endswith('-SNAPSHOT'):
        runtimeVersion = version.replace('-SNAPSHOT', '.%s' % getHash())

    # Note this is not a compressed zip
    with tarfile.open(output, 'w:gz') as output:
        for file in files:
            if '.zip' in file:
                with ZipFile(file, 'r') as zip_part:
                    for f in zip_part.infolist():
                        dest = f.filename
                        if base not in dest:
                            dest = base + 'apache-karaf-3.0.8/system/' + f.filename
                        addFile(output, dest, zip_part.open(f), f.file_size)
            elif '.oar' in file:
                with ZipFile(file, 'r') as oar:
                    app_xml = oar.open('app.xml').read()
                    app_name = re.search('name="([^"]+)"', app_xml).group(1)
                    dest = base + 'apps/%(name)s/%(name)s.oar' % { 'name': app_name}
                    addFile(output, dest, open(file), os.stat(file).st_size)
                    dest = base + 'apps/%s/app.xml' % app_name
                    addString(output, dest, app_xml)
                    for f in oar.infolist():
                        filename = f.filename
                        if 'm2' in filename:
                            dest = base + 'apache-karaf-3.0.8/system/' + filename[3:]
                            if dest not in written_files:
                                addFile(output, dest, oar.open(f), f.file_size)
                                written_files.add(dest)
            elif 'features.xml' in file:
                dest = base + 'apache-karaf-3.0.8/system/org/onosproject/onos-features/%s/' % version
                dest += 'onos-features-%s-features.xml' % version
                with open(file) as f:
                    addFile(output, dest, f, os.stat(file).st_size)
        addString(output, base + 'apps/org.onosproject.drivers/active', '')
        addString(output, base + 'VERSION', runtimeVersion)

if __name__ == '__main__':
    import sys

    if len(sys.argv) < 3:
        print 'USAGE' #FIXME
        sys.exit(1)

    output = sys.argv[1]
    version = sys.argv[2]
    args = sys.argv[3:]

    stageOnos(output, version, args)
