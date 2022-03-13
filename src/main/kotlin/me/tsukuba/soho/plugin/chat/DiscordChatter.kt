package me.tsukuba.soho.plugin.chat

import org.bukkit.event.EventHandler
import io.papermc.paper.event.player.AsyncChatEvent
import me.tsukuba.soho.plugin.chat.discord.DiscordBot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.BanList
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.PluginLogger
import org.bukkit.plugin.java.JavaPlugin
import java.text.DateFormat
import java.util.*

typealias CommandHandle = DiscordChatter.(args: List<String>) -> Unit

@Suppress("unused")
class DiscordChatter: JavaPlugin(), Listener {
    private lateinit var bot: DiscordBot
    private val logger = PluginLogger(this)

    companion object {
        const val botId = 883677891844517898L
        val commandMap = mutableMapOf<String, CommandHandle>()
        private val dateFormatter = DateFormat.getDateInstance(DateFormat.FULL, Locale.JAPANESE)
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

                    val cmdHandle = commandMap[cmd] ?: return@Runnable run {
                        sendMessage("コマンド'$cmd'が見つかりませんでした。" +
                                    "[<@!$botId> help]を実行して、有効なコマンドの一覧を確認してください。")
                    }

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
                bot.sendMessage("使用方法: <@!${botId}> op playerName [playerName1 playerName2...]")
            } else {
                var message = ""
                var success = 0

                for (name in it) {
                    val player = server.getPlayerExact(name) ?: server.getOfflinePlayerIfCached(name)

                    if (player == null) {
                        message += "プレイヤー'$name'は見つかりませんでした。\n"
                        continue
                    }

                    player.isOp = true
                    if (player is Player) {
                        player.recalculatePermissions()
                        player.sendMessage(
                            Component.text(
                                "<System> Now you're an op.",
                                Style.style(TextColor.color(171, 191, 207), TextDecoration.ITALIC)
                            )
                        )
                    }
                    success++
                }

                bot.sendMessage("${message}${success}人のプレイヤーをopにしました。")
            }
        }
        commandMap["deop"] = {
            if (it.isEmpty()) {
                bot.sendMessage("使用方法: <@!${botId}> deop playerName [playerName1 playerName2...]")
            } else {
                var message = ""
                var success = 0

                for (name in it) {
                    val player = server.getPlayerExact(name) ?: server.getOfflinePlayerIfCached(name)

                    if (player == null) {
                        message += "プレイヤー'$name'は見つかりませんでした。\n"
                        continue
                    }

                    player.isOp = false
                    if (player is Player) {
                        player.recalculatePermissions()
                        player.sendMessage(
                            Component.text(
                                "<System> Now you're deprived an op permission.",
                                Style.style(TextColor.color(171, 191, 207), TextDecoration.ITALIC)
                            )
                        )
                    }
                    success++
                }

                bot.sendMessage("${message}${success}人のプレイヤーからop権限を剥奪しました。")
            }
        }
        commandMap["help"] = {
            bot.sendMessage(
                """
                Minecraft/Discord Chatbot@${description.version} by ${description.authors.joinToString(", ")}
                
                ${description.description}
                
                有効なコマンド: ${commandMap.keys.joinToString(", ")}""".trimIndent()
            )
        }
        commandMap["players"] = {
            when (it.firstOrNull()) {
                "online" -> {
                    bot.sendMessage("""
                        |${server.onlinePlayers.size}人のプレイヤーが現在オンラインです。
                        |${server.onlinePlayers.joinToString("\n|") { 
                            i -> i.name 
                        }}""".trimMargin("|"))
                }
                "offline" -> {
                    bot.sendMessage("""
                        |${server.offlinePlayers.size}人のプレイヤーが現在オフラインです。
                        |${server.offlinePlayers.joinToString("\n|") {
                            i -> "${i.name ?: "<不明なプレイヤー>"} (${(Date(i.lastSeen))})"
                        }}""".trimMargin("|"))
                }
                "banned" -> {
                    bot.sendMessage("""
                        |${server.bannedPlayers.size}人のプレイヤーがアクセスを禁止されています。
                        |${server.bannedPlayers.joinToString("\n|") {
                            i -> "${i.name ?: "<不明なプレイヤー>"} (${(Date(i.lastLogin))})"
                    }}""".trimMargin("|"))
                }
                "all" -> {
                    val players = server.onlinePlayers.plus(server.offlinePlayers)
                    bot.sendMessage("""
                        |${players.size}人のプレイヤーのログイン履歴があります。
                        |${players.joinToString("\n|") {
                            i -> "${i.name ?: "<不明なプレイヤー>"} (最終ログイン: ${(Date(i.lastSeen))})"
                    }}""".trimMargin("|"))
                }
                else -> {
                    bot.sendMessage("使用方法: <@!${botId}> players <online | offline | banned | all>")
                }
            }
        }
        commandMap["ban"] = {
            if (it.isEmpty()) {
                bot.sendMessage("使用方法: <@!${botId}> ban playerName [reason]")
            } else {
                val name = it.first()
                val player = server.getPlayerExact(name) ?: server.getOfflinePlayerIfCached(name)

                if (player == null) {
                    bot.sendMessage("プレイヤー'$name'は見つかりませんでした。")
                } else {
                    val reason = it.getOrNull(1) ?: "<不明な理由です>"
                    player.banPlayer(reason)
                    bot.sendMessage("プレイヤー'${player.name}'のアクセスを禁止しました。理由: $reason")
                }
            }
        }
        commandMap["pardon"] = {
            if (it.isEmpty()) {
                bot.sendMessage("使用方法: <@!${botId}> pardon playerName")
            } else {
                val name = it.first()
                val player = server.getPlayerExact(name) ?: server.getOfflinePlayerIfCached(name)

                if (player == null) {
                    bot.sendMessage("プレイヤー'$name'は見つかりませんでした。")
                } else {
                    server.getBanList(BanList.Type.NAME).pardon(name)
                    bot.sendMessage("プレイヤー'${player.name}'のアクセス禁止を解除しました")
                }
            }
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
