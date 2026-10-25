# M3U Lite

A small, fast IPTV player for Android TV. It plays your own M3U playlists and Xtream Codes accounts,
and nothing else: it comes with no channels, no account and no ads. About 700 KB.

## Features

- **Playlists**: add an M3U link or an Xtream Codes account (server, username, password). Add as many
  as you like; their channels are put together. Each is fetched again when the app opens, so editing a
  playlist changes the channels, and the last copy is kept for when it can't be reached.
- **Favourites**: hold OK on a channel and it moves to the top of the list.
- **Search**: a box at the top of the channel list.
- **Your own channels**: add a single stream link, or edit or remove any channel. Your changes are
  kept apart from the playlists, so reloading one doesn't undo them.
- **HLS, DASH and MPEG-TS** live streams, through Media3 (ExoPlayer). One player is reused for every
  channel, so switching is quick.
- **English and Arabic**, with the whole app right to left in Arabic.

## The remote

| While watching | |
| --- | --- |
| Up / Down, CH+ / CH- | Previous / next channel |
| OK | Channel list |
| Left twice (Right twice in Arabic) | Settings |
| Back | Leave |

| In the channel list | |
| --- | --- |
| Up / Down | Move; from the last channel round to the first and back. Up on the first channel goes to the search box |
| Left / Right | Previous / next group |
| OK | Watch |
| Hold OK | Favourite, or no longer a favourite |
| Menu | Edit or remove the channel |
| Left twice | Settings |

Settings has the language, your playlists (OK on one to edit, reload or remove it) and your own channels; Back or Right (Left in Arabic) returns to the channel list.

## Install

Download the APK from [Releases](../../releases), or the latest build from
[Actions](../../actions), and sideload it:

```bash
adb install -r m3u-lite-3.5.apk
```

### Adding a playlist from a computer

Typing a long link with a remote is slow. Over ADB:

```bash
adb shell am broadcast -n com.majnioui.m3ulite/.AddPlaylist --es url https://example.com/list.m3u --es name "My list"
adb shell am broadcast -n com.majnioui.m3ulite/.AddPlaylist --es url http://host:port --es user USER --es pass PASS --es name "My account"
```

Only the ADB shell can send this: the receiver requires the `DUMP` permission, which apps can't hold.

## Build

JDK 17 and the Android SDK (platform 34):

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/m3u-lite-release.apk
```

Without signing settings the release APK is signed with your debug key. To sign with a release key,
set `M3U_KEYSTORE`, `M3U_KEYSTORE_PASSWORD`, `M3U_KEY_ALIAS` and `M3U_KEY_PASSWORD`.

GitHub Actions builds the APK on every push (the APK is in the run's artifacts) and, for a `v*` tag,
attaches it with its SHA-256 to a release. It signs with the release key from the repository secrets
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Permissions

- `INTERNET`: to fetch your playlists, the channel logos and the streams.

Cleartext HTTP is allowed because many IPTV servers only offer it. The app has no analytics and sends
nothing anywhere except to the playlists and streams you add.

## Home screen card

The app also declares a card for the [CleanXiaomiTV](https://github.com/majnioui/CleanXiaomiTV)
home screen; other launchers ignore it.

## Licence

MIT. The Outfit font is under the SIL Open Font License (`FONT-LICENSE-OFL.txt`).
