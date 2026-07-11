# Update metadata

- `data-v1.json` / `data.json` — update manifests for the **client only**; APKs are
  attached to [GitHub Releases](https://github.com/dashchan-pixel/client/releases)
  of this repository (tag = version name). Regenerate the hashes with
  `Dashchan-Meta/generate.py` against the release APK when publishing.
- `themes.json` — theme repository.
- Extension updates come from a separate source (default:
  [TrixiEther/Dashchan-Meta](https://github.com/TrixiEther/Dashchan-Meta)),
  configurable in Settings → General → Updates (extensions).
- Changelogs are read from `metadata/` of this repository via the GitHub API.
