package com.ascmusic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val expiresAtSeconds: Long,
)

data class SupabaseAuthCallback(
    val type: String,
    val accessToken: String,
    val refreshToken: String,
)

internal fun parseSupabaseAuthCallback(value: String): SupabaseAuthCallback {
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        throw IllegalArgumentException("O link de autenticação do ASC Music está malformado.")
    }
    require(uri.scheme == "ascmusic" && uri.host == "auth") { "O link de autenticação não pertence ao ASC Music." }
    val expectedType = when (uri.path) {
        "/confirm" -> setOf("signup", "email")
        "/recovery" -> setOf("recovery")
        else -> throw IllegalArgumentException("O caminho do link de autenticação não é permitido.")
    }
    val parameters = (uri.rawFragment ?: uri.rawQuery)
        ?.split('&')
        ?.filter(String::isNotEmpty)
        ?.associate { pair ->
            val parts = pair.split('=', limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
        }
        ?: emptyMap()
    val type = parameters["type"].orEmpty()
    require(type in expectedType) { "O tipo do link de autenticação não corresponde ao destino." }
    require(parameters["token_type"].isNullOrBlank() || parameters["token_type"] == "bearer") {
        "O link de autenticação usa um tipo de token não suportado."
    }
    val accessToken = parameters["access_token"].orEmpty()
    val refreshToken = parameters["refresh_token"].orEmpty()
    require(accessToken.isNotBlank() && refreshToken.isNotBlank()) { "O link não contém uma sessão renovável." }
    return SupabaseAuthCallback(type, accessToken, refreshToken)
}

class SecureSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("asc-secure-session", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.supabase.session"

    @Synchronized
    fun read(): SupabaseSession? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size <= NONCE_BYTES) throw IllegalStateException("A sessão criptografada está inválida.")
        val nonce = bytes.copyOfRange(0, NONCE_BYTES)
        val ciphertext = bytes.copyOfRange(NONCE_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
        val json = JSONObject(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
        return SupabaseSession(
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            userId = json.getString("userId"),
            email = json.optString("email"),
            expiresAtSeconds = json.getLong("expiresAtSeconds"),
        )
    }

    @Synchronized
    fun write(session: SupabaseSession) {
        val json = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("email", session.email)
            .put("expiresAtSeconds", session.expiresAtSeconds)
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "A sessão criptografada não pôde ser persistida."
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "A sessão local não pôde ser removida." }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

class SupabaseAuthClient(
    private val sessionStore: SecureSessionStore,
    private val projectUrl: String = BuildConfig.SUPABASE_URL,
    private val publicKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
) {
    init {
        if (projectUrl.isNotBlank()) requireHttps(projectUrl)
    }

    fun isConfigured(): Boolean = projectUrl.isNotBlank() && publicKey.isNotBlank()

    fun signUp(email: String, password: String): SupabaseSession? {
        requireConfigured()
        requirePassword(password)
        return parseAndSave(
            authRequest(
                "/auth/v1/signup?redirect_to=${encodedAuthRedirect("confirm")}",
                JSONObject().put("email", email.trim()).put("password", password),
            ),
        )
    }

    fun signIn(email: String, password: String): SupabaseSession {
        requireConfigured()
        requirePassword(password)
        return parseAndSave(
            authRequest("/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password)),
        ) ?: throw IOExceptionMessage("The identity provider did not return a signed-in session.")
    }

    fun recover(email: String) {
        requireConfigured()
        authRequest(
            "/auth/v1/recover?redirect_to=${encodedAuthRedirect("recovery")}",
            JSONObject().put("email", email.trim()),
        )
    }

    fun acceptAuthCallback(callback: SupabaseAuthCallback): SupabaseSession {
        requireConfigured()
        val user = request("/auth/v1/user", "GET", null, callback.accessToken)
        val userId = user.optString("id")
        if (userId.isBlank()) throw IOExceptionMessage("Identity callback did not return a user.")
        val expiresAt = decodeTokenExpiry(callback.accessToken)
        if (expiresAt <= System.currentTimeMillis() / 1000) throw IOExceptionMessage("The identity callback session has expired.")
        return SupabaseSession(
            accessToken = callback.accessToken,
            refreshToken = callback.refreshToken,
            userId = userId,
            email = user.optString("email"),
            expiresAtSeconds = expiresAt,
        ).also(sessionStore::write)
    }

    fun updatePassword(password: String) {
        requireConfigured()
        requirePassword(password)
        val session = validSession() ?: throw IOExceptionMessage("The recovery session has expired. Request a new link.")
        request("/auth/v1/user", "PUT", JSONObject().put("password", password), session.accessToken)
    }

    @Synchronized
    fun validSession(): SupabaseSession? {
        val session = sessionStore.read() ?: return null
        if (session.expiresAtSeconds > System.currentTimeMillis() / 1000 + REFRESH_MARGIN_SECONDS) return session
        return refreshSession()
    }

    @Synchronized
    fun refreshSession(): SupabaseSession? {
        val session = sessionStore.read() ?: return null
        return try {
            val refreshed = authRequest(
                "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", session.refreshToken),
            )
            parseAndSave(refreshed)
        } catch (error: Exception) {
            if (error is HttpStatusException && error.statusCode in setOf(400, 401)) sessionStore.clear()
            throw error
        }
    }

    fun signOut() {
        val session = sessionStore.read()
        try {
            if (session != null && isConfigured()) {
                request("/auth/v1/logout", "POST", null, session.accessToken)
            }
        } finally {
            sessionStore.clear()
        }
    }

    private fun authRequest(path: String, body: JSONObject): JSONObject =
        request(path, "POST", body, null)

    private fun encodedAuthRedirect(path: String): String =
        URLEncoder.encode("ascmusic://auth/$path", StandardCharsets.UTF_8.name())

    private fun decodeTokenExpiry(token: String): Long {
        val payload = token.split('.').getOrNull(1)
            ?: throw IOExceptionMessage("The identity callback token is malformed.")
        return try {
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
            JSONObject(decoded).getLong("exp")
        } catch (_: Exception) {
            throw IOExceptionMessage("The identity callback token has invalid claims.")
        }
    }

    private fun request(path: String, method: String, body: JSONObject?, accessToken: String?): JSONObject {
        val connection = URL(projectUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("apikey", publicKey)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            if (accessToken != null) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            if (status !in 200..299) {
                val message = try {
                    JSONObject(text).optString("msg").ifBlank {
                        JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("error_description") }
                    }
                } catch (_: Exception) {
                    ""
                }
                throw HttpStatusException(status, message.ifBlank { "Identity provider request failed (HTTP $status)." })
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAndSave(json: JSONObject): SupabaseSession? {
        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) return null
        val user = json.optJSONObject("user") ?: throw IOExceptionMessage("Identity response did not contain a user.")
        val userId = user.optString("id")
        val refreshToken = json.optString("refresh_token")
        if (userId.isBlank() || refreshToken.isBlank()) throw IOExceptionMessage("Identity response did not contain a refreshable session.")
        val session = SupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = user.optString("email"),
            expiresAtSeconds = json.optLong("expires_at", System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)),
        )
        sessionStore.write(session)
        return session
    }

    private fun requireConfigured() {
        if (!isConfigured()) throw IOExceptionMessage("Configure SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY for Android.")
    }

    private fun requirePassword(password: String) {
        if (password.length < 8) throw IllegalArgumentException("The password must contain at least 8 characters.")
    }

    private fun requireHttps(value: String) {
        val url = URL(value)
        require(url.protocol == "https") { "Supabase authentication requires HTTPS." }
    }

    private class IOExceptionMessage(message: String) : IllegalStateException(message)

    class HttpStatusException(val statusCode: Int, message: String) : IllegalStateException(message)

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val REFRESH_MARGIN_SECONDS = 60
    }
}
