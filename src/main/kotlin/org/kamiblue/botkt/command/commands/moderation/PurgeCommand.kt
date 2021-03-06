package org.kamiblue.botkt.command.commands.moderation

import kotlinx.coroutines.delay
import net.ayataka.kordis.entity.message.Message
import net.ayataka.kordis.entity.server.Server
import net.ayataka.kordis.event.events.message.MessageDeleteEvent
import net.ayataka.kordis.event.events.message.MessageEditEvent
import net.ayataka.kordis.event.events.message.MessageReceiveEvent
import org.kamiblue.botkt.ConfigType
import org.kamiblue.botkt.LoggingConfig
import org.kamiblue.botkt.PermissionTypes
import org.kamiblue.botkt.Permissions.hasPermission
import org.kamiblue.botkt.command.BotCommand
import org.kamiblue.botkt.command.Category
import org.kamiblue.botkt.command.options.HasPermission
import org.kamiblue.botkt.manager.managers.ConfigManager
import org.kamiblue.botkt.utils.*
import org.kamiblue.botkt.utils.StringUtils.toHumanReadable
import org.kamiblue.event.listener.asyncListener
import java.time.Instant

object PurgeCommand : BotCommand(
    name = "purge",
    category = Category.MODERATION,
    description = "Purges a number of messages in a channel based on parameters."
) {
    private val cachedMsgs = ArrayDeque<Pair<Long, Message>>(110)
    private val config get() = ConfigManager.readConfigSafe<LoggingConfig>(ConfigType.LOGGING, false)

    init {
        int("amount") { numberArg ->
            execute("Purge X messages, excluding protected", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                val msgs = message.channel
                    .getMessages()
                    .filter { !it.author.hasPermission(PermissionTypes.COUNCIL_MEMBER) && it.author?.bot == false }
                    .take(numberArg.value)

                purge(msgs, message)
            }
        }

        user("purge this user") { userArg ->
            int("amount") { numberArg ->
                execute("Purge X messages sent by a user", HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                    val user = userArg.value
                    if (
                        message.author?.id != user.id &&
                        !message.author.hasPermission(PermissionTypes.PURGE_PROTECTED) &&
                        (user.hasPermission(PermissionTypes.COUNCIL_MEMBER) || user.bot)
                    ) {
                        channel.error(
                            "Sorry, but you're missing the " +
                                "'${PermissionTypes.PURGE_PROTECTED.name.toHumanReadable()}'" +
                                " permission, which is required to purge " +
                                "'${PermissionTypes.COUNCIL_MEMBER.name.toHumanReadable()}'" +
                                " messages / bot messages"
                        )
                        return@execute
                    }

                    val msgs = message.channel
                        .getMessages()
                        .filter { it.author?.id == user.id }
                        .take(numberArg.value)

                    purge(msgs, message)
                }
            }
        }

        literal("protected", "force") {
            int("amount") { numberArg ->
                execute("Purge X messages, including council & bot", HasPermission.get(PermissionTypes.PURGE_PROTECTED)) {
                    val msgs = message.channel
                        .getMessages()
                        .take(numberArg.value)

                    purge(msgs, message)
                }
            }
        }

        asyncListener<MessageEditEvent> {
            if (it.message == null) return@asyncListener
            val oldMessage = cachedMsgs.find { cache -> cache.first == it.messageId }

            log(oldMessage?.second, it.message!!, it.server, true)
        }

        asyncListener<MessageDeleteEvent> { deletedMsg -> // TODO: Kordis doesn't support content of deleted messages
            val messages: ArrayList<Message> = arrayListOf()

            deletedMsg.messageIds.forEach { deleted ->
                cachedMsgs.find { cache -> cache.first == deleted }?.let { cached ->
                    messages.add(cached.second)
                }
            }

            messages.sortedBy { msg -> msg.timestamp.prettyFormat() }.forEach { message ->
                log(null, message, deletedMsg.server, false)
                delay(100)
            }
        }

        asyncListener<MessageReceiveEvent> {
            cacheMessage(it.message)
        }
    }

    private suspend fun log(oldMessage: Message?, message: Message, server: Server?, edit: Boolean) {
        val channel = server?.textChannels?.find(config?.loggingChannel ?: -1)
        if (config?.loggingChannel == null || channel == null) return
        if (config?.ignoreChannels?.contains(message.channel.id) == true) return
        if (config?.loggingChannel == message.channel.id) return

        config?.ignorePrefix?.let { prefix ->
            if (message.author.hasPermission(PermissionTypes.COUNCIL_MEMBER) &&
                (message.content.startsWith(prefix) || oldMessage?.content?.startsWith(prefix) == true)
            ) return
        }

        if (edit) {
            channel.send {
                embed {
                    oldMessage?.let {
                        joinToFields(it.content.lines(), "\n", titlePrefix = "Original Message")
                    }
                    joinToFields(message.content.lines(), "\n", titlePrefix = "New Message")

                    description = message.contextLink
                    author(message.author?.tag, iconUrl = message.author?.avatar?.url)
                    footer("ID: ${message.author?.id}")
                    timestamp = Instant.now()
                    color = Colors.EDITED_MESSAGE.color
                }
            }
        } else {
            channel.send {
                embed {
                    joinToFields(message.content.lines(), "\n", titlePrefix = "Deleted Message")
                    description = message.contextLink
                    author(message.author?.tag, iconUrl = message.author?.avatar?.url)
                    footer("ID: ${message.author?.id}")
                    timestamp = Instant.now()
                    color = Colors.ERROR.color
                }
            }
        }
    }

    private fun cacheMessage(toCache: Message?) { // TODO: Kordis does not support viewing the old edited message
        while (cachedMsgs.size >= 100) {
            cachedMsgs.removeFirstOrNull()
        }

        toCache?.let { message ->
            if (message.content.isBlank()) return
            cachedMsgs.add(Pair(message.id, message))
        }
    }

    private suspend fun purge(msgs: Collection<Message>, message: Message) {
        val response = message.channel.send {
            embed {
                field(
                    "${msgs.size} messages were purged by:",
                    message.author?.mention.toString()
                )
                footer("ID: ${message.author?.id}", message.author?.avatar?.url)
                color = Colors.ERROR.color
            }
        }

        msgs.tryDeleteAll() // we want to safe delete because messages could get deleted by users while purging
        delay(5000)
        response.tryDelete()
        message.tryDelete()
    }
}
