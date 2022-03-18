package me.tsukuba.soho.plugin.chat.rcon

import org.bukkit.plugin.PluginLogger
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

class RemoteConsoleClient(
    private val logger: PluginLogger,
    private val port: Int,
    private val pass: String): AutoCloseable {
    private val syncer: ReentrantLock = ReentrantLock()
    private lateinit var socket: Socket
    private var connected = false
    val isConnected get() = connected
    private val random: Random

    init {
        val trng = File("/dev/random")
        if (!trng.exists()) {
            random = Random(Instant.now().toEpochMilli())
        } else {
            trng.inputStream().use {
                random = Random(ByteBuffer.wrap(it.readNBytes(8)).long)
            }
        }
    }

    fun connect(): Boolean {
        syncer.withLock {
            if (connected) {
                return false
            }

            logger.info("connecting to 127.0.0.1:$port")

            socket = Socket("127.0.0.1", port)
            socket.keepAlive = true
            socket.soTimeout = 5000

            val resp = send(
                RemoteConsolePacket(
                    random.nextInt(),
                    RemoteConsolePacketType.ServerAuth,
                    pass.toByteArray()
                ),
                true
            )

            logger.info("response(" +
                    "reqId: ${resp?.requestId?.toString() ?: "unknown"}, " +
                    "type: ${resp?.type?.name ?: "unknown"}, " +
                    "payload: <${resp?.payload?.size} bytes of data>)")

            if (resp == null || resp.requestId == -1) {
                logger.warning("failed to connect to rcon server. " +
                        "reason: ${if(resp == null) "no response was returned." else "authentication failed."}")
                close()
                return false
            }

            connected = true
        }
        return true
    }

    fun command(cmdline: String): String {
        logger.info("executing command via rcon: '$cmdline'")

        syncer.withLock {
            if (!connected) {
                logger.warning("socket had not been connected to the host")
                return "リモートコンソールが有効になっていないため、コマンドを実行できません"
            }

            if (cmdline.isBlank() || cmdline.isEmpty()) {
                logger.warning("given command-line was empty")
                return "コマンド列が空であるため、実行できませんでした。"
            }

            val resp = send(
                RemoteConsolePacket(
                    random.nextInt(),
                    RemoteConsolePacketType.ServerCommand,
                    cmdline.toByteArray()
                )
            )

            if (resp == null) {
                logger.warning("server responded corrupted or empty response")
                return "不明な理由でコマンドの実行に失敗しました。時間をおいて、再試行してください。"
            }

            return resp.payload.toString(Charsets.UTF_8)
        }
    }

    private fun send(packet: RemoteConsolePacket, skipConnectionChecking: Boolean = false): RemoteConsolePacket? {
        if (!connected && !skipConnectionChecking) {
            logger.warning("socket had not been connected to the host")
            return null
        }

        if (socket.isClosed) {
            logger.warning("socket was already closed")
            return null
        }

        val rawPacket = packet.pack()

        socket.getOutputStream().let {
            it.write(rawPacket, 0, rawPacket.size)
            it.flush()
            logger.info("${rawPacket.size} bytes of data was sent")
        }

        return try {
            socket.getInputStream().construct()
        } catch (err: IOException) {
            logger.warning("an error was happened during reading response: ${err.localizedMessage}")
            null
        }
    }

    override fun close() {
        syncer.withLock {
            socket.close()
            connected = false
        }
    }
}