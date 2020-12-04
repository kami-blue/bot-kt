package org.kamiblue.botkt.command.commands

import org.kamiblue.botkt.command.*
import org.kamiblue.botkt.utils.AnimatableEmoji
import org.kamiblue.botkt.utils.MessageSendUtils.error
import org.kamiblue.botkt.utils.MessageSendUtils.normal
import org.kamiblue.botkt.utils.StringUtils.readBytes

object StealEmojiCommand : CommandOld("stealemoji") {
    init {
        emoji("emoji") {
            doesLater { context ->
                val aEmoji: AnimatableEmoji = context arg "emoji"

                val foundEmoji = server?.emojis?.findByName(aEmoji.emoji.name)
                if (foundEmoji != null) {
                    message.error("There is already an emoji with the name `${aEmoji.emoji.name}`!")
                    return@doesLater
                }

                val extension = if (aEmoji.animated) "gif" else "png"
                val bytes = "https://cdn.discordapp.com/emojis/${aEmoji.emoji.id}.$extension".readBytes()

                val emoji = server?.createEmoji {
                    name = aEmoji.emoji.name
                    image = bytes
                } ?: run {
                    message.error("Guild is null, make sure you're not running this from a DM!")
                    return@doesLater
                }

                message.normal("Successfully stolen emoji `${emoji.name}`!")
            }

        }
    }
}