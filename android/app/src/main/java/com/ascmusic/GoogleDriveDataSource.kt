package com.ascmusic

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream

/** Range-aware Media3 source for Drive files; it never downloads the complete file before playback. */
class GoogleDriveDataSource(private val client: GoogleDriveClient) : BaseDataSource(/* isNetwork = */ true) {
    private var input: InputStream? = null
    private var currentUri: Uri? = null
    private var opened = false
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        val fileId = dataSpec.uri.lastPathSegment?.takeIf(String::isNotBlank)
            ?: throw IOException("O arquivo do Google Drive não possui identificador.")
        transferInitializing(dataSpec)
        val response = try {
            client.openMedia(fileId, dataSpec.position, dataSpec.length)
        } catch (error: Exception) {
            throw IOException("Não foi possível abrir o arquivo do Google Drive.", error)
        }
        input = response.stream
        currentUri = dataSpec.uri
        opened = true
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else response.contentLength
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val stream = input ?: return C.RESULT_END_OF_INPUT
        val requested = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val count = stream.read(buffer, offset, requested)
        if (count == -1) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            input?.close()
        } finally {
            input = null
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val client: GoogleDriveClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = GoogleDriveDataSource(client)
    }
}
