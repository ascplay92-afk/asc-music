package com.ascmusic

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class AudioTrack(
    val id: String,
    val uri: String,
    val name: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val metadataError: String = "",
    val version: String = "",
    val revision: String = "",
    val isrc: String = "",
    val albumArt: ByteArray? = null,
    val folderUri: String = "",
    val favorite: Boolean = false,
)

data class MusicPlaylist(
    val id: String,
    val name: String,
    val source: String,
    val tracks: List<PlaylistTrack>,
)

data class PlaylistTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val version: String = "",
    val isrc: String = "",
)

enum class MatchStatus {
    AVAILABLE,
    MISSING,
    POSSIBLE_MATCH,
    DUPLICATE,
}

data class TrackMatch(
    val status: MatchStatus,
    val candidates: List<AudioTrack>,
)

data class LibraryMatchSummary(
    val analyzed: Int,
    val found: Int,
    val missing: Int,
    val possible: Int,
    val duplicates: Int,
) {
    val completeness: Int?
        get() = if (analyzed == 0) null else found * 100 / analyzed
}

enum class RepeatMode {
    OFF,
    ONE,
    ALL,
}

fun nextRepeatMode(mode: RepeatMode): RepeatMode = when (mode) {
    RepeatMode.OFF -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.OFF
}

fun createManualPlaylist(name: String, text: String): MusicPlaylist {
    val tracks = text.lines().map(String::trim).filter(String::isNotBlank).map { line ->
        val separator = Regex("\\s+[—–-]\\s+").find(line)
        if (separator == null) PlaylistTrack(UUID.randomUUID().toString(), line, "")
        else {
            val pieces = line.split(separator.value, limit = 2)
            PlaylistTrack(UUID.randomUUID().toString(), pieces[1].trim(), pieces[0].trim())
        }
    }
    require(tracks.isNotEmpty()) { "Adicione ao menos uma música." }
    return MusicPlaylist(UUID.randomUUID().toString(), name.ifBlank { "Nova playlist" }, "Manual", tracks)
}

fun restoreQueueTracks(savedIds: List<String>, indexedTracks: List<AudioTrack>): List<AudioTrack> {
    val byId = indexedTracks.associateBy { it.id }
    return savedIds.mapNotNull(byId::get)
}

fun canReuseIndexedTrack(track: AudioTrack?, currentRevision: String): Boolean =
    track != null && currentRevision.isNotBlank() && track.revision == currentRevision

fun libraryDeltaForFolder(
    existing: List<AudioTrack>,
    folderUri: String,
    scanned: List<AudioTrack>,
): Pair<List<AudioTrack>, List<String>> {
    val scannedById = scanned.associateBy { it.id }
    val retained = existing.filterNot { it.folderUri == folderUri && it.id !in scannedById }
    val updated = (retained + scanned).associateBy { it.id }.values.toList()
    val removedIds = existing.filter { it.folderUri == folderUri && it.id !in scannedById }.map { it.id }
    return updated to removedIds
}

data class FolderLibraryReconciliation(
    val tracks: List<AudioTrack>,
    val scannedTracks: List<AudioTrack>,
    val removedIds: List<String>,
)

fun reconcileFolderScan(
    existing: List<AudioTrack>,
    folderUri: String,
    scan: FolderScanResult,
    excludedTrackIds: Set<String>,
): FolderLibraryReconciliation {
    val favoriteById = existing.associate { it.id to it.favorite }
    val scannedTracks = scan.tracks
        .filterNot { it.id in excludedTrackIds }
        .map { it.copy(favorite = favoriteById[it.id] == true) }
    if (scan.complete) {
        val (tracks, removedIds) = libraryDeltaForFolder(existing, folderUri, scannedTracks)
        return FolderLibraryReconciliation(tracks, scannedTracks, removedIds)
    }

    val tracksById = existing.associateBy { it.id }.toMutableMap()
    scannedTracks.forEach { track -> tracksById[track.id] = track }
    return FolderLibraryReconciliation(tracksById.values.toList(), scannedTracks, emptyList())
}

fun missingPersistedFolderAccess(
    folders: List<Pair<String, String>>,
    persistedReadUris: Set<String>,
): Set<String> = folders.map { it.first }.filterNot(persistedReadUris::contains).toSet()

fun persistSafReadGrant(takePersistableReadGrant: () -> Unit): Boolean = try {
    takePersistableReadGrant()
    true
} catch (_: SecurityException) {
    false
}

fun duplicateTrackIds(tracks: List<AudioTrack>): Set<String> {
    val groups = tracks
        .filter { it.metadataError.isBlank() && normalizeTrackTitle(it.title).isNotBlank() && artistNames(it.artist).isNotEmpty() }
        .groupBy { "${normalizeTrackTitle(it.title)}|${artistNames(it.artist).sorted().joinToString("|")}" }
    return groups.values.flatMap { group ->
        group.flatMapIndexed { index, track ->
            group.drop(index + 1)
                .filter { other ->
                    versionFlags(track.title, track.version) == versionFlags(other.title, other.version)
                        && (track.durationMs <= 0 || other.durationMs <= 0 || kotlin.math.abs(track.durationMs - other.durationMs) <= 2000)
                }
                .flatMap { listOf(track.id, it.id) }
        }
    }.toSet()
}

fun summarizeMatches(matches: List<TrackMatch>): LibraryMatchSummary {
    val duplicates = matches.count { it.status == MatchStatus.DUPLICATE }
    return LibraryMatchSummary(
        analyzed = matches.size,
        found = matches.count { it.status == MatchStatus.AVAILABLE || it.status == MatchStatus.DUPLICATE },
        missing = matches.count { it.status == MatchStatus.MISSING },
        possible = matches.count { it.status == MatchStatus.POSSIBLE_MATCH },
        duplicates = duplicates,
    )
}

fun normalize(value: String): String {
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
    return decomposed.replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
        .trim()
        .replace("\\s+".toRegex(), " ")
}

private val versionTerms = mapOf(
    "live" to listOf("live", "ao vivo", "en vivo", "concert"),
    "remix" to listOf("remix", "remixed", "mix version", "club mix", "extended mix", "radio edit"),
    "acoustic" to listOf("acoustic", "acoustic version", "acústica", "acustico", "acustica"),
    "instrumental" to listOf("instrumental", "karaoke", "backing track"),
    "remastered" to listOf("remaster", "remastered", "remasterizada", "remasterizado"),
    "explicit" to listOf("explicit", "explicit version", "dirty version"),
    "clean" to listOf("clean", "clean version", "radio version", "censored"),
)

private val versionSuffixes = setOf(
    "live", "ao vivo", "en vivo", "concert",
    "remix", "remixed", "mix version", "club mix", "extended mix", "radio edit",
    "acoustic", "acoustic version", "acustico", "acustica",
    "instrumental", "karaoke", "backing track",
    "remaster", "remastered", "remasterizada", "remasterizado",
    "explicit", "explicit version", "dirty version",
    "clean", "clean version", "radio version", "censored",
).map(::normalize).toSet()

fun normalizeTrackTitle(value: String): String {
    var title = value
    while (true) {
        val suffix = Regex("\\s*[\\[(]([^\\[\\]()]+)[\\])]\\s*$").find(title) ?: break
        if (normalize(suffix.groupValues[1]) !in versionSuffixes) break
        title = title.substring(0, suffix.range.first)
    }
    return normalize(title)
}

private fun versionFlags(title: String, version: String): Set<String> {
    val values = listOf(title, version)
    val text = normalize(values.joinToString(" "))
    val flags = versionTerms.filterValues { terms ->
        terms.any { term -> Regex("(^| )${Regex.escape(normalize(term))}( |$)").containsMatchIn(text) }
    }.keys.toMutableSet()
    values.map(::normalize).forEach { normalized ->
        Regex("\\b(?:live|concert|ao vivo|en vivo)\\s+(?:at|from|in|em|no|na|de|do|da|desde)\\s+(.+)$")
            .find(normalized)?.groupValues?.get(1)?.let { flags.add("live-location:$it") }
        Regex("\\b(club mix|extended mix|radio edit|mix version)\\s*$")
            .find(normalized)?.groupValues?.get(1)?.let { flags.add("remix-type:$it") }
        Regex("\\b((?:19|20)\\d{2})\\s+remaster(?:ed)?\\s*$")
            .find(normalized)?.groupValues?.get(1)?.let { flags.add("remaster-year:$it") }
    }
    return flags
}

private fun tokenSimilarity(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) return 0.0
    val leftTokens = left.split(" ").toSet()
    val rightTokens = right.split(" ").toSet()
    val union = leftTokens union rightTokens
    return if (union.isEmpty()) 0.0 else (leftTokens intersect rightTokens).size.toDouble() / union.size
}

fun compareTrack(track: PlaylistTrack, library: List<AudioTrack>): TrackMatch {
    val expectedVariants = versionFlags(track.title, track.version)
    val expectedTitle = normalizeTrackTitle(track.title)
    val candidates = library.mapNotNull { file ->
        val foundTitle = normalizeTrackTitle(file.title.ifBlank { file.name.substringBeforeLast(".") })
        if (expectedTitle.isBlank() || tokenSimilarity(expectedTitle, foundTitle) < 0.30) return@mapNotNull null

        val variants = versionFlags(file.title, file.version)
        val versionMismatch = expectedVariants != variants
        val exactTitle = expectedTitle == foundTitle
        val expectedArtists = artistNames(track.artist)
        val foundArtists = artistNames(file.artist)
        val artistMatches = expectedArtists.isNotEmpty() && foundArtists.isNotEmpty()
            && expectedArtists.any { it in foundArtists }
        val durationMatches = track.durationMs <= 0 || file.durationMs <= 0
            || kotlin.math.abs(track.durationMs - file.durationMs) <= 2000
        val identifierMatches = track.isrc.isBlank() || file.isrc.isBlank() || normalize(track.isrc) == normalize(file.isrc)
        val confident = exactTitle && artistMatches && durationMatches && identifierMatches && !versionMismatch && file.metadataError.isBlank()
        MatchCandidate(file, confident)
    }.sortedByDescending { it.confident }

    val exact = candidates.filter { it.confident }
    return when {
        exact.size > 1 -> TrackMatch(MatchStatus.DUPLICATE, exact.map { it.file })
        exact.size == 1 -> TrackMatch(MatchStatus.AVAILABLE, exact.map { it.file })
        candidates.isNotEmpty() -> TrackMatch(MatchStatus.POSSIBLE_MATCH, candidates.map { it.file })
        else -> TrackMatch(MatchStatus.MISSING, emptyList())
    }
}

private fun artistNames(value: String): Set<String> = value
    .split(Regex("\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+|[,;&]"))
    .map(::normalize)
    .filter(String::isNotBlank)
    .toSet()

private data class MatchCandidate(
    val file: AudioTrack,
    val confident: Boolean,
)

fun availabilityPercent(playlist: MusicPlaylist, library: List<AudioTrack>): Int {
    if (playlist.tracks.isEmpty()) return 0
    val usable = playlist.tracks.count { track ->
        compareTrack(track, library).status in setOf(MatchStatus.AVAILABLE, MatchStatus.DUPLICATE)
    }
    return (usable * 100.0 / playlist.tracks.size).toInt()
}

fun parseCsvPlaylist(text: String, name: String = "Playlist importada"): MusicPlaylist {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAYLIST_CSV_BYTES) {
        "O CSV excede o limite de 10 MiB."
    }
    val source = text.removePrefix("\uFEFF")
    val delimiter = detectCsvDelimiter(source)
    val rows = parseCsvRows(source, delimiter).filter { row -> row.any(String::isNotBlank) }
    require(rows.size >= 2) { "O CSV precisa ter cabeçalho e pelo menos uma música." }
    val headers = rows.first().map(::normalize)
    val titleIndex = headers.indexOfFirst { it in setOf("title", "track", "track name", "name", "song") }
    val artistIndex = headers.indexOfFirst { it in setOf("artist", "artists") }
    val albumIndex = headers.indexOf("album")
    val durationIndex = headers.indexOfFirst { it in setOf("duration", "duration ms", "duration seconds") }
    val versionIndex = headers.indexOf("version")
    val isrcIndex = headers.indexOf("isrc")
    require(titleIndex >= 0) { "O CSV precisa de uma coluna Title, Track, Name ou Song." }
    val tracks = rows.drop(1).mapNotNull { cells ->
        val title = cells.getOrNull(titleIndex).orEmpty().trim()
        if (title.isBlank()) return@mapNotNull null
        PlaylistTrack(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            artist = cells.getOrNull(artistIndex).orEmpty().trim(),
            album = cells.getOrNull(albumIndex).orEmpty().trim(),
            durationMs = parseCsvDuration(cells.getOrNull(durationIndex).orEmpty(), headers.getOrNull(durationIndex).orEmpty()),
            version = cells.getOrNull(versionIndex).orEmpty().trim(),
            isrc = cells.getOrNull(isrcIndex).orEmpty().trim(),
        )
    }
    require(tracks.isNotEmpty()) { "O CSV não contém faixas válidas." }
    require(tracks.size <= MAX_PLAYLIST_TRACKS) { "O CSV excede o limite de 10.000 faixas." }
    return MusicPlaylist(
        id = java.util.UUID.randomUUID().toString(),
        name = name.trim().ifBlank { "Playlist importada" },
        source = "CSV",
        tracks = tracks,
    )
}

const val MAX_PLAYLIST_CSV_BYTES = 10 * 1024 * 1024
const val MAX_PLAYLIST_TRACKS = 10_000

private fun detectCsvDelimiter(text: String): Char {
    var commas = 0
    var semicolons = 0
    var quoted = false
    var index = 0
    while (index < text.length) {
        when (val character = text[index]) {
            '"' -> {
                if (quoted && text.getOrNull(index + 1) == '"') index++
                else quoted = !quoted
            }
            '\r', '\n' -> if (!quoted) return if (semicolons > commas) ';' else ','
            ',' -> if (!quoted) commas++
            ';' -> if (!quoted) semicolons++
        }
        index++
    }
    return if (semicolons > commas) ';' else ','
}

private fun parseCsvRows(text: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var afterQuote = false
    var index = 0
    while (index < text.length) {
        val character = text[index]
        when {
            quoted && character == '"' && text.getOrNull(index + 1) == '"' -> {
                field.append('"')
                index++
            }
            character == '"' && quoted -> {
                quoted = false
                afterQuote = true
            }
            character == '"' && field.isEmpty() && !afterQuote -> quoted = true
            character == '"' -> throw IllegalArgumentException("O CSV contém aspas fora de um campo citado.")
            quoted -> field.append(character)
            afterQuote && character.isWhitespace() && character != '\r' && character != '\n' -> Unit
            afterQuote && character != delimiter && character != '\r' && character != '\n' ->
                throw IllegalArgumentException("O CSV contém texto inválido após uma aspa.")
            character == delimiter -> {
                row.add(field.toString().trim())
                field.clear()
                afterQuote = false
            }
            character == '\r' || character == '\n' -> {
                row.add(field.toString().trim())
                field.clear()
                rows.add(row.toList())
                row.clear()
                afterQuote = false
                if (character == '\r' && text.getOrNull(index + 1) == '\n') index++
            }
            else -> field.append(character)
        }
        index++
    }
    require(!quoted) { "O CSV contém aspas sem fechamento." }
    if (field.isNotEmpty() || row.isNotEmpty() || afterQuote) {
        row.add(field.toString().trim())
        rows.add(row.toList())
    }
    return rows
}

private fun parseCsvDuration(value: String, header: String): Long {
    val parts = value.trim().split(":")
    if (parts.size in 2..3 && parts.all { it.toLongOrNull() != null }) {
        val seconds = parts.fold(0L) { total, part -> total * 60 + part.toLong() }
        return seconds * 1000
    }
    val number = value.trim().toDoubleOrNull() ?: return 0L
    if (!number.isFinite() || number <= 0) return 0L
    return when (header) {
        "duration ms" -> number.toLong()
        "duration seconds" -> (number * 1000).toLong()
        else -> if (number > 1000) number.toLong() else (number * 1000).toLong()
    }
}
