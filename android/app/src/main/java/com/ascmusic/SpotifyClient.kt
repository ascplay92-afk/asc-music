package com.ascmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String,
    val trackCount: Int,
)

data class SpotifyTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isrc: String,
    val sourceUrl: String,
)

class SpotifyClient(
    context: Context,
    private val clientId: String = BuildConfig.SPOTIFY_CLIENT_ID,
    private val redirectUri: String = REDIRECT_URI,
) {
    private val tokenStore = EncryptedOAuthTokenStore(context, "spotify")
    private val pending = context.getSharedPreferences("asc-spotify-pending", Context.MODE_PRIVATE)
    @Volatile private var cancelled = false

    fun isConfigured(): Boolean = clientId.isNotBlank()

    fun hasStoredSession(): Boolean = runCatching { tokenStore.read() != null }.getOrDefault(false)

    fun cancel() {
        cancelled = true
    }

    fun authorizationIntent(): Intent {
        cancelled = false
        require(isConfigured()) { "Configure SPOTIFY_CLIENT_ID para conectar o Spotify." }
        val request = createPkceRequest(
            authorizationEndpoint = "https://accounts.spotify.com/authorize",
            clientId = clientId,
            redirectUri = redirectUri,
            scope = "playlist-read-private playlist-read-collaborative",
        )
        check(pending.edit().putString("state", request.state).putString("verifier", request.verifier).commit()) {
            "Não foi possível iniciar a autorização do Spotify."
        }
        return request.intent
    }

    fun completeAuthorization(callback: Uri) {
        require(callback.scheme == "ascmusic" && callback.host == "spotify" && callback.path == "/auth") {
            "O callback do Spotify não pertence ao ASC Music."
        }
        callback.getQueryParameter("error")?.let { throw IllegalStateException("Autorização do Spotify recusada: $it.") }
        val state = callback.getQueryParameter("state").orEmpty()
        require(state.isNotBlank() && state == pending.getString("state") && pending.getString("verifier").orEmpty().isNotBlank()) {
            "O estado da autorização do Spotify não pôde ser validado."
        }
        require(callback.getQueryParameter("code").orEmpty().isNotBlank()) { "O callback do Spotify não contém um código." }
        val response = jsonHttpRequest(
            "https://accounts.spotify.com/api/token",
            "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            formBody(
                mapOf(
                    "client_id" to clientId,
                    "code" to callback.getQueryParameter("code").orEmpty(),
                    "code_verifier" to pending.getString("verifier").orEmpty(),
                    "grant_type" to "authorization_code",
                    "redirect_uri" to redirectUri,
                ),
            ),
        )
        tokenStore.write(parseTokens(response, ""))
        pending.edit().clear().apply()
    }

    fun disconnect() {
        tokenStore.clear()
        pending.edit().clear().apply()
    }

    fun listPlaylists(): List<SpotifyPlaylist> {
        cancelled = false
        val result = mutableListOf<SpotifyPlaylist>()
        var next: String? = "/me/playlists?limit=50"
        while (next != null) {
            ensureNotCancelled()
            val page = spotifyGet(next)
            val items = page.optJSONArray("items") ?: org.json.JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                result += SpotifyPlaylist(
                    id = item.getString("id"),
                    name = item.optString("name", "Playlist sem nome"),
                    description = item.optString("description"),
                    trackCount = item.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
                )
            }
            next = page.optString("next").ifBlank { null }
            if (result.size > MAX_PLAYLISTS) throw IllegalStateException("O Spotify retornou playlists demais para carregar com segurança.")
        }
        return result
    }

    fun readPlaylistTracks(playlistId: String): List<SpotifyTrack> {
        cancelled = false
        require(playlistId.matches(Regex("[A-Za-z0-9]{22}"))) { "O identificador da playlist Spotify é inválido." }
        val result = mutableListOf<SpotifyTrack>()
        var next: String? = "/playlists/${Uri.encode(playlistId)}/tracks?limit=100"
        while (next != null) {
            ensureNotCancelled()
            val page = spotifyGet(next)
            val items = page.optJSONArray("items") ?: org.json.JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index).optJSONObject("track") ?: continue
                if (item.optString("type") != "track" || item.optString("name").isBlank()) continue
                val artists = item.optJSONArray("artists")
                val artist = if (artists != null) (0 until artists.length()).map { artists.getJSONObject(it).optString("name") }.filter(String::isNotBlank).joinToString(", ") else ""
                result += SpotifyTrack(
                    title = item.getString("name"),
                    artist = artist,
                    album = item.optJSONObject("album")?.optString("name").orEmpty(),
                    durationMs = item.optLong("duration_ms", 0),
                    isrc = item.optJSONObject("external_ids")?.optString("isrc").orEmpty(),
                    sourceUrl = item.optJSONObject("external_urls")?.optString("spotify").orEmpty(),
                )
            }
            next = page.optString("next").ifBlank { null }
            if (result.size > MAX_TRACKS) throw IllegalStateException("A playlist do Spotify excede o limite seguro de importação.")
        }
        return result
    }

    private fun spotifyGet(path: String): JSONObject {
        val token = validAccessToken()
        val url = if (path.startsWith("https://")) {
            require(Uri.parse(path).host == "api.spotify.com" && Uri.parse(path).path.startsWith("/v1/")) {
                "O Spotify retornou uma paginação fora da API oficial."
            }
            path
        } else "https://api.spotify.com/v1${if (path.startsWith('/')) path else "/$path"}"
        return try {
            jsonHttpRequest(url, "GET", mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"))
        } catch (error: ProviderHttpException) {
            if (error.statusCode == 401) tokenStore.clear()
            if (error.statusCode == 429) throw IllegalStateException("O Spotify atingiu o limite de requisições. Tente novamente mais tarde.")
            if (error.statusCode == 403) throw IllegalStateException("O Spotify negou esta playlist ou o escopo autorizado.")
            throw error
        }
    }

    private fun ensureNotCancelled() {
        if (cancelled) throw java.util.concurrent.CancellationException("Operação do Spotify cancelada.")
    }

    private fun validAccessToken(): String {
        val current = tokenStore.read() ?: throw IllegalStateException("Conecte o Spotify antes de acessar playlists.")
        if (current.expiresAtSeconds > System.currentTimeMillis() / 1000 + 60) return current.accessToken
        if (current.refreshToken.isBlank()) throw IllegalStateException("A autorização do Spotify expirou. Conecte novamente.")
        val refreshed = jsonHttpRequest(
            "https://accounts.spotify.com/api/token",
            "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            formBody(mapOf("client_id" to clientId, "refresh_token" to current.refreshToken, "grant_type" to "refresh_token")),
        )
        return parseTokens(refreshed, current.refreshToken).also(tokenStore::write).accessToken
    }

    private fun parseTokens(response: JSONObject, fallbackRefreshToken: String): OAuthTokens {
        val access = response.optString("access_token")
        require(access.isNotBlank()) { "O Spotify não retornou um token de acesso válido." }
        return OAuthTokens(access, response.optString("refresh_token").ifBlank { fallbackRefreshToken }, System.currentTimeMillis() / 1000 + response.optLong("expires_in", 3600))
    }

    companion object {
        const val REDIRECT_URI = "ascmusic://spotify/auth"
        private const val MAX_PLAYLISTS = 1000
        private const val MAX_TRACKS = 10_000
    }
}
