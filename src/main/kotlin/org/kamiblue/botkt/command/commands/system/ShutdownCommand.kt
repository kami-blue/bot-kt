package org.kamiblue.botkt.command.commands.system

import org.kamiblue.botkt.Main
import org.kamiblue.botkt.PermissionTypes
import org.kamiblue.botkt.command.BotCommand
import org.kamiblue.botkt.command.Category
import org.kamiblue.botkt.command.options.HasPermission
import org.kamiblue.botkt.utils.Colors

object ShutdownCommand : BotCommand(
    name = "shutdown",
    category = Category.SYSTEM,
    description = "Shutdown the bot"
) {
    init {
        execute(HasPermission.get(PermissionTypes.REBOOT_BOT)) {
            message.channel.send {
                embed {
                    title = "Shutting down..."
                    color = Colors.SUCCESS.color
                }
            }
            Main.exit()
        }
    }
}
