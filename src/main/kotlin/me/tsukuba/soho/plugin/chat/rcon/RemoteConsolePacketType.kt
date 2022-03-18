package me.tsukuba.soho.plugin.chat.rcon

enum class RemoteConsolePacketType(val type: Int) {
    ServerAuth(3),
    ServerCommand(2),
    Unknown(-1)
}

fun Int.asPacketType()
    = RemoteConsolePacketType.values().find { it.type == this } ?: RemoteConsolePacketType.Unknown
