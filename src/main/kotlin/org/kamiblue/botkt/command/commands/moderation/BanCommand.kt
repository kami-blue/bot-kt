package org.kamiblue.botkt.command.commands.moderation

import kotlinx.coroutines.delay
import net.ayataka.kordis.DiscordClientImpl
import net.ayataka.kordis.entity.message.Message
import net.ayataka.kordis.entity.server.Server
import net.ayataka.kordis.entity.server.permission.Permission
import net.ayataka.kordis.entity.user.User
import net.ayataka.kordis.exception.NotFoundException
import org.kamiblue.botkt.*
import org.kamiblue.botkt.Permissions.hasPermission
import org.kamiblue.botkt.command.*
import org.kamiblue.botkt.command.options.HasPermission
import org.kamiblue.botkt.command.options.ServerOnly
import org.kamiblue.botkt.manager.managers.ConfigManager.readConfigSafe
import org.kamiblue.botkt.utils.Colors
import org.kamiblue.botkt.utils.checkPermission
import org.kamiblue.botkt.utils.error
import org.kamiblue.botkt.utils.normal
import org.kamiblue.commons.extension.max
import org.kamiblue.commons.utils.MathUtils

object BanCommand : BotCommand(
    name = "ban",
    category = Category.MODERATION,
    description = "Ban a user or multiple users"
) {
    private const val banReason = "Ban Reason:"
    private val multiSpaceRegex = Regex(" [ ]+")

    init {
        literal("multi") {
            greedy("list of users") { usersArg ->
                execute(
                    "Ban a list of users by ID",
                    ServerOnly,
                    HasPermission.get(PermissionTypes.MASS_BAN)
                ) {
                    val server = server!!
                    val author = message.author
                    val split = usersArg.value
                        .replace("\n", " ")
                        .replace(multiSpaceRegex, " ")
                        .split(" ")
                    val collected = arrayListOf<User>()

                    val message = message.channel.send {
                        embed {
                            description = "Banning [calculating] members..."
                            color = Colors.ERROR.color
                            footer("This will take about 100ms per member")
                        }
                    }

                    split.forEach {
                        it.toLongOrNull()?.let { long ->
                            Main.client.getUser(long)?.let { user ->
                                collected.add(user)
                            }
                        }
                        delay(100)
                    }

                    ban(collected, server, message, author)
                }
            }
        }

        literal("regex") {
            literal("confirm") {
                greedy("userRegex") { userRegexArg ->
                    execute(
                        "Mass ban server members by regex",
                        ServerOnly,
                        HasPermission.get(PermissionTypes.MASS_BAN)
                    ) {
                        val server = server!!
                        val author = message.author
                        val regex = userRegexArg.value.toRegex()
                        val filtered = server.members.filter { it.name.contains(regex) }

                        val message = message.channel.send {
                            embed {
                                description = "Banning [calculating] members..."
                                color = Colors.ERROR.color
                            }
                        }

                        ban(filtered, server, message, author)
                    }
                }
            }

            greedy("userRegex") { userRegexArg ->
                execute(
                    "Preview mass banning by regex",
                    ServerOnly,
                    HasPermission.get(PermissionTypes.MASS_BAN)
                ) {
                    val regex = userRegexArg.value.toRegex()

                    val members = server?.members ?: run {
                        channel.error("Server members are null, are you running this from a DM?")
                        return@execute
                    }

                    val filtered = members.filter { it.name.contains(regex) }
                        .joinToString(separator = "\n") { it.mention }

                    if (members.isEmpty()) {
                        channel.error("Couldn't find any members that match the regex `$regex`!")
                    } else {
                        channel.normal(
                            filtered.max(
                                2048,
                                "\nNot all users are shown, due to size limitations."
                            )
                        )
                    }
                }
            }
        }

        user("user") { user ->
            literal("purge") {
                greedy("reason") { reason ->
                    execute(
                        "Delete messages, custom reason",
                        ServerOnly,
                        HasPermission.get(PermissionTypes.COUNCIL_MEMBER)
                    ) {
                        ban(user.value, true, reason.value, server, message)
                    }
                }
            }

            greedy("reason") { reason ->
                execute(
                    "Don't delete messages, custom reason",
                    ServerOnly,
                    HasPermission.get(PermissionTypes.COUNCIL_MEMBER)
                ) {
                    ban(user.value, false, reason.value, server, message)
                }
            }

            execute(
                "Don't delete messages, use default reason",
                ServerOnly,
                HasPermission.get(PermissionTypes.COUNCIL_MEMBER)
            ) {
                ban(user.value, false, null, server, message)
            }
        }
    }

    // Allow plugins to use this method.
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun ban(
        users: List<User>,
        server: Server,
        message: Message,
        author: User?
    ) {
        message.edit {
            description = "Banning [calculating] members..."
            color = Colors.ERROR.color
        }

        var banned = 0
        val reason = "Mass ban by ${author?.mention}"

        if (users.isEmpty()) {
            message.edit {
                description = "Not banning anybody! 0 members found."
                color = Colors.ERROR.color
            }
            return
        } else {
            message.edit {
                description = "Banning ${users.size} members, will take an estimated " +
                    "${MathUtils.round((users.size * 200) / 1000.0, 2)} seconds..."
                color = Colors.ERROR.color
            }
        }

        users.forEach {
            banned++
            ban(it, true, reason, server, null)
            delay(200)
        }

        message.edit {
            field(
                "$banned members were banned by:",
                author?.mention.toString()
            )
            field(
                banReason,
                reason
            )
            footer("ID: ${author?.id}", author?.avatar?.url)
            color = Colors.ERROR.color
        }
    }

    // Allow plugins to use this method.
    @Suppress("MemberVisibilityCanBePrivate")
    suspend fun ban(
        user: User,
        deleteMsgs: Boolean, // if we should delete the past day of their messages or not
        reason: String?, // reason why they were banned. tries to dm before banning
        nullableServer: Server?,
        message: Message?
    ) {
        val server = nullableServer
            ?: run { message?.channel?.error("Server is null, make sure you aren't running this from a DM!"); return }

        val deleteMessageDays = if (deleteMsgs) 1 else 0
        val fixedReason = if (!reason.isNullOrBlank()) reason else readConfigSafe<UserConfig>(
            ConfigType.USER,
            false
        )?.defaultBanReason ?: "No Reason Specified"

        if (!canBan(user, message, server)) return

        messageReason(user, message, server, fixedReason)

        try {
            user.ban(
                server,
                deleteMessageDays,
                fixedReason
            )
        } catch (e: Exception) {
            message?.channel?.send {
                embed {
                    title = "Error"
                    description = "That user's role is higher then mine, I can't ban them!"
                    field("Stacktrace:", "```${e.message}\n${e.stackTraceToString().max(256)}```")
                    color = Colors.ERROR.color
                }
            }
            return
        }

        message?.let { msg ->
            msg.channel.send {
                embed {
                    field(
                        "${user.tag} was banned by:",
                        msg.author?.mention ?: "Ban message author not found!"
                    )
                    field(
                        banReason,
                        fixedReason
                    )
                    footer("ID: ${user.id}", user.avatar.url)
                    color = Colors.ERROR.color
                }
            }
        }
    }

    private suspend fun messageReason(
        bannedUser: User,
        message: Message?,
        server: Server,
        fixedReason: String
    ) {
        val user = message?.author ?: Main.client.botUser
        try {
            bannedUser.getPrivateChannel().send {
                embed {
                    field(
                        "You were banned by:",
                        user.mention
                    )
                    field(
                        "In the guild:",
                        server.name
                    )
                    field(
                        banReason,
                        fixedReason
                    )
                    color = Colors.ERROR.color
                    footer("ID: ${user.id}", user.avatar.url)
                }
            }
        } catch (e: Exception) {
            message?.channel?.send {
                embed {
                    title = "Error"
                    description =
                        "I couldn't DM that user the ban reason, they might have had DMs disabled."
                    color = Colors.ERROR.color
                }
            }
        }
    }

    private suspend fun canBan(user: User, message: Message?, server: Server): Boolean {
        when {
            user.hasPermission(PermissionTypes.COUNCIL_MEMBER) -> {
                message?.channel?.error("That user is protected, I can't do that.")
                return false
            }

            user.id == message?.author?.id -> {
                message.channel.error("You can't ban yourself!")
                return false
            }

            else -> {
                try {
                    checkPermission(
                        Main.client as DiscordClientImpl,
                        server,
                        Permission.BAN_MEMBERS
                    )
                } catch (e: NotFoundException) {
                    message?.channel?.error("Client is not fully initialized, member list not loaded!")
                    return false
                }
                return true
            }
        }
    }
}
