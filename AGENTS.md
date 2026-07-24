# AGENTS.md

The **main app** of the Dashchan rework — an Android imageboard browser that dynamically loads separate **extension APKs** (one per imageboard, built from a sibling `extensions` repo).

## Build

- Build with the **Gradle wrapper** directly: `./gradlew assembleRelease` / `assembleDebug` / `installRelease` (adb) / `clean`.
- Signing is handled in `build.gradle.kts`: the `release` config auto-signs from `keystore.properties` (or `KEYSTORE_FILENAME` env), else falls back to the debug key. No wrapper script needed.
- Gradle 9.6, AGP 9.2, Kotlin DSL (`build.gradle.kts` / `settings.gradle.kts`), Java 17. **100% Kotlin** — `compileReleaseJavaWithJavac` compiles nothing (AIDL still generates Java, registered by AGP).
- minSdk / compile / target = **36** (Android 16). Target = **arm64-v8a only**.
- `allWarningsAsErrors` is on — **the release build must stay warning-clean.**

## Source map

- `src/chan/**` — the **extension API surface** (`@Public` / `@Extendable`). The extension APKs link against these signatures. **Never change a `@Public` declaration's nullability** (field type, method param/return). Private fields and internal helpers are fair game.
- `src/com/mishiranu/dashchan/**` — the app itself (`ui`, `content`, `widget`, `media`, `graphics`, `util`).
- `media/VideoPlayer.kt` — Media3 (ExoPlayer) facade; keeps the old partial-file streaming model via a custom `DataSource`. Replaced the retired ffmpeg JNI player + Webm `.so` extension.
- `chan/http/**` — networking on **OkHttp 5**, keeping the old chan HTTP API shape. Kept: manual redirect policy, single-connection throttling, `FirewallResolver` (Cloudflare/anti-DDoS). Removed: custom sockets, WebSocket, GMS hacks.
- `res/**` — **density-qualified folders are intentionally gone**: every bitmap has been redrawn as a `<vector>` in plain `res/drawable/`, so there is nothing to bucket. Add new icons as vectors; don't reintroduce `drawable-*dpi/` PNGs. The launcher lives in `res/mipmap-anydpi/` (no `-v26` — adaptive icons are unconditional at minSdk 36).
- `lang/values*/strings.xml` — user-facing strings. **Every new string must be translated in the same change**, not left for later: add it to `lang/values/` *and* to `values-ru`, `values-it`, `values-pt-rBR`. Keep the alphabetical order of each file, and reuse the wording an existing string already established for the same concept (e.g. ru "Тред", pt-rBR "Fio" for *thread*).

## Git / workflow

- **`rework` is the default branch** (origin `dashchan-pixel/client`, `origin/HEAD → rework`). Push/publish only with explicit user OK.
- To recover pre-conversion Java when a warning/NPE is suspect:
  `git show "$(git log --format=%H --diff-filter=D -1 -- <path>.java)^:<path>.java"`
- **`.gitignore` is an allowlist** (`/*` then `!/…`) — a new top-level file won't be tracked until it's explicitly allowlisted.

## Traps left by the Java→Kotlin conversion

The J2K converter systematically inserted `!!` / non-null casts where Java handled `null` gracefully — turning fallbacks into crashes and making the original null checks show up as "always true/false" warnings. Several shipping NPEs have already been found this way.

- **Never delete a "dead" null check or silence a warning with `!!`** without recovering the original Java and matching its semantics. `!!` *is* correct where the Java dereferenced unconditionally.
- **The `!!` de-noising campaign is ongoing** (~3,079 left by J2K, clustering on a few hundred nullable field *declarations* — fix the declaration and use sites collapse, compiler-proven). `scripts/hot-nullables.sh` ranks declarations by `!!` pressure.
- **The cheap wins are gone** (slices A–R, ~3,079 → ~1,380). The early slices retired dozens of `!!` per declaration; the ranking now tops out around 60 and falls into single digits, spread across ~170 files. What remains is per-site work, and a growing share of it is *correct* — expect later slices to land 20–40, not 200. **Raw `!!` count is no longer a good proxy for remaining work.**
- **Decision rule:** a field null-checked *anywhere* (`if`, `?.`, `?:`) is an optional — keep it nullable, hoist `val x = this.x ?: return`. Only a field used *exclusively* through `!!` is a `lateinit` candidate. **Never turn an unguarded `x!!.foo()` into `x?.foo()`** — that hides a crash as a silent no-op.
- **Useful tell:** when one call site of a method `!!`s an argument and a sibling site doesn't, the outlier is usually the J2K artifact. That's how `ThreadsPage`'s `applyFilter(query!!)` was caught — `ArchivePage` called the same nullable-accepting method clean.
- Gotchas: `CommonUtils.toArray` returns **null for an empty collection**; `ChanConfiguration.getTitle()` is nullable by design.

**Out of scope — do not "fix":**

- `chan/content/model/Post.kt` (56 `!!`, the hottest file in `chan/`) is **done**. It is a two-state class — `builder` XOR `postNumberCompat`, one per constructor — and the Java dereferenced `builder` unguarded in every accessor; only `getPostNumber()` branches. Every `!!` is faithful. It will keep topping the count; leave it. Rewriting those into `?.` would silently change `@Public` ABI behaviour.
- More generally, `chan/` was swept in slice K and its residue is largely load-bearing. New effort belongs in `com/mishiranu/dashchan/`.

## Lint & inspections

- Use **`./gradlew lintRelease`** (report: `build/reports/lint-results-release.{html,xml,txt}`). `assembleRelease` only gates FATAL, so a green build can still hide non-fatal errors.
- Still open at minSdk 36: `ObsoleteSdkInt` dead checks, leftover `@TargetApi`.

## Deliberate design — do NOT fix

- Custom `X509TrustManager` (`chan/http/HttpClient.kt`) + two `onReceivedSslError` handlers (`ui/BrowserFragment.kt`, `content/service/webview/WebViewService.kt`) **proceed despite SSL errors on purpose** — required for the firewall/Cloudflare bypass. Lint flags it as a MITM exposure; that's expected. Leave it.

## Testing

If a single device/emulator is connected, you may use it to build/install/test. **Never delete or uninstall anything from it without asking**, and **do not otherwise manipulate the device** — it may be a real phone in use by a person.
