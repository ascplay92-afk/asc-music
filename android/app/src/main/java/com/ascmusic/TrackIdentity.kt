package com.ascmusic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.Base64

fun stableTrackId(title: String, artist: String, album: String = ""): String {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    val value = listOf(title, artist, album).joinToString("\u001f", transform = ::normalize)
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return "track-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
