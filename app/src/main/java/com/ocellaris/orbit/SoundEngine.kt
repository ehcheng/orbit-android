package com.ocellaris.orbit

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthesized retro arcade sounds. No sample files needed.
 * All sounds generated from sine/square waves at runtime.
 */
class SoundEngine {

    private val sampleRate = 22050
    private val scope = CoroutineScope(Dispatchers.Default)

    /** Play a sound asynchronously */
    private fun play(samples: ShortArray) {
        scope.launch {
            val bufferSize = samples.size * 2
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size)
            track.play()
            delay(samples.size * 1000L / sampleRate + 50)
            track.release()
        }
    }

    /** Square wave — classic 8-bit sound */
    private fun square(freq: Float, t: Float): Float {
        return if (sin(2.0 * PI * freq * t) >= 0) 1f else -1f
    }

    /** Sine wave */
    private fun sine(freq: Float, t: Float): Float {
        return sin(2.0 * PI * freq * t).toFloat()
    }

    /** Generate samples for a given duration */
    private fun generate(durationMs: Int, generator: (t: Float) -> Float): ShortArray {
        val numSamples = (sampleRate * durationMs / 1000f).toInt()
        return ShortArray(numSamples) { i ->
            val t = i.toFloat() / sampleRate
            (generator(t) * 16000).toInt().toShort()
        }
    }

    // ---- GAME SOUNDS ----

    /** Normal catch — sparkly arpeggio (the pleasing one) */
    fun playCatch() {
        val samples = generate(200) { t ->
            val step = (t * 15).toInt()
            val freqs = floatArrayOf(523f, 659f, 784f, 1047f, 1319f)
            val freq = freqs[(step % freqs.size)]
            val env = (1f - t * 5f).coerceAtLeast(0f)
            sine(freq, t) * env * 0.3f + square(freq, t) * env * 0.15f
        }
        play(samples)
    }

    /** Perfect catch — rising blip, pitch goes up a half step per streak
     *  streak 1 = base, 2 = +1 half step, keeps climbing, resets on miss */
    fun playPerfect(streak: Int = 1) {
        // Half step ratio = 2^(1/12) ≈ 1.05946
        val halfStep = 2f.pow(1f / 12f)
        // Cap at 12 semitones (one octave) to avoid ear-piercing frequencies
        val pitchMult = halfStep.pow((streak - 1).coerceIn(0, 12).toFloat())
        val baseFreq = 400f * pitchMult

        val samples = generate(150) { t ->
            val freq = baseFreq + t * 3000f * pitchMult
            val env = (1f - t * 6.7f).coerceAtLeast(0f)
            square(freq, t) * env * 0.4f + sine(freq * 2f, t) * env * 0.2f
        }
        play(samples)
    }

    /** Release — short whoosh/zap */
    fun playRelease() {
        val samples = generate(80) { t ->
            val freq = 800f - t * 6000f  // falling pitch
            val env = (1f - t * 12.5f).coerceAtLeast(0f)
            square(freq.coerceAtLeast(50f), t) * env * 0.25f
        }
        play(samples)
    }

    /** Game over — descending sad tones */
    fun playGameOver() {
        val samples = generate(500) { t ->
            val step = (t * 6).toInt()
            val freqs = floatArrayOf(440f, 370f, 330f, 294f)
            val freq = freqs[step.coerceAtMost(freqs.size - 1)]
            val env = (1f - t * 2f).coerceAtLeast(0f)
            sine(freq, t) * env * 0.35f + sine(freq * 0.5f, t) * env * 0.15f
        }
        play(samples)
    }

    /** Start game — quick ascending bleep */
    fun playStart() {
        val samples = generate(150) { t ->
            val freq = 300f + t * 2000f
            val env = (1f - t * 6.7f).coerceAtLeast(0f)
            sine(freq, t) * env * 0.3f
        }
        play(samples)
    }

    /** Letter change in name entry — short tick */
    fun playTick() {
        val samples = generate(40) { t ->
            val freq = 1200f
            val env = (1f - t * 25f).coerceAtLeast(0f)
            square(freq, t) * env * 0.2f
        }
        play(samples)
    }

    /** Confirm name — satisfied beep */
    fun playConfirm() {
        val samples = generate(150) { t ->
            val freq = if (t < 0.07f) 600f else 900f
            val env = (1f - t * 6.7f).coerceAtLeast(0f)
            sine(freq, t) * env * 0.35f
        }
        play(samples)
    }

    fun release() {
        scope.cancel()
    }
}
