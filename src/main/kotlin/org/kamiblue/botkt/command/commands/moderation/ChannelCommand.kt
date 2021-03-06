package org.kamiblue.botkt.command.commands.moderation

import kotlinx.coroutines.runBlocking
import net.ayataka.kordis.entity.edit
import net.ayataka.kordis.entity.message.Message
import net.ayataka.kordis.entity.server.Server
import net.ayataka.kordis.entity.server.channel.ServerChannel
import net.ayataka.kordis.entity.server.channel.text.ServerTextChannel
import net.ayataka.kordis.entity.server.permission.PermissionSet
import net.ayataka.kordis.entity.server.permission.overwrite.RolePermissionOverwrite
import org.kamiblue.botkt.*
import org.kamiblue.botkt.command.*
import org.kamiblue.botkt.command.options.HasPermission
import org.kamiblue.botkt.event.events.ShutdownEvent
import org.kamiblue.botkt.manager.managers.ConfigManager
import org.kamiblue.botkt.utils.*
import org.kamiblue.botkt.utils.StringUtils.toHumanReadable
import org.kamiblue.event.listener.listener
import kotlin.collections.set

object ChannelCommand : BotCommand(
    name = "channel",
    alias = arrayOf("ch"),
    category = Category.MODERATION,
    description = "Modify, copy, save, archive and slow channels"
) {
    private val config get() = ConfigManager.readConfigSafe<ArchivedChannelsConfig>(ConfigType.ARCHIVE_CHANNEL, false)
    private val permissions = HashMap<String, Collection<RolePermissionOverwrite>>()
    private val rolePermHistory = HashMap<ServerChannel, ArrayDeque<List<RolePermissionOverwrite>>>()

    private var previousChange: Triple<Pair<ChangeType, String>, ServerChannel, Collection<RolePermissionOverwrite>>? = null

    init {
        literal("save") {
            execute("Save a channel's permissions", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                val serverChannel = message.serverChannel() ?: return@execute
                val name = serverChannel.name

                save(name, serverChannel, message)
            }

            string("name") { name ->
                execute("Save a channel's permissions", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                    val serverChannel = message.serverChannel() ?: return@execute

                    save(name.value, serverChannel, message)
                }
            }
        }

        literal("print") {
            execute("Print the current channels permissions", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                val c = message.serverChannel() ?: return@execute
                val name = c.name

                print(name, message)
            }

            string("name") { name ->
                execute("Print a channels permissions", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                    print(name.value, message)
                }
            }
        }

        literal("load") {
            string("name") { name ->
                execute("Load a channels permissions from saved", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                    load(name.value, message)
                }
            }
        }

        @Suppress("UNREACHABLE_CODE") // TODO: Doesn't work
        literal("undo") {
            execute("Undo the last change to channels", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                channel.error("Undo isn't fully supported yet!")
                return@execute

                undo(message)
            }
        }

        literal("sync") {
            literal("category") {
                execute("Sync category permissions to channel permissions", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                    val c = message.serverChannel() ?: return@execute

                    sync(true, message, c)
                }
            }

            execute("Sync channel permissions to category permissions", HasPermission.get(PermissionTypes.MANAGE_CHANNELS)) {
                val c = message.serverChannel() ?: return@execute

                sync(false, message, c)
            }
        }

        literal("slow") {
            execute("Remove slowmode for the current channel", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                message.serverChannel?.let {
                    it.edit {
                        rateLimitPerUser = 0
                    }

                    channel.success("Removed slowmode")
                } ?: run {
                    channel.error("Server channel is null, are you running this from a DM?")
                }
            }

            int("wait") { waitArg ->
                execute("Set slowmode for the current channel", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                    val wait = waitArg.value

                    message.serverChannel?.let {
                        it.edit {
                            rateLimitPerUser = wait
                        }

                        channel.success(if (wait != 0) "Set slowmode to ${wait}s" else "Removed slowmode")
                    } ?: run {
                        channel.error("Server channel is null, are you running this from a DM?")
                    }
                }
            }
        }

        literal("archive") {
            execute("Archive the current channel", HasPermission.get(PermissionTypes.ARCHIVE_CHANNEL)) {
                val config = config ?: run {
                    config?.amount = 0
                    ConfigManager.writeConfig(ConfigType.ARCHIVE_CHANNEL) // attempt to save a blank config
                    ConfigManager.readConfigSafe<ArchivedChannelsConfig>(ConfigType.ARCHIVE_CHANNEL, true) ?: run {
                        channel.error("`${ConfigType.ARCHIVE_CHANNEL.configPath}` is not setup and failed to create!")
                        return@execute
                    } // attempt to load said config
                }

                val c = message.serverChannel() ?: return@execute
                val s = server ?: run { channel.error("Server is null, are you running this from a DM?"); return@execute }
                val everyone = s.roles.find(s.id)!! // this cannot be null, as it's the @everyone role and we already checked server null
                val oldName = c.name

                config.amount = config.amount?.let { it + 1 } ?: 1

                c.edit {
                    userPermissionOverwrites.clear()
                    rolePermissionOverwrites.clear()
                    rolePermissionOverwrites.add(RolePermissionOverwrite(everyone, PermissionSet(0), PermissionSet(1024))) // disallow read for everyone
                    name = "archived-${config.amount}"
                }

                channel.success("Changed name from `$oldName` to `archived-${config.amount}`")
            }
        }

        literal("lock") {
            literal("category") {
                execute("Lock all channels in the category", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                    lockOrUnlock(category = true, lock = true, message, server)
                }
            }

            execute("Lock the current channel", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                lockOrUnlock(category = false, lock = true, message, server)
            }
        }

        literal("unlock") {
            literal("category") {
                execute("Unlock all the channels in the category", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                    lockOrUnlock(category = true, lock = false, message, server)
                }
            }

            execute("Unlock the current channel", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                lockOrUnlock(category = false, lock = false, message, server)
            }
        }

        BackgroundScope.launchLooping("Archived channel saving", 1800000L) {
            ConfigManager.writeConfig(ConfigType.ARCHIVE_CHANNEL)

            Main.logger.debug("Saved archived channels amount")
        }

        listener<ShutdownEvent> {
            runBlocking {
                ConfigManager.writeConfig(ConfigType.ARCHIVE_CHANNEL)
            }
        }
    }

    private suspend fun save(saveName: String, serverChannel: ServerChannel, message: Message) {
        val selectedConfig = ArrayList(serverChannel.rolePermissionOverwrites)

        previousChange = Triple(Pair(ChangeType.SAVE, saveName), serverChannel, serverChannel.rolePermissionOverwrites)
        // make sure to run this AFTER saving previous state
        permissions[saveName] = selectedConfig

        message.channel.success("Saved current channel permissions, use `$name print $saveName` to print permissions!")
    }

    private suspend fun print(name: String, message: Message) {
        val selectedChannel = permissions[name] ?: run {
            message.channel.error("Couldn't find `$name` in saved channel presets!")
            return
        }

        val string = selectedChannel.joinToString(separator = "\n") {
            "${it.role.mention}\n" +
                "Allow: ${it.allow.pretty()}\n" +
                "Deny: ${it.deny.pretty()}\n"
        }

        permissions[name] = selectedChannel

        if (string.isBlank()) {
            message.channel.error("No saved permissions for `$name`!")
        } else message.channel.normal(string)
    }

    private suspend fun load(name: String, message: Message) {
        val selectedChannel = permissions[name] ?: run {
            message.channel.error("Couldn't find `$name` in saved channel presets!")
            return
        }

        val serverChannel = message.serverChannel() ?: return
        previousChange = Triple(Pair(ChangeType.LOAD, name), serverChannel, serverChannel.rolePermissionOverwrites)

        serverChannel.setPermissions(selectedChannel)

        message.channel.success("Loaded channel permissions from `$name`!")
    }

    private suspend fun undo(message: Message) {
        val m = message.channel.normal("Attempting to undo last change...")

        previousChange?.let {
            m.edit {
                description = "Attempting to undo last change...\nFound: ${it.second.name.toHumanReadable()}"
                color = Colors.PRIMARY.color
            }

            when (it.first.first) {
                ChangeType.SAVE -> {
                    permissions[it.first.second] = it.third

                    m.edit {
                        description = "Attempting to undo last change...\n" +
                            "Found: ${it.second.name.toHumanReadable()}\n\n" +
                            "Unsaved, set ${it.first.second} to original permissions"
                        color = Colors.SUCCESS.color
                    }

                    previousChange = null
                }

                ChangeType.LOAD -> {
                    it.second.setPermissions(it.third)

                    m.edit {
                        description = "Attempting to undo last change...\n" +
                            "Found: ${it.second.name.toHumanReadable()}\n\n" +
                            "Unloaded, set ${it.first.second} to original permissions"
                        color = Colors.SUCCESS.color
                    }

                    previousChange = null
                }
            }
        } ?: run {
            m.edit {
                description = "Attempting to undo last change...\nCouldn't find any recent changes"
            }
        }
    }

    private suspend fun sync(reverse: Boolean, message: Message, serverChannel: ServerChannel) {
        val category = message.serverChannel?.category
        val perms = category?.rolePermissionOverwrites ?: run {
            message.channel.error("Channel category is null! Are you running this from a DM?")
            return
        }

        if (reverse) {
            category.setPermissions(perms)
            message.channel.success("Synchronized category permissions to the `${serverChannel.name.toHumanReadable()}` channel!")
        } else {
            serverChannel.setPermissions(perms)
            message.channel.success("Synchronized channel permissions to the `${category.name.toHumanReadable()}` category!")
        }
    }

    private suspend fun lockOrUnlock(category: Boolean, lock: Boolean, message: Message, server: Server?) {
        if (server == null) {
            message.channel.error("Server is null, are you running this from a DM?")
            return
        }

        val everyone = server.roles.find(server.id)!! // this cannot be null, as it's the @everyone role and we already checked server null

        val channel = (if (category) message.serverChannel?.category else message.serverChannel)
            ?: run { message.channel.error("${if (category) "Category" else "Server channel"} was null, was you running this from a DM?"); return }

        val perm = RolePermissionOverwrite(everyone, PermissionSet(0), PermissionSet(2048))

        if (lock) {
            rolePermHistory.getOrPut(channel, ::ArrayDeque).apply {
                add(channel.rolePermissionOverwrites.toList())
                while (size > 5) this.removeFirst()
            }

            channel.edit {
                rolePermissionOverwrites.add(perm)
            }
            message.channel.success("Locked ${if (category) "category" else "channel"}!")
        } else {
            channel.tryGetPrevPerm()?.let {
                channel.setPermissions(it)
            } ?: channel.edit {
                rolePermissionOverwrites.remove(perm)
            }
            message.channel.success("Unlocked ${if (category) "category" else "channel"}!")
        }
    }

    private fun ServerChannel.tryGetPrevPerm(): Collection<RolePermissionOverwrite>? {
        return rolePermHistory[this]?.lastOrNull()
            ?: permissions[this.name]
            ?: if (this is ServerTextChannel) permissions[this.category?.name]
            else null
    }

    private suspend fun ServerChannel.setPermissions(permissions: Collection<RolePermissionOverwrite>) {
        this.edit {
            this.rolePermissionOverwrites.clear()
            this.rolePermissionOverwrites.addAll(permissions)
        }
    }

    private suspend fun Message.serverChannel(): ServerChannel? {
        val sc = this.server?.channels?.find(this.channel.id)

        if (sc == null) {
            channel.error("Channel is null! Are you running this from a DM?")
        }

        return sc
    }

    private enum class ChangeType {
        SAVE, LOAD
    }
}
