#!/usr/bin/env python
#
# Copyright © 2023 Github Lzhiyong
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# pylint: disable=not-callable, line-too-long, no-else-return

import os
import subprocess
import json
from pathlib import Path

def check(command):
    try:
        output = subprocess.check_output("command -v {}".format(command), shell=True)
        print(output.decode("utf-8"))
    except subprocess.CalledProcessError as e:
        print("please install the {} package".format(command))
        exit()

def main():
    # check the git command
    check('git')
    # source code location
    with open('repos.json', 'r') as file:
        repos = json.load(file)
    # the tree-sitter grammars sources
    dest = Path.cwd() / 'src/main/cpp'
    
    for proj in repos:
        if not dest.joinpath(proj['name']).exists():
            command = 'git clone --depth 1 {} {}'.format(proj['url'], dest / proj['name'])
            subprocess.run(command, shell=True)
            
    
if __name__ == '__main__':
    main()

