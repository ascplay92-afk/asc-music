package com.ascmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicModelsTest {
    @Test
    fun stableTrackIdentityNormalizesAccentsAndSeparatesMetadata() {
        assertEquals(
            stableTrackId("Beyoncé", "Artist", "Album"),
            stableTrackId("Beyonce", "Artist", "Album"),
        )
        assertTrue(stableTrackId("Beyoncé", "Artist", "Album").startsWith("track-"))
        assertFalse(stableTrackId("Beyoncé", "Artist", "Album") == stableTrackId("Beyoncé", "Another Artist", "Album"))
    }

    private fun requested(title: String = "Caju", artist: String = "Liniker", version: String = "") =
        PlaylistTrack("playlist-1", title, artist, version = version)

    private fun audio(
        id: String,
        title: String = "Caju",
        artist: String = "Liniker",
        version: String = "",
        metadataError: String = "",
    ) = AudioTrack(
        id = id,
        uri = "content://music/$id",
        name = "unrelated-file-name.mp3",
        title = title,
        artist = artist,
        album = "",
        durationMs = 180_000,
        format = "mp3",
        metadataError = metadataError,
        version = version,
    )

    @Test
    fun exactTitleArtistAndMetadataMatchAutomatically() {
        assertEquals(MatchStatus.AVAILABLE, compareTrack(requested(), listOf(audio("1"))).status)
    }

    @Test
    fun filenameDoesNotOverrideAudioMetadata() {
        assertEquals(MatchStatus.AVAILABLE, compareTrack(requested(), listOf(audio("2"))).status)
    }

    @Test
    fun accentsAreNormalized() {
        assertEquals(
            MatchStatus.AVAILABLE,
            compareTrack(requested("Corações à deriva", "Marisa Monte"), listOf(audio("3", "Coracoes a deriva", "Marisa Monte"))).status,
        )
    }

    @Test
    fun featuredArtistsAreCompared() {
        assertEquals(
            MatchStatus.AVAILABLE,
            compareTrack(requested("Downtown", "Anitta feat. J Balvin"), listOf(audio("4", "Downtown", "J Balvin"))).status,
        )
    }

    @Test
    fun liveRemixAndCleanVersionsAreNotConfusedWithOriginal() {
        assertEquals(MatchStatus.POSSIBLE_MATCH, compareTrack(requested("Caju (Live)"), listOf(audio("5"))).status)
        assertEquals(MatchStatus.POSSIBLE_MATCH, compareTrack(requested("Caju", version = "Club Mix"), listOf(audio("6"))).status)
        assertEquals(MatchStatus.POSSIBLE_MATCH, compareTrack(requested("Caju (Clean)"), listOf(audio("7"))).status)
    }

    @Test
    fun distinctLiveVenuesAndRemixSubtypesRemainAmbiguous() {
        assertEquals(
            MatchStatus.POSSIBLE_MATCH,
            compareTrack(requested("Caju (Live at Wembley)"), listOf(audio("8", "Caju (Live at Tokyo)"))).status,
        )
        assertEquals(
            MatchStatus.POSSIBLE_MATCH,
            compareTrack(requested(version = "Club Mix"), listOf(audio("9", version = "Extended Mix"))).status,
        )
    }

    @Test
    fun parentheticalPartNumberIsNotRemovedFromTitle() {
        assertEquals(
            MatchStatus.POSSIBLE_MATCH,
            compareTrack(requested("Caju (Parte 2)"), listOf(audio("10", "Caju"))).status,
        )
    }

    @Test
    fun duplicateFilesAndMissingMetadataRequireDistinctStates() {
        assertEquals(MatchStatus.DUPLICATE, compareTrack(requested(), listOf(audio("11a"), audio("11b"))).status)
        assertEquals(
            MatchStatus.POSSIBLE_MATCH,
            compareTrack(requested(), listOf(audio("12", artist = "", metadataError = "Missing tags"))).status,
        )
    }

    @Test
    fun duplicateLibraryFilesAreIdentifiedWithoutCollapsingDifferentRecordings() {
        assertEquals(
            setOf("dup-a", "dup-b"),
            duplicateTrackIds(
                listOf(
                    audio("dup-a"),
                    audio("dup-b"),
                    audio("different-duration").copy(durationMs = 220_000),
                    audio("different-version", version = "Live"),
                ),
            ),
        )
    }

    @Test
    fun folderRefreshAddsUpdatesAndRemovesOnlyFilesFromThatFolder() {
        val existing = listOf(
            audio("keep").copy(folderUri = "tree-a"),
            audio("remove").copy(folderUri = "tree-a"),
            audio("other-folder").copy(folderUri = "tree-b"),
        )
        val (updated, removed) = libraryDeltaForFolder(
            existing,
            "tree-a",
            listOf(audio("keep").copy(title = "Updated"), audio("new").copy(folderUri = "tree-a")),
        )
        assertEquals(setOf("keep", "new", "other-folder"), updated.map { it.id }.toSet())
        assertEquals(listOf("remove"), removed)
        assertEquals("Updated", updated.first { it.id == "keep" }.title)
    }

    @Test
    fun incompleteFolderScanPreservesPreviouslyIndexedTracksAndFavorites() {
        val existing = listOf(
            audio("keep").copy(folderUri = "tree-a", favorite = true),
            audio("not-readable").copy(folderUri = "tree-a"),
            audio("other-folder").copy(folderUri = "tree-b"),
        )
        val result = reconcileFolderScan(
            existing,
            "tree-a",
            FolderScanResult(
                tracks = listOf(audio("keep").copy(title = "Updated"), audio("new")),
                failures = 1,
                complete = false,
            ),
            excludedTrackIds = setOf("new"),
        )

        assertEquals(setOf("keep", "not-readable", "other-folder"), result.tracks.map { it.id }.toSet())
        assertEquals("Updated", result.tracks.first { it.id == "keep" }.title)
        assertEquals(true, result.tracks.first { it.id == "keep" }.favorite)
        assertEquals(emptyList<String>(), result.removedIds)
        assertEquals(listOf("keep"), result.scannedTracks.map { it.id })
    }

    @Test
    fun persistedFolderAccessIsReportedPerSavedTreeGrant() {
        assertEquals(
            setOf("tree-revoked"),
            missingPersistedFolderAccess(
                listOf("tree-saved" to "Music", "tree-revoked" to "Old music"),
                setOf("tree-saved"),
            ),
        )
        assertEquals(emptySet<String>(), missingPersistedFolderAccess(emptyList(), emptySet()))
    }

    @Test
    fun safReadGrantPersistenceReportsDeniedPermissions() {
        var attempted = false
        assertTrue(persistSafReadGrant { attempted = true })
        assertTrue(attempted)
        assertFalse(persistSafReadGrant { throw SecurityException("Permission denied") })
    }

    @Test
    fun unchangedFolderRevisionsReuseMetadataAndChangedOrMissingRevisionsReindex() {
        val indexed = audio("stable").copy(revision = "1024:1730000000")
        assertEquals(true, canReuseIndexedTrack(indexed, "1024:1730000000"))
        assertEquals(false, canReuseIndexedTrack(indexed, "2048:1730000001"))
        assertEquals(false, canReuseIndexedTrack(indexed, ""))
        assertEquals(false, canReuseIndexedTrack(null, "1024:1730000000"))
    }

    @Test
    fun manualPlaylistsAndSavedQueuesPreserveOrderAndSkipUnavailableFiles() {
        val playlist = createManualPlaylist("My list", "Liniker — Caju\nDjavan - Oceano\nSó o título")
        assertEquals("My list", playlist.name)
        assertEquals(listOf("Caju", "Oceano", "Só o título"), playlist.tracks.map { it.title })
        assertEquals(listOf("Liniker", "Djavan", ""), playlist.tracks.map { it.artist })
        assertThrows(IllegalArgumentException::class.java) { createManualPlaylist("", " \n ") }

        val first = audio("queue-first")
        val second = audio("queue-second")
        assertEquals(listOf(second, first, second), restoreQueueTracks(listOf("queue-second", "queue-first", "queue-second"), listOf(first, second)))
        assertEquals(listOf(first), restoreQueueTracks(listOf("queue-first", "unavailable"), listOf(first)))
    }

    @Test
    fun repeatPlayerStateCyclesThroughOffSingleAndQueue() {
        assertEquals(RepeatMode.ONE, nextRepeatMode(RepeatMode.OFF))
        assertEquals(RepeatMode.ALL, nextRepeatMode(RepeatMode.ONE))
        assertEquals(RepeatMode.OFF, nextRepeatMode(RepeatMode.ALL))
    }

    @Test
    fun safAudioFilterUsesMimeTypesAndSupportedExtensionsWithoutBroadStorageAccess() {
        assertEquals(true, isSupportedAudioDocument("audio/mpeg", "track.bin"))
        assertEquals(true, isSupportedAudioDocument("application/octet-stream", "track.flac"))
        assertEquals(false, isSupportedAudioDocument("application/pdf", "cover.jpg"))
        assertEquals(false, isSupportedAudioDocument(null, "document.txt"))
    }

    @Test
    fun availabilitySummarySeparatesIndexedFilesFromAnalyzedPlaylistTracks() {
        val noPlaylistTracks = summarizeMatches(emptyList())
        assertEquals(0, noPlaylistTracks.analyzed)
        assertEquals(0, noPlaylistTracks.found)
        assertEquals(0, noPlaylistTracks.missing)
        assertEquals(null, noPlaylistTracks.completeness)

        val summary = summarizeMatches(
            listOf(
                compareTrack(requested(), listOf(audio("available"))),
                compareTrack(requested("Absent", "Unknown"), emptyList()),
                compareTrack(requested(), listOf(audio("duplicate-a"), audio("duplicate-b"))),
                compareTrack(requested("Uncertain"), listOf(audio("possible", "Uncertain", artist = ""))),
            ),
        )
        assertEquals(4, summary.analyzed)
        assertEquals(2, summary.found)
        assertEquals(1, summary.missing)
        assertEquals(1, summary.possible)
        assertEquals(1, summary.duplicates)
        assertEquals(50, summary.completeness)
    }

    @Test
    fun quotedCsvRetainsCommasNewlinesAndMusicMetadata() {
        val playlist = parseCsvPlaylist(
            "Title,Artist,Album,Duration,Version,ISRC\r\n\"Caju, ao vivo\",\"Liniker\",\"Álbum\nEspecial\",3:21,Live,BRABC2400001\r\n",
            "Importada",
        )
        val track = playlist.tracks.single()
        assertEquals("Importada", playlist.name)
        assertEquals("Caju, ao vivo", track.title)
        assertEquals("Álbum\nEspecial", track.album)
        assertEquals(201_000L, track.durationMs)
        assertEquals("Live", track.version)
        assertEquals("BRABC2400001", track.isrc)
    }

    @Test
    fun malformedAndOversizedCsvAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCsvPlaylist("Title,Artist\n\"Caju,Liniker")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCsvPlaylist("Title,Artist\nCaju,Liniker\"")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCsvPlaylist("Artist,Album\nLiniker,CAJU")
        }
    }
}
