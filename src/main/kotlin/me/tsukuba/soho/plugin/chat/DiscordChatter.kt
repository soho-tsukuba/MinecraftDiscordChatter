package me.tsukuba.soho.plugin.chat

import org.bukkit.event.EventHandler
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.*
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerKickEvent
import me.tsukuba.soho.plugin.chat.discord.DiscordBot
import me.tsukuba.soho.plugin.chat.map.MapRenderer
import me.tsukuba.soho.plugin.chat.rcon.RemoteConsoleClient
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.BanList
import org.bukkit.World
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.PluginLogger
import org.bukkit.plugin.java.JavaPlugin
import java.lang.Runnable
import java.nio.file.Path
import java.text.DateFormat
import java.util.*
import kotlin.io.path.*

typealias CommandHandle = DiscordChatter.(args: List<String>) -> Unit

@Suppress("unused")
class DiscordChatter: JavaPlugin(), Listener {
    private var _bot: DiscordBot? = null
    private val bot: DiscordBot
        get() = _bot!!
    private val initialized get() = _bot != null
    private val logger = PluginLogger(this)
    private var _nickname: String? = null
    val nickname: String? get() = _nickname
    private val props = Properties()
    private var rcon: RemoteConsoleClient? = null
    private lateinit var mapRenderer: MapRenderer
    private val serverPath: Path

    init {
        try {
            val path = javaClass.protectionDomain.codeSource.location.toURI().toPath()

            serverPath = (if (path.extension == "jar") {
                path.parent.parent
            } else {
                path.parent.resolve("../".repeat(javaClass.packageName.split('.').size))
            })

            val propsPath = serverPath.resolve("server.properties")

            if (propsPath.exists() && propsPath.isRegularFile()) {
                val file = propsPath.toFile()
                if (file.canRead()) {
                    file.inputStream().use {
                        props.load(it)
                    }
                    logger.info("server.properties loaded with ${props.size} entries.")
                } else {
                    logger.warning("cannot read server.properties, skipping")
                }
            } else {
                logger.warning("server.properties not found at the expected location: $propsPath, skipping.")
            }
        } catch (err: java.lang.Exception) {
            logger.warning("cannot get library path because of security reason: ${err.localizedMessage}")
            throw err
        }
    }

    companion object {
        const val botId = 883677891844517898L
        val commandMap = mutableMapOf<String, CommandHandle>()
        val dateFormatter: DateFormat = DateFormat.getDateInstance(DateFormat.FULL, Locale.JAPAN)
    }

    override fun onEnable() {
        config.addDefaults(
            mapOf(
                "token" to null,
                "channel_id" to null,
                "nickname" to null
            )
        )
        mapRenderer = MapRenderer(
            config.getConfigurationSection("map_image") ?: config.createSection("map_image"),
            serverPath
        )
        saveConfig()

        val token = config.getString("token") ?: throw IllegalArgumentException("token is null")
        val channelId = config.getLong("channel_id")
        _nickname = config.getString("nickname")

        initializeBotCommands()

        _bot = DiscordBot(token, channelId, this) { it, perm ->
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
                        .replace("<@$botId>", "")
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
                    bot.sendMessage("""
                        使用方法: <@!${botId}> players <online | offline | banned | all>
                          プレイヤーの数及び一覧を表示します。""".trimIndent())
                }
            }
        }
        commandMap["ban"] = {
            if (it.isEmpty()) {
                bot.sendMessage("""
                    使用方法: <@!${botId}> ban playerName [reason]
                      指定したプレイヤーのアクセスを禁止します。理由は省略可能です。""".trimIndent())
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
                bot.sendMessage("""
                    使用方法: <@!${botId}> pardon playerName
                      指定したプレイヤーのアクセス禁止を解除します。""".trimIndent())
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
        commandMap["cache"] = {
            if (it.isEmpty()) {
                bot.sendMessage("""
                    使用方法: <@!${botId}> cache <expire | info>
                      Discordのサーバー情報のキャッシュを確認または更新します。""".trimIndent())
            } else {
                when (it.first()) {
                    "expire" -> runBlocking {
                        bot.sendMessage("Discordサーバー情報のキャッシュを更新します。")
                        bot.expireIdCaches()
                            .doOnSuccess {
                                bot.sendMessage("Discordサーバー情報のキャッシュを正常に更新しました。")
                            }
                            .doOnError {
                                bot.sendMessage("Discordサーバー情報のキャッシュの更新に失敗しました。")
                            }
                            .subscribe()
                    }
                    "info" -> {
                        val info = bot.cacheInfo
                        if (info.initialized) {
                            bot.sendMessage("現在、${info.cachedRoles}個のロールと${info.cachedUsers}人" +
                                            "のユーザー情報がキャッシュされています。" +
                                            "キャッシュを更新するには、<@!${botId}> cache expire コマンドを利用してください。" +
                                            "キャッシュが古い場合、ゲーム内からのメンションがうまく動作しないことがあります。")
                        } else {
                            bot.sendMessage("初期化が完了していません。")
                        }
                    }
                }
            }
        }
        commandMap["run"] = {
            if (it.isEmpty()) {
                bot.sendMessage("""
                    使用方法: <@!${botId}> run <Minecraft commands>
                      MinecraftのコマンドをOP権限で実行します。""".trimIndent())
            } else {
                val console = rcon
                if (console == null || !console.isConnected) {
                    bot.sendMessage("コマンドコンソールが有効になっていないため、コマンドを実行できません")
                } else {
                    CoroutineScope(Dispatchers.Default).launch {
                        val resp = console.command(it.joinToString(" "))
                        bot.sendMessage(resp.replace(Regex("§\\w"), ""))
                    }
                }
            }
        }
        commandMap["maps"] = {
            when(it.firstOrNull()) {
                null -> {
                    bot.sendMessage("""
                        使用方法: <@!${botId}> maps <generate | list | get>
                          現在のワールドにおける、オーバーワールドの生成済みマップ全体の画像を生成/取得します。""".trimIndent())
                }
                "generate" -> {
                    val world = server.worlds.find { world -> world.environment == World.Environment.NORMAL }
                    if (world == null) {
                        bot.sendMessage("現在のワールドには生成済みのオーバーワードがありません。")
                    } else {
                        bot.sendMessage("マップを生成しています...")
                        CoroutineScope(Dispatchers.Default).launch {
                            bot.uploadImage(mapRenderer.renderMap(world).toFile())
                            bot.sendMessage("マップの生成が完了しました。")
                        }
                    }
                }
                "list" -> {
                    val files = mapRenderer
                        .dataPath
                        .listDirectoryEntries()
                        .filter { path -> path.isRegularFile() && path.extension == "png" }
                        .joinToString("\n") {
                                path -> "- ${path.nameWithoutExtension} (${path.fileSize() / 1024}kB)"
                        }
                    bot.sendMessage(
                        "それぞれの生成された画像を見るには、<@!${botId}> maps get <name [name1...]> を利用してください。\n\n$files"
                    )
                }
                "get" -> {
                    val names = it.drop(1)
                    CoroutineScope(Dispatchers.Default).launch {
                        for (name in names) {
                            bot.uploadImage(
                                mapRenderer
                                    .dataPath
                                    .resolve(if (name.endsWith(".png")) name else "$name.png")
                                    .toFile()
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDisable() {
        if (initialized) {
            bot.close()
            rcon?.close()
        }
    }

    private fun connectRcon() = CoroutineScope(Dispatchers.Default).launch {
        val port = props.getProperty("rcon.port").toIntOrNull()
        val pass = props.getProperty("rcon.password")

        if (port != null && pass != null) {
            rcon = run {
                val con = RemoteConsoleClient(logger, port, pass)
                if (con.connect()) {
                    logger.info("rcon connected.")
                    con
                } else {
                    null
                }
            }
        } else {
            logger.warning("reading config failed")
        }
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        val task = Runnable {
            logger.info("server loading done, trying to connect remote console from localhost")
            rcon?.close()
            connectRcon()
        }

        server.scheduler.runTaskLaterAsynchronously(this, task, 20)
    }

    @EventHandler
    fun onPlayerLoggedIn(event: PlayerJoinEvent) {
        updateName()
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        updateName()
    }

    @EventHandler
    fun onPlayerKicked(event: PlayerKickEvent) {
        updateName()
    }

    private fun updateName() {
        val task = Runnable {
            CoroutineScope(Dispatchers.Default).launch {
                bot.updateNickname("${nickname ?: "mchatter"} [${server.onlinePlayers.size}/${server.maxPlayers}]")
            }
        }
        server.scheduler.runTaskLater(this, task, 10)
    }

    @EventHandler
    fun onChat(event: AsyncChatEvent) {
        bot.sendMessage(event.player, event.message())
    }
}
