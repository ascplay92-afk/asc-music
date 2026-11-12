package com.ascmusic

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class Media3PlayerTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().targetContext }

    @Test
    fun localWavePlaybackSupportsPauseSeekResumeAndNaturalCompletion() {
        val audioFile = createWaveFile(context, "player-state.wav", 2_000)
        val player = AtomicReference<ExoPlayer>()
        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val ended = CountDownLatch(1)
        onMainThread {
            val instance = ExoPlayer.Builder(context).build()
            instance.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) ready.countDown()
                    if (playbackState == Player.STATE_ENDED) ended.countDown()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playing.countDown()
                }
            })
            instance.setMediaItem(MediaItem.fromUri(audioFile.toURI().toString()))
            instance.prepare()
            player.set(instance)
        }

        try {
            assertTrue("WAV did not become ready", ready.await(5, TimeUnit.SECONDS))

            val durationMs = AtomicLong()
            onMainThread {
                durationMs.set(player.get().duration)
                assertTrue("WAV duration was not parsed", durationMs.get() > 0)
                player.get().play()
            }
            assertTrue("Player did not enter the playing state", playing.await(5, TimeUnit.SECONDS))
            val paused = AtomicBoolean(true)
            onMainThread {
                player.get().seekTo(durationMs.get() / 2)
                assertTrue(
                    "Seek did not update the current position",
                    player.get().currentPosition >= durationMs.get() / 2 - 200,
                )
                player.get().pause()
                paused.set(player.get().isPlaying)
            }
            assertFalse("Player remained active after pause", paused.get())

            onMainThread {
                player.get().seekTo((durationMs.get() - 200).coerceAtLeast(0))
                player.get().play()
            }
            assertTrue("Player did not finish the local WAV", ended.await(5, TimeUnit.SECONDS))
        } finally {
            onMainThread { player.get().release() }
            audioFile.delete()
        }
    }

    @Test
    fun unavailableLocalFileReportsPlaybackError() {
        val missingFile = File(context.cacheDir, "missing-player-test.wav")
        missingFile.delete()
        val player = AtomicReference<ExoPlayer>()
        val failed = CountDownLatch(1)
        onMainThread {
            val instance = ExoPlayer.Builder(context).build()
            instance.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    failed.countDown()
                }
            })
            instance.setMediaItem(MediaItem.fromUri(missingFile.toURI().toString()))
            instance.prepare()
            player.set(instance)
        }

        try {
            assertTrue("Missing file did not produce a player error", failed.await(5, TimeUnit.SECONDS))
        } finally {
            onMainThread { player.get().release() }
        }
    }

    private fun onMainThread(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
    }

    private fun createWaveFile(context: Context, name: String, durationMs: Int): File {
        val sampleRate = 16_000
        val sampleCount = sampleRate * durationMs / 1_000
        val dataLength = sampleCount * Short.SIZE_BYTES
        val buffer = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataLength)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * Short.SIZE_BYTES)
        buffer.putShort(Short.SIZE_BYTES.toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataLength)
        repeat(sampleCount) { index ->
            val sample = (sin(2.0 * PI * 440 * index / sampleRate) * Short.MAX_VALUE / 4).toInt()
            buffer.putShort(sample.toShort())
        }
        return File(context.cacheDir, name).apply { writeBytes(buffer.array()) }
    }
}
