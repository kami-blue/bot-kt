package org.kamiblue.botkt.command.commands.moderation

import net.ayataka.kordis.entity.find
import net.ayataka.kordis.entity.server.member.Member
import org.kamiblue.botkt.PermissionTypes
import org.kamiblue.botkt.command.BotCommand
import org.kamiblue.botkt.command.Category
import org.kamiblue.botkt.command.MessageExecuteEvent
import org.kamiblue.botkt.command.options.HasPermission
import org.kamiblue.botkt.manager.managers.MuteManager
import org.kamiblue.botkt.utils.Colors
import org.kamiblue.botkt.utils.error

object UnmuteCommand : BotCommand(
    name = "unmute",
    alias = arrayOf("unshut"),
    category = Category.MODERATION,
    description = "Now start talking!"
) {
    init {
        user("user") { userArg ->
            execute("Unmute user", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                if (server == null) {
                    channel.error("Server is null, are you running this from a DM?")
                    return@execute
                }

                val member = server.members.find(userArg.value) ?: run {
                    channel.error("Member not found!")
                    return@execute
                }

                val serverMuteInfo = MuteManager.serverMap.getOrPut(server.id) { MuteManager.ServerMuteInfo(server) }
                val mutedRole = serverMuteInfo.getMutedRole()

                when {
                    serverMuteInfo.muteMap.remove(member.id) != null -> {
                        sendUnMuteDM(member)
                        serverMuteInfo.coroutineMap.remove(member.id)?.cancel()
                        member.removeRole(mutedRole)

                        message.channel.send {
                            embed {
                                field(
                                    "${member.tag} was unmuted by:",
                                    message.author?.mention.toString()
                                )
                                footer("ID: ${member.id}", member.avatar.url)
                                color = Colors.SUCCESS.color
                            }
                        }
                    }

                    member.roles.contains(mutedRole) -> {
                        sendUnMuteDM(member)
                        member.removeRole(mutedRole)

                        message.channel.send {
                            embed {
                                description = "Warning: ${member.mention} was not muted using the bot, removed muted role."
                                field(
                                    "${member.tag} was unmuted by:",
                                    message.author?.mention.toString()
                                )
                                footer("ID: ${member.id}", member.avatar.url)
                                color = Colors.WARN.color
                            }
                        }
                    }

                    else -> {
                        channel.error("${member.mention} is not muted")
                    }
                }
            }
        }
    }

    private suspend fun MessageExecuteEvent.sendUnMuteDM(member: Member) {
        try {
            member.getPrivateChannel().send {
                embed {
                    field(
                        "You were unmuted by:",
                        message.author?.mention.toString()
                    )
                    field(
                        "In the guild:",
                        server?.name.toString()
                    )
                    color = Colors.SUCCESS.color
                    footer("ID: ${message.author?.id}", message.author?.avatar?.url)
                }
            }
        } catch (e: Exception) {
            message.channel.send {
                embed {
                    title = "Error"
                    description = "I couldn't DM that user the unmute, they might have had DMs disabled."
                    color = Colors.ERROR.color
                }
            }
        }
    }
}
