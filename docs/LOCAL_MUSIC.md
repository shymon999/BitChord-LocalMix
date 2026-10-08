# Using your local music

LocalMix reads the music Android has indexed on your phone. It keeps three things separate: finding the file named in a playlist, recognizing that file as a catalog recording, and using a catalog recording to start radio.

A file can play locally even if no catalog match exists. A radio match does not repair a missing playlist path.

## Getting started

1. Copy your music to the phone and grant LocalMix access to audio files. If you have selected a local-music folder in settings, make sure the files are inside it.
2. Check that the tracks appear in your local library. Android needs to scan newly copied files before the app can find them.
3. Play a local track directly, or use the playlist import action to select an M3U file or a folder containing playlists.
4. To start radio, open a local track's menu and choose the radio action while online. Choose a recording if LocalMix asks you to confirm one.

You do not need to import a playlist before using local music or radio.

## Tags and filenames

There is no LocalMix-specific tag format. Ordinary title, artist and album tags work. The app reads metadata through Android's media library, including the track's duration.

For reliable matching, use the actual song title and credited artist. Keep meaningful version names, such as “Live”, “Acoustic” or “Example Remix”, in the title. Album tags can help distinguish releases; artwork is not needed for recognition.

If the title tag is missing, the filename without its extension is used as a fallback. An informative filename is better than `track01.mp3`, but a filename such as `Artist - Title.mp3` is not a reliable substitute for proper title and artist tags. Missing artist metadata can still appear as **Unknown Artist**.

You do not need to add YouTube video IDs to filenames or tags. LocalMix does not rewrite or rename your audio files during scanning, matching or playlist import.

## M3U and M3U8 playlists

Import a single file, several files, or a folder containing `.m3u` and `.m3u8` playlists. UTF-8 is the recommended encoding. Files that are not valid UTF-8 are read using Windows-1250, so convert playlists from other encodings to UTF-8 before importing them.

Entries keep their original order. Repeated tracks stay repeated. A missing file stays visible as an unavailable row and is skipped during playback. LocalMix does not fill that gap with an unrelated search result.

For playlists you move between devices, relative paths are usually the easiest choice. For example, with the playlist inside your music folder:

```text
Music/
  Evening.m3u8
  Example Artist/
    Example Album/
      01 - Example Song.flac
```

The playlist can contain:

```m3u
#EXTM3U
#PLAYLIST:Evening
#EXTINF:215,Example Artist - Example Song
Example Artist/Example Album/01 - Example Song.flac
```

Keep the relative folder structure when copying both the playlist and music. `#EXTINF` descriptions and durations are optional. Available tracks use the file's Android metadata; an `#EXTINF` description does not replace missing tags in an otherwise available audio file.

### Paths from another computer

Android absolute paths and `file://` paths are supported. Windows drive paths and paths from other devices may need a different root on the phone.

The importer tries to infer the playlist's parent folder or a moved root by comparing the remaining folder paths with indexed files. It does not pick a file just because its basename matches. Keeping artist and album folders intact makes this work more reliably.

If entries remain missing, check that the actual files are on the phone and indexed, then correct the playlist's paths and reimport it. Relative paths beside the music avoid depending on a computer's drive letter. The resolver handles slash differences and falls back to case and Unicode normalization, but an ambiguous path is kept unresolved.

Remote stream URLs and HLS playlists are not supported as local-file playlists. An Android `content://` entry is usable only when it points to a file in the current media index; it is not a portable path for another phone.

### Updating a playlist

Edit the original file and use **Reimport** to update the existing imported playlist. The app keeps a record of the source document and its path. Selecting the same file through a folder picker or a file picker can still update the same playlist.

Files moved or newly added on the phone can be resolved again when Android's media library changes. If you changed the playlist's entries, reimport the source file to read those changes.

## Local copies of catalog tracks

With **Use local files instead of streaming** enabled, LocalMix checks whether a YouTube Music track has a matching file on the phone. Matching compares title, artist, recording version and duration; album metadata is supporting evidence.

For automatic local playback, both durations must be known and close, the main artist must match, and the title must be effectively the same. Different live recordings, covers and remixes are not treated as interchangeable just because the base song title matches. Several distinct local recordings that fit the same result can prevent a match.

When a local copy is found, the catalog row gets the available-offline badge and playback can use the file. The track keeps its catalog identity for likes, history and radio. This is a link to your existing file, not a new app download.

The match review screen lets you confirm a match or detach a wrong file. Some accepted matches are marked for review because their versions, credited artists or durations differ slightly. Detaching leaves your audio file in place.

## Radio from a local file

Radio needs a YouTube Music recording as its starting point. LocalMix first checks a saved choice for the file. Otherwise, it searches YouTube Music using the title and artist and compares the song results with the local metadata.

A confident match starts radio automatically. If the results are close, the duration is missing or the version is unclear, the app offers choices. You can run a manual search, choose a recording or say that none are correct. Your answer is remembered for the file; replacing or changing it can require another match.

This is metadata matching, not audio fingerprinting. The audio file is not uploaded. The song metadata used in the search is sent to YouTube Music. Unreleased recordings and songs absent from its catalog may have no suitable seed.

Background autoplay uses a remembered or confident match and does not open a choice dialog. If it cannot find one, it leaves that file without a radio seed.

Choosing a radio seed does not replace the local track or turn recommendations into offline files. Radio discovery requires a connection. Individual tracks already downloaded or available as matched local files can still play from storage.

## Offline use and backups

Local playlists and local audio can play offline. Artist pages can show available downloaded and local tracks, including guest credits, before an online page is available. Online search, catalog browsing and new radio recommendations require a connection.

The app backup includes imported playlist records, local playback matches and saved radio choices. It does not include your audio files or original M3U files. When moving to another phone, copy those files separately, grant audio access again and check playlist paths and matches against the new device's media library.
