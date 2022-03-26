package me.tsukuba.soho.plugin.chat.discord

import discord4j.common.util.Snowflake
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.ReactiveEventAdapter
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.spec.EmbedCreateSpec
import discord4j.core.spec.MessageCreateSpec
import discord4j.gateway.intent.Intent
import discord4j.gateway.intent.IntentSet
import kotlinx.coroutines.*
import me.tsukuba.soho.plugin.chat.DiscordChatter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.bukkit.entity.Player
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import java.io.File
import java.util.*
import java.util.logging.Level

typealias CommandHandler = DiscordBot.(message: Message, commandPermission: Boolean) -> Unit

data class CacheInfo(val initialized: Boolean, val cachedRoles: Int, val cachedUsers: Int)

class DiscordBot(
    token: String,
    private val channelId: Long,
    plugin: DiscordChatter,
    private val handler: CommandHandler) {

    private val logger = plugin.logger
    private val nickname = plugin.nickname
    private val server = plugin.server
    private var _client: GatewayDiscordClient? = null
    private val client: GatewayDiscordClient get() {
        return _client!!
    }
    private val hasClient: Boolean get() {
        return _client != null
    }
    private lateinit var channel: TextChannel
    private lateinit var guild: Guild
    private lateinit var roleIds: Map<String, Long>
    private lateinit var userIds: Map<String, Long>

    val cacheInfo: CacheInfo
        get() = CacheInfo(hasClient, if(hasClient) roleIds.size else 0, if(hasClient) userIds.size else 0)

    init {
        CoroutineScope(Dispatchers.Default).launch {
            initializeAsync(token)
            logger.info("successfully connected to the discord server.")
        }
    }

    private suspend fun initializeAsync(token: String) = withContext(Dispatchers.IO) {
        val client = DiscordClient
            .create(token)
            .gateway()
            .setEnabledIntents(
                IntentSet.of(
                    Intent.GUILD_MEMBERS
                ).or(IntentSet.nonPrivileged()))
            .login()
            .block()!!

        val ch = client.getChannelById(Snowflake.of(channelId)).block() ?: run {
            logger.log(Level.SEVERE, "channel with id $channelId was not found")
            return@withContext
        }

        if (ch !is TextChannel) {
            logger.log(Level.SEVERE, "channel with id $channelId was found, but it is not a text channel")
            return@withContext
        }

        channel = ch
        guild = channel.guild.block()!!

        expireIdCaches().block()

        val (_, roleId) = client
            .getGuildRoles(channel.guildId)
            .map { it.name to it.id }
            .filter { it.first == "engineer" }
            .blockFirst() ?: (null to null)

        logger.info("$roleId, $handler")

        if (roleId != null) {
            client.eventDispatcher.on(object: ReactiveEventAdapter () {
                override fun onMessageCreate(event: MessageCreateEvent): Publisher<*> {
                    if (event.message.channelId != channel.id) {
                        return Mono.empty<Void>()
                    }

                    if (event.message.author.isEmpty) {
                        return Mono.empty<Void>()
                    }

                    if (event.message.author.get().id.asLong() == DiscordChatter.botId) {
                        return Mono.empty<Void>()
                    }

                    logger.info("message received: ${event.message.content}")

                    val hasPermission = event.message.data.member().run {
                        if (isAbsent) {
                            return@run false
                        }
                        val memberData = get()
                        memberData.roles().any {
                            it.asLong() == roleId.asLong()
                        }
                    }

                    handler(event.message, hasPermission)

                    return Mono.empty<Void>()
                }
            }).subscribe()
        }

        _client = client
        updateNickname("$nickname [${server.onlinePlayers.size}/${server.maxPlayers}]")
    }

    fun expireIdCaches(): Mono<Unit> {
        return Mono.zip(
            guild.roles.collectList().doOnSuccess {
                roleIds = it.associate { i -> i.name to i.id.asLong() }
            }.doOnError {
                logger.severe("Error occurred during collecting roles: ${it.message}\n${it.stackTraceToString()}")
            },
            guild.members.collectList().doOnSuccess {
                userIds = it.flatMap { i ->
                    listOf(
                        i.username to i.id.asLong(),
                        i.displayName to i.id.asLong()
                    )
                }.toMap()
            }.doOnError {
                logger.severe("Error occurred during collecting members: ${it.message}\n${it.stackTraceToString()}")
            }).map { }
    }

    fun sendMessage(fromUser: Player, message: Component) {
        if (message !is TextComponent) {
            logger.info("message received but not redirected: $message")
            return
        }

        val msg = "[${fromUser.name}] ${message.content()}"

        runBlocking {
            sendMessageInternal(msg)
        }
        logger.info("message sent: $msg")
    }

    fun sendMessage(message: String) {
        runBlocking {
            sendMessageInternal(message)
        }
    }

    private suspend fun sendMessageInternal(message: String) {
        if (!hasClient) {
            logger.warning("initialization is not yet finished, or failed.")
            return
        }
        return withContext(Dispatchers.IO) {
            val msg = message.replace(Regex("@(.+?)\\b")) {
                val target = it.groups[1]
                when {
                    target == null -> "@"
                    userIds.containsKey(target.value) -> "<@!${userIds[target.value]}>"
                    roleIds.containsKey(target.value) -> "<@&${roleIds[target.value]}>"
                    else -> "@${target.value}"
                }
            }

            channel.restChannel.createMessage(msg).block()
        }
    }

    suspend fun uploadImage(img: File): Unit = withContext(Dispatchers.IO) {
        val lastMod = DiscordChatter.dateFormatter.format(Date(img.lastModified()))

        val message: MessageCreateSpec = MessageCreateSpec
            .builder()
            .addFile(img.name, img.inputStream())
            .addEmbed(
                EmbedCreateSpec
                    .builder()
                    .title("オーバーワールドのマップ (generated at ${lastMod})")
                    .image("attachment://${img.name}")
                    .build()
            )
            .build()

        channel.restChannel.createMessage(
            message.asRequest()
        ).block()
    }

    suspend fun updateNickname(name: String): Unit = withContext(Dispatchers.IO) {
        guild.changeSelfNickname(name).doOnError {
            logger.severe("Error occurred during updating nickname: ${it.message}\n${it.stackTraceToString()}")
        }.block()
    }

    fun close() {
        if (!hasClient) {
            logger.warning("initialization is not yet finished, or failed.")
            return
        }
        runBlocking {
            updateNickname("$nickname [OFFLINE]")
        }
        client.eventDispatcher.shutdown()
        client.logout().block()
    }
}
