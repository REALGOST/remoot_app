package com.remote.streamer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared low-latency binary wire protocol for bidirectional Android <-> PC streaming.
 * Header format (18 bytes total):
 * - Magic (4 bytes): 0x5354524D ("STRM")
 * - Type (1 byte): Packet type (Video, Audio, Touch, Heartbeat, Config)
 * - Flags (1 byte): Bit 0 = Keyframe / SPS-PPS, Bit 1 = Compressed
 * - Sequence (4 bytes): Monotonically increasing packet sequence number
 * - Timestamp (8 bytes): System.nanoTime() / 1_000_000 for RTT & jitter tracking
 * - Payload Length (4 bytes): Big-endian length of data following header
 */
object StreamProtocol {
    const val MAGIC: Int = 0x5354524D // "STRM"
    const val HEADER_SIZE: Int = 18

    const val TYPE_DISCOVERY_PING: Byte = 0x01
    const val TYPE_DISCOVERY_PONG: Byte = 0x02
    const val TYPE_VIDEO_FRAME: Byte    = 0x10
    const val TYPE_AUDIO_FRAME: Byte    = 0x11
    const val TYPE_TOUCH_EVENT: Byte    = 0x20
    const val TYPE_KEY_EVENT: Byte      = 0x21
    const val TYPE_CONFIG_SYNC: Byte    = 0x30
    const val TYPE_HEARTBEAT: Byte      = 0x40

    const val FLAG_KEYFRAME: Byte       = 0x01
    const val FLAG_CONFIG_HEADER: Byte  = 0x02

    const val TOUCH_ACTION_DOWN: Byte   = 0x00
    const val TOUCH_ACTION_MOVE: Byte   = 0x01
    const val TOUCH_ACTION_UP: Byte     = 0x02
    const val TOUCH_ACTION_CANCEL: Byte = 0x03

    const val DEFAULT_DISCOVERY_PORT: Int = 8888
    const val DEFAULT_STREAM_PORT: Int    = 9999
    const val DEFAULT_CONTROL_PORT: Int   = 9998

    fun pack(
        type: Byte,
        flags: Byte = 0,
        sequence: Int,
        timestampMs: Long = System.currentTimeMillis(),
        payload: ByteArray
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(type)
        buffer.put(flags)
        buffer.putInt(sequence)
        buffer.putLong(timestampMs)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun packTouchEvent(
        action: Byte,
        pointerId: Byte,
        normalizedX: Float,
        normalizedY: Float,
        pressure: Float = 1.0f,
        buttonState: Int = 0
    ): ByteArray {
        val payload = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN)
        payload.put(action)
        payload.put(pointerId)
        payload.putFloat(normalizedX)
        payload.putFloat(normalizedY)
        payload.putFloat(pressure)
        payload.putInt(buttonState)
        return payload.array()
    }

    fun unpackTouchEvent(payload: ByteArray): TouchData? {
        if (payload.size < 18) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val action = buffer.get()
        val pointerId = buffer.get()
        val normX = buffer.float
        val normY = buffer.float
        val pressure = buffer.float
        val buttonState = buffer.int
        return TouchData(action, pointerId, normX, normY, pressure, buttonState)
    }

    data class Header(
        val magic: Int,
        val type: Byte,
        val flags: Byte,
        val sequence: Int,
        val timestampMs: Long,
        val payloadLength: Int
    )

    fun unpackHeader(bytes: ByteArray, offset: Int = 0): Header? {
        if (bytes.size - offset < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.int
        if (magic != MAGIC) return null
        val type = buffer.get()
        val flags = buffer.get()
        val seq = buffer.int
        val timestamp = buffer.long
        val length = buffer.int
        return Header(magic, type, flags, seq, timestamp, length)
    }
}

data class TouchData(
    val action: Byte,
    val pointerId: Byte,
    val normalizedX: Float,
    val normalizedY: Float,
    val pressure: Float,
    val buttonState: Int
)