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
| `activities/tageditor/TagWriter.kt`, `activities/tageditor/AbsTagEditorActivity.kt`, `fragments/lyrics/LyricsFragment.kt`, `res/values/strings.xml` | Tag editor saves safely on Android 11+: one working copy per song (songs sharing a file name no longer overwrite each other), each edited copy verified before it replaces the original, the original truncated before writing, and failures reported instead of ignored. |
| `dialogs/AllTagsDialog.kt`, `util/TagDump.kt` (new, copyright © 2026 crunchbank), `dialogs/SongDetailDialog.kt`, `res/values/strings.xml` | "All tags" viewer, opened from a song's Details: the audio stream, every standard tag field with all of its values, other fields in the file, and embedded artwork. Read-only. |
| `repository/GenreRepository.kt`, `repository/SongRepository.kt`, `MainModule.kt` | On Android 11+ the Genres tab splits genre strings such as "Pop, Folk" (also `;` and `\|`) into separate genres, merging names that differ only in case. |
| `activities/tageditor/TagWriter.kt`, `AbsTagEditorActivity.kt` | The genre box shows every genre field in the file, joined with commas, and saving replaces all of them with one field. |
| `activities/tageditor/AbsTagEditorActivity.kt`, `SongTagEditorActivity.kt`, `AlbumTagEditorActivity.kt`, `res/values/strings.xml`, `res/drawable/ic_auto_fix.xml` | "Fix genres" button on the genre box: pick which genres to keep, written back as a tidy comma-separated list. The icon is Material Icons "auto_fix_high" (Apache License 2.0, Google). |
| `res/drawable/avd_favorite.xml` | The favourite animation always ends on a completely filled heart (it could stop half full). |
| `app/build.gradle.kts` | App ID `io.github.crunchbank.sharkmusic`. |
| `res/values/donottranslate.xml`, `res/values/strings.xml` | App name "Shark Music". |
| `res/drawable/ic_launcher_*.xml`, `res/mipmap-*/ic_launcher*`, `ic_launcher-web.png`, `fastlane/metadata/android/en-US/images/icon.png` | New launcher icon (shark fin on a vinyl record), copyright © 2026 crunchbank. |
| `assets/license.html` | Shark Music copyright, licence, warranty and source notice on the in-app licences screen. |
| `util/PlayCounts.kt` (new, copyright © 2026 crunchbank), `db/PlayCountDao.kt`, `service/MusicService.kt`, `activities/base/AbsMusicServiceActivity.kt` | Plays are counted by the music service once at least half a song has been heard, including with the screen off; the screen-based count that counted every track change is removed. "Most played" uses these counts. |
| `helper/StopWatch.kt`, `service/SongPlayCountHelper.kt` | The listening timer restarts on each track change and no longer adds the time since 1970 when paused while stopped. |
| `helper/SortOrder.kt`, `repository/SongRepository.kt`, `repository/GenreRepository.kt`, `fragments/songs/SongsFragment.kt`, `fragments/artists/AbsArtistDetailsFragment.kt`, `model/Artist.kt`, `res/menu/menu_artist_song_sort_order.xml`, `res/values/ids.xml`, `res/values/strings.xml` | "Most played" sort order for the Songs tab and an artist's songs. |
| `activities/MainActivity.kt` | The "What's new" sheet (Retro Music's changelog) no longer opens on launch. |
| `fragments/backup/RestoreActivity.kt`, `BackupViewModel.kt`, `helper/BackupHelper.kt`, `res/layout/activity_restore.xml`, `res/values/strings.xml` | Restoring a backup shows a progress bar and "Restoring…", then whether it worked, found nothing to restore, or failed; when settings are restored it says the app will restart before restarting it. Restore is disabled when nothing is ticked. |
| `util/PlaylistAutoBackup.kt` (new, copyright © 2026 crunchbank), `db/PlaylistDao.kt`, `App.kt`, `res/xml/pref_advanced.xml`, `res/values/strings.xml` | Playlists are kept as .m3u files in the Music folder, with song paths relative to it, rewritten whenever a playlist changes (Settings → Other → Playlists). Only files the app wrote are overwritten or deleted. |
| `assets/retro-changelog.html` | Removed the "Looking for maintainers/contributors" notice. |
| `fragments/home/HomeFragment.kt`, `HomeBinding.kt`, `res/layout*/fragment_home.xml`, `res/navigation/main_graph.xml`, `res/xml/pref_ui.xml`, `util/PreferenceUtil.kt`, `glide/RetroGlideExtension.kt` | Removed the home screen greeting ("Welcome, …" with an avatar, or the optional banner), its Profile page (`UserInfoFragment` and its layouts, `views/HomeImageLayout.kt`) and the Banner setting. |
| `fastlane/metadata/android/en-US/short_description.txt`, `full_description.txt` | Store listing text describes Shark Music. |
| `fastlane/metadata/android/en-US/images/featureGraphic.png`, `phoneScreenshots/*.jpg` | New feature graphic and screenshots of Shark Music, copyright © 2026 crunchbank. The library shown is invented demo content with generated artwork. Retro Music's old changelog file is removed. |
| `README.md`, `NOTICE.md`, `.gitignore` | Shark Music README (replaces Retro Music's), this file, and ignored signing files. |

"Retro Music" is the name of the original project. Shark Music is not affiliated with or endorsed by it.
