<p align="center"><img src="app/src/main/ic_launcher-web.png" alt="Shark Music icon" width="160"></p>

<h1 align="center">Shark Music</h1>

A music player for Android, based on [Retro Music Player](https://github.com/RetroMusicPlayer/RetroMusicPlayer).

## Download

Get the latest APK from [Releases](https://github.com/crunchbank/SharkMusic/releases). The app ID is
`io.github.crunchbank.sharkmusic`, so it installs alongside Retro Music.

## Changes from Retro Music

- The Playlists tab and home screen no longer wait for genres to load, and genres load in a fraction of a second
  on large libraries.
- The Genres tab splits genres such as "Pop, Folk" into separate genres (Android 11+).
- Safer tag editing: saving can no longer overwrite one song's audio with another's or leave a damaged file, and
  failures are reported.
- An "All tags" viewer (song menu → Details → All tags) and a "Fix genres" button in the tag editor.
- The favourite heart fills completely.
- Now and then a shark fin surfaces in the now-playing progress line (Settings → Now playing → Shark sightings).
- Playlists are backed up automatically as .m3u files in the Music folder, with relative paths.
- Plays are counted properly (including with the screen off), and songs can be sorted by "Most played" in the
  Songs tab and an artist's page.
- The "What's new" sheet no longer opens on launch.
- No "Welcome" greeting or Profile page on the home screen.

## Licence

GNU GPL v3 — see [LICENSE.md](LICENSE.md). Retro Music Player is by Hemanth Savarala and contributors;
modifications are copyright © 2026 crunchbank and listed in [NOTICE.md](NOTICE.md).
