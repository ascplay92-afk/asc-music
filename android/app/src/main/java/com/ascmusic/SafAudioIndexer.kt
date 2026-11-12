package com.ascmusic

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.util.ArrayDeque

data class FolderScanResult(
    val tracks: List<AudioTrack>,
    val failures: Int,
    val complete: Boolean,
)

fun isSupportedAudioDocument(mimeType: String?, displayName: String): Boolean {
    val audioExtensions = setOf("aac", "aiff", "alac", "amr", "flac", "m4a", "m4b", "mp3", "oga", "ogg", "opus", "wav", "weba", "wma")
    val extension = displayName.substringAfterLast('.', "").lowercase()
    return mimeType?.startsWith("audio/") == true || extension in audioExtensions
}

class SafAudioIndexer(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun revision(uri: Uri): String {
        return resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ""
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else 0L
            val modified = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) else 0L
            "$size:$modified"
        }.orEmpty()
    }

    fun readAudioFile(uri: Uri): AudioTrack = readTrack(
        uri,
        uri.lastPathSegment?.substringAfterLast('/') ?: "Arquivo sem nome",
        "",
    )

    fun scanFolder(
        treeUri: Uri,
        existingTracks: List<AudioTrack> = emptyList(),
        excludedTrackIds: Set<String> = emptySet(),
    ): FolderScanResult {
        val queue = ArrayDeque<Pair<String, Int>>()
        val visited = mutableSetOf<String>()
        val tracks = mutableListOf<AudioTrack>()
        val existingById = existingTracks.associateBy { it.id }
        var failures = 0
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: IllegalArgumentException) {
            return FolderScanResult(emptyList(), 1, false)
        }
        queue.add(rootId to 0)
        var documentCount = 0
        while (queue.isNotEmpty()) {
            val (documentId, depth) = queue.removeFirst()
            if (!visited.add(documentId)) continue
            if (depth > MAX_FOLDER_DEPTH || visited.size > MAX_DOCUMENTS) {
                failures++
                break
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val rows = try {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )
            } catch (_: SecurityException) {
                failures++
                continue
            } catch (_: RuntimeException) {
                failures++
                continue
            }
            if (rows == null) {
                failures++
                continue
            }
            try {
                rows.use { cursor ->
                    val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idColumn < 0 || mimeColumn < 0 || nameColumn < 0) {
                        failures++
                        return@use
                    }
                    while (cursor.moveToNext()) {
                        documentCount++
                        if (documentCount > MAX_DOCUMENTS) {
                            failures++
                            break
                        }
                        val childId = cursor.getString(idColumn) ?: continue
                        val mime = cursor.getString(mimeColumn)
                        val name = cursor.getString(nameColumn).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(childId to depth + 1)
                        } else if (isSupportedAudioDocument(mime, name)) {
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val audioDocumentId = documentUri.toString()
                            if (audioDocumentId in excludedTrackIds) continue
                            try {
                                val previous = existingById[audioDocumentId]
                                val revision = revision(documentUri)
                                val reusable = previous?.takeIf { canReuseIndexedTrack(it, revision) }
                                tracks.add(reusable?.copy(folderUri = treeUri.toString()) ?: readTrack(documentUri, name, treeUri.toString()))
                            } catch (_: SecurityException) {
                                failures++
                            } catch (_: RuntimeException) {
                                failures++
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {
                failures++
            } catch (_: RuntimeException) {
                failures++
            }
        }
        return FolderScanResult(tracks, failures, failures == 0)
    }

    private fun readTrack(uri: Uri, fallbackName: String, folderUri: String): AudioTrack {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME) else null
        } ?: fallbackName.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "Arquivo sem nome" }
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val embeddedTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val art = retriever.embeddedPicture
                ?.takeIf { it.size <= MAX_SOURCE_ARTWORK_BYTES }
                ?.let(::createArtworkThumbnail)
            val revision = revision(uri)
            return AudioTrack(
                id = uri.toString(),
                uri = uri.toString(),
                name = name,
                title = embeddedTitle.ifBlank { name.substringBeforeLast(".") },
                artist = artist,
                album = album,
                durationMs = duration,
                format = name.substringAfterLast(".", ""),
                metadataError = if (embeddedTitle.isBlank() || artist.isBlank() || album.isBlank()) "Metadados ausentes ou incompletos; o nome original foi preservado." else "",
                version = embeddedTitle,
                revision = revision,
                albumArt = art,
                folderUri = folderUri,
            )
        } catch (error: RuntimeException) {
            return AudioTrack(
                id = uri.toString(),
                uri = uri.toString(),
                name = name,
                title = name.substringBeforeLast("."),
                artist = "",
                album = "",
                durationMs = 0,
                format = name.substringAfterLast(".", ""),
                metadataError = error.message ?: "Não foi possível ler os metadados; confirme se o arquivo pode ser reproduzido.",
                revision = "",
                folderUri = folderUri,
            )
        } finally {
            retriever.release()
        }
    }

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun createArtworkThumbnail(bytes: ByteArray): ByteArray? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val scale = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / MAX_ARTWORK_EDGE)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = scale },
        ) ?: return null
        return try {
            val output = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, output)
            output.toByteArray().takeIf { it.size <= MAX_ARTWORK_BYTES }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_SOURCE_ARTWORK_BYTES = 8 * 1024 * 1024
        const val MAX_ARTWORK_BYTES = 128 * 1024
        const val MAX_ARTWORK_EDGE = 512
        const val MAX_FOLDER_DEPTH = 32
        const val MAX_DOCUMENTS = 50_000
    }
}
