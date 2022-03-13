package me.tsukuba.soho.plugin.chat

import org.bukkit.event.EventHandler
import io.papermc.paper.event.player.AsyncChatEvent
import me.tsukuba.soho.plugin.chat.discord.DiscordBot
import net.kyori.adventure.text.Component
import org.bukkit.event.Listener
import org.bukkit.plugin.PluginLogger
import org.bukkit.plugin.java.JavaPlugin

typealias CommandHandle = DiscordChatter.(args: List<String>) -> Unit

@Suppress("unused")
class DiscordChatter: JavaPlugin(), Listener {
    private lateinit var bot: DiscordBot
    private val logger = PluginLogger(this)

    companion object {
        private const val botId = 883677891844517898L
        val commandMap = mutableMapOf<String, CommandHandle>()
    }

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

        initializeBotCommands()

        bot = DiscordBot(token, channelId, logger) { it, perm ->
            val task = Runnable {
                if (it.memberMentions.any { it.id.asLong() == botId }) {
                    if (it.author.isEmpty || it.author.get().id.asLong() == botId) {
                        return@Runnable
                    }

                    if (!perm) {
                        sendMessage("You have no permission to run the command.")
                        return@Runnable
                    }

                    val text = it.content
                        .replace("<@!$botId>", "")
                        .split(' ', '\n')
                        .filter { it.isNotEmpty() }

                    if(text.isEmpty()) {
                        return@Runnable
                    }

                    val cmd = text.first()
                    val args = text.drop(1)

                    val cmdHandle = commandMap[cmd] ?: return@Runnable sendMessage("command $cmd can't be found!")

                    this@DiscordChatter.cmdHandle(args)

                    return@Runnable
                }

                if (it.author.isPresent) {
                    server.broadcast(Component.text(
                        "<${it.authorAsMember.block()?.displayName}@discord> ${it.content}"
                    ))
                }
            }
            server.scheduler.runTask(this@DiscordChatter, task)
        }

        server.pluginManager.registerEvents(this, this)
    }

    private fun initializeBotCommands() {
        commandMap["op"] = {
            if (it.isEmpty()) {
                bot.sendMessage("Usage: <@!${botId}> op playerName [playerName1 playerName2...]")
            } else {
                var message = ""
                var success = 0

                for (name in it) {
                    val player = server.getPlayerExact(name)

                    if (player == null) {
                        message += "player '$name' was not found.\n"
                        continue
                    }

                    player.isOp = true
                    player.recalculatePermissions()
                    success++
                }

                bot.sendMessage("${message}successfully oped $success player(s)")
            }
        }
        commandMap["deop"] = {
            if (it.isEmpty()) {
                bot.sendMessage("Usage: <@!${botId}> deop playerName [playerName1 playerName2...]")
            } else {
                var message = ""
                var success = 0

                for (name in it) {
                    val player = server.getPlayerExact(name)

                    if (player == null) {
                        message += "player '$name' was not found.\n"
                        continue
                    }

                    player.isOp = false
                    player.recalculatePermissions()
                    success++
                }

                bot.sendMessage("${message}successfully de-oped $success player(s)")
            }
        }
        commandMap["help"] = {
            bot.sendMessage("""
                Minecraft/Discord Chatbot@${description.version} by ${description.authors.joinToString(", ")}
                
                ${description.description}
                
                Available commands: ${commandMap.keys.joinToString(", ")}""".trimIndent())
        }
    }

    override fun onDisable() {
        bot.close()
    }

    @EventHandler
    @Suppress("unused")
    fun onChat(event: AsyncChatEvent) {
        bot.sendMessage(event.player, event.message())
    }
}
