package org.kamiblue.botkt.command.commands.`fun`

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import net.ayataka.kordis.entity.message.Message
import net.ayataka.kordis.entity.server.Server
import org.kamiblue.botkt.Main
import org.kamiblue.botkt.PermissionTypes
import org.kamiblue.botkt.command.*
import org.kamiblue.botkt.command.options.HasPermission
import org.kamiblue.botkt.utils.StringUtils.toHumanReadable
import org.kamiblue.botkt.utils.error
import org.kamiblue.botkt.utils.normal
import java.io.FileNotFoundException

object StealEmojiCommand : BotCommand(
    name = "stealemoji",
    category = Category.FUN,
    description = "Emoji theif!"
) {
    init {
        emoji("emoji") { emojiArg ->
            execute(HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                val emoji = emojiArg.value

                if (!emoji.isCustom) {
                    channel.error("Emoji must be a custom emoji")
                    return@execute
                }

                val extension = if (emojiArg.value.animated) "gif" else "png"

                val bytes = Main.discordHttp.get<ByteArray> {
                    url("https://cdn.discordapp.com/emojis/${emoji.id}.$extension")
                }

                addEmoji(emoji.name, bytes, message, server)
            }
        }

        string("name") { name ->
            long("emoji id") { idArg ->
                execute(HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                    val id = idArg.value
                    val bytes = downloadFromId(id) ?: return@execute
                    addEmoji(name.value, bytes, message, server)
                }
            }

            greedy("emoji url") { urlArg ->
                execute(HasPermission.get(PermissionTypes.COUNCIL_MEMBER)) {
                    val idUnchecked = try {
                        urlArg.value.substring(34, 52)
                    } catch (e: StringIndexOutOfBoundsException) {
                        channel.error("${urlArg.name.toHumanReadable()} is not valid format!")
                        return@execute
                    }

                    val id = idUnchecked.toLongOrNull() ?: run {
                        channel.error("Emoji ID `$idUnchecked` could not be formatted to a Long!")
                        return@execute
                    }

                    val bytes = downloadFromId(id) ?: return@execute
                    addEmoji(name.value, bytes, message, server)
                }
            }
        }
    }

    private suspend fun MessageExecuteEvent.downloadFromId(id: Long): ByteArray? {
        return try {
            try {
                Main.discordHttp.get<ByteArray> {
                    url("https://cdn.discordapp.com/emojis/$id.gif")
                }
            } catch (e: ClientRequestException) {
                Main.discordHttp.get<ByteArray> {
                    url("https://cdn.discordapp.com/emojis/$id.png")
                }
            }
        } catch (e: FileNotFoundException) {
            channel.error("Couldn't find an emoji with the ID `$id`!")
            null
        }
    }

    private suspend fun addEmoji(emojiName: String, emojiImage: ByteArray, message: Message, server: Server?) {
        val foundEmoji = server?.emojis?.findByName(emojiName)
        if (foundEmoji != null) {
            message.channel.error("There is already an emoji with the name `$emojiName`!")
            return
        }

        val emoji = server?.createEmoji {
            name = emojiName
            image = emojiImage
        } ?: run {
            message.channel.error("Guild is null, make sure you're not running this from a DM!")
            return
        }

        message.channel.normal("Successfully stolen emoji `${emoji.name}`!")
    }
}
