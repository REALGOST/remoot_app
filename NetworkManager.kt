package com.remote.streamer.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.remote.streamer.protocol.StreamProtocol
import com.remote.streamer.protocol.TouchData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-ready low-latency NetworkManager for Android API 34.
 * Features:
 * - UDP Beacon Broadcasting & Discovery for zero-config PC pairing
 * - Wi-Fi MulticastLock handling (essential on modern Android)
 * - Bidirectional Low-Latency TCP/UDP streaming socket pipelines
 * - Dynamic role switching: Client (connects to PC) or Server (listens for PC)
 * - Framing and demultiplexing of H.264 video NAL units, audio, touch, and heartbeats
 * - RTT / Latency telemetry calculation
 */
class NetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "NetworkManager"
        const val BROADCAST_INTERVAL_MS = 1500L
        const val SOCKET_TIMEOUT_MS = 6000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null

    sealed class ConnectionState {
        object Idle : ConnectionState()
        data class Discovering(val message: String) : ConnectionState()
        data class Discovered(val pcName: String, val ip: String, val port: Int) : ConnectionState()
        data class Connecting(val endpoint: String) : ConnectionState()
        data class Connected(val remoteAddress: String, val isServer: Boolean) : ConnectionState()
        data class Error(val reason: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps

    private val _bitrateKbps = MutableStateFlow(0L)
    val bitrateKbps: StateFlow<Long> = _bitrateKbps

    private val _incomingVideoFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingVideoFrames: SharedFlow<ByteArray> = _incomingVideoFrames

    private val _incomingAudioFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingAudioFrames: SharedFlow<ByteArray> = _incomingAudioFrames

    private val _incomingTouchEvents = MutableSharedFlow<TouchData>(extraBufferCapacity = 128)
    val incomingTouchEvents: SharedFlow<TouchData> = _incomingTouchEvents

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outStream: DataOutputStream? = null
    private var inStream: DataInputStream? = null
    private var udpDiscoverySocket: DatagramSocket? = null

    private val packetSequence = AtomicInteger(0)
    private var isRunning = false
    private var bytesTransferredSinceLastCheck = 0L
    private var framesReceivedSinceLastCheck = 0

    init {
        acquireMulticastLock()
        startMetricsLoop()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("RemoteStreamerMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Acquired Wi-Fi Multicast Lock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    fun startDiscovery(role: String, onDiscovered: (pcName: String, ip: String, port: Int) -> Unit) {
        scope.launch {
            _connectionState.value = ConnectionState.Discovering("Broadcasting on UDP :${StreamProtocol.DEFAULT_DISCOVERY_PORT}...")
            try {
                udpDiscoverySocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 2000
                }

                val broadcastPayload = "DISCOVER_PC_SERVER:$role:${StreamProtocol.DEFAULT_STREAM_PORT}".toByteArray()
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(
                    broadcastPayload,
                    broadcastPayload.size,
                    broadcastAddr,
                    StreamProtocol.DEFAULT_DISCOVERY_PORT
                )

                val receiveJob = launch {
                    val buffer = ByteArray(1024)
                    while (isActive) {
                        try {
                            val receivePacket = DatagramPacket(buffer, buffer.size)
                            udpDiscoverySocket?.receive(receivePacket)
                            val message = String(receivePacket.data, 0, receivePacket.length)
                            Log.d(TAG, "UDP response received: $message from ${receivePacket.address}")

                            if (message.startsWith("PC_SERVER_OFFER")) {
                                val parts = message.split(":")
                                val pcName = if (parts.size > 1) parts[1] else "PC Host"
                                val port = if (parts.size > 2) parts[2].toIntOrNull() ?: StreamProtocol.DEFAULT_STREAM_PORT else StreamProtocol.DEFAULT_STREAM_PORT
                                val pcIp = receivePacket.address.hostAddress ?: "192.168.1.100"

                                withContext(Dispatchers.Main) {
                                    _connectionState.value = ConnectionState.Discovered(pcName, pcIp, port)
                                    onDiscovered(pcName, pcIp, port)
                                }
                                break
                            }
                        } catch (e: SocketTimeoutException) {
                        } catch (e: Exception) {
                            break
                        }
                    }
                }

                while (isActive && _connectionState.value is ConnectionState.Discovering) {
                    udpDiscoverySocket?.send(sendPacket)
                    delay(BROADCAST_INTERVAL_MS)
                }
                receiveJob.cancelAndJoin()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Discovery failed: ${e.message}")
            }
        }
    }

    fun startServer(port: Int = StreamProtocol.DEFAULT_STREAM_PORT) {
        scope.launch {
            try {
                stopConnection()
                isRunning = true
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                _connectionState.value = ConnectionState.Connecting("Listening on port $port for PC...")

                val socket = serverSocket!!.accept()
                clientSocket = socket
                setupSocketStreams(socket, isServer = true)
            } catch (e: Exception) {
                if (isRunning) {
                    _connectionState.value = ConnectionState.Error("Server error: ${e.message}")
                }
            }
        }
    }

    fun startClient(hostIp: String, port: Int = StreamProtocol.DEFAULT_STREAM_PORT) {
        scope.launch {
            try {
                stopConnection()
                isRunning = true
                _connectionState.value = ConnectionState.Connecting("Connecting to PC at $hostIp:$port...")

                val socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                socket.connect(InetSocketAddress(hostIp, port), SOCKET_TIMEOUT_MS)

                clientSocket = socket
                setupSocketStreams(socket, isServer = false)
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Failed to connect: ${e.message}")
            }
        }
    }

    private fun setupSocketStreams(socket: Socket, isServer: Boolean) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 512 * 1024
            socket.receiveBufferSize = 512 * 1024

            outStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            inStream = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))

            val remoteAddr = socket.inetAddress?.hostAddress ?: "Unknown"
            _connectionState.value = ConnectionState.Connected(remoteAddr, isServer)

            scope.launch { readLoop() }
            scope.launch { heartbeatLoop() }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Stream setup error: ${e.message}")
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val headerBuffer = ByteArray(StreamProtocol.HEADER_SIZE)
        try {
            while (isActive && isRunning) {
                val input = inStream ?: break
                input.readFully(headerBuffer)
                bytesTransferredSinceLastCheck += StreamProtocol.HEADER_SIZE

                val header = StreamProtocol.unpackHeader(headerBuffer) ?: continue
                val payload = ByteArray(header.payloadLength)
                if (header.payloadLength > 0) {
                    input.readFully(payload)
                    bytesTransferredSinceLastCheck += header.payloadLength
                }

                when (header.type) {
                    StreamProtocol.TYPE_VIDEO_FRAME -> {
                        framesReceivedSinceLastCheck++
                        _incomingVideoFrames.emit(payload)
                    }
                    StreamProtocol.TYPE_AUDIO_FRAME -> {
                        _incomingAudioFrames.emit(payload)
                    }
                    StreamProtocol.TYPE_TOUCH_EVENT -> {
                        val touch = StreamProtocol.unpackTouchEvent(payload)
                        if (touch != null) {
                            _incomingTouchEvents.emit(touch)
                        }
                    }
                    StreamProtocol.TYPE_HEARTBEAT -> {
                        val now = System.currentTimeMillis()
                        val rtt = now - header.timestampMs
                        if (rtt in 0..2000) {
                            _latencyMs.value = rtt
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                _connectionState.value = ConnectionState.Error("Disconnected: ${e.message}")
            }
        }
    }

    fun sendVideoFrame(nalUnit: ByteArray, isKeyFrame: Boolean) {
        val flags = if (isKeyFrame) StreamProtocol.FLAG_KEYFRAME else 0
        sendPacket(StreamProtocol.TYPE_VIDEO_FRAME, flags.toByte(), nalUnit)
    }

    fun sendAudioFrame(audioBuffer: ByteArray) {
        sendPacket(StreamProtocol.TYPE_AUDIO_FRAME, 0, audioBuffer)
    }

    fun sendTouchEvent(
        action: Byte,
        pointerId: Byte,
        normalizedX: Float,
        normalizedY: Float,
        pressure: Float = 1.0f
    ) {
        val payload = StreamProtocol.packTouchEvent(action, pointerId, normalizedX, normalizedY, pressure)
        sendPacket(StreamProtocol.TYPE_TOUCH_EVENT, 0, payload)
    }

    @Synchronized
    private fun sendPacket(type: Byte, flags: Byte, payload: ByteArray) {
        if (!isRunning || outStream == null) return
        try {
            val seq = packetSequence.incrementAndGet()
            val packedBytes = StreamProtocol.pack(
                type = type,
                flags = flags,
                sequence = seq,
                timestampMs = System.currentTimeMillis(),
                payload = payload
            )
            outStream?.write(packedBytes)
            outStream?.flush()
            bytesTransferredSinceLastCheck += packedBytes.size
        } catch (e: Exception) {
            Log.e(TAG, "Send packet failed: ${e.message}")
        }
    }

    private suspend fun heartbeatLoop() {
        while (isActive && isRunning) {
            delay(1000)
            sendPacket(StreamProtocol.TYPE_HEARTBEAT, 0, ByteArray(0))
        }
    }

    private fun startMetricsLoop() {
        scope.launch {
            while (isActive) {
                delay(1000)
                _fps.value = framesReceivedSinceLastCheck
                _bitrateKbps.value = (bytesTransferredSinceLastCheck * 8) / 1024
                framesReceivedSinceLastCheck = 0
                bytesTransferredSinceLastCheck = 0
            }
        }
    }

    fun stopConnection() {
        isRunning = false
        try {
            outStream?.close()
            inStream?.close()
            clientSocket?.close()
            serverSocket?.close()
            udpDiscoverySocket?.close()
        } catch (e: Exception) {
        } finally {
            outStream = null
            inStream = null
            clientSocket = null
            serverSocket = null
            udpDiscoverySocket = null
            _connectionState.value = ConnectionState.Idle
        }
    }

    fun release() {
        stopConnection()
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {}
        scope.cancel()
    }
}