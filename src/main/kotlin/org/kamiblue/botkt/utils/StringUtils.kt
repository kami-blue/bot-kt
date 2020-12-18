package org.kamiblue.botkt.utils

import java.net.URLEncoder

object StringUtils {
    private val urlRegex = "https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_+.~#?&/=]*)".toRegex()
    private val humanReadableRegex = "[_-]".toRegex()

    fun String.isUrl() = urlRegex.matches(this)

    fun String.toHumanReadable() = this.toLowerCase().replace(humanReadableRegex, " ").capitalizeWords()

    fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.capitalize() }

    fun String.urlEncode(): String = URLEncoder.encode(this, "utf-8")

    fun String.toUserID() = this.replace("[<@!>]".toRegex(), "").toLongOrNull()

    fun String.isBlankOrEmpty() = this.isBlank() || this.isEmpty()
}
