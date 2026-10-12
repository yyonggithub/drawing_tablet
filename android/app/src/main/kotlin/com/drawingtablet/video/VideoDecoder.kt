package com.drawingtablet.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.drawingtablet.network.VideoPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "VideoDecoder"
private const val MIME_TYPE = "video/avc"

/**
 * Hardware H.264 video decoder using MediaCodec.
 *
 * Decoding and rendering run on a dedicated high-priority thread (like
 * Moonlight's renderer thread) so UI/GC activity on other threads cannot add
 * scheduling jitter to the video path.
 */
class VideoDecoder {
    @Volatile
    private var codec: MediaCodec? = null
    @Volatile
    private var surface: Surface? = null
    private var decodeThread: Thread? = null

    // Codec Specific Data (SPS and PPS)
    private var csd0: ByteBuffer? = null // SPS
    private var csd1: ByteBuffer? = null // PPS

    /**
     * Invoked when the decoder needs a fresh keyframe to recover (decode
     * error or missing reference frame). Wired by the UI to ask the server.
     */
    @Volatile
    var onNeedKeyframe: (() -> Unit)? = null

    private var width: Int = 1920
    private var height: Int = 1080

    @Volatile
    private var isConfigured = false

    // Written by the decode thread and by configure() on the surface callback.
    @Volatile
    private var waitingForKeyframe = true
    private var framesRendered = 0

    /**
     * Receipt time of each queued frame, keyed by its presentation timestamp,
     * so the latency stat can be attributed to the frame actually presented
     * (MediaCodec output lags input by a frame or two). Decode-thread only.
     */
    private val pendingReceipts = HashMap<Long, Long>()

    /** Decode-thread only: throttles the "still waiting for a keyframe" request. */
    private var lastKeyframeRequestMs = 0L

    // FPS tracking – updated once per second on the decode thread
    private var fpsFrameCount = 0
    private var lastFpsTime = System.nanoTime()
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps

    /**
     * Approximate client-side latency: time from a frame finishing arrival to
     * being submitted for display (reassembly + queue + decode). Shown in the
     * stats overlay to verify latency work.
     */
    private val _decodeLatencyMs = MutableStateFlow(0)
    val decodeLatencyMs: StateFlow<Int> = _decodeLatencyMs

    /**
     * Configure the decoder with the given dimensions and output surface.
     *
     * @param refreshRate Display refresh rate in Hz, used to hint the codec
     *                    (0 = unknown).
     */
    fun configure(
        width: Int,
        height: Int,
        surface: Surface,
        csd0: ByteBuffer? = null,
        csd1: ByteBuffer? = null,
        refreshRate: Int = 0
    ): Boolean {
        this.width = width
        this.height = height
        this.surface = surface
        this.csd0 = csd0
        this.csd1 = csd1
        pendingReceipts.clear()

        try {
            codec?.release()
            codec = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    if (refreshRate > 0) {
                        setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate)
                    }
                    csd0?.let { setByteBuffer("csd-0", it) }
                    csd1?.let { setByteBuffer("csd-1", it) }
                }
                configure(format, surface, null, 0)
                start()

                // Ask the codec to run in realtime mode: lower decode latency at
                // the cost of power. Not supported everywhere, hence try/catch.
                try {
                    setParameters(Bundle().apply {
                        putInt(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                        putInt(MediaFormat.KEY_PRIORITY, 0)
                    })
                    Log.i(TAG, "Decoder set to realtime operating rate")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set low-latency decoder parameters", e)
                }
            }
            isConfigured = true
            waitingForKeyframe = (csd0 == null || csd1 == null)
            Log.i(TAG, "Decoder configured: ${width}x${height}, csd0=${csd0 != null}, csd1=${csd1 != null}, refresh=${refreshRate}Hz")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure decoder", e)
            isConfigured = false
            waitingForKeyframe = true
            return false
        }
    }

    /**
     * Start decoding video packets from the given queue.
     */
    fun startDecoding(packets: LinkedBlockingQueue<VideoPacket>) {
        if (!isConfigured) {
            Log.w(TAG, "Decoder not configured")
            return
        }

        // Never leave an old decode thread running against the new state.
        joinDecodeThread()

        decodeThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val packet = try {
                    // Short timeout: when nothing arrives we still drain the
                    // decoder so the final frame of a screen change is not
                    // stuck waiting for the next packet.
                    packets.poll(5, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                }

                if (packet == null) {
                    renderLatestOutput()

                    // If we are still waiting for a keyframe (e.g. the first
                    // IDR was lost), ask for another one at most twice a second.
                    val now = SystemClock.uptimeMillis()
                    if (waitingForKeyframe && now - lastKeyframeRequestMs > 500) {
                        lastKeyframeRequestMs = now
                        Log.w(TAG, "Still waiting for a keyframe; requesting a new one")
                        onNeedKeyframe?.invoke()
                    }
                    continue
                }

                decode(packet)
            }
            Log.i(TAG, "Decode thread stopped")
        }, "dt-decoder").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    private fun joinDecodeThread() {
        val thread = decodeThread ?: return
        decodeThread = null
        thread.interrupt()
        try {
            thread.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Decode a single video packet.
     */
    private fun decode(packet: VideoPacket) {
        val currentSurface = surface ?: run {
            Log.w(TAG, "No surface available to decode to")
            return
        }

        if (waitingForKeyframe) {
            if (!packet.isKeyframe) {
                // Drop: without the IDR, reference frames cannot be decoded,
                // and replaying them later would only delay the keyframe.
                return
            }
            Log.i(TAG, "Got keyframe seq=${packet.sequence} (${packet.data.size}B), attempting to extract CSD")

            // Try to extract SPS and PPS from the keyframe
            val (extractedSps, extractedPps) = extractH264Csd(packet.data)

            if (extractedSps != null && extractedPps != null) {
                if (csd0 != extractedSps || csd1 != extractedPps) {
                    Log.i(TAG, "Extracted new SPS/PPS. Reconfiguring decoder.")
                    // Reconfigure the decoder with the new CSD
                    // Stop and release existing codec first if it exists
                    try {
                        codec?.stop()
                        codec?.release()
                        codec = null
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to stop/release old codec during reconfig", e)
                    }
                    configure(width, height, currentSurface, extractedSps, extractedPps)
                } else {
                    Log.i(TAG, "SPS/PPS are the same. No reconfiguration needed.")
                    waitingForKeyframe = false
                }

            } else {
                Log.w(TAG, "Could not extract SPS/PPS from keyframe seq=${packet.sequence}. Will retry with next keyframe.")
                return
            }
        }

        queueAndDrain(packet)
    }

    private fun queueAndDrain(packet: VideoPacket) {
        val codec = codec ?: return

        try {
            // Get input buffer
            val inputIndex = codec.dequeueInputBuffer(10000) // 10ms timeout
            if (inputIndex < 0) {
                Log.w(TAG, "No input buffer available for seq=${packet.sequence}")
                return
            }

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(packet.data)

            val flags = if (packet.isKeyframe) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }

            codec.queueInputBuffer(
                inputIndex,
                0,
                packet.data.size,
                packet.timestamp,
                flags
            )

            // Remember when this frame arrived so the latency stat can be
            // attributed to the frame that is actually presented.
            if (pendingReceipts.size > 64) {
                pendingReceipts.clear()
            }
            pendingReceipts[packet.timestamp] = packet.receivedAtMs

            renderLatestOutput()
        } catch (e: Exception) {
            Log.e(TAG, "Decode error at seq=${packet.sequence}", e)
            waitingForKeyframe = true
            onNeedKeyframe?.invoke()
        }
    }

    /**
     * Drain decoded output and display only the newest frame.
     *
     * Releasing every output buffer would let stale frames queue up in
     * SurfaceFlinger; instead the older ones are dropped without rendering and
     * the newest is submitted immediately (this is what Moonlight's
     * low-latency render paths do). Submitting a presentation timestamp lets a
     * newer frame replace this one within the same vsync period.
     */
    private fun renderLatestOutput() {
        val codec = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        var latestIndex = -1
        var latestPtsUs = 0L

        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (outputIndex >= 0) {
                if (latestIndex >= 0) {
                    // Drop the previously seen (older) frame without displaying it.
                    codec.releaseOutputBuffer(latestIndex, false)
                    pendingReceipts.remove(latestPtsUs)
                }
                latestIndex = outputIndex
                latestPtsUs = bufferInfo.presentationTimeUs
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Output format changed: ${codec.outputFormat}")
            }
            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }

        if (latestIndex < 0) return

        codec.releaseOutputBuffer(latestIndex, System.nanoTime())

        framesRendered++
        fpsFrameCount++

        // Update FPS once per second
        val now = System.nanoTime()
        if (now - lastFpsTime >= 1_000_000_000L) {
            _fps.value = fpsFrameCount
            fpsFrameCount = 0
            lastFpsTime = now
        }

        // Latency of the frame we actually presented (receipt -> submit).
        pendingReceipts.remove(latestPtsUs)?.let { receivedAtMs ->
            val ageMs = SystemClock.uptimeMillis() - receivedAtMs
            if (ageMs in 0..1000) {
                _decodeLatencyMs.value = ageMs.toInt()
            }
        }

        if (framesRendered <= 5 || framesRendered % 120 == 0) {
            Log.i(TAG, "Rendered frame #$framesRendered pts=$latestPtsUs fps=${_fps.value}")
        }
    }

    /**
     * Stop decoding and release resources.
     */
    fun stop() {
        joinDecodeThread()
        pendingReceipts.clear()

        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed", e)
        }
        codec = null
        isConfigured = false
    }

    fun close() {
        stop()
    }
}

/**
 * Extracts H.264 SPS and PPS NAL units from a byte array containing H.264 byte-stream data.
 * Assumes NAL units are prefixed with start codes (0x00000001 or 0x000001).
 * Returns a Pair<ByteBuffer, ByteBuffer> where first is SPS (CSD-0) and second is PPS (CSD-1).
 */
fun extractH264Csd(data: ByteArray): Pair<ByteBuffer?, ByteBuffer?> {
    var sps: ByteBuffer? = null
    var pps: ByteBuffer? = null

    var i = 0
    while (i < data.size - 4) {
        // Look for NAL unit start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
        if (data[i].toInt() == 0x00 && data[i + 1].toInt() == 0x00) {
            var nalStart = -1
            if (data[i + 2].toInt() == 0x01) {
                nalStart = i + 3
            } else if (data[i + 2].toInt() == 0x00 && data[i + 3].toInt() == 0x01) {
                nalStart = i + 4
            }

            if (nalStart != -1 && nalStart < data.size) {
                val nalType = data[nalStart].toInt() and 0x1F

                // Find the end of the current NAL unit (next start code or end of data)
                var nalEnd = data.size
                for (j in nalStart until data.size - 3) {
                    if (data[j].toInt() == 0x00 && data[j + 1].toInt() == 0x00 && data[j + 2].toInt() == 0x01) {
                        nalEnd = j
                        break
                    }
                    if (data[j].toInt() == 0x00 && data[j + 1].toInt() == 0x00 && data[j + 2].toInt() == 0x00 && data[j + 3].toInt() == 0x01) {
                        nalEnd = j
                        break
                    }
                }

                val nalUnit = ByteBuffer.wrap(data, nalStart, nalEnd - nalStart)

                when (nalType) {
                    7 -> sps = nalUnit // SPS
                    8 -> pps = nalUnit // PPS
                }

                if (sps != null && pps != null) {
                    return Pair(sps, pps)
                }
                i = nalEnd // Move to the next NAL unit
                continue
            }
        }
        i++
    }
    return Pair(sps, pps)
}
