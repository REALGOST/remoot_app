package com.remote.streamer.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.remote.streamer.MainActivity
import com.remote.streamer.accessibility.RemoteAccessibilityService
import com.remote.streamer.network.NetworkManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Production-ready Streaming Foreground Service targeting Android 14 (API 34).
 * 
 * Handles:
 * 1. Android 14 Foreground Service lifecycle with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 * 2. MediaProjection + MediaCodec H.264 hardware encoding for Phone -> PC streaming
 * 3. Audio capture via AudioPlaybackCapture (Internal Audio) & Mic
 * 4. MediaCodec H.264 hardware decoding for PC -> Phone rendering to SurfaceView
 * 5. Incoming PC touch events routed to RemoteAccessibilityService or ADB fallback
 */
class StreamingService : Service() {

    companion object {
        private const val TAG = "StreamingService"
        const val CHANNEL_ID = "streamer_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVER = "com.remote.streamer.ACTION_START_SERVER"
        const val ACTION_START_CLIENT = "com.remote.streamer.ACTION_START_CLIENT"
        const val ACTION_STOP = "com.remote.streamer.ACTION_STOP"
        const val ACTION_SET_DECODER_SURFACE = "com.remote.streamer.ACTION_SET_DECODER_SURFACE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_HOST_IP = "extra_host_ip"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"

        var activeSurface: Surface? = null
        var networkManagerInstance: NetworkManager? = null
            private set
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // MediaProjection & Video Encoder (Phone to PC)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var isEncoderRunning = false

    // Video Decoder (PC to Phone)
    private var videoDecoder: MediaCodec? = null
    private var isDecoderConfigured = false

    // Audio Capture
    private var audioRecord: AudioRecord? = null
    private var isAudioRecording = false

    private lateinit var networkManager: NetworkManager

    override fun onCreate() {
        super.onCreate()
        networkManager = NetworkManager(this)
        networkManagerInstance = networkManager

        acquireLocks()
        createNotificationChannel()
        listenToIncomingControlEvents()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                startForegroundWithMediaProjectionNotification("Streaming Android Screen to PC (Server Role)")
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val width = intent.getIntExtra(EXTRA_WIDTH, 1080)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1920)
                val dpi = intent.getIntExtra(EXTRA_DPI, 400)
                val port = intent.getIntExtra(EXTRA_PORT, 9999)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    initServerRole(resultCode, resultData, width, height, dpi, port)
                } else {
                    Log.e(TAG, "Invalid MediaProjection token or user denied permission")
                    stopSelf()
                }
            }
            ACTION_START_CLIENT -> {
                startForegroundWithConnectedDeviceNotification("Receiving PC Stream (Client Role)")
                val hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: "192.168.1.100"
                val port = intent.getIntExtra(EXTRA_PORT, 9999)
                initClientRole(hostIp, port)
            }
            ACTION_SET_DECODER_SURFACE -> {
                activeSurface?.let { surface ->
                    setupHardwareDecoder(surface)
                }
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithMediaProjectionNotification(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundWithConnectedDeviceNotification(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Streamer Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen & Input Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low-latency bidirectional PC streaming session"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteStreamer:WakeLock").apply {
                acquire(12 * 60 * 60 * 1000L)
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteStreamer:WifiLock").apply {
                acquire()
            }
            Log.d(TAG, "WakeLock & High-Performance WifiLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire system locks: ${e.message}")
        }
    }

    // ==========================================
    // 1. SERVER ROLE: Phone Screen -> PC App
    // ==========================================
    private fun initServerRole(resultCode: Int, resultData: Intent, width: Int, height: Int, dpi: Int, port: Int) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        // Android 14 API 34 Requirement: Callback MUST be registered before creating VirtualDisplay
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection session terminated by system")
                stopStreaming()
            }
        }, Handler(Looper.getMainLooper()))

        // Start Low-latency Network Server socket
        networkManager.startServer(port)

        // Setup Hardware H.264 Video Encoder
        setupHardwareEncoder(width, height)

        // Create VirtualDisplay capturing into Encoder's Surface
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenStreamCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null, null
        )

        // Start Audio Capture loop
        startAudioCapture()
    }

    private fun setupHardwareEncoder(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000) // 6 Mbps
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s keyframe interval for fast recovery
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = videoEncoder?.createInputSurface()
        videoEncoder?.start()
        isEncoderRunning = true

        serviceScope.launch(Dispatchers.IO) {
            drainEncoder()
        }
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isEncoderRunning && videoEncoder != null) {
            try {
                val outputBufferIndex = videoEncoder!!.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = videoEncoder!!.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)

                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                        networkManager.sendVideoFrame(chunk, isKeyframe)
                    }
                    videoEncoder?.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encoder drain error: ${e.message}")
                break
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        serviceScope.launch(Dispatchers.IO) {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                    val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build()

                    audioRecord = AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setAudioPlaybackCaptureConfig(playbackConfig)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate, channelConfig, audioFormat, bufferSize
                    )
                }

                audioRecord?.startRecording()
                isAudioRecording = true
                val audioBuffer = ByteArray(bufferSize)

                while (isAudioRecording && isActive) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        val payload = audioBuffer.copyOf(readBytes)
                        networkManager.sendAudioFrame(payload)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture error: ${e.message}")
            }
        }
    }

    // ==========================================
    // 2. CLIENT ROLE: PC Desktop Stream -> Phone Screen
    // ==========================================
    private fun initClientRole(hostIp: String, port: Int) {
        networkManager.startClient(hostIp, port)
        serviceScope.launch(Dispatchers.IO) {
            networkManager.incomingVideoFrames.collect { nalPacket ->
                feedDecoder(nalPacket)
            }
        }
    }

    fun setupHardwareDecoder(surface: Surface) {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1)
            }

            videoDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoDecoder?.configure(format, surface, null, 0)
            videoDecoder?.start()
            isDecoderConfigured = true
            Log.d(TAG, "Hardware H.264 video decoder successfully bound to SurfaceView")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video decoder: ${e.message}", e)
        }
    }

    private fun feedDecoder(nalData: ByteArray) {
        if (!isDecoderConfigured || videoDecoder == null) return
        try {
            val inputIndex = videoDecoder!!.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = videoDecoder!!.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(nalData)
                val presentationTimeUs = System.nanoTime() / 1000
                videoDecoder!!.queueInputBuffer(inputIndex, 0, nalData.size, presentationTimeUs, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = videoDecoder!!.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                videoDecoder!!.releaseOutputBuffer(outputIndex, true)
                outputIndex = videoDecoder!!.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decoder feed error: ${e.message}")
        }
    }

    // ==========================================
    // 3. REMOTE TOUCH DISPATCH (When Phone is Server)
    // ==========================================
    private fun listenToIncomingControlEvents() {
        serviceScope.launch(Dispatchers.Default) {
            networkManager.incomingTouchEvents.collect { touch ->
                val injected = RemoteAccessibilityService.instance?.simulateTouch(
                    action = touch.action,
                    normalizedX = touch.normalizedX,
                    normalizedY = touch.normalizedY
                ) ?: false

                if (!injected) {
                    dispatchAdbTouchFallback(touch.action, touch.normalizedX, touch.normalizedY)
                }
            }
        }
    }

    private fun dispatchAdbTouchFallback(action: Byte, normX: Float, normY: Float) {
        try {
            val displayMetrics = resources.displayMetrics
            val pixelX = (normX * displayMetrics.widthPixels).toInt()
            val pixelY = (normY * displayMetrics.heightPixels).toInt()

            if (action == 0.toByte()) {
                Runtime.getRuntime().exec(arrayOf("input", "tap", "$pixelX", "$pixelY"))
            }
        } catch (e: Exception) {}
    }

    private fun stopStreaming() {
        isEncoderRunning = false
        isAudioRecording = false
        isDecoderConfigured = false

        try {
            virtualDisplay?.release()
            mediaProjection?.stop()
            videoEncoder?.stop()
            videoEncoder?.release()
            encoderInputSurface?.release()
            videoDecoder?.stop()
            videoDecoder?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Clean-up warning: ${e.message}")
        } finally {
            virtualDisplay = null
            mediaProjection = null
            videoEncoder = null
            encoderInputSurface = null
            videoDecoder = null
            audioRecord = null
            networkManager.stopConnection()
        }
    }

    override fun onDestroy() {
        stopStreaming()
        networkManager.release()
        serviceScope.cancel()

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {}
        super.onDestroy()
    }
}