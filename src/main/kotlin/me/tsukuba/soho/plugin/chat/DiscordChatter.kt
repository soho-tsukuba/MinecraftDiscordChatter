package me.tsukuba.soho.plugin.chat

import org.bukkit.event.EventHandler
import io.papermc.paper.event.player.AsyncChatEvent
import me.tsukuba.soho.plugin.chat.discord.DiscordBot
import org.bukkit.command.CommandException
import org.bukkit.event.Listener
import org.bukkit.plugin.PluginLogger
import org.bukkit.plugin.java.JavaPlugin

class DiscordChatter: JavaPlugin(), Listener {
    private lateinit var bot: DiscordBot
    private val logger = PluginLogger(this)

    override fun onEnable() {
        config.addDefaults(
            mapOf(
                "token" to null,
                "channel_id" to null
            )
        )
        saveConfig()

        val token = config.getString("token") ?: throw IllegalArgumentException("token is null")
        val channelId = config.getLong("channel_id")

        bot = DiscordBot(token, channelId, logger) {
            val task = object: Runnable {
                override fun run() {
                    val sender = DiscordCommandSender(server, this@DiscordChatter)
                    val cmdline = if (it.startsWith('/')) it.substring(1) else it

                    try {
                        val executed = server.dispatchCommand(sender, cmdline)

                        if (!executed) {
                            val name = cmdline.split(' ', limit = 1)[0]
                            sendMessage("[error] command $name could not be found")
                            return
                        }
                    } catch (err: CommandException) {
                        sendMessage("[error] unknown error")
                        return
                    }

                    sendMessage("[success]\n${sender.getMessage()}")
                }
            }
            server.scheduler.runTaskAsynchronously(this@DiscordChatter, task)
        }

        server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
        bot.close()
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        bot.sendMessage(event.player, event.message())
    }
}
