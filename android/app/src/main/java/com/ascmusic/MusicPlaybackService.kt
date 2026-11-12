package com.ascmusic

import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var database: LibraryDatabase
    private val auth by lazy { SupabaseAuthClient(SecureSessionStore(this)) }
    private val googleDrive by lazy { GoogleDriveClient(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceMutex = Mutex()
    private var periodicSave: Job? = null
    private var reloadingAccount = false
    @Volatile
    private var activeOwnerId: String? = null
    private var lastQueuedPreferencesOwnerId: String? = null
    private var lastQueuedPreferences: Map<String, String> = emptyMap()

    @OptIn(markerClass = [UnstableApi::class])
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            return if (controller.uid == Process.myUid() || controller.isTrusted) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                mediaItem?.mediaId?.takeIf(String::isNotBlank)?.let { trackId ->
                    val ownerId = activeOwnerId
                    serviceScope.launch(Dispatchers.IO) {
                        if (ownerId != null && auth.validSession()?.userId == ownerId) {
                            val playedAt = System.currentTimeMillis()
                            database.addAccountHistory(ownerId, trackId, playedAt)
                            val track = database.readTracks(listOf(trackId)).firstOrNull()
                            if (track != null && auth.validSession()?.userId == ownerId) {
                                database.enqueueSyncChange(
                                    ownerId,
                                    "history",
                                    "history-${UUID.randomUUID()}",
                                    JSONObject()
                                        .put("trackId", trackId)
                                        .put("title", track.title.ifBlank { track.name })
                                        .put("artist", track.artist)
                                        .put("album", track.album)
                                        .put("playedAt", playedAt),
                                )
                            }
                        }
                    }
                }
            }
            persistSnapshot()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = persistSnapshot()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = persistSnapshot()
        override fun onRepeatModeChanged(repeatMode: Int) = persistSnapshot()
        override fun onPlaybackStateChanged(playbackState: Int) = persistSnapshot()
    }

    override fun onCreate() {
        super.onCreate()
        database = LibraryDatabase(this)
        val dataSourceFactory = DefaultDataSource.Factory(this, GoogleDriveDataSource.Factory(googleDrive))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
        serviceScope.launch {
            restorePlayback()
            periodicSave = launch {
                while (true) {
                    delay(PERSIST_INTERVAL_MS)
                    persistSnapshot()
                }
            }
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD_ACCOUNT) {
            serviceScope.launch {
                reloadingAccount = true
                player.clearMediaItems()
                restorePlayback()
                reloadingAccount = false
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun restorePlayback() {
        val snapshotAndTracks = withContext(Dispatchers.IO) {
            val ownerId = auth.validSession()?.userId
            activeOwnerId = ownerId
            if (ownerId == null) return@withContext null
            val snapshot = database.readAccountPlaybackSnapshot(ownerId)
            snapshot to database.readTracks(snapshot.trackIds)
        }
        if (snapshotAndTracks == null) return
        val (snapshot, tracks) = snapshotAndTracks
        lastQueuedPreferencesOwnerId = activeOwnerId
        lastQueuedPreferences = preferenceValues(snapshot)
        val ordered = restoreQueueTracks(snapshot.trackIds, tracks)
        if (ordered.isEmpty()) return
        player.setMediaItems(ordered.map(::mediaItem))
        player.shuffleModeEnabled = snapshot.shuffleEnabled
        player.repeatMode = snapshot.repeatMode.toMedia3()
        player.volume = snapshot.volume
        val index = snapshot.currentIndex.coerceIn(0, ordered.lastIndex)
        player.seekTo(index, snapshot.positionMs.coerceAtLeast(0))
        player.prepare()
        if (snapshot.wasPlaying) player.play()
    }

    private fun persistSnapshot() {
        if (reloadingAccount || !::player.isInitialized || !::database.isInitialized) return
        val snapshot = PlaybackSnapshot(
            trackIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode.toAppMode(),
            volume = player.volume,
            wasPlaying = player.playWhenReady,
        )
        serviceScope.launch(Dispatchers.IO) {
            val ownerId = activeOwnerId ?: return@launch
            if (auth.validSession()?.userId != ownerId) return@launch
            persistenceMutex.withLock {
                if (auth.validSession()?.userId != ownerId) return@withLock
                database.saveAccountQueue(ownerId, snapshot)
                if (lastQueuedPreferencesOwnerId != ownerId) {
                    lastQueuedPreferencesOwnerId = ownerId
                    lastQueuedPreferences = emptyMap()
                }
                val preferences = preferenceValues(snapshot)
                preferences.forEach { (key, value) ->
                    if (lastQueuedPreferences[key] == value) return@forEach
                    val payloadValue: Any = when (key) {
                        "shuffle" -> snapshot.shuffleEnabled
                        "repeat" -> snapshot.repeatMode.name.lowercase()
                        else -> snapshot.volume.toDouble()
                    }
                    database.enqueueSyncChange(ownerId, "setting", key, JSONObject().put("value", payloadValue))
                }
                lastQueuedPreferences = preferences
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        periodicSave?.cancel()
        if (::player.isInitialized && ::database.isInitialized) {
            val snapshot = PlaybackSnapshot(
                trackIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
                currentIndex = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode.toAppMode(),
                volume = player.volume,
                wasPlaying = player.playWhenReady,
            )
            runBlocking(Dispatchers.IO) {
                val ownerId = activeOwnerId
                if (ownerId != null && auth.validSession()?.userId == ownerId) {
                    database.saveAccountQueue(ownerId, snapshot)
                }
            }
        }
        serviceScope.cancel()
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) {
            player.removeListener(playerListener)
            player.release()
        }
        if (::database.isInitialized) database.close()
        super.onDestroy()
    }

    private fun mediaItem(track: AudioTrack): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(track.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkData(track.albumArt, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build(),
        )
        .build()

    private fun preferenceValues(snapshot: PlaybackSnapshot): Map<String, String> = mapOf(
        "shuffle" to snapshot.shuffleEnabled.toString(),
        "repeat" to snapshot.repeatMode.name.lowercase(),
        "volume" to snapshot.volume.coerceIn(0f, 1f).toString(),
    )

    private fun Int.toAppMode() = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    private fun RepeatMode.toMedia3() = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    companion object {
        const val ACTION_RELOAD_ACCOUNT = "com.ascmusic.action.RELOAD_ACCOUNT"
        const val PERSIST_INTERVAL_MS = 5_000L
    }
}
