# Changelog

All notable changes to Orbit are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
How to cut a release: [RELEASING.md](RELEASING.md).

## [Unreleased]

## [1.0.0] - 2026-10-28

The first public release.

### Added

- **Custom themes:** photo themes that live on the TV instead of inside the app, for photos that shouldn't ship with Orbit (movie stills, your own pictures). `tools/custom_theme.sh <folder>` builds one and installs it; `--list` and `--remove <id>` manage them. They appear in Settings → Theme and in "Shuffle themes". See "Custom themes" in the README.
- **Snapshot of any photo, and of idle mode:** the adb snapshot hook takes `--es photo <theme>/<id>` and `--ez idleMode true`, for checking photos against the clock on the real TV and for screenshots.
- README with screenshots, a "what you need" table (Orbit alone / with a home server / with Sports Remote), what Orbit can't do, every setting, and a privacy section.
- **Amazon Music and Alexa's player** in the now-playing card, alongside Spotify.

### Changed

- "[Explicit]" and "(Clean)" tags are hidden from song and album names in the now-playing pill and card, and stripped before looking up lyrics, so tagged songs (Amazon Music) now find their lyrics.
- ◀◀ restarts the song when the music app can't skip back (Alexa's player): it seeks to 0:00 if allowed, or else sends the app the remote's Previous button.
- `versionName` now follows the release version (`1.0.0`, `versionCode` 10000), and Settings → About shows it.
- `deploy.sh`, `setup-tv.sh` and `tools/custom_theme.sh` take the TV's address as an argument, `TV_IP`, or `TV_IP=` in a gitignored `.env`; there's no built-in address any more.
- `setup-tv.sh` works for any TV: it installs Orbit and grants its permissions. De-bloating Amazon's shopping, ad and demo apps is opt-in (`--debloat`), and a fallback launcher is optional (`FALLBACK_APK`).

### Removed

- The one-time migrations that set up the original household's TV (its server address, city and NFL team) on upgrade.
- Unused third-party app logos (`assets/banners/`). App tiles use the banners the apps themselves install on the TV.

### Included from before 1.0.0

- Liquid-glass clock and glass weather; wallpaper themes (Orbit, National Parks, Ocean) that follow the time of day.
- Glass app shelf with Move / Hide, and HDMI input tiles.
- Game line with live scores from Sports Remote; NFL score alerts over any app.
- Idle photo-frame mode with the playing song, album art and synced lyrics (LRCLIB).
- First-run setup screens and a standalone mode that needs no home server.

[Unreleased]: https://github.com/jwizenfeld04/Orbit-Home/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/jwizenfeld04/Orbit-Home/releases/tag/v1.0.0
