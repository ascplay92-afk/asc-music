package com.ascmusic

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().targetContext }
    private val testDatabaseName = "asc-music-test-${UUID.randomUUID()}.db"

    @Before
    fun setUp() {
        context.deleteDatabase(testDatabaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDatabaseName)
    }

    @Test
    fun testLibraryFavoritesPlaylistsQueueHistoryAndPlaybackPreferencesPersist() {
        val track = AudioTrack(
            id = "content://music/one",
            uri = "content://music/one",
            name = "one.flac",
            title = "Caju",
            artist = "Liniker",
            album = "Indigo Borboleta Anil",
            durationMs = 180_000,
            format = "flac",
            albumArt = byteArrayOf(1, 2, 3),
        )
        val playlist = MusicPlaylist(
            "playlist-one",
            "Favorites",
            "Manual",
            listOf(PlaylistTrack("row-one", "Caju", "Liniker")),
        )
        LibraryDatabase(context, testDatabaseName).use { database ->
            database.applyChanges(listOf(track))
            database.setFavorite(track.id, true)
            database.savePlaylists(listOf(playlist))
            database.saveFolder("content://tree/music", "Music")
            database.addHistory(track.id)
            database.saveQueue(
                PlaybackSnapshot(
                    trackIds = listOf(track.id),
                    currentIndex = 0,
                    positionMs = 45_000,
                    shuffleEnabled = true,
                    repeatMode = RepeatMode.ALL,
                    volume = 0.6f,
                    wasPlaying = true,
                ),
            )
        }

        LibraryDatabase(context, testDatabaseName).use { database ->
            val savedTrack = database.readTracks().single()
            assertEquals(track.id, savedTrack.id)
            assertEquals("Caju", savedTrack.title)
            assertEquals(true, savedTrack.favorite)
            assertEquals(listOf(1, 2, 3), savedTrack.albumArt?.map { it.toInt() })
            assertEquals(listOf(playlist), database.readPlaylists())
            assertEquals(listOf("content://tree/music" to "Music"), database.readFolders())
            assertEquals(listOf(track.id), database.readPlaybackSnapshot().trackIds)
            assertEquals(45_000L, database.readPlaybackSnapshot().positionMs)
            assertEquals(true, database.readPlaybackSnapshot().shuffleEnabled)
            assertEquals(RepeatMode.ALL, database.readPlaybackSnapshot().repeatMode)
            assertEquals(true, database.readPlaybackSnapshot().wasPlaying)
            assertEquals(listOf(track.id), database.readHistory().map { it.id })
        }
    }

    @Test
    fun testVersionOneDatabaseMigrationPreservesIndexedTrack() {
        val oldDatabase = context.openOrCreateDatabase(testDatabaseName, 0, null)
        oldDatabase.execSQL(
            """CREATE TABLE tracks (
                id TEXT PRIMARY KEY NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL,
                title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL, format TEXT NOT NULL, metadata_error TEXT NOT NULL,
                version TEXT NOT NULL, revision TEXT NOT NULL, isrc TEXT NOT NULL
            )""",
        )
        oldDatabase.insertOrThrow(
            "tracks",
            null,
            ContentValues().apply {
                put("id", "content://music/legacy")
                put("uri", "content://music/legacy")
                put("name", "legacy.mp3")
                put("title", "Legacy title")
                put("artist", "Legacy artist")
                put("album", "Legacy album")
                put("duration_ms", 123_000)
                put("format", "mp3")
                put("metadata_error", "")
                put("version", "")
                put("revision", "1:2")
                put("isrc", "")
            },
        )
        oldDatabase.version = 1
        oldDatabase.close()

        LibraryDatabase(context, testDatabaseName).use { database ->
            val migrated = database.readTracks().single()
            assertEquals("Legacy title", migrated.title)
            assertEquals("Legacy artist", migrated.artist)
            assertEquals("Legacy album", migrated.album)
            assertEquals("1:2", migrated.revision)
            assertEquals(false, migrated.favorite)
        }
    }

    @Test
    fun testSyncedEntitiesAndOutboxAreIsolatedByAccount() {
            LibraryDatabase(context, testDatabaseName).use { database ->
                database.storeRemoteSyncEntity(
                    "supabase:user-a",
                    JSONObject("""{"type":"playlist","id":"shared-id","version":1,"payload":{"name":"A list","tracks":[]}}"""),
                )
                database.storeRemoteSyncEntity(
                    "supabase:user-b",
                    JSONObject("""{"type":"playlist","id":"shared-id","version":1,"payload":{"name":"B list","tracks":[]}}"""),
                )
                database.storeRemoteSyncEntity(
                    "supabase:user-a",
                    JSONObject("""{"type":"favorite","id":"track-id","version":2,"deleted":true,"payload":{"favorite":true}}"""),
                )
                database.storeRemoteSyncEntity(
                    "supabase:user-b",
                    JSONObject("""{"type":"favorite","id":"track-id","version":1,"payload":{"favorite":true}}"""),
                )
                database.enqueueSyncChange("supabase:user-a", "playlist", "local-id", JSONObject("""{"name":"A"}"""))
                database.enqueueSyncChange("supabase:user-b", "playlist", "local-id", JSONObject("""{"name":"B"}"""))
                database.saveSyncCursor("supabase:user-a", 12)
                database.saveSyncCursor("supabase:user-b", 3)

                assertEquals("A list", database.readRemotePlaylists("supabase:user-a").single().name)
                assertEquals("B list", database.readRemotePlaylists("supabase:user-b").single().name)
                assertEquals(false, database.readRemoteFavoriteStates("supabase:user-a")["track-id"])
                assertEquals(true, database.readRemoteFavoriteStates("supabase:user-b")["track-id"])
                assertEquals("A", database.readSyncOutbox("supabase:user-a").single().payload.getString("name"))
                assertEquals("B", database.readSyncOutbox("supabase:user-b").single().payload.getString("name"))
                assertEquals(12L, database.readSyncCursor("supabase:user-a"))
                assertEquals(3L, database.readSyncCursor("supabase:user-b"))
            }
    }

    @Test
    fun testAccountScopedPersonalDataIsolatedAndLegacyTablesRemainUntouched() {
        val track = AudioTrack("content://music/account", "content://music/account", "account.mp3", "Account", "Artist", "", 0, "mp3")
        val playlistA = MusicPlaylist("same-id", "A", "manual", listOf(PlaylistTrack("a-track", "A track", "Artist")))
        val playlistB = MusicPlaylist("same-id", "B", "manual", listOf(PlaylistTrack("b-track", "B track", "Artist")))

        LibraryDatabase(context, testDatabaseName).use { database ->
            database.applyChanges(listOf(track))
            database.savePlaylists(listOf(MusicPlaylist("legacy", "Legacy", "manual", emptyList())))
            database.setFavorite(track.id, true)
            database.addHistory(track.id)
            database.saveQueue(PlaybackSnapshot(listOf(track.id), 0, 10, false, RepeatMode.OFF, 1f))

            database.saveAccountPlaylists("supabase:user-a", listOf(playlistA))
            database.saveAccountPlaylists("supabase:user-b", listOf(playlistB))
            database.setAccountFavorite("supabase:user-a", "track-a", true)
            database.setAccountFavorite("supabase:user-b", "track-a", false)
            database.addAccountHistory("supabase:user-a", track.id, 1000)
            database.addAccountHistory("supabase:user-b", track.id, 2000)
            database.saveAccountQueue("supabase:user-a", PlaybackSnapshot(listOf(track.id), 0, 20, true, RepeatMode.ONE, 0.5f))
            database.saveAccountQueue("supabase:user-b", PlaybackSnapshot(emptyList(), 0, 30, false, RepeatMode.ALL, 0.7f))

            assertEquals("A", database.readAccountPlaylists("supabase:user-a").single().name)
            assertEquals("B", database.readAccountPlaylists("supabase:user-b").single().name)
            assertEquals(true, database.readAccountFavoriteStates("supabase:user-a")["track-a"])
            assertEquals(false, database.readAccountFavoriteStates("supabase:user-b")["track-a"])
            assertEquals(listOf(track.id), database.readAccountHistory("supabase:user-a").map { it.id })
            assertEquals(listOf(track.id), database.readAccountHistory("supabase:user-b").map { it.id })
            assertEquals(20L, database.readAccountPlaybackSnapshot("supabase:user-a").positionMs)
            assertEquals(30L, database.readAccountPlaybackSnapshot("supabase:user-b").positionMs)

            assertEquals(listOf("legacy"), database.readPlaylists().map { it.id })
            assertEquals(true, database.readTracks().single().favorite)
            assertEquals(listOf(track.id), database.readHistory().map { it.id })
            assertEquals(listOf(track.id), database.readPlaybackSnapshot().trackIds)
        }
    }

    @Test
    fun testVersionFourMigrationCreatesAccountTablesWithoutAssigningLegacyData() {
        val oldDatabase = context.openOrCreateDatabase(testDatabaseName, 0, null)
        oldDatabase.execSQL(
            """CREATE TABLE tracks (
                id TEXT PRIMARY KEY NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL,
                title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL, format TEXT NOT NULL, metadata_error TEXT NOT NULL,
                version TEXT NOT NULL, revision TEXT NOT NULL, isrc TEXT NOT NULL,
                album_art BLOB, folder_uri TEXT NOT NULL DEFAULT '', is_favorite INTEGER NOT NULL DEFAULT 0
            )""",
        )
        oldDatabase.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL)")
        oldDatabase.execSQL(
            """CREATE TABLE playlist_tracks (
                playlist_id TEXT NOT NULL, position INTEGER NOT NULL, id TEXT NOT NULL,
                title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL, version TEXT NOT NULL, isrc TEXT NOT NULL,
                PRIMARY KEY (playlist_id, position)
            )""",
        )
        oldDatabase.execSQL("CREATE TABLE library_folders (uri TEXT PRIMARY KEY NOT NULL, label TEXT NOT NULL)")
        oldDatabase.execSQL("CREATE TABLE library_exclusions (track_id TEXT PRIMARY KEY NOT NULL)")
        oldDatabase.execSQL("CREATE TABLE playback_queue (position INTEGER PRIMARY KEY NOT NULL, track_id TEXT NOT NULL)")
        oldDatabase.execSQL("CREATE TABLE playback_history (played_at INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL)")
        oldDatabase.execSQL("CREATE TABLE playback_preferences (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        oldDatabase.execSQL(
            """CREATE TABLE sync_outbox (
                operation_id TEXT PRIMARY KEY NOT NULL, owner_id TEXT NOT NULL, entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL, base_version INTEGER NOT NULL, payload_json TEXT NOT NULL,
                deleted INTEGER NOT NULL, created_at INTEGER NOT NULL
            )""",
        )
        oldDatabase.execSQL(
            """CREATE TABLE sync_entity_versions (
                owner_id TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
                version INTEGER NOT NULL, PRIMARY KEY(owner_id, entity_type, entity_id)
            )""",
        )
        oldDatabase.execSQL("CREATE TABLE sync_metadata (owner_id TEXT PRIMARY KEY NOT NULL, cursor INTEGER NOT NULL DEFAULT 0)")
        oldDatabase.execSQL(
            """CREATE TABLE sync_remote_entities (
                owner_id TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
                version INTEGER NOT NULL, payload_json TEXT NOT NULL, deleted INTEGER NOT NULL,
                PRIMARY KEY(owner_id, entity_type, entity_id)
            )""",
        )
        oldDatabase.execSQL("INSERT INTO playlists(id, name, source) VALUES ('legacy', 'Legacy', 'manual')")
        oldDatabase.execSQL("INSERT INTO library_folders(uri, label) VALUES ('content://tree/legacy', 'Legacy folder')")
        oldDatabase.execSQL("INSERT INTO library_exclusions(track_id) VALUES ('legacy-track')")
        oldDatabase.execSQL("INSERT INTO playback_queue(position, track_id) VALUES (0, 'legacy-track')")
        oldDatabase.execSQL("INSERT INTO playback_history(track_id) VALUES ('legacy-track')")
        oldDatabase.execSQL("INSERT INTO playback_preferences(key, value) VALUES ('volume', '0.4')")
        oldDatabase.version = 4
        oldDatabase.close()

        LibraryDatabase(context, testDatabaseName).use { database ->
            assertEquals(listOf("legacy"), database.readPlaylists().map { it.id })
            assertEquals(listOf("content://tree/legacy" to "Legacy folder"), database.readFolders())
            assertEquals(listOf("legacy-track"), database.readPlaybackSnapshot().trackIds)
            assertEquals("0.4", database.preference("volume", "missing"))
            assertEquals(emptyList<MusicPlaylist>(), database.readAccountPlaylists("supabase:user-a"))
            assertEquals(emptyMap<String, Boolean>(), database.readAccountFavoriteStates("supabase:user-a"))
            assertEquals(emptyList<AudioTrack>(), database.readAccountHistory("supabase:user-a"))
            assertEquals(emptyList<String>(), database.readAccountPlaybackSnapshot("supabase:user-a").trackIds)
        }
    }

    @Test
    fun testVersionsOneTwoAndThreeUpgradeToVersionFive() {
        for (version in 1..3) {
            val databaseName = "asc-music-version-$version-${UUID.randomUUID()}.db"
            val oldDatabase = context.openOrCreateDatabase(databaseName, 0, null)
            oldDatabase.execSQL(
                if (version == 1) {
                    """CREATE TABLE tracks (
                        id TEXT PRIMARY KEY NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL,
                        title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL, format TEXT NOT NULL, metadata_error TEXT NOT NULL,
                        version TEXT NOT NULL, revision TEXT NOT NULL, isrc TEXT NOT NULL
                    )"""
                } else {
                    """CREATE TABLE tracks (
                        id TEXT PRIMARY KEY NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL,
                        title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL, format TEXT NOT NULL, metadata_error TEXT NOT NULL,
                        version TEXT NOT NULL, revision TEXT NOT NULL, isrc TEXT NOT NULL,
                        album_art BLOB, folder_uri TEXT NOT NULL DEFAULT '', is_favorite INTEGER NOT NULL DEFAULT 0
                    )"""
                },
            )
            oldDatabase.execSQL(
                """INSERT INTO tracks(id, uri, name, title, artist, album, duration_ms, format, metadata_error, version, revision, isrc)
                   VALUES ('track-$version', 'content://music/$version', 'track.mp3', 'Track $version', 'Artist', '', 1000, 'mp3', '', '', '', '')""",
            )
            if (version >= 2) {
                oldDatabase.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL)")
                oldDatabase.execSQL(
                    """CREATE TABLE playlist_tracks (
                        playlist_id TEXT NOT NULL, position INTEGER NOT NULL, id TEXT NOT NULL,
                        title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL, version TEXT NOT NULL, isrc TEXT NOT NULL,
                        PRIMARY KEY (playlist_id, position)
                    )""",
                )
                oldDatabase.execSQL("CREATE TABLE library_folders (uri TEXT PRIMARY KEY NOT NULL, label TEXT NOT NULL)")
                oldDatabase.execSQL("CREATE TABLE library_exclusions (track_id TEXT PRIMARY KEY NOT NULL)")
                oldDatabase.execSQL("CREATE TABLE playback_queue (position INTEGER PRIMARY KEY NOT NULL, track_id TEXT NOT NULL)")
                oldDatabase.execSQL("CREATE TABLE playback_history (played_at INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL)")
                oldDatabase.execSQL("CREATE TABLE playback_preferences (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            }
            if (version >= 3) {
                oldDatabase.execSQL(
                    """CREATE TABLE sync_outbox (
                        operation_id TEXT PRIMARY KEY NOT NULL, owner_id TEXT NOT NULL, entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL, base_version INTEGER NOT NULL, payload_json TEXT NOT NULL,
                        deleted INTEGER NOT NULL, created_at INTEGER NOT NULL
                    )""",
                )
                oldDatabase.execSQL(
                    """CREATE TABLE sync_entity_versions (
                        owner_id TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
                        version INTEGER NOT NULL, PRIMARY KEY(owner_id, entity_type, entity_id)
                    )""",
                )
                oldDatabase.execSQL("CREATE TABLE sync_metadata (owner_id TEXT PRIMARY KEY NOT NULL, cursor INTEGER NOT NULL DEFAULT 0)")
            }
            oldDatabase.version = version
            oldDatabase.close()

            LibraryDatabase(context, databaseName).use { database ->
                assertEquals("Track $version", database.readTracks().single().title)
                assertEquals(emptyList<MusicPlaylist>(), database.readAccountPlaylists("supabase:user-a"))
            }
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun testRemovingTrackFromIndexDoesNotDeleteOriginalDocument() {
        val track = AudioTrack("content://music/keep", "content://music/keep", "keep.mp3", "Keep", "Artist", "", 0, "mp3")
        LibraryDatabase(context, testDatabaseName).use { database ->
            database.applyChanges(listOf(track))
            database.removeTrackFromIndex(track.id)
            assertTrue(database.readTracks().isEmpty())
            assertTrue(track.uri.startsWith("content://"))
            assertEquals(setOf(track.id), database.readExcludedTrackIds())
        }
    }

    @Test
    fun testRemovingFolderRemovesOnlyItsIndexedTracksAndQueueEntries() {
        val folderTrack = AudioTrack(
            "content://music/folder-track",
            "content://music/folder-track",
            "folder.mp3",
            "Folder",
            "Artist",
            "",
            0,
            "mp3",
            folderUri = "content://tree/folder",
        )
        val otherTrack = AudioTrack(
            "content://music/other-track",
            "content://music/other-track",
            "other.mp3",
            "Other",
            "Artist",
            "",
            0,
            "mp3",
            folderUri = "content://tree/other",
        )
        LibraryDatabase(context, testDatabaseName).use { database ->
            database.applyChanges(listOf(folderTrack, otherTrack))
            database.saveQueue(
                PlaybackSnapshot(
                    trackIds = listOf(folderTrack.id, otherTrack.id),
                    currentIndex = 0,
                    positionMs = 0,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.OFF,
                    volume = 1f,
                ),
            )
            database.removeFolder("content://tree/folder")

            assertEquals(listOf(otherTrack.id), database.readTracks().map { it.id })
            assertEquals(listOf(otherTrack.id), database.readPlaybackSnapshot().trackIds)
        }
    }

    @Test
    fun testAccountHistoryKeepsTimestampAndSnapshotAfterTrackRemoval() {
        val track = AudioTrack(
            id = "content://music/history",
            uri = "content://music/history",
            name = "history.mp3",
            title = "History title",
            artist = "History artist",
            album = "History album",
            durationMs = 1000,
            format = "mp3",
        )

        LibraryDatabase(context, testDatabaseName).use { database ->
            database.applyChanges(listOf(track))
            database.addAccountHistory("supabase:user-a", track.id, 1_700_000_000_123L)
            database.removeTrackFromIndex(track.id)

            val entry = database.readAccountHistoryEntries("supabase:user-a").single()
            assertEquals(1_700_000_000_123L, entry.playedAtMs)
            assertEquals("History title", entry.track.title)
            assertEquals("History artist", entry.track.artist)
            assertEquals("History album", entry.track.album)
        }
    }

    @Test
    fun testAccountPlaylistUpsertDoesNotDeleteOtherPlaylists() {
        val first = MusicPlaylist("first", "First", "manual", emptyList())
        val second = MusicPlaylist("second", "Second", "manual", emptyList())

        LibraryDatabase(context, testDatabaseName).use { database ->
            database.saveAccountPlaylists("supabase:user-a", listOf(first, second))
            database.saveAccountPlaylist("supabase:user-a", first.copy(name = "Updated"))

            assertEquals(
                setOf("first", "second"),
                database.readAccountPlaylists("supabase:user-a").map { it.id }.toSet(),
            )
            assertEquals("Updated", database.readAccountPlaylists("supabase:user-a").first { it.id == "first" }.name)
        }
    }

    @Test
    fun testRemotePageApplyRollsBackAccountStateAndCursorTogether() {
        val valid = JSONObject()
            .put("type", "playlist")
            .put("id", "remote-playlist")
            .put("version", 1)
            .put("deleted", false)
            .put("payload", JSONObject().put("name", "Remote").put("source", "web"))
        val invalid = JSONObject()
            .put("type", "favorite")
            .put("id", "track")
            .put("deleted", false)
            .put("payload", JSONObject().put("favorite", true))

        LibraryDatabase(context, testDatabaseName).use { database ->
            try {
                database.applyRemoteChanges("supabase:user-a", listOf(valid, invalid), 12)
                throw AssertionError("Expected malformed remote record to fail")
            } catch (_: Exception) {
                assertEquals(emptyList<MusicPlaylist>(), database.readAccountPlaylists("supabase:user-a"))
                assertEquals(0L, database.readSyncCursor("supabase:user-a"))
            }
        }
    }

    @Test
    fun testRemoteHistoryAndSettingsUseWorkerPayloadShape() {
        val track = AudioTrack(
            id = "content://music/remote-history",
            uri = "content://music/remote-history",
            name = "remote.mp3",
            title = "Remote title",
            artist = "Remote artist",
            album = "Remote album",
            durationMs = 1000,
            format = "mp3",
        )
        val history = JSONObject()
            .put("type", "history")
            .put("id", "history-event-1")
            .put("version", 1)
            .put("deleted", false)
            .put("payload", JSONObject()
                .put("trackId", track.id)
                .put("title", track.title)
                .put("artist", track.artist)
                .put("album", track.album)
                .put("playedAt", 1_700_000_000_123L))
        val setting = JSONObject()
            .put("type", "setting")
            .put("id", "shuffle")
            .put("version", 1)
            .put("deleted", false)
            .put("payload", JSONObject().put("value", true))

        LibraryDatabase(context, testDatabaseName).use { database ->
            database.applyChanges(listOf(track))
            database.applyRemoteChanges("supabase:user-a", listOf(history, setting), 2)

            val entry = database.readAccountHistoryEntries("supabase:user-a").single()
            assertEquals(1_700_000_000_123L, entry.playedAtMs)
            assertEquals("Remote title", entry.track.title)
            assertEquals("true", database.accountPreference("supabase:user-a", "shuffle", "false"))
            assertEquals(2L, database.readSyncCursor("supabase:user-a"))
        }
    }
}
