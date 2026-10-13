package com.drawingtablet.network

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "NetworkClient"
private const val HEARTBEAT_TIMEOUT_MS = 5000L
private const val TCP_CONNECT_TIMEOUT_MS = 5000
private const val TCP_CONNECT_ATTEMPT_TIMEOUT_MS = 1500
private const val TCP_READ_TIMEOUT_MS = 100

/** Accumulator for a fragmented video frame. */
private class FragmentBuffer(
    val sequence: Int,
    val timestamp: Long,
    val isKeyframe: Boolean,
    val fragmentCount: Int
) {
    val fragments = arrayOfNulls<ByteArray>(fragmentCount)
    var received = 0
}

/**
 * Connection state.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val width: Int, val height: Int) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Network client using TCP for input/control and UDP for video.
 * 
 * Protocol v2:
 * - TCP connection for reliable input events and control packets
 * - UDP socket for receiving low-latency video stream
 */
class NetworkClient(
    private val serverAddress: String,
    private val serverPort: Int = 9867,
    /** Display refresh rate in Hz, reported to the server so it can cap the
     *  stream rate. 0 = unknown / don't cap. */
    private val displayRefreshRateHz: Int = 0
) {
    // TCP for input/control
    private var tcpSocket: Socket? = null
    private var tcpOutput: DataOutputStream? = null
    private var tcpInput: DataInputStream? = null
    private var tcpReceiveJob: Job? = null
    
    // UDP for video
    private var udpSocket: DatagramSocket? = null
    private var udpReceiveJob: Job? = null
    
    private var heartbeatJob: Job? = null
    private var pingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /**
     * Estimated one-way network latency in milliseconds (RTT / 2), measured
     * with the ping round trip. Shown in the stats overlay to split
     * end-to-end latency into network vs. decode.
     */
    private val _networkLatencyMs = MutableStateFlow(0)
    val networkLatencyMs: StateFlow<Int> = _networkLatencyMs

    /** Send time of the outstanding ping (µs), or 0 when none is pending. */
    @Volatile
    private var pingSentAtUs = 0L

    /**
     * Estimated `client_clock - server_clock` offset in microseconds, derived
     * from ping round trips. Shared with the decoder so it can turn a frame's
     * server capture timestamp into a true capture -> display latency.
     */
    val serverClockOffsetUs = AtomicLong(0)

    /** Recent offset estimates; the smallest (least-delayed) one is used. */
    private val offsetSamples = ArrayDeque<Long>(8)

    /** Video payload bytes received since the last report (for bitrate). */
    private val videoBytesSinceReport = AtomicLong(0)

    /** Completed frames received / dropped since the last report. */
    private val framesReceived = AtomicLong(0)
    private val queueDrops = AtomicLong(0)

    /** Received video bitrate in Mbps, updated once per second. */
    private val _bitrateMbps = MutableStateFlow(0f)
    val bitrateMbps: StateFlow<Float> = _bitrateMbps

    /**
     * Queue of reassembled frames awaiting decode. Bounded, oldest dropped:
     * the decoder should always work on the freshest frame.
     */
    private val _videoPackets = LinkedBlockingQueue<VideoPacket>(2)
    val videoPackets: LinkedBlockingQueue<VideoPacket> = _videoPackets

    /** Pending fragment buffers keyed by sequence number. */
    private val fragmentBuffers = HashMap<Int, FragmentBuffer>()

    /** Sequence number of the last fully assembled frame, or -1 if none yet. */
    private var lastCompletedSequence = -1

    /** Rate-limits keyframe requests triggered by loss detection. */
    private var lastKeyframeRequestMs = 0L

    /**
     * Dedicated writer for input packets.
     *
     * Input events are queued and written immediately on this thread instead
     * of spawning a coroutine per event (which adds scheduling latency and GC
     * pressure at stylus rates of several hundred events per second).
     */
    private val inputQueue = ArrayBlockingQueue<ByteArray>(1024)
    private var inputWriterThread: Thread? = null

    /** Timestamp of the last packet received from the server. */
    @Volatile
    private var lastActivityTime = 0L

    // Lock for TCP write operations
    private val tcpWriteLock = Any()

    /**
     * Connect to the server.
     */
    suspend fun connect() {
        if (_connectionState.value is ConnectionState.Connected) {
            return
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            withContext(Dispatchers.IO) {
                // 1. Create UDP socket first to know the local port.
                //    Receive buffer must be enlarged *before* binding; the
                //    kernel clamps it to net.core.rmem_max.
                udpSocket = DatagramSocket(null).apply {
                    soTimeout = 100 // Short timeout for non-blocking reads
                    try {
                        receiveBufferSize = 2 * 1024 * 1024
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enlarge UDP receive buffer", e)
                    }
                    bind(InetSocketAddress(0))
                }
                val udpPort = udpSocket!!.localPort
                Log.i(TAG, "UDP socket bound to port $udpPort")

                // 2. Connect TCP. Resolve every address and try them in order,
                //    IPv4 first: a host name can map to several addresses (the
                //    mDNS host is advertised on every interface, including
                //    unreachable bridges and IPv6 link-local) and the server
                //    listens on IPv4.
                val candidates = InetAddress.getAllByName(serverAddress)
                    .sortedByDescending { it is Inet4Address }
                Log.i(TAG, "TCP candidates: ${candidates.map { it.hostAddress }}")

                var lastError: Exception? = null
                var socket: Socket? = null
                for (address in candidates) {
                    try {
                        socket = Socket().apply {
                            soTimeout = TCP_READ_TIMEOUT_MS
                            tcpNoDelay = true // Disable Nagle's algorithm for lower latency
                            connect(
                                InetSocketAddress(address, serverPort),
                                TCP_CONNECT_ATTEMPT_TIMEOUT_MS
                            )
                        }
                        Log.i(TAG, "TCP connected to ${address.hostAddress}:$serverPort")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "TCP connect to ${address.hostAddress} failed: ${e.message}")
                        lastError = e
                    }
                }

                tcpSocket = socket ?: throw (lastError
                    ?: IllegalStateException("No address to connect to"))
                tcpOutput = DataOutputStream(tcpSocket!!.getOutputStream())
                tcpInput = DataInputStream(tcpSocket!!.getInputStream())

                // 3. Send Connect packet over TCP
                val connectPacket = ProtocolCodec.encodeControl(
                    ControlPacket.Connect(PROTOCOL_VERSION)
                )
                sendTcp(connectPacket)

                // 4. Wait for ConnectAck. Any other control packet that
                //    arrives first (e.g. a heartbeat from a server that does
                //    not gate them on the handshake) is ignored rather than
                //    treated as a failed connection.
                val ackDeadline = System.currentTimeMillis() + TCP_CONNECT_TIMEOUT_MS
                var handshaked = false
                while (!handshaked && System.currentTimeMillis() < ackDeadline) {
                    val response = try {
                        receiveTcpPacket()
                    } catch (e: SocketTimeoutException) {
                        continue
                    }

                    when (val decoded = ProtocolCodec.decode(response)) {
                        is ControlPacket.ConnectAck -> {
                            Log.i(TAG, "Connected: ${decoded.width}x${decoded.height}")

                            // 5. Tell server our UDP port for video
                            val setPortPacket = ProtocolCodec.encodeControl(
                                ControlPacket.SetUdpPort(udpPort)
                            )
                            sendTcp(setPortPacket)

                            // 6. Report the display refresh rate so the host
                            //    can avoid streaming faster than the panel can
                            //    present.
                            if (displayRefreshRateHz > 0) {
                                Log.i(TAG, "Reporting display refresh rate: ${displayRefreshRateHz}Hz")
                                val refreshPacket = ProtocolCodec.encodeControl(
                                    ControlPacket.SetRefreshRate(displayRefreshRateHz)
                                )
                                sendTcp(refreshPacket)
                            }

                            lastActivityTime = System.currentTimeMillis()
                            _connectionState.value = ConnectionState.Connected(decoded.width, decoded.height)

                            // Start receive loops
                            startTcpReceiveLoop()
                            startUdpReceiveLoop()
                            startHeartbeat()
                            startInputWriter()
                            startPing()
                            handshaked = true
                        }
                        is ControlPacket.ConnectNack -> {
                            Log.e(TAG, "Connection refused: ${decoded.reason}")
                            _connectionState.value = ConnectionState.Error(decoded.reason)
                            cleanup()
                            return@withContext
                        }
                        else -> {
                            Log.i(TAG, "Ignoring packet while awaiting ConnectAck: $decoded")
                        }
                    }
                }

                if (!handshaked) {
                    Log.e(TAG, "Timed out waiting for ConnectAck")
                    _connectionState.value = ConnectionState.Error("Handshake timed out")
                    cleanup()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            cleanup()
        }
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        scope.launch {
            try {
                val disconnectPacket = ProtocolCodec.encodeControl(ControlPacket.Disconnect)
                sendTcp(disconnectPacket)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending disconnect", e)
            }
        }

        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun cleanup() {
        tcpReceiveJob?.cancel()
        udpReceiveJob?.cancel()
        heartbeatJob?.cancel()
        pingJob?.cancel()
        pingSentAtUs = 0
        offsetSamples.clear()
        serverClockOffsetUs.set(0)
        videoBytesSinceReport.set(0)
        framesReceived.set(0)
        queueDrops.set(0)
        _bitrateMbps.value = 0f
        stopInputWriter()
        
        try { tcpOutput?.close() } catch (e: Exception) {}
        try { tcpInput?.close() } catch (e: Exception) {}
        try { tcpSocket?.close() } catch (e: Exception) {}
        try { udpSocket?.close() } catch (e: Exception) {}
        
        tcpSocket = null
        tcpOutput = null
        tcpInput = null
        udpSocket = null
        fragmentBuffers.clear()
        lastCompletedSequence = -1
    }

    /**
     * Send an input event to the server (via TCP).
     *
     * Non-blocking: the packet is encoded on the caller's thread and handed
     * to the dedicated writer thread, so input never waits behind other
     * network I/O and the UI thread never blocks on a stalled socket.
     */
    fun sendInput(event: TimestampedInputEvent) {
        if (_connectionState.value !is ConnectionState.Connected) return

        val packet = InputPacket(
            timestamp = event.timestamp,
            event = event.event
        )
        val data = ProtocolCodec.encodeInput(packet)

        if (!inputQueue.offer(data)) {
            // The writer is backed up (e.g. a WiFi stall). Keep the newest
            // position rather than stalling the UI thread: a stale queue
            // feels worse than a slightly sparse stroke.
            inputQueue.poll()
            inputQueue.offer(data)
        }
    }

    /**
     * Start the dedicated input writer thread (once per session).
     */
    private fun startInputWriter() {
        if (inputWriterThread != null) return

        inputWriterThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val data = inputQueue.take()
                    try {
                        sendTcp(data)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send input", e)
                    }
                }
            } catch (_: InterruptedException) {
                // Session is shutting down.
            }
        }, "dt-input-writer").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    private fun stopInputWriter() {
        inputWriterThread?.interrupt()
        inputWriterThread = null
        inputQueue.clear()
    }

    /**
     * Request a keyframe from the server (via TCP).
     */
    fun requestKeyframe() {
        if (_connectionState.value !is ConnectionState.Connected) return

        scope.launch {
            try {
                val data = ProtocolCodec.encodeControl(ControlPacket.RequestKeyframe)
                sendTcp(data)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request keyframe", e)
            }
        }
    }

    /**
     * Send data over TCP (thread-safe).
     */
    private fun sendTcp(data: ByteArray) {
        synchronized(tcpWriteLock) {
            tcpOutput?.let { out ->
                out.write(data)
                out.flush()
            }
        }
    }

    /**
     * Receive a single framed packet from TCP.
     * Blocking call - reads header then payload.
     */
    private fun receiveTcpPacket(): ByteArray {
        val input = tcpInput ?: throw IllegalStateException("TCP not connected")
        
        // Read header: [type: 1 byte][length: 2 bytes LE]
        val header = ByteArray(3)
        input.readFully(header)
        
        val length = ByteBuffer.wrap(header, 1, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short.toInt() and 0xFFFF
        
        // Read payload
        val payload = ByteArray(length)
        if (length > 0) {
            input.readFully(payload)
        }
        
        // Return full packet (header + payload)
        return header + payload
    }

    /**
     * Start TCP receive loop for control packets (heartbeats, etc.)
     */
    private fun startTcpReceiveLoop() {
        tcpReceiveJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                try {
                    val data = receiveTcpPacket()
                    lastActivityTime = System.currentTimeMillis()

                    when (val decoded = ProtocolCodec.decode(data)) {
                        is ControlPacket.Heartbeat -> {
                            // Server is alive
                            Log.d(TAG, "TCP heartbeat received")
                        }
                        is ControlPacket.Ping -> {
                            // Echo of our ping (older server without Pong):
                            // still good enough for the round-trip estimate.
                            val sentAt = pingSentAtUs
                            if (sentAt > 0) {
                                pingSentAtUs = 0
                                val rttUs = SystemClock.elapsedRealtimeNanos() / 1000 - sentAt
                                if (rttUs in 0..1_000_000) {
                                    _networkLatencyMs.value = (rttUs / 2 / 1000).toInt()
                                }
                            }
                        }
                        is ControlPacket.Pong -> {
                            // Our ping echoed with the server's clock.
                            val sentAt = pingSentAtUs
                            if (sentAt > 0) {
                                pingSentAtUs = 0
                                val receivedAt = SystemClock.elapsedRealtimeNanos() / 1000
                                val rttUs = receivedAt - sentAt
                                if (rttUs in 0..1_000_000) {
                                    _networkLatencyMs.value = (rttUs / 2 / 1000).toInt()
                                    // Assumes both directions are roughly
                                    // symmetric so one-way ~= rtt / 2.
                                    addOffsetSample(receivedAt - decoded.serverUs - rttUs / 2)
                                }
                            }
                        }
                        else -> {
                            Log.d(TAG, "TCP received: $decoded")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal - check for overall timeout
                    if (lastActivityTime > 0 &&
                        System.currentTimeMillis() - lastActivityTime > HEARTBEAT_TIMEOUT_MS
                    ) {
                        Log.w(TAG, "Connection timed out – no activity for ${HEARTBEAT_TIMEOUT_MS}ms")
                        _connectionState.value = ConnectionState.Error("Connection lost")
                        break
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "TCP receive error", e)
                        _connectionState.value = ConnectionState.Error("Connection lost: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    /**
     * Start UDP receive loop for video packets.
     */
    private fun startUdpReceiveLoop() {
        udpReceiveJob = scope.launch {
            val buffer = ByteArray(65536)
            
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    
                    val data = buffer.copyOf(packet.length)
                    lastActivityTime = System.currentTimeMillis()

                    when (val decoded = ProtocolCodec.decode(data)) {
                        is VideoPacket -> {
                            // No per-fragment logging here: at 100 fps a frame
                            // is up to ~200 datagrams and logcat formatting
                            // would dominate the receive loop.
                            decoded.receivedAtMs = SystemClock.uptimeMillis()
                            val complete = assembleFragment(decoded)
                            if (complete != null) {
                                videoBytesSinceReport.addAndGet(complete.data.size.toLong())
                                framesReceived.incrementAndGet()
                                if (!_videoPackets.offer(complete)) {
                                    // Decoder is behind: drop the oldest frame
                                    // so we always decode the newest one.
                                    _videoPackets.poll()
                                    _videoPackets.offer(complete)
                                    queueDrops.incrementAndGet()
                                }
                            }
                        }
                        else -> {
                            // Ignore non-video packets on UDP
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "UDP receive error", e)
                    }
                }
            }
        }
    }

    /**
     * Start heartbeat sender (via TCP).
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                try {
                    val data = ProtocolCodec.encodeControl(ControlPacket.Heartbeat)
                    sendTcp(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed", e)
                }
                delay(1000)
            }
        }
    }

    /**
     * Start the latency probe: one ping per second, replied to with a Pong
     * carrying the server clock, so the UI can show the network share and the
     * end-to-end frame latency. Also reports the received bitrate.
     */
    private fun startPing() {
        pingJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                try {
                    pingSentAtUs = SystemClock.elapsedRealtimeNanos() / 1000
                    val data = ProtocolCodec.encodeControl(ControlPacket.Ping)
                    sendTcp(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Ping failed", e)
                }
                delay(1000)
                reportStats()
            }
        }
    }

    /** Print the once-per-second network stats: latency, bitrate, drops. */
    private fun reportStats() {
        val bytes = videoBytesSinceReport.getAndSet(0)
        val mbps = bytes * 8.0 / 1_000_000.0
        _bitrateMbps.value = mbps.toFloat()
        val rx = framesReceived.getAndSet(0)
        val drops = queueDrops.getAndSet(0)
        Log.i(
            TAG,
            "net ${_networkLatencyMs.value}ms, bitrate ${"%.1f".format(mbps)}Mbps, " +
                "rx ${rx}fps, queueDrop $drops"
        )
    }

    /**
     * Keep the smallest recent offset estimate: the least-delayed round trip
     * yields the most accurate clock offset.
     */
    private fun addOffsetSample(offsetUs: Long) {
        offsetSamples.addLast(offsetUs)
        if (offsetSamples.size > 5) offsetSamples.removeFirst()
        serverClockOffsetUs.set(offsetSamples.minOrNull() ?: 0L)
    }

    /**
     * Buffer a fragment and return a fully-reassembled VideoPacket once all
     * fragments for a given sequence number have arrived, or null if still
     * waiting.  Single-packet frames (fragmentCount == 1) pass through
     * immediately.
     */
    private fun assembleFragment(fragment: VideoPacket): VideoPacket? {
        if (fragment.fragmentCount == 1) {
            noteCompletedSequence(fragment.sequence)
            return fragment
        }

        val buf = fragmentBuffers.getOrPut(fragment.sequence) {
            FragmentBuffer(
                sequence = fragment.sequence,
                timestamp = fragment.timestamp,
                isKeyframe = fragment.isKeyframe,
                fragmentCount = fragment.fragmentCount
            )
        }

        // Guard: skip if index is out of range
        if (fragment.fragmentIndex < 0 || fragment.fragmentIndex >= buf.fragmentCount) {
            Log.w(TAG, "Bad fragment index ${fragment.fragmentIndex} for count ${buf.fragmentCount}")
            return null
        }

        // Duplicate fragment – ignore
        if (buf.fragments[fragment.fragmentIndex] != null) return null

        buf.fragments[fragment.fragmentIndex] = fragment.data
        buf.received++

        if (buf.received < buf.fragmentCount) {
            // Evict stale incomplete buffers (keep at most 3 pending frames)
            while (fragmentBuffers.size > 3) {
                val oldest = fragmentBuffers.keys.minOrNull() ?: break
                if (oldest != fragment.sequence) {
                    Log.d(TAG, "evicting incomplete seq=$oldest")
                    fragmentBuffers.remove(oldest)
                    // The decoder will be missing a reference frame, so ask
                    // the server for a fresh keyframe instead of waiting for
                    // the next periodic one.
                    requestKeyframeThrottled()
                } else {
                    break
                }
            }
            return null
        }

        // All fragments present – concatenate in order
        fragmentBuffers.remove(fragment.sequence)
        val totalSize = buf.fragments.sumOf { it!!.size }
        val assembled = ByteArray(totalSize)
        var offset = 0
        for (piece in buf.fragments) {
            System.arraycopy(piece!!, 0, assembled, offset, piece.size)
            offset += piece.size
        }

        noteCompletedSequence(buf.sequence)

        return VideoPacket(
            sequence = buf.sequence,
            timestamp = buf.timestamp,
            isKeyframe = buf.isKeyframe,
            fragmentIndex = 0,
            fragmentCount = 1,
            data = assembled,
            receivedAtMs = fragment.receivedAtMs
        )
    }

    /**
     * Track completed frame sequences and ask for a recovery keyframe when a
     * gap is detected (Moonlight-style IDR request on loss).
     */
    private fun noteCompletedSequence(sequence: Int) {
        if (lastCompletedSequence >= 0 && sequence != lastCompletedSequence + 1) {
            Log.d(TAG, "Frame gap: expected ${lastCompletedSequence + 1}, got $sequence")
            requestKeyframeThrottled()
        }
        lastCompletedSequence = sequence
    }

    /** Ask the server for a keyframe, at most once every 100 ms. */
    private fun requestKeyframeThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestMs < 100) return
        lastKeyframeRequestMs = now
        requestKeyframe()
    }

    fun close() {
        disconnect()
        scope.cancel()
    }
}
