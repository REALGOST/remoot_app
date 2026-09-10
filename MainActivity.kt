package com.remote.streamer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.remote.streamer.accessibility.RemoteAccessibilityService
import com.remote.streamer.network.NetworkManager
import com.remote.streamer.protocol.StreamProtocol
import com.remote.streamer.service.StreamingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity for Android 14 (API 34) Bidirectional Wi-Fi Remote Streamer.
 * 
 * Implements:
 * 1. Role 1 (CLIENT - PC to Phone): Fullscreen SurfaceView rendering PC video with touch interception
 * 2. Role 2 (SERVER - Phone to PC): MediaProjection launcher & Foreground StreamingService controller
 * 3. UDP Auto-Discovery listener & pairing
 * 4. Low-latency Touch dispatching (down, move, up) with resolution normalization
 * 5. Real-time telemetry monitoring (Latency RTT, FPS, Bitrate)
 */
class MainActivity : ComponentActivity(), SurfaceHolder.Callback, View.OnTouchListener {

    private enum class AppRole {
        CLIENT_PC_TO_PHONE,
        SERVER_PHONE_TO_PC
    }

    private var currentRole = AppRole.CLIENT_PC_TO_PHONE
    private var streamingService: StreamingService? = null
    private var isBound = false

    private lateinit var surfaceView: SurfaceView
    private var surfaceHolder: SurfaceHolder? = null

    // Target PC connection details
    private var targetHostIp: String = "192.168.1.100"
    private var targetPort: Int = StreamProtocol.DEFAULT_STREAM_PORT

    // Service Connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound = true
            observeServiceMetrics()

            // If surface is ready and we are in client mode, bind surface to decoder
            if (currentRole == AppRole.CLIENT_PC_TO_PHONE) {
                surfaceHolder?.surface?.let { surface ->
                    StreamingService.activeSurface = surface
                    streamingService?.setupHardwareDecoder(surface)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            isBound = false
        }
    }

    // MediaProjection Launcher for Server Role (Screen Capture)
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startStreamingServer(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Runtime Permissions Launcher (Android 13+ Notifications & Audio)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!notifGranted) {
            Toast.makeText(this, "Notification permission needed for Foreground Service", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        requestAppPermissions()

        // Setup Fullscreen SurfaceView
        surfaceView = SurfaceView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            holder.addCallback(this@MainActivity)
            setOnTouchListener(this@MainActivity)
        }
        setContentView(surfaceView)

        // Bind to background StreamingService
        val intent = Intent(this, StreamingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Start UDP Auto-Discovery
        startAutoDiscovery()
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startAutoDiscovery() {
        val netManager = StreamingService.networkManagerInstance ?: NetworkManager(this)
        netManager.startDiscovery(
            role = if (currentRole == AppRole.CLIENT_PC_TO_PHONE) "CLIENT" else "SERVER"
        ) { pcName, ip, port ->
            targetHostIp = ip
            targetPort = port
            runOnUiThread {
                Toast.makeText(this, "Auto-discovered PC '$pcName' at $ip:$port", Toast.LENGTH_LONG).show()
                if (currentRole == AppRole.CLIENT_PC_TO_PHONE) {
                    connectToPcClientRole(ip, port)
                }
            }
        }
    }

    /**
     * Role 1 Trigger: Client mode (Receive PC stream, transmit phone touches)
     */
    fun connectToPcClientRole(hostIp: String = targetHostIp, port: Int = targetPort) {
        currentRole = AppRole.CLIENT_PC_TO_PHONE
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_CLIENT
            putExtra(StreamingService.EXTRA_HOST_IP, hostIp)
            putExtra(StreamingService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * Role 2 Trigger: Server mode (Stream Android screen & audio to PC)
     */
    fun startServerRole() {
        currentRole = AppRole.SERVER_PHONE_TO_PC
        checkAccessibilityServiceEnabled()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startStreamingServer(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_SERVER
            putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamingService.EXTRA_RESULT_DATA, data)
            putExtra(StreamingService.EXTRA_WIDTH, metrics.widthPixels)
            putExtra(StreamingService.EXTRA_HEIGHT, metrics.heightPixels)
            putExtra(StreamingService.EXTRA_DPI, metrics.densityDpi)
            putExtra(StreamingService.EXTRA_PORT, targetPort)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * CLIENT ROLE: Touch event interception on Fullscreen SurfaceView.
     * Converts raw touch coordinates into normalized fractions (0.0 to 1.0)
     * and dispatches them to PC over the network.
     */
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null || currentRole != AppRole.CLIENT_PC_TO_PHONE) return false

        val viewWidth = surfaceView.width.toFloat()
        val viewHeight = surfaceView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return false

        val actionType: Byte = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> StreamProtocol.TOUCH_ACTION_DOWN
            MotionEvent.ACTION_MOVE -> StreamProtocol.TOUCH_ACTION_MOVE
            MotionEvent.ACTION_UP -> StreamProtocol.TOUCH_ACTION_UP
            MotionEvent.ACTION_CANCEL -> StreamProtocol.TOUCH_ACTION_CANCEL
            else -> return false
        }

        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex).toByte()
        val normX = (event.getX(pointerIndex) / viewWidth).coerceIn(0.0f, 1.0f)
        val normY = (event.getY(pointerIndex) / viewHeight).coerceIn(0.0f, 1.0f)
        val pressure = event.getPressure(pointerIndex)

        // Send touch packet to PC
        StreamingService.networkManagerInstance?.sendTouchEvent(
            action = actionType,
            pointerId = pointerId,
            normalizedX = normX,
            normalizedY = normY,
            pressure = pressure
        )
        return true
    }

    private fun checkAccessibilityServiceEnabled() {
        if (RemoteAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "Enable 'Remote Streamer Touch Service' in Accessibility settings for PC control",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun observeServiceMetrics() {
        val netManager = StreamingService.networkManagerInstance ?: return
        lifecycleScope.launch {
            netManager.latencyMs.collectLatest { latency -> }
        }
        lifecycleScope.launch {
            netManager.fps.collectLatest { fps -> }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        StreamingService.activeSurface = holder.surface
        if (currentRole == AppRole.CLIENT_PC_TO_PHONE) {
            streamingService?.setupHardwareDecoder(holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
        StreamingService.activeSurface = null
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}