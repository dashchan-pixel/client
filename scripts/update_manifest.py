#!/usr/bin/env python3
"""Refresh the client package in update/data-v1.json for a release APK.

Usage: update_manifest.py <apk> <manifest> <tag>

Writes the APK's length/sha256 and the newest metadata/versions.json entry
(whose name must equal <tag>) into the manifest. The release workflow runs
this against the CI-built APK, so the published manifest always hashes the
exact binary attached to the GitHub release.
"""
import hashlib
import json
import os
import sys


def main():
	apk_path, manifest_path, tag = sys.argv[1:4]
	root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
	with open(os.path.join(root, 'metadata', 'versions.json')) as f:
		versions = json.load(f)['versions']
	latest = max(versions, key=lambda v: v['code'])
	if latest['name'] != tag:
		raise SystemExit(f"tag {tag} does not match newest version {latest['name']}")

	with open(apk_path, 'rb') as f:
		data = f.read()
	sha = hashlib.sha256(data).hexdigest().upper()

	with open(manifest_path) as f:
		manifest = json.load(f)
	app = next(a for a in manifest['applications'] if a['name'] == 'client')
	package = app['packages'][0]
	package['version_name'] = latest['name']
	package['version_code'] = latest['code']
	package['length'] = len(data)
	package['sha256sum'] = ':'.join(sha[i:i + 2] for i in range(0, len(sha), 2))
	package['source'] = ('//github.com/dashchan-pixel/client/releases/download/'
			f"{tag}/dashchan-pixel-{latest['code']}.apk")
	with open(manifest_path, 'w') as f:
		json.dump(manifest, f, indent='\t', ensure_ascii=False)
		f.write('\n')


if __name__ == '__main__':
	main()
