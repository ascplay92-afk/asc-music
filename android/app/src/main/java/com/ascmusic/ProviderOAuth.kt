package com.ascmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtSeconds: Long,
)

internal class EncryptedOAuthTokenStore(context: Context, provider: String) {
    private val preferences = context.applicationContext.getSharedPreferences("asc-$provider-oauth", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.$provider.oauth"

    @Synchronized
    fun read(): OAuthTokens? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > NONCE_BYTES) { "O token OAuth armazenado está inválido." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, NONCE_BYTES)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(NONCE_BYTES, bytes.size)), StandardCharsets.UTF_8))
        return OAuthTokens(json.getString("accessToken"), json.optString("refreshToken"), json.getLong("expiresAtSeconds"))
    }

    @Synchronized
    fun write(tokens: OAuthTokens) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(
            JSONObject()
                .put("accessToken", tokens.accessToken)
                .put("refreshToken", tokens.refreshToken)
                .put("expiresAtSeconds", tokens.expiresAtSeconds)
                .toString()
                .toByteArray(StandardCharsets.UTF_8),
        )
        check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "O token OAuth não pôde ser persistido."
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

internal data class OAuthPkceRequest(val intent: Intent, val state: String, val verifier: String)

internal fun createPkceRequest(
    authorizationEndpoint: String,
    clientId: String,
    redirectUri: String,
    scope: String,
    state: String = randomUrlToken(),
): OAuthPkceRequest {
    val verifier = randomUrlToken(64)
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
    val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val uri = Uri.parse(authorizationEndpoint).buildUpon()
        .appendQueryParameter("client_id", clientId)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("redirect_uri", redirectUri)
        .appendQueryParameter("scope", scope)
        .appendQueryParameter("state", state)
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("code_challenge", challenge)
        .appendQueryParameter("access_type", "offline")
        .appendQueryParameter("prompt", "consent")
        .build()
    return OAuthPkceRequest(Intent(Intent.ACTION_VIEW, uri), state, verifier)
}

internal fun randomUrlToken(size: Int = 32): String {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

internal fun formBody(values: Map<String, String>): ByteArray = values.entries.joinToString("&") { (key, value) ->
    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
}.toByteArray(StandardCharsets.UTF_8)

internal fun jsonHttpRequest(
    url: String,
    method: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
): JSONObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
    try {
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { input -> BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() } }.orEmpty()
        if (status !in 200..299) throw ProviderHttpException(status, text)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    } finally {
        connection.disconnect()
    }
}

internal class ProviderHttpException(val statusCode: Int, body: String) :
    IllegalStateException("O provedor respondeu HTTP $statusCode${body.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}.")
