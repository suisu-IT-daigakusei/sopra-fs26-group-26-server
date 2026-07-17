#!/usr/bin/env python3
'''Prune only stale build cache and unused images; never touch Docker volumes.'''

import subprocess

subprocess.run(
    ['docker', 'builder', 'prune', '--force', '--filter', 'until=168h'],
    check=True,
)
subprocess.run(
    ['docker', 'image', 'prune', '--all', '--force', '--filter', 'until=720h'],
    check=True,
)
