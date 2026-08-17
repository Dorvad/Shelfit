package com.shelfit.sentinel.platform.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.shelfit.sentinel.core.audio.AudioCaptureConfig
import com.shelfit.sentinel.core.audio.AudioFrame
import com.shelfit.sentinel.core.audio.AudioInput
import com.shelfit.sentinel.core.audio.AudioInputUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Microphone capture on top of `AudioRecord`.
 *
 * The only class in the app that opens the microphone. It streams PCM and nothing
 * else — no interpretation, no file, no cache. There is no code path here that
 * writes audio anywhere: each buffer is handed to the collector and becomes garbage
 * on the next iteration.
 *
 * **Source choice matters.** `UNPROCESSED` is preferred, then `VOICE_RECOGNITION`,
 * with plain `MIC` last. The first two bypass the automatic gain control, noise
 * suppression and dynamics processing that the voice-call path applies. Those would
 * actively fight clap detection: AGC flattens exactly the peak-to-background
 * contrast the detector measures, and noise suppressors treat a transient as noise.
 *
 * **Lifecycle.** The flow is cold. Collection opens the device; the `finally` block
 * stops and releases it on completion, cancellation or failure. A cancelled
 * collection can be blocked inside `read` at the time, so teardown waits at most one
 * frame — 16 ms at the default settings.
 *
 * Reads block, so the producer runs on [Dispatchers.IO]; feature extraction stays on
 * whichever dispatcher collects the flow.
 *
 * **Batching.** One read fetches [AudioCaptureConfig.readSamples] and is sliced into
 * several analysis frames sharing that buffer at different offsets. Analysis resolution
 * is unchanged; what drops is the number of times the capture thread wakes up, which is
 * the part of continuous monitoring that actually costs battery. The batch buffer is
 * allocated fresh each read and never reused, so frames sharing it cannot be corrupted
 * by the next read however far ahead the producer runs.
 */
class AudioRecordInput(context: Context) : AudioInput {

    private val appContext = context.applicationContext

    /**
     * Annotated rather than checked here: the detector verifies the permission
     * through `SensorStatusProvider` before it ever collects this flow, and a
     * `SecurityException` is still translated if the permission is revoked mid-run.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun frames(config: AudioCaptureConfig): Flow<AudioFrame> = flow {
        val record = openRecord(config)
        try {
            try {
                record.startRecording()
            } catch (error: IllegalStateException) {
                throw AudioInputUnavailableException("Could not start recording", error)
            }

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioInputUnavailableException(
                    "Microphone did not start — another app may be holding it",
                )
            }

            var samplePosition = 0L
            while (true) {
                val buffer = ShortArray(config.readSamples)
                val read = record.read(buffer, 0, buffer.size)

                if (read < 0) throw AudioInputUnavailableException(readErrorMessage(read))
                if (read == 0) continue

                // Slice the batch into analysis frames. A short final slice is dropped
                // rather than analysed: a partial frame would skew every level in it.
                var offset = 0
                while (offset + config.frameSamples <= read) {
                    emit(
                        AudioFrame(
                            samples = buffer,
                            sampleCount = config.frameSamples,
                            startTimestampMillis =
                                samplePosition * MILLIS_PER_SECOND / config.sampleRateHz,
                            offset = offset,
                        ),
                    )
                    samplePosition += config.frameSamples
                    offset += config.frameSamples
                }
            }
        } finally {
            // stop() throws if the device already stopped itself; releasing is what
            // actually matters.
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openRecord(config: AudioCaptureConfig): AudioRecord {
        val minimumBytes = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBytes <= 0) {
            throw AudioInputUnavailableException(
                "Device does not support ${config.sampleRateHz} Hz mono capture",
            )
        }

        // Several batches of headroom so a scheduling hiccup drops no audio.
        val bufferBytes = maxOf(
            minimumBytes,
            config.readSamples * BYTES_PER_SAMPLE * BUFFERED_BATCHES,
        )

        for (source in preferredSources()) {
            val record = try {
                AudioRecord(
                    source,
                    config.sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (error: SecurityException) {
                throw AudioInputUnavailableException("Microphone permission denied", error)
            } catch (error: IllegalArgumentException) {
                continue
            }

            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }

        throw AudioInputUnavailableException("No usable microphone input available")
    }

    /**
     * Most faithful source first. `UNPROCESSED` is only offered when the device
     * advertises support, because on hardware that lacks it the constructor may
     * succeed and then deliver processed — or silent — audio.
     */
    private fun preferredSources(): List<Int> {
        val audioManager = ContextCompat.getSystemService(appContext, AudioManager::class.java)
        val supportsUnprocessed = audioManager
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBooleanStrictOrNull() == true

        return buildList {
            if (supportsUnprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
    }

    private fun readErrorMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "Microphone read failed: not recording"
        AudioRecord.ERROR_BAD_VALUE -> "Microphone read failed: bad parameters"
        AudioRecord.ERROR_DEAD_OBJECT -> "Microphone was taken by another app"
        else -> "Microphone read failed (code $code)"
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val BUFFERED_BATCHES = 3
        const val MILLIS_PER_SECOND = 1_000L
    }
}
