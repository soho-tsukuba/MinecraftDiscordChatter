package me.tsukuba.soho.plugin.chat.rcon

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.logging.Logger

data class RemoteConsolePacket(
    val requestId: Int,
    val type: RemoteConsolePacketType,
    val payload: ByteArray
) {
    companion object {
        val logger: Logger = Logger.getLogger(RemoteConsolePacket::class.java.canonicalName)
    }

    val size get() = Int.SIZE_BYTES + Int.SIZE_BYTES + payload.size + 2
    val packetSize get() = size + Int.SIZE_BYTES

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteConsolePacket) return false

        if (requestId != other.requestId) return false
        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = requestId
        result = 31 * result + type.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    fun pack(): ByteArray =
        ByteBuffer
            .allocate(packetSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size)
            .putInt(requestId)
            .putInt(type.type)
            .put(payload)
            .put(byteArrayOf(0, 0))
            .array()
}

fun InputStream.waitForBytes(bytes: Int, timeoutInMillis: Long = 5000L): Boolean {
    val waitStart = System.currentTimeMillis()
    while (available() < bytes) {
        if (System.currentTimeMillis() - waitStart > timeoutInMillis) {
            RemoteConsolePacket.logger.warning("reading timeout exceeded")
            return false
        }
    }
    return true
}

fun InputStream.construct(): RemoteConsolePacket {
    val header = ByteArray(Int.SIZE_BYTES * 3)
    val din = DataInputStream(this)

    din.readFully(header)
    val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

    RemoteConsolePacket.logger.info("${headerBuffer.remaining()} bytes read")

    val size = headerBuffer.int - Int.SIZE_BYTES * 2 - 2

    RemoteConsolePacket.logger.info("server respond with $size bytes of payload.")

    if (size < 0) {
        throw java.lang.IllegalStateException("payload size must be non-negative, but got $size.")
    }

    val payload = ByteArray(size)
    din.readFully(payload)
    din.readNBytes(2)

    return RemoteConsolePacket(
        headerBuffer.int,
        headerBuffer.int.asPacketType(),
        payload
    )
}
