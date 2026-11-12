package com.ascmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedTime: String,
    val revision: String,
    val parents: List<String>,
)

class GoogleDriveClient(
    context: Context,
    private val clientId: String = BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
    private val redirectUri: String = REDIRECT_URI,
) {
    private val tokenStore = EncryptedOAuthTokenStore(context, "google-drive")
    private val pending = context.getSharedPreferences("asc-google-drive-pending", Context.MODE_PRIVATE)
    @Volatile private var cancelled = false

    fun isConfigured(): Boolean = clientId.isNotBlank()

    fun hasStoredSession(): Boolean = runCatching { tokenStore.read() != null }.getOrDefault(false)

    fun cancel() {
        cancelled = true
    }

    fun authorizationIntent(): Intent {
        cancelled = false
        require(isConfigured()) { "Configure GOOGLE_DRIVE_CLIENT_ID para conectar o Google Drive." }
        val request = createPkceRequest(
            authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            clientId = clientId,
            redirectUri = redirectUri,
            scope = "https://www.googleapis.com/auth/drive.readonly",
        )
        check(pending.edit().putString("state", request.state).putString("verifier", request.verifier).commit()) {
            "Não foi possível iniciar a autorização do Google Drive."
        }
        return request.intent
    }

    fun completeAuthorization(callback: Uri): OAuthTokens {
        require(callback.scheme == "ascmusic" && callback.host == "drive" && callback.path == "/auth") {
            "O callback do Google Drive não pertence ao ASC Music."
        }
        callback.getQueryParameter("error")?.let { throw IllegalStateException("Autorização do Google Drive recusada: $it.") }
        val state = callback.getQueryParameter("state").orEmpty()
        val expectedState = pending.getString("state").orEmpty()
        val verifier = pending.getString("verifier").orEmpty()
        require(state.isNotBlank() && state == expectedState && verifier.isNotBlank()) {
            "O estado da autorização do Google Drive não pôde ser validado."
        }
        val code = callback.getQueryParameter("code").orEmpty()
        require(code.isNotBlank()) { "O callback do Google Drive não contém um código." }
        val response = jsonHttpRequest(
            "https://oauth2.googleapis.com/token",
            "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            formBody(
                mapOf(
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "grant_type" to "authorization_code",
                    "redirect_uri" to redirectUri,
                ),
            ),
        )
        val tokens = parseTokens(response, tokenStore.read()?.refreshToken.orEmpty())
        tokenStore.write(tokens)
        pending.edit().clear().apply()
        return tokens
    }

    fun signOut() {
        val current = runCatching { tokenStore.read() }.getOrNull()
        if (!current?.accessToken.isNullOrBlank()) {
            runCatching {
                jsonHttpRequest(
                    "https://oauth2.googleapis.com/revoke?token=${Uri.encode(current?.accessToken)}",
                    "POST",
                    mapOf("Accept" to "application/json"),
                )
            }
        }
        tokenStore.clear()
        pending.edit().clear().apply()
    }

    fun listFolders(parentId: String? = null): List<GoogleDriveFile> {
        cancelled = false
        val query = buildString {
            append("mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            if (!parentId.isNullOrBlank()) append(" and '$parentId' in parents")
        }
        return listFiles(query)
    }

    fun listAudioFiles(folderId: String): List<GoogleDriveFile> {
        cancelled = false
        val output = mutableListOf<GoogleDriveFile>()
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue.add(folderId)
        while (queue.isNotEmpty()) {
            ensureNotCancelled()
            val parent = queue.removeFirst()
            if (!visited.add(parent)) continue
            val children = listFiles("'$parent' in parents and trashed = false")
            children.forEach { file ->
                if (file.mimeType == FOLDER_MIME) queue.add(file.id)
                else if (isAudio(file)) {
                    output += file
                    if (output.size > MAX_FILES) throw IllegalStateException("A pasta do Google Drive excede o limite seguro de 50.000 arquivos.")
                }
            }
        }
        return output
    }

    fun openMedia(fileId: String, position: Long = 0, length: Long = -1): DriveMediaResponse {
        val token = validAccessToken()
        val connection = URL("$DRIVE_API/files/${Uri.encode(fileId)}?alt=media").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (position > 0 || length >= 0) {
            val end = if (length >= 0) position + length - 1 else null
            connection.setRequestProperty("Range", "bytes=$position-${end ?: ""}")
        }
        val status = connection.responseCode
        if (status !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect()
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) tokenStore.clear()
            throw ProviderHttpException(status, "O arquivo do Google Drive não está disponível.")
        }
        return DriveMediaResponse(connection, connection.inputStream, connection.contentLengthLong)
    }

    private fun listFiles(query: String): List<GoogleDriveFile> {
        val result = mutableListOf<GoogleDriveFile>()
        var pageToken: String? = null
        do {
            ensureNotCancelled()
            val parameters = buildString {
                append("q=").append(Uri.encode(query))
                append("&pageSize=1000&orderBy=folder,name")
                append("&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime,version,md5Checksum,parents)")
                pageToken?.let { append("&pageToken=").append(Uri.encode(it)) }
            }
            val page = try {
                jsonHttpRequest("$DRIVE_API/files?$parameters", "GET", mapOf("Authorization" to "Bearer ${validAccessToken()}"))
            } catch (error: ProviderHttpException) {
                if (error.statusCode == 401) tokenStore.clear()
                if (error.statusCode == 429) throw IllegalStateException("O Google Drive atingiu o limite de requisições. Tente novamente mais tarde.")
                throw error
            }
            val files = page.optJSONArray("files") ?: org.json.JSONArray()
            for (index in 0 until files.length()) result += parseFile(files.getJSONObject(index))
            pageToken = page.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return result
    }

    private fun ensureNotCancelled() {
        if (cancelled) throw java.util.concurrent.CancellationException("Operação do Google Drive cancelada.")
    }

    private fun validAccessToken(): String {
        val current = tokenStore.read() ?: throw IllegalStateException("Conecte o Google Drive antes de acessar os arquivos.")
        if (current.expiresAtSeconds > System.currentTimeMillis() / 1000 + 60) return current.accessToken
        if (current.refreshToken.isBlank()) {
            tokenStore.clear()
            throw IllegalStateException("A autorização do Google Drive expirou. Conecte novamente.")
        }
        return try {
            val response = jsonHttpRequest(
                "https://oauth2.googleapis.com/token",
                "POST",
                mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
                formBody(mapOf("client_id" to clientId, "refresh_token" to current.refreshToken, "grant_type" to "refresh_token")),
            )
            parseTokens(response, current.refreshToken).also(tokenStore::write).accessToken
        } catch (error: Exception) {
            if (error is ProviderHttpException && error.statusCode in setOf(400, 401)) tokenStore.clear()
            throw error
        }
    }

    private fun parseTokens(response: JSONObject, fallbackRefreshToken: String): OAuthTokens {
        val access = response.optString("access_token")
        require(access.isNotBlank()) { "O Google não retornou um token de acesso válido." }
        return OAuthTokens(
            accessToken = access,
            refreshToken = response.optString("refresh_token").ifBlank { fallbackRefreshToken },
            expiresAtSeconds = System.currentTimeMillis() / 1000 + response.optLong("expires_in", 3600),
        )
    }

    private fun parseFile(json: JSONObject) = GoogleDriveFile(
        id = json.getString("id"),
        name = json.optString("name", "Arquivo sem nome"),
        mimeType = json.optString("mimeType"),
        sizeBytes = json.optLong("size", 0),
        modifiedTime = json.optString("modifiedTime"),
        revision = json.optString("version").ifBlank { json.optString("md5Checksum") },
        parents = json.optJSONArray("parents")?.let { array -> (0 until array.length()).map(array::getString) } ?: emptyList(),
    )

    private fun isAudio(file: GoogleDriveFile): Boolean =
        file.mimeType.startsWith("audio/") || file.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    data class DriveMediaResponse(val connection: HttpURLConnection, val stream: InputStream, val contentLength: Long)

    companion object {
        const val REDIRECT_URI = "ascmusic://drive/auth"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        private val AUDIO_EXTENSIONS = setOf("aac", "flac", "m4a", "mp3", "ogg", "opus", "wav", "wma")
        private const val MAX_FILES = 50_000
    }
}
