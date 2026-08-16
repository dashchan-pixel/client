# Dashchan [redacted]

> [!CAUTION]
> After eight releases, public distribution is now suspended. Future versions
> and updates are for subscribers at [patreon.com/pikun](https://patreon.com/pikun),
> starting with the cheapest tier. I may give away free access from time to time, so
> keep an eye out.

A modern Android imageboard client built for fast, comfortable one-handed browsing
of large and media-heavy threads. Imageboards stay modular as separately installed
extensions, while user-written JavaScript Commands let you tailor posting, threads
and forum connection workflows to how you browse.

> [!IMPORTANT]
> **Android 16 or newer is required.** Devices on older Android versions are not
> supported and cannot install the app.

A fork of [TrixiEther/DashchanFork](https://github.com/TrixiEther/DashchanFork),
itself a fork of [Mishiranu/Dashchan](https://github.com/Mishiranu/Dashchan).

## Features

### Browsing and threads

* Supports many imageboards through separately installable extensions
* Thumb-friendly floating actions and search on boards, threads and results;
  reorder the action bar with a long press or turn it vertical with a two-finger tap
* Thread watcher with reply notifications, per-board refresh intervals and support
  for following bump-limited threads into their continuations
* Echo collects replies to your posts in a dedicated inbox with unread highlighting
  and a drawer badge
* Popular view ranks the posts that attracted the most replies, while gallery
  controls filter and sort a thread's attachments
* Search across every setting from one place, then jump straight to the matching row
* Automatic post filtering with regular expressions, configurable swipe actions,
  HTML thread archiving and clear highlighting of your posts and replies
* The open board, thread and back stack survive swipe-away, reboot and low-memory
  process death; saved drafts have a browsable home of their own

### Media

* Built-in image gallery and Media3 video/audio player — no separate WebM or video
  extension required
* Flow turns a thread's videos into a vertical feed with swipe navigation,
  pre-buffering, automatic advance and the same controls as the gallery
* Picture-in-picture playback follows a Flow playlist and expands back into the
  gallery or feed at the same video, position and playback speed
* Reels-style controls provide mute, configurable speeds up to 8x, quick seeking and
  picture-in-picture; playback can fall back to software decoding for difficult files
* Filter attachments by images, GIFs or videos and sort them by post order, name,
  size, resolution or type
* Smooth frame-rate-matched playback, optional multi-threaded video playback and
  reverse image search with Google Lens or Yandex

### Posting

* The posting form opens as a draggable sheet over the thread, so the post being
  answered stays in view; a classic full-screen form remains available
* Solved captchas are reused briefly, reCAPTCHA 3 and invisible reCAPTCHA can run in
  the background, and known bans are checked before a captcha is spent
* Attachments can be renamed and reordered by dragging, with per-file settings
  remembered between posts
* Voting on boards that support it, a captcha timer with automatic reload and
  persistent drafts for text shared from other apps

### Commands

* Commands are small async JavaScript programs that turn repetitive imageboard work
  into actions inside the app. Scope them to every forum, selected forums or one
  board, then run them by hand from the ⌘ menu
* Draft commands receive the posting form's comment and attachments and may return
  replacements. Use them for one-tap transformations or run them automatically as a
  final pass before Send
* Thread commands receive post metadata, raw comment HTML and attached files for a
  whole thread or one post at a time. They may temporarily replace comments and files
  on screen — enough to translate, decrypt or reshape a thread without changing the
  remote posts or their cached originals. Run them by hand, on one post, or
  automatically when a thread opens
* App commands receive no draft or posts. They show their return value as a message
  and can act only through permissions granted to that command: app settings, forum
  cookies, and the forum's visible IP and proxy. They are not native Android plugins:
  beyond network requests, those narrow bridges are their only way into the app. One
  may be designated to change the visible IP when a forum bans the current one
* Scripts can ask the user questions, keep JSON values between runs, read an optional
  shared environment, make unrestricted cross-origin network requests with `fetch`,
  use WebCrypto, and load shared or downloaded libraries. Each run uses a fresh
  engine and ends when it returns, is cancelled or times out; a command is not a
  background service

### Personalization and privacy

* A live theme editor exposes every colour on an HSV picker and previews a real
  thread; the included theme set ranges from Claude and Dracula to Nord, Sepia,
  Solarized and Yotsuba
* Optional OS-driven day/night themes and one corner-radius control shared by
  dialogs, menus, cards, toasts and highlights
* Per-forum connection controls include authenticated proxies, a sending-only mode,
  custom User-Agents and a visible-address check that confirms the IP and location
  the forum actually sees
* External links can open in ephemeral Custom Tabs that retain no history or cookies
* Predictive back navigation, live download/posting progress and refresh-rate-aware
  scrolling make the app feel native on current Android devices

Version history with changelogs lives in [metadata/versions.json](metadata/versions.json)
and [metadata/en/changelogs](metadata/en/changelogs).

## Screenshots

<p>
<img src="metadata/en/images/phoneScreenshots/1.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/2.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/3.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/4.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/5.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/6.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/7.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/8.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/9.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/10.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/11.png" width="20%" />
<img src="metadata/en/images/phoneScreenshots/12.png" width="20%" />
</p>

## Building Guide

1. Install JDK 17 or higher
2. Install Android SDK, define `ANDROID_HOME` environment variable or set `sdk.dir` in `local.properties`
3. Run `./gradlew assembleRelease`

The resulting APK file will appear as `build/outputs/apk/release/dashchan-redacted-release.apk`.

### Build Signed Binary

You can create `keystore.properties` in the source code directory with the following properties:

```properties
store.file=%PATH_TO_KEYSTORE_FILE%
store.password=%KEYSTORE_PASSWORD%
key.alias=%KEY_ALIAS%
key.password=%KEY_PASSWORD%
```

Without it, release builds are signed with the debug keystore.

### Building Extensions

The source code of extensions is available in the
[dashchan-redacted/extensions](https://github.com/dashchan-redacted/extensions) repository;
the original is
[Mishiranu/Dashchan-Extensions](https://github.com/Mishiranu/Dashchan-Extensions).

## License

Dashchan [redacted] is available under the [GNU General Public License, version 3 or later](COPYING).
