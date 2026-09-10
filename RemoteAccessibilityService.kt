package com.remote.streamer.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.remote.streamer.protocol.StreamProtocol

/**
 * AccessibilityService that simulates real screen touches on Android without requiring root.
 * Listens for touch packets arriving from the connected PC when Android acts as the SERVER.
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessService"
        var instance: RemoteAccessibilityService? = null
            private set
    }

    private var currentGesturePath: Path? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Remote Accessibility Touch Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun simulateTouch(action: Byte, normalizedX: Float, normalizedY: Float): Boolean {
        val metrics = resources.displayMetrics
        val absX = (normalizedX * metrics.widthPixels).coerceIn(0f, metrics.widthPixels.toFloat())
        val absY = (normalizedY * metrics.heightPixels).coerceIn(0f, metrics.heightPixels.toFloat())

        return when (action) {
            StreamProtocol.TOUCH_ACTION_DOWN -> {
                lastX = absX
                lastY = absY
                currentGesturePath = Path().apply {
                    moveTo(absX, absY)
                }
                dispatchClickGesture(absX, absY)
            }
            StreamProtocol.TOUCH_ACTION_MOVE -> {
                if (currentGesturePath != null) {
                    val swipePath = Path().apply {
                        moveTo(lastX, lastY)
                        lineTo(absX, absY)
                    }
                    lastX = absX
                    lastY = absY
                    dispatchStrokeGesture(swipePath, 50L)
                } else {
                    false
                }
            }
            StreamProtocol.TOUCH_ACTION_UP, StreamProtocol.TOUCH_ACTION_CANCEL -> {
                currentGesturePath = null
                true
            }
            else -> false
        }
    }

    private fun dispatchClickGesture(x: Float, y: Float): Boolean {
        val clickPath = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchStrokeGesture(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}