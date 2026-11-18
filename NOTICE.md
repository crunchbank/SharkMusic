# Notice

Shark Music is a modified version of
[Retro Music Player](https://github.com/RetroMusicPlayer/RetroMusicPlayer),
copyright © Hemanth Savarala and contributors.

Modifications are copyright © 2026 crunchbank and, like the original, are licensed under the
GNU General Public License, version 3 or (at your option) any later version. See [LICENSE.md](LICENSE.md).
This program comes with ABSOLUTELY NO WARRANTY.

Source code: https://github.com/crunchbank/SharkMusic

## Modifications

The git history records every change, with its author and date. In summary, as of 2026-11-18,
based on upstream `dev` at 0be018679 (2026-11-12):

| File | Change |
|---|---|
| `service/MusicService.kt` | The playback service can return to the foreground after `quit()`, so Android doesn't mute playback when the screen turns off. |
| `fragments/LibraryViewModel.kt` | Library sections (playlists, genres, home, suggestions) are each posted as soon as they load. |
| `repository/GenreRepository.kt` | Genre song counts come from a single query on Android 11+. |
| `fragments/home/HomeFragment.kt`, `fragments/library/LibraryFragment.kt` | Toolbar title "Shark Music". |
| `app/build.gradle.kts` | App ID `io.github.crunchbank.sharkmusic`. |
| `res/values/donottranslate.xml`, `res/values/strings.xml` | App name "Shark Music". |
| `res/drawable/ic_launcher_*.xml`, `res/mipmap-*/ic_launcher*`, `ic_launcher-web.png` | New launcher icon (shark fin on a vinyl record), copyright © 2026 crunchbank. |
| `assets/license.html` | Shark Music copyright, licence, warranty and source notice on the in-app licences screen. |
| `README.md`, `NOTICE.md`, `.gitignore` | Fork notice, this file, and ignored signing files. |

"Retro Music" is the name of the original project. Shark Music is not affiliated with or endorsed by it.
