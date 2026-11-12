package com.ascmusic

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class WorkerSyncClient(
    private val auth: SupabaseAuthClient,
    private val database: LibraryDatabase,
    private val apiBaseUrl: String = BuildConfig.ASC_API_BASE_URL,
) {
    @Volatile private var cancelled = false
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        activeConnection?.disconnect()
    }

    init {
        if (apiBaseUrl.isNotBlank()) require(URL(apiBaseUrl).protocol == "https") {
            "The ASC Worker API must use HTTPS."
        }
    }

    fun synchronize(): SyncOutcome {
        cancelled = false
        if (apiBaseUrl.isBlank()) throw IllegalStateException("Configure ASC_API_BASE_URL for Android.")
        val session = auth.validSession() ?: throw IllegalStateException("Entre na conta ASC Music para sincronizar.")
        val ownerId = session.userId
        val pending = database.readSyncOutbox(ownerId)
        pending.chunked(MAX_CHANGES).forEach { batch ->
            ensureNotCancelled()
            val changes = JSONArray()
            batch.forEach { change ->
                changes.put(
                    JSONObject()
                        .put("operationId", change.operationId)
                        .put("type", change.type)
                        .put("id", change.id)
                        .put("baseVersion", change.baseVersion)
                        .put("payload", change.payload)
                        .put("deleted", change.deleted),
                )
            }
            val response = request("/api/sync", "POST", JSONObject().put("changes", changes), ownerId)
            val results = response.optJSONArray("results")
                ?: throw IllegalStateException("O Worker retornou uma resposta de sincronização inválida.")
            if (results.length() != batch.size) throw IllegalStateException("O Worker não confirmou todas as alterações.")
            for (index in 0 until results.length()) {
                requireCurrentOwner(ownerId)
                val result = results.getJSONObject(index)
                val change = batch.firstOrNull { it.operationId == result.optString("operationId") }
                    ?: throw IllegalStateException("O Worker retornou uma operação desconhecida.")
                if (result.optString("status") == "conflict") {
                    throw SyncConflictException(change, result.optJSONObject("current"))
                }
                if (result.optString("status") != "applied" || !result.has("version")) {
                    throw IllegalStateException("O Worker retornou um resultado de sincronização inválido.")
                }
                database.acknowledgeSyncChange(ownerId, change, result.getLong("version"))
            }
        }

        var cursor = database.readSyncCursor(ownerId)
        var pages = 0
        while (true) {
            ensureNotCancelled()
            if (++pages > MAX_PAGES) throw IllegalStateException("Limite de páginas excedido; tente sincronizar novamente.")
            val page = request("/api/sync?cursor=$cursor&limit=$PAGE_SIZE", "GET", null, ownerId)
            val changes = page.optJSONArray("changes")
                ?: throw IllegalStateException("O Worker retornou uma página inválida.")
            val records = (0 until changes.length()).map { changes.getJSONObject(it) }
            val nextCursor = page.optLong("cursor", cursor)
            if (nextCursor < cursor) throw IllegalStateException("O cursor do Worker retrocedeu.")
            requireCurrentOwner(ownerId)
            database.applyRemoteChanges(ownerId, records, nextCursor)
            cursor = nextCursor
            if (!page.optBoolean("hasMore")) break
        }
        return SyncOutcome(database.readSyncOutbox(ownerId).size, cursor)
    }

    private fun request(path: String, method: String, body: JSONObject?, expectedOwnerId: String): JSONObject {
        ensureNotCancelled()
        var session = auth.validSession() ?: throw IllegalStateException("A sessão ASC expirou. Entre novamente.")
        if (session.userId != expectedOwnerId) throw IllegalStateException("The account changed during synchronization; no changes were applied.")
        var response = send(path, method, body, session.accessToken)
        if (response.statusCode == 401) {
            session = auth.refreshSession() ?: throw IllegalStateException("A sessão ASC expirou. Entre novamente.")
            if (session.userId != expectedOwnerId) throw IllegalStateException("The account changed during synchronization; no changes were applied.")
            response = send(path, method, body, session.accessToken)
        }
        if (response.statusCode !in 200..299) {
            val error = response.body.optString("error").ifBlank { "ASC Worker request failed (HTTP ${response.statusCode})." }
            throw IllegalStateException(error)
        }
        return response.body
    }

    private fun requireCurrentOwner(expectedOwnerId: String) {
        val currentOwnerId = auth.validSession()?.userId
            ?: throw IllegalStateException("The ASC session expired. Sign in again.")
        if (currentOwnerId != expectedOwnerId) {
            throw IllegalStateException("The account changed during synchronization; no changes were applied.")
        }
    }

    private fun send(path: String, method: String, body: JSONObject?, accessToken: String): HttpResult {
        val connection = URL(apiBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        activeConnection = connection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            val response = try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject().put("error", "ASC Worker returned a non-JSON response.")
            }
            return HttpResult(status, response)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun ensureNotCancelled() {
        if (cancelled) throw java.util.concurrent.CancellationException("Sincronização cancelada; alterações preservadas.")
    }

    data class SyncOutcome(val pendingChanges: Int, val cursor: Long)

    data class SyncConflictException(val change: SyncOutboxChange, val remote: JSONObject?) :
        IllegalStateException("Conflito de versão em ${change.type} ${change.id}; a alteração local foi preservada.")

    private data class HttpResult(val statusCode: Int, val body: JSONObject)

    private companion object {
        const val MAX_CHANGES = 100
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 100
        const val TIMEOUT_MS = 15_000
    }
}
