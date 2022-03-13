@file:Suppress("BlockingMethodInNonBlockingContext")

package me.tsukuba.soho.plugin.chat.discord

import discord4j.common.util.Snowflake
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.ReactiveEventAdapter
import discord4j.core.event.domain.message.MessageCreateEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.bukkit.entity.Player
import org.bukkit.plugin.PluginLogger
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import java.util.logging.Level

typealias CommandHandler = DiscordBot.(cmd: String) -> Unit

class DiscordBot(
    token: String,
    private val channelId: Long,
    private val logger: PluginLogger,
    private val handler: CommandHandler? = null) {

    private var _client: GatewayDiscordClient? = null
    private val client: GatewayDiscordClient get() {
        return _client!!
    }
    private val hasClient: Boolean get() {
        return _client != null
    }
    private lateinit var channel: TextChannel

    init {
        runBlocking {
            initializeAsync(token)
            logger.info("successfully connected to the discord server.")
        }
    }

    private suspend fun initializeAsync(token: String) = withContext(Dispatchers.Default) {
        val client = DiscordClient.create(token).login().block()!!
        val ch = client.getChannelById(Snowflake.of(channelId)).block() ?: run {
            logger.log(Level.SEVERE, "channel with id $channelId was not found.")
            return@withContext
        }

        if (ch !is TextChannel) {
            logger.log(Level.SEVERE, "channel with id $channelId was found, but it is not a text channel.")
            return@withContext
        }

        channel = ch

        val (_, roleId) = client
            .getGuildRoles(channel.guildId)
            .map { it.name to it.id }
            .filter { it.first == "engineer" }
            .blockFirst() ?: (null to null)

        logger.info("$roleId, $handler")

        if (roleId != null && handler != null) {
            client.eventDispatcher.on(object: ReactiveEventAdapter () {
                override fun onMessageCreate(event: MessageCreateEvent): Publisher<*> {
                    logger.info("message received: ${event.message.data.content()}")

                    if (event.message.data.mentions().all { it.id().asLong() != 883677891844517898L }) {
                        return Mono.empty<Void>()
                    }

                    val hasPermission = event.message.data.member().run {
                        if (isAbsent) {
                            return@run false
                        }
                        val memberData = get()
                        memberData.roles().any {
                            it.asLong() == roleId.asLong()
                        }
                    }
                    if (!hasPermission) {
                        return channel.createMessage("You have no permission to run the command.")
                    }

                    logger.info(
                        "User (${
                            event.message.userData.username()
                        }) trying to execute command from Discord: ${
                            event.message.content
                        }"
                    )

                    handler!!(
                        event.message
                            .data
                            .content()
                            .replace(Regex("<@![0-9]+>"), "")
                            .trim()
                    )

                    return Mono.empty<Void>()
                }
            }).subscribe()
        }

        _client = client
    }

    fun sendMessage(fromUser: Player, message: Component) {
        if (!hasClient) {
            logger.warning("initialization is not yet finished, or failed.")
            return
        }
        runBlocking {
            withContext(Dispatchers.Default) {
                if (message !is TextComponent) {
                    logger.info("message received but not redirected: $message")
                    return@withContext
                }

                val msg = "[${fromUser.customName ?: fromUser.name}] ${message.content()}"

                channel.restChannel.createMessage(msg).block()
                logger.info("message sent: $msg")
            }
        }
    }

    fun sendMessage(message: String) {
        runBlocking {
            withContext(Dispatchers.Default) {
                channel.restChannel.createMessage(message).block()
            }
        }
    }

    fun close() {
        client.eventDispatcher.shutdown()
        client.logout().block()
    }
}