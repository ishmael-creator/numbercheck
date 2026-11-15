#!/usr/bin/env python3
"""Copies today's list + setup into the Android app so it works straight after install."""
import json, os, shutil

root = os.path.join(os.path.dirname(__file__), '..')
data = os.path.join(root, 'web', 'data')
assets = os.path.join(root, 'android', 'app', 'src', 'main', 'assets')
os.makedirs(assets, exist_ok=True)

manifest = json.load(open(os.path.join(data, 'manifest.json')))
shutil.copy(os.path.join(data, 'manifest.json'), os.path.join(assets, 'manifest.json'))
shutil.copy(os.path.join(data, manifest['full']), os.path.join(assets, 'list.bin'))
json.dump({'s': os.environ['NUMBERCHECK_SALT'].strip(), 'u': os.environ['SITE_URL'].rstrip('/')},
          open(os.path.join(assets, 'config.json'), 'w'))
print(f"Bundled {manifest['total']} numbers (version {manifest['version']}) into the app")
