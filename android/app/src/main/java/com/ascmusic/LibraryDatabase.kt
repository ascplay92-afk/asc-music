package com.ascmusic

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

data class PlaybackSnapshot(
    val trackIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val volume: Float,
    val wasPlaying: Boolean = false,
)

data class AccountHistoryEntry(
    val track: AudioTrack,
    val playedAtMs: Long,
)

data class SyncOutboxChange(
    val operationId: String,
    val type: String,
    val id: String,
    val baseVersion: Long,
    val payload: org.json.JSONObject,
    val deleted: Boolean,
)

class LibraryDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(database: SQLiteDatabase) {
        createTracks(database)
        createLibraryTables(database)
        createAccountScopedTables(database)
    }

    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE tracks ADD COLUMN album_art BLOB")
            database.execSQL("ALTER TABLE tracks ADD COLUMN folder_uri TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE tracks ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
            createLibraryTables(database)
        }
        if (oldVersion < 3) createSyncTables(database)
        if (oldVersion < 4) createRemoteSyncTable(database)
        if (oldVersion < 5) createAccountScopedTables(database)
        if (oldVersion < 6) migrateAccountHistorySnapshots(database)
    }

    private fun createTracks(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE tracks (
                id TEXT PRIMARY KEY NOT NULL,
                uri TEXT NOT NULL,
                name TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                format TEXT NOT NULL,
                metadata_error TEXT NOT NULL,
                version TEXT NOT NULL,
                revision TEXT NOT NULL,
                isrc TEXT NOT NULL,
                album_art BLOB,
                folder_uri TEXT NOT NULL DEFAULT '',
                is_favorite INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    private fun createLibraryTables(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE playlists (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                source TEXT NOT NULL
            )""",
        )
        database.execSQL(
            """CREATE TABLE playlist_tracks (
                playlist_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                version TEXT NOT NULL,
                isrc TEXT NOT NULL,
                PRIMARY KEY (playlist_id, position),
                FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
            )""",
        )
        database.execSQL("CREATE TABLE library_folders (uri TEXT PRIMARY KEY NOT NULL, label TEXT NOT NULL)")
        database.execSQL("CREATE TABLE library_exclusions (track_id TEXT PRIMARY KEY NOT NULL)")
        database.execSQL("CREATE TABLE playback_queue (position INTEGER PRIMARY KEY NOT NULL, track_id TEXT NOT NULL)")
        database.execSQL(
            """CREATE TABLE playback_history (
                played_at INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id TEXT NOT NULL
            )""",
        )
        database.execSQL("CREATE TABLE playback_preferences (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        createSyncTables(database)
    }

    private fun createSyncTables(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_outbox (
                operation_id TEXT PRIMARY KEY NOT NULL,
                owner_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                base_version INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                deleted INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS sync_outbox_owner_created ON sync_outbox(owner_id, created_at)")
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_entity_versions (
                owner_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                PRIMARY KEY(owner_id, entity_type, entity_id)
            )""",
        )
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_metadata (owner_id TEXT PRIMARY KEY NOT NULL, cursor INTEGER NOT NULL DEFAULT 0)")
        createRemoteSyncTable(database)
    }

    private fun createRemoteSyncTable(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_remote_entities (
                owner_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                payload_json TEXT NOT NULL,
                deleted INTEGER NOT NULL,
                PRIMARY KEY(owner_id, entity_type, entity_id)
            )""",
        )
    }

    private fun createAccountScopedTables(database: SQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS account_playlists (
                owner_id TEXT NOT NULL,
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY (owner_id, id)
            )""",
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS account_playlist_tracks (
                owner_id TEXT NOT NULL,
                playlist_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                version TEXT NOT NULL,
                isrc TEXT NOT NULL,
                PRIMARY KEY (owner_id, playlist_id, position),
                FOREIGN KEY (owner_id, playlist_id)
                    REFERENCES account_playlists(owner_id, id)
                    ON DELETE CASCADE
            )""",
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS account_favorites (
                owner_id TEXT NOT NULL,
                track_id TEXT NOT NULL,
                favorite INTEGER NOT NULL CHECK (favorite IN (0, 1)),
                PRIMARY KEY (owner_id, track_id)
            )""",
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS account_playback_history (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_id TEXT NOT NULL,
                track_id TEXT NOT NULL,
                played_at_ms INTEGER NOT NULL,
                title_snapshot TEXT NOT NULL DEFAULT '',
                artist_snapshot TEXT NOT NULL DEFAULT '',
                album_snapshot TEXT NOT NULL DEFAULT ''
            )""",
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS account_playback_queue (
                owner_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                track_id TEXT NOT NULL,
                PRIMARY KEY (owner_id, position)
            )""",
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS account_playback_preferences (
                owner_id TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (owner_id, key)
            )""",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS account_history_owner_played ON account_playback_history(owner_id, event_id DESC)")
        database.execSQL("CREATE INDEX IF NOT EXISTS account_history_owner_played_at ON account_playback_history(owner_id, played_at_ms DESC, event_id DESC)")
        database.execSQL("CREATE INDEX IF NOT EXISTS account_favorites_owner ON account_favorites(owner_id)")
    }

    private fun migrateAccountHistorySnapshots(database: SQLiteDatabase) {
        database.execSQL("ALTER TABLE account_playback_history ADD COLUMN title_snapshot TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE account_playback_history ADD COLUMN artist_snapshot TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE account_playback_history ADD COLUMN album_snapshot TEXT NOT NULL DEFAULT ''")
        database.execSQL("CREATE INDEX IF NOT EXISTS account_history_owner_played_at ON account_playback_history(owner_id, played_at_ms DESC, event_id DESC)")
    }

    private fun requireOwner(ownerId: String) {
        require(ownerId.isNotBlank() && ownerId.length <= MAX_OWNER_ID_LENGTH) {
            "A conta ASC Music é necessária para acessar dados pessoais."
        }
    }

    fun enqueueSyncChange(ownerId: String, type: String, id: String, payload: JSONObject, deleted: Boolean = false) {
        requireOwner(ownerId)
        require(type in setOf("playlist", "favorite", "history", "library", "setting"))
        require(id.isNotBlank() && id.length <= 512 && !id.any { it == '/' || it == '\\' || it.code < 0x20 })
        val database = writableDatabase
        database.beginTransaction()
        try {
            val payloadJson = payload.toString()
            val latest = database.query(
                "sync_outbox",
                arrayOf("payload_json", "deleted"),
                "owner_id = ? AND entity_type = ? AND entity_id = ?",
                arrayOf(ownerId, type, id),
                null,
                null,
                "created_at DESC",
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to (cursor.getInt(1) != 0) else null
            }
            if (latest?.first == payloadJson && latest.second == deleted) {
                database.setTransactionSuccessful()
                return
            }
            val version = database.query(
                "sync_entity_versions",
                arrayOf("version"),
                "owner_id = ? AND entity_type = ? AND entity_id = ?",
                arrayOf(ownerId, type, id),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            val pending = database.compileStatement(
                "SELECT COUNT(*) FROM sync_outbox WHERE owner_id = ? AND entity_type = ? AND entity_id = ?",
            ).run {
                bindString(1, ownerId)
                bindString(2, type)
                bindString(3, id)
                simpleQueryForLong()
            }
            database.insertOrThrow(
                "sync_outbox",
                null,
                ContentValues().apply {
                    put("operation_id", UUID.randomUUID().toString())
                    put("owner_id", ownerId)
                    put("entity_type", type)
                    put("entity_id", id)
                    put("base_version", version + pending)
                    put("payload_json", payloadJson)
                    put("deleted", if (deleted) 1 else 0)
                    put("created_at", System.currentTimeMillis())
                },
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun readSyncOutbox(ownerId: String): List<SyncOutboxChange> {
        requireOwner(ownerId)
        val result = mutableListOf<SyncOutboxChange>()
        readableDatabase.query(
            "sync_outbox",
            arrayOf("operation_id", "entity_type", "entity_id", "base_version", "payload_json", "deleted"),
            "owner_id = ?",
            arrayOf(ownerId),
            null,
            null,
            "created_at, rowid",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SyncOutboxChange(
                        operationId = cursor.getString(0),
                        type = cursor.getString(1),
                        id = cursor.getString(2),
                        baseVersion = cursor.getLong(3),
                        payload = JSONObject(cursor.getString(4)),
                        deleted = cursor.getInt(5) != 0,
                    ),
                )
            }
        }
        return result
    }

    fun acknowledgeSyncChange(ownerId: String, change: SyncOutboxChange, version: Long) {
        requireOwner(ownerId)
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("sync_outbox", "owner_id = ? AND operation_id = ?", arrayOf(ownerId, change.operationId))
            database.insertWithOnConflict(
                "sync_entity_versions",
                null,
                ContentValues().apply {
                    put("owner_id", ownerId)
                    put("entity_type", change.type)
                    put("entity_id", change.id)
                    put("version", version)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun rebaseSyncChange(ownerId: String, change: SyncOutboxChange, currentVersion: Long) {
        requireOwner(ownerId)
        require(currentVersion >= 0)
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("sync_outbox", "owner_id = ? AND operation_id = ?", arrayOf(ownerId, change.operationId))
            database.insertOrThrow(
                "sync_outbox",
                null,
                ContentValues().apply {
                    put("operation_id", UUID.randomUUID().toString())
                    put("owner_id", ownerId)
                    put("entity_type", change.type)
                    put("entity_id", change.id)
                    put("base_version", currentVersion)
                    put("payload_json", change.payload.toString())
                    put("deleted", if (change.deleted) 1 else 0)
                    put("created_at", System.currentTimeMillis())
                },
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun recordRemoteSyncVersion(ownerId: String, type: String, id: String, version: Long) {
        requireOwner(ownerId)
        writableDatabase.insertWithOnConflict(
            "sync_entity_versions",
            null,
            ContentValues().apply {
                put("owner_id", ownerId)
                put("entity_type", type)
                put("entity_id", id)
                put("version", version)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun storeRemoteSyncEntity(ownerId: String, record: JSONObject) {
        requireOwner(ownerId)
        writableDatabase.insertWithOnConflict(
            "sync_remote_entities",
            null,
            ContentValues().apply {
                put("owner_id", ownerId)
                put("entity_type", record.getString("type"))
                put("entity_id", record.getString("id"))
                put("version", record.getLong("version"))
                put("payload_json", record.optJSONObject("payload")?.toString() ?: "{}")
                put("deleted", if (record.optBoolean("deleted")) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun readRemotePlaylists(ownerId: String): List<MusicPlaylist> {
        requireOwner(ownerId)
        val result = mutableListOf<MusicPlaylist>()
        readableDatabase.query(
            "sync_remote_entities",
            arrayOf("entity_id", "payload_json"),
            "owner_id = ? AND entity_type = 'playlist' AND deleted = 0",
            arrayOf(ownerId),
            null,
            null,
            "entity_id",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = JSONObject(cursor.getString(1))
                val tracksJson = payload.optJSONArray("tracks") ?: org.json.JSONArray()
                val tracks = (0 until tracksJson.length()).map { index ->
                    val track = tracksJson.getJSONObject(index)
                    PlaylistTrack(
                        id = stableTrackId(track.optString("title"), track.optString("artist"), track.optString("album")),
                        title = track.optString("title"),
                        artist = track.optString("artist"),
                        album = track.optString("album"),
                        durationMs = (track.optDouble("durationSeconds") * 1000).toLong(),
                        isrc = track.optString("isrc"),
                    )
                }
                result.add(
                    MusicPlaylist(
                        id = cursor.getString(0),
                        name = payload.optString("name"),
                        source = payload.optString("source", "manual"),
                        tracks = tracks,
                    ),
                )
            }
        }
        return result
    }

    fun readRemoteFavoriteStates(ownerId: String): Map<String, Boolean> {
        requireOwner(ownerId)
        val result = mutableMapOf<String, Boolean>()
        readableDatabase.query(
            "sync_remote_entities",
            arrayOf("entity_id", "payload_json", "deleted"),
            "owner_id = ? AND entity_type = 'favorite'",
            arrayOf(ownerId),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] =
                    cursor.getInt(2) == 0 && JSONObject(cursor.getString(1)).optBoolean("favorite")
            }
        }
        return result
    }

    fun readSyncCursor(ownerId: String): Long {
        requireOwner(ownerId)
        return readableDatabase.query(
        "sync_metadata",
        arrayOf("cursor"),
        "owner_id = ?",
        arrayOf(ownerId),
        null,
        null,
        null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    fun saveSyncCursor(ownerId: String, cursor: Long) {
        requireOwner(ownerId)
        writableDatabase.insertWithOnConflict(
            "sync_metadata",
            null,
            ContentValues().apply { put("owner_id", ownerId); put("cursor", cursor) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun applyRemoteChanges(
        ownerId: String,
        records: List<JSONObject>,
        cursor: Long,
    ) {
        requireOwner(ownerId)
        val database = writableDatabase
        database.beginTransaction()
        try {
            records.forEach { record ->
                storeRemoteSyncEntity(database, ownerId, record)
                applyAccountRemoteEntity(database, ownerId, record)
                database.insertWithOnConflict(
                    "sync_entity_versions",
                    null,
                    ContentValues().apply {
                        put("owner_id", ownerId)
                        put("entity_type", record.getString("type"))
                        put("entity_id", record.getString("id"))
                        put("version", record.getLong("version"))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            database.insertWithOnConflict(
                "sync_metadata",
                null,
                ContentValues().apply {
                    put("owner_id", ownerId)
                    put("cursor", cursor)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun saveAccountPlaylists(ownerId: String, playlists: List<MusicPlaylist>) {
        requireOwner(ownerId)
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("account_playlist_tracks", "owner_id = ?", arrayOf(ownerId))
            database.delete("account_playlists", "owner_id = ?", arrayOf(ownerId))
            playlists.forEach { playlist -> writeAccountPlaylist(database, ownerId, playlist) }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun saveAccountPlaylist(ownerId: String, playlist: MusicPlaylist) {
        requireOwner(ownerId)
        val database = writableDatabase
        database.beginTransaction()
        try {
            writeAccountPlaylist(database, ownerId, playlist)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun deleteAccountPlaylist(ownerId: String, playlistId: String) {
        requireOwner(ownerId)
        writableDatabase.delete(
            "account_playlists",
            "owner_id = ? AND id = ?",
            arrayOf(ownerId, playlistId),
        )
    }

    fun readAccountPlaylists(ownerId: String): List<MusicPlaylist> {
        requireOwner(ownerId)
        val result = mutableListOf<MusicPlaylist>()
        readableDatabase.query(
            "account_playlists",
            arrayOf("id", "name", "source"),
            "owner_id = ?",
            arrayOf(ownerId),
            null,
            null,
            "rowid",
        ).use { playlists ->
            while (playlists.moveToNext()) {
                val id = playlists.getString(0)
                val tracks = mutableListOf<PlaylistTrack>()
                readableDatabase.query(
                    "account_playlist_tracks",
                    arrayOf("id", "title", "artist", "album", "duration_ms", "version", "isrc"),
                    "owner_id = ? AND playlist_id = ?",
                    arrayOf(ownerId, id),
                    null,
                    null,
                    "position",
                ).use { rows ->
                    while (rows.moveToNext()) {
                        tracks.add(
                            PlaylistTrack(
                                id = rows.getString(0),
                                title = rows.getString(1),
                                artist = rows.getString(2),
                                album = rows.getString(3),
                                durationMs = rows.getLong(4),
                                version = rows.getString(5),
                                isrc = rows.getString(6),
                            ),
                        )
                    }
                }
                result.add(MusicPlaylist(id, playlists.getString(1), playlists.getString(2), tracks))
            }
        }
        return result
    }

    fun setAccountFavorite(ownerId: String, trackId: String, favorite: Boolean) {
        requireOwner(ownerId)
        require(trackId.isNotBlank())
        writableDatabase.insertWithOnConflict(
            "account_favorites",
            null,
            ContentValues().apply {
                put("owner_id", ownerId)
                put("track_id", trackId)
                put("favorite", if (favorite) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun readAccountFavoriteStates(ownerId: String): Map<String, Boolean> {
        requireOwner(ownerId)
        val result = mutableMapOf<String, Boolean>()
        readableDatabase.query(
            "account_favorites",
            arrayOf("track_id", "favorite"),
            "owner_id = ?",
            arrayOf(ownerId),
            null,
            null,
            "track_id",
        ).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getInt(1) != 0
        }
        return result
    }

    fun addAccountHistory(
        ownerId: String,
        trackId: String,
        playedAtMs: Long = System.currentTimeMillis(),
        snapshot: Triple<String, String, String>? = null,
    ) {
        requireOwner(ownerId)
        require(trackId.isNotBlank())
        val database = writableDatabase
        database.beginTransaction()
        try {
            writeAccountHistory(database, ownerId, trackId, playedAtMs, snapshot)
            pruneAccountHistory(database, ownerId)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun readAccountHistory(ownerId: String): List<AudioTrack> {
        return readAccountHistoryEntries(ownerId).map { it.track }
    }

    fun readAccountHistoryEntries(ownerId: String): List<AccountHistoryEntry> {
        requireOwner(ownerId)
        val entries = mutableListOf<Triple<String, Long, Triple<String, String, String>>>()
        readableDatabase.query(
            "account_playback_history",
            arrayOf("track_id", "played_at_ms", "title_snapshot", "artist_snapshot", "album_snapshot"),
            "owner_id = ?",
            arrayOf(ownerId),
            null,
            null,
            "played_at_ms DESC, event_id DESC",
            MAX_HISTORY_ENTRIES.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += Triple(
                    cursor.getString(0),
                    cursor.getLong(1),
                    Triple(cursor.getString(2), cursor.getString(3), cursor.getString(4)),
                )
            }
        }
        val tracks = readTracks(entries.map { it.first }.distinct()).associateBy { it.id }
        return entries.map { (trackId, playedAtMs, snapshot) ->
            AccountHistoryEntry(
                track = tracks[trackId] ?: AudioTrack(
                    id = trackId,
                    uri = "",
                    name = snapshot.first.ifBlank { trackId },
                    title = snapshot.first,
                    artist = snapshot.second,
                    album = snapshot.third,
                    durationMs = 0,
                    format = "",
                    metadataError = "A faixa não está mais indexada nesta biblioteca.",
                ),
                playedAtMs = playedAtMs,
            )
        }
    }

    fun saveAccountQueue(ownerId: String, snapshot: PlaybackSnapshot) {
        requireOwner(ownerId)
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("account_playback_queue", "owner_id = ?", arrayOf(ownerId))
            snapshot.trackIds.forEachIndexed { index, trackId ->
                database.insertOrThrow(
                    "account_playback_queue",
                    null,
                    ContentValues().apply {
                        put("owner_id", ownerId)
                        put("position", index)
                        put("track_id", trackId)
                    },
                )
            }
            setAccountPreference(database, ownerId, "current_index", snapshot.currentIndex.toString())
            setAccountPreference(database, ownerId, "position_ms", snapshot.positionMs.coerceAtLeast(0).toString())
            setAccountPreference(database, ownerId, "shuffle", snapshot.shuffleEnabled.toString())
            setAccountPreference(database, ownerId, "repeat", snapshot.repeatMode.name)
            setAccountPreference(database, ownerId, "volume", snapshot.volume.coerceIn(0f, 1f).toString())
            setAccountPreference(database, ownerId, "was_playing", snapshot.wasPlaying.toString())
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun readAccountPlaybackSnapshot(ownerId: String): PlaybackSnapshot {
        requireOwner(ownerId)
        val ids = mutableListOf<String>()
        readableDatabase.query(
            "account_playback_queue",
            arrayOf("track_id"),
            "owner_id = ?",
            arrayOf(ownerId),
            null,
            null,
            "position",
        ).use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return PlaybackSnapshot(
            trackIds = ids,
            currentIndex = accountPreference(ownerId, "current_index", "0").toIntOrNull() ?: 0,
            positionMs = accountPreference(ownerId, "position_ms", "0").toLongOrNull() ?: 0L,
            shuffleEnabled = accountPreference(ownerId, "shuffle", "false").toBoolean(),
            repeatMode = parseRepeatMode(accountPreference(ownerId, "repeat", RepeatMode.OFF.name)),
            volume = (accountPreference(ownerId, "volume", "1").toFloatOrNull() ?: 1f).coerceIn(0f, 1f),
            wasPlaying = accountPreference(ownerId, "was_playing", "false").toBoolean(),
        )
    }

    fun accountPreference(ownerId: String, key: String, defaultValue: String): String {
        requireOwner(ownerId)
        return accountPreference(readableDatabase, ownerId, key, defaultValue)
    }

    fun setAccountPreference(ownerId: String, key: String, value: String) {
        requireOwner(ownerId)
        setAccountPreference(writableDatabase, ownerId, key, value)
    }

    private fun accountPreference(database: SQLiteDatabase, ownerId: String, key: String, defaultValue: String): String {
        database.query(
            "account_playback_preferences",
            arrayOf("value"),
            "owner_id = ? AND key = ?",
            arrayOf(ownerId, key),
            null,
            null,
            null,
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else defaultValue }
    }

    private fun setAccountPreference(database: SQLiteDatabase, ownerId: String, key: String, value: String) {
        database.insertWithOnConflict(
            "account_playback_preferences",
            null,
            ContentValues().apply {
                put("owner_id", ownerId)
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun writeAccountPlaylist(
        database: SQLiteDatabase,
        ownerId: String,
        playlist: MusicPlaylist,
    ) {
        database.insertWithOnConflict(
            "account_playlists",
            null,
            ContentValues().apply {
                put("owner_id", ownerId)
                put("id", playlist.id)
                put("name", playlist.name)
                put("source", playlist.source)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        database.delete(
            "account_playlist_tracks",
            "owner_id = ? AND playlist_id = ?",
            arrayOf(ownerId, playlist.id),
        )
        playlist.tracks.forEachIndexed { index, track ->
            database.insertOrThrow(
                "account_playlist_tracks",
                null,
                ContentValues().apply {
                    put("owner_id", ownerId)
                    put("playlist_id", playlist.id)
                    put("position", index)
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("duration_ms", track.durationMs)
                    put("version", track.version)
                    put("isrc", track.isrc)
                },
            )
        }
    }

    private fun writeAccountHistory(
        database: SQLiteDatabase,
        ownerId: String,
        trackId: String,
        playedAtMs: Long,
        remoteSnapshot: Triple<String, String, String>? = null,
    ) {
        val track = readTracks(listOf(trackId)).firstOrNull()
        val snapshot = remoteSnapshot ?: Triple(track?.title.orEmpty(), track?.artist.orEmpty(), track?.album.orEmpty())
        database.insertOrThrow(
            "account_playback_history",
            null,
            ContentValues().apply {
                put("owner_id", ownerId)
                put("track_id", trackId)
                put("played_at_ms", playedAtMs)
                put("title_snapshot", snapshot.first)
                put("artist_snapshot", snapshot.second)
                put("album_snapshot", snapshot.third)
            },
        )
    }

    private fun pruneAccountHistory(database: SQLiteDatabase, ownerId: String) {
        database.execSQL(
            """DELETE FROM account_playback_history
               WHERE owner_id = ? AND event_id NOT IN
               (SELECT event_id FROM account_playback_history WHERE owner_id = ?
                ORDER BY played_at_ms DESC, event_id DESC LIMIT ?)""",
            arrayOf(ownerId, ownerId, MAX_HISTORY_ENTRIES),
        )
    }

    private fun applyAccountRemoteEntity(
        database: SQLiteDatabase,
        ownerId: String,
        record: JSONObject,
    ) {
        val type = record.getString("type")
        val id = record.getString("id")
        val deleted = record.optBoolean("deleted")
        val payload = record.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "playlist" -> {
                if (deleted) {
                    database.delete(
                        "account_playlists",
                        "owner_id = ? AND id = ?",
                        arrayOf(ownerId, id),
                    )
                } else {
                    val tracks = payload.optJSONArray("tracks") ?: org.json.JSONArray()
                    val playlist = MusicPlaylist(
                        id = id,
                        name = payload.optString("name"),
                        source = payload.optString("source", "manual"),
                        tracks = (0 until tracks.length()).map { index ->
                            val track = tracks.getJSONObject(index)
                            PlaylistTrack(
                                id = stableTrackId(track.optString("title"), track.optString("artist"), track.optString("album")),
                                title = track.optString("title"),
                                artist = track.optString("artist"),
                                album = track.optString("album"),
                                durationMs = (track.optDouble("durationSeconds") * 1000).toLong(),
                                version = track.optString("version"),
                                isrc = track.optString("isrc"),
                            )
                        },
                    )
                    writeAccountPlaylist(database, ownerId, playlist)
                }
            }
            "favorite" -> {
                database.insertWithOnConflict(
                    "account_favorites",
                    null,
                    ContentValues().apply {
                        put("owner_id", ownerId)
                        put("track_id", id)
                        put("favorite", if (!deleted && payload.optBoolean("favorite")) 1 else 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            "setting" -> {
                val key = id
                if (key.isNotBlank() && !deleted && payload.has("value")) {
                    setAccountPreference(database, ownerId, key, payload.optString("value"))
                }
            }
            "history" -> {
                val trackId = payload.optString("trackId")
                if (trackId.isNotBlank() && !deleted) {
                    writeAccountHistory(
                        database,
                        ownerId,
                        trackId,
                        payload.optLong("playedAt", payload.optLong("playedAtMs", System.currentTimeMillis())),
                        Triple(
                            payload.optString("title"),
                            payload.optString("artist"),
                            payload.optString("album"),
                        ),
                    )
                    pruneAccountHistory(database, ownerId)
                }
            }
        }
    }

    private fun storeRemoteSyncEntity(
        database: SQLiteDatabase,
        ownerId: String,
        record: JSONObject,
    ) {
        database.insertWithOnConflict(
            "sync_remote_entities",
            null,
            ContentValues().apply {
                put("owner_id", ownerId)
                put("entity_type", record.getString("type"))
                put("entity_id", record.getString("id"))
                put("version", record.getLong("version"))
                put("payload_json", record.optJSONObject("payload")?.toString() ?: "{}")
                put("deleted", if (record.optBoolean("deleted")) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun readTracks(): List<AudioTrack> = readTracksWhere(null, null)

    fun readTracks(ids: Collection<String>): List<AudioTrack> {
        if (ids.isEmpty()) return emptyList()
        return ids.distinct().chunked(500).flatMap { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            readTracksWhere("id IN ($placeholders)", batch.toTypedArray())
        }
    }

    private fun readTracksWhere(selection: String?, selectionArgs: Array<String>?): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
        readableDatabase.query(
            "tracks",
            arrayOf("id", "uri", "name", "title", "artist", "album", "duration_ms", "format", "metadata_error", "version", "revision", "isrc", "album_art", "folder_uri", "is_favorite"),
            selection,
            selectionArgs,
            null,
            null,
            "rowid",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    AudioTrack(
                        id = cursor.getString(0),
                        uri = cursor.getString(1),
                        name = cursor.getString(2),
                        title = cursor.getString(3),
                        artist = cursor.getString(4),
                        album = cursor.getString(5),
                        durationMs = cursor.getLong(6),
                        format = cursor.getString(7),
                        metadataError = cursor.getString(8),
                        version = cursor.getString(9),
                        revision = cursor.getString(10),
                        isrc = cursor.getString(11),
                        albumArt = if (cursor.isNull(12)) null else cursor.getBlob(12),
                        folderUri = cursor.getString(13),
                        favorite = cursor.getInt(14) != 0,
                    ),
                )
            }
        }
        return result
    }

    fun applyChanges(
        tracks: List<AudioTrack>,
        removedIds: Collection<String> = emptyList(),
        clearExclusions: Boolean = true,
    ) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            removedIds.forEach { id -> database.delete("tracks", "id = ?", arrayOf(id)) }
            tracks.forEach { track ->
                if (clearExclusions) database.delete("library_exclusions", "track_id = ?", arrayOf(track.id))
                database.insertWithOnConflict(
                    "tracks",
                    null,
                    trackValues(track),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun removeTrackFromIndex(id: String) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.insertWithOnConflict(
                "library_exclusions",
                null,
                ContentValues().apply { put("track_id", id) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.delete("tracks", "id = ?", arrayOf(id))
            database.delete("playback_queue", "track_id = ?", arrayOf(id))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun includeTracks(ids: Collection<String>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            ids.forEach { id -> database.delete("library_exclusions", "track_id = ?", arrayOf(id)) }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun readExcludedTrackIds(): Set<String> {
        val result = mutableSetOf<String>()
        readableDatabase.query("library_exclusions", arrayOf("track_id"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        return result
    }

    fun setFavorite(trackId: String, favorite: Boolean) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) },
            "id = ?",
            arrayOf(trackId),
        )
    }

    fun savePlaylists(playlists: List<MusicPlaylist>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("playlist_tracks", null, null)
            database.delete("playlists", null, null)
            playlists.forEach { playlist ->
                database.insertOrThrow(
                    "playlists",
                    null,
                    ContentValues().apply {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("source", playlist.source)
                    },
                )
                playlist.tracks.forEachIndexed { index, track ->
                    database.insertOrThrow(
                        "playlist_tracks",
                        null,
                        ContentValues().apply {
                            put("playlist_id", playlist.id)
                            put("position", index)
                            put("id", track.id)
                            put("title", track.title)
                            put("artist", track.artist)
                            put("album", track.album)
                            put("duration_ms", track.durationMs)
                            put("version", track.version)
                            put("isrc", track.isrc)
                        },
                    )
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun readPlaylists(): List<MusicPlaylist> {
        val result = mutableListOf<MusicPlaylist>()
        readableDatabase.query("playlists", arrayOf("id", "name", "source"), null, null, null, null, "rowid").use { playlists ->
            while (playlists.moveToNext()) {
                val id = playlists.getString(0)
                val tracks = mutableListOf<PlaylistTrack>()
                readableDatabase.query(
                    "playlist_tracks",
                    arrayOf("id", "title", "artist", "album", "duration_ms", "version", "isrc"),
                    "playlist_id = ?",
                    arrayOf(id),
                    null,
                    null,
                    "position",
                ).use { rows ->
                    while (rows.moveToNext()) {
                        tracks.add(
                            PlaylistTrack(
                                id = rows.getString(0),
                                title = rows.getString(1),
                                artist = rows.getString(2),
                                album = rows.getString(3),
                                durationMs = rows.getLong(4),
                                version = rows.getString(5),
                                isrc = rows.getString(6),
                            ),
                        )
                    }
                }
                result.add(MusicPlaylist(id, playlists.getString(1), playlists.getString(2), tracks))
            }
        }
        return result
    }

    fun saveFolder(uri: String, label: String) {
        writableDatabase.insertWithOnConflict(
            "library_folders",
            null,
            ContentValues().apply { put("uri", uri); put("label", label) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun removeFolder(uri: String) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("library_exclusions", "track_id IN (SELECT id FROM tracks WHERE folder_uri = ?)", arrayOf(uri))
            database.delete("playback_queue", "track_id IN (SELECT id FROM tracks WHERE folder_uri = ?)", arrayOf(uri))
            database.delete("library_folders", "uri = ?", arrayOf(uri))
            database.delete("tracks", "folder_uri = ?", arrayOf(uri))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun readFolders(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        readableDatabase.query("library_folders", arrayOf("uri", "label"), null, null, null, null, "rowid").use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0) to cursor.getString(1))
        }
        return result
    }

    fun saveQueue(snapshot: PlaybackSnapshot) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("playback_queue", null, null)
            snapshot.trackIds.forEachIndexed { index, trackId ->
                database.insertOrThrow(
                    "playback_queue",
                    null,
                    ContentValues().apply { put("position", index); put("track_id", trackId) },
                )
            }
            setPreference(database, "current_index", snapshot.currentIndex.toString())
            setPreference(database, "position_ms", snapshot.positionMs.coerceAtLeast(0).toString())
            setPreference(database, "shuffle", snapshot.shuffleEnabled.toString())
            setPreference(database, "repeat", snapshot.repeatMode.name)
            setPreference(database, "volume", snapshot.volume.coerceIn(0f, 1f).toString())
            setPreference(database, "was_playing", snapshot.wasPlaying.toString())
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun readPlaybackSnapshot(): PlaybackSnapshot {
        val ids = mutableListOf<String>()
        readableDatabase.query("playback_queue", arrayOf("track_id"), null, null, null, null, "position").use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return PlaybackSnapshot(
            trackIds = ids,
            currentIndex = preference("current_index", "0").toIntOrNull() ?: 0,
            positionMs = preference("position_ms", "0").toLongOrNull() ?: 0L,
            shuffleEnabled = preference("shuffle", "false").toBoolean(),
            repeatMode = parseRepeatMode(preference("repeat", RepeatMode.OFF.name)),
            volume = (preference("volume", "1").toFloatOrNull() ?: 1f).coerceIn(0f, 1f),
            wasPlaying = preference("was_playing", "false").toBoolean(),
        )
    }

    fun addHistory(trackId: String) {
        writableDatabase.insert(
            "playback_history",
            null,
            ContentValues().apply { put("track_id", trackId) },
        )
        writableDatabase.execSQL(
            "DELETE FROM playback_history WHERE played_at NOT IN (SELECT played_at FROM playback_history ORDER BY played_at DESC LIMIT 500)",
        )
    }

    fun readHistory(): List<AudioTrack> {
        val ids = mutableListOf<String>()
        readableDatabase.query("playback_history", arrayOf("track_id"), null, null, null, null, "played_at DESC", "500").use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        val tracks = readTracks(ids.distinct())
        val byId = tracks.associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    fun preference(key: String, defaultValue: String): String {
        readableDatabase.query(
            "playback_preferences",
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else defaultValue
        }
    }

    fun setPreference(key: String, value: String) {
        setPreference(writableDatabase, key, value)
    }

    private fun setPreference(database: SQLiteDatabase, key: String, value: String) {
        database.insertWithOnConflict(
            "playback_preferences",
            null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun parseRepeatMode(value: String): RepeatMode = try {
        RepeatMode.valueOf(value.uppercase())
    } catch (_: IllegalArgumentException) {
        RepeatMode.OFF
    }

    private fun trackValues(track: AudioTrack) = ContentValues().apply {
        put("id", track.id)
        put("uri", track.uri)
        put("name", track.name)
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        put("duration_ms", track.durationMs)
        put("format", track.format)
        put("metadata_error", track.metadataError)
        put("version", track.version)
        put("revision", track.revision)
        put("isrc", track.isrc)
        put("album_art", track.albumArt)
        put("folder_uri", track.folderUri)
        put("is_favorite", if (track.favorite) 1 else 0)
    }

    internal companion object {
        internal const val DATABASE_NAME = "asc-music-library.db"
        const val DATABASE_VERSION = 6
        private const val MAX_OWNER_ID_LENGTH = 512
        private const val MAX_HISTORY_ENTRIES = 500
    }
}
