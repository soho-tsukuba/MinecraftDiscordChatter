package me.tsukuba.soho.plugin.chat

import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.permissions.*
import org.bukkit.plugin.Plugin
import java.util.*

class DiscordCommandSender(private val _server: Server, private val defaultPlugin: Plugin): CommandSender {
    val effectivePermission = mutableSetOf(
        PermissionAttachmentInfo(
            DiscordPermissible,
            PermissionDefault.OP.name,
            PermissionAttachment(defaultPlugin, DiscordPermissible),
            true
        )
    )

    private var message: String = ""

    companion object DiscordPermissible: Permissible {
        private var attachment: PermissionAttachment? = null

        override fun isOp(): Boolean { return true }

        override fun setOp(value: Boolean) {}

        override fun isPermissionSet(name: String): Boolean { return true }

        override fun isPermissionSet(perm: Permission): Boolean { return true }

        override fun hasPermission(name: String): Boolean { return true }

        override fun hasPermission(perm: Permission): Boolean { return true }

        override fun addAttachment(plugin: Plugin, name: String, value: Boolean): PermissionAttachment {
            attachment = attachment ?: PermissionAttachment(plugin, this)
            return attachment!!
        }

        override fun addAttachment(plugin: Plugin): PermissionAttachment {
            attachment = attachment ?: PermissionAttachment(plugin, this)
            return attachment!!
        }

        override fun addAttachment(plugin: Plugin, name: String, value: Boolean, ticks: Int): PermissionAttachment {
            attachment = attachment ?: PermissionAttachment(plugin, this)
            return attachment!!
        }

        override fun addAttachment(plugin: Plugin, ticks: Int): PermissionAttachment {
            attachment = attachment ?: PermissionAttachment(plugin, this)
            return attachment!!
        }

        override fun removeAttachment(attachment: PermissionAttachment) {}

        override fun recalculatePermissions() {}

        override fun getEffectivePermissions(): MutableSet<PermissionAttachmentInfo> {
            return mutableSetOf(
                PermissionAttachmentInfo(this, PermissionDefault.OP.name, attachment, true)
            )
        }
    }

    override fun sendMessage(message: String) {
        this.message = "${this.message}\n$message"
    }

    override fun sendMessage(vararg messages: String) {
        message = "$message\n${messages.joinToString("\n")}"
    }

    override fun sendMessage(sender: UUID?, message: String) {
        this.message = "${this.message}\n$message"
    }

    override fun sendMessage(sender: UUID?, vararg messages: String) {
        message = "$message\n${messages.joinToString("\n")}"
    }

    override fun isOp(): Boolean { return true }

    override fun setOp(value: Boolean) {}

    override fun isPermissionSet(name: String): Boolean {
        return true
    }

    override fun isPermissionSet(perm: Permission): Boolean {
        return true
    }

    override fun hasPermission(name: String): Boolean {
        return true
    }

    override fun hasPermission(perm: Permission): Boolean {
        return true
    }

    override fun addAttachment(plugin: Plugin, name: String, value: Boolean): PermissionAttachment {
        return addAttachment(plugin, "", false, 0)
    }

    override fun addAttachment(plugin: Plugin): PermissionAttachment {
        return addAttachment(plugin, "", false, 0)
    }

    override fun addAttachment(plugin: Plugin, name: String, value: Boolean, ticks: Int): PermissionAttachment {
        return PermissionAttachment(plugin, DiscordPermissible)
    }

    override fun addAttachment(plugin: Plugin, ticks: Int): PermissionAttachment {
        return addAttachment(plugin, "", false, ticks)
    }

    override fun removeAttachment(attachment: PermissionAttachment) {}

    override fun recalculatePermissions() {}

    override fun getEffectivePermissions(): MutableSet<PermissionAttachmentInfo> {
        return mutableSetOf(
            PermissionAttachmentInfo(
                DiscordPermissible,
                PermissionDefault.OP.name,
                PermissionAttachment(defaultPlugin, DiscordPermissible),
                true
            )
        )
    }

    override fun getServer(): Server {
        return _server
    }

    override fun getName(): String {
        return "Discord"
    }

    override fun spigot(): CommandSender.Spigot {
        TODO("Not yet implemented")
    }

    override fun name(): Component {
        return Component.text(name)
    }

    fun getMessage(): String = message

    fun clearMessage() {
        message = ""
    }
}