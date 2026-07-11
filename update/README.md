# Update metadata

- `data-v1.json` — update manifest for the **client only**; APKs are attached to
  [GitHub Releases](https://github.com/dashchan-pixel/client/releases) of this
  repository (tag = version name).
- `themes.json` — theme repository.
- Extension updates come from separate, combinable sources (default:
  [TrixiEther/Dashchan-Meta](https://github.com/TrixiEther/Dashchan-Meta)),
  configurable in Settings → General → Updates (extensions).
- Changelogs are read from `metadata/` of this repository via the GitHub API.

## Releasing

1. Append the new version to `metadata/versions.json` and add
   `metadata/{en,ru}/changelogs/<code>.txt` (one atomic commit), push.
2. Push a tag named after the version (e.g. `26.7.5-pixel`).
3. The `Release` workflow builds the APK, publishes the GitHub release with the
   English changelog as its body, and commits a refreshed `data-v1.json`
   (via `scripts/update_manifest.py`) hashing the exact published binary.
