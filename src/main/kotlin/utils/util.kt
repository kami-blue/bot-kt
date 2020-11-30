package org.kamiblue.botkt.utils

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ayataka.kordis.entity.server.Server
import net.ayataka.kordis.entity.server.permission.PermissionSet
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kamiblue.botkt.AuthConfig
import org.kamiblue.botkt.ConfigManager.readConfigSafe
import org.kamiblue.botkt.ConfigType
import org.kamiblue.botkt.CounterConfig
import org.kamiblue.botkt.utils.MessageSendUtils.log
import org.kamiblue.botkt.utils.StringUtils.toHumanReadable
import java.util.concurrent.TimeUnit

/**
 * @return a pretty formatted set of permissions, "None" if empty
 */
fun PermissionSet.pretty() =
    if (this.isEmpty()) "None"
    else this.joinToString { it.name.toHumanReadable() }

/**
 * @return [T] from [url]
 */
inline fun <reified T> request(url: String): T {
    val request = Request.Builder().url(url).get().build()
    val response = OkHttpClient().newCall(request).execute()

    return Gson().fromJson(response.body!!.string(), T::class.java)
}

/**
 * [authType] is the type of header to be used. "Bot" is for Discord's API, while Github uses "token".
 * @return [T] from [url] with the [token] as the Authorization header
 */
inline fun <reified T> authenticatedRequest(authType: String, token: String, url: String): T {
    val request = Request.Builder().addHeader("Authorization", "$authType $token").url(url).get().build()
    val response = OkHttpClient().newCall(request).execute()

    return Gson().fromJson(response.body!!.string(), T::class.java)
}

/**
 * Defaults to 10 minutes if null
 * @return update interval for member / download counters in milliseconds
 */
fun configUpdateInterval(): Long {
    val updateInterval = readConfigSafe<CounterConfig>(ConfigType.COUNTER, false)?.updateInterval
        ?: return TimeUnit.MINUTES.toMillis(10)
    return TimeUnit.MINUTES.toMillis(updateInterval)
}

/**
 * @return non-null bot authentication token
 */
fun getAuthToken(): String {
    return readConfigSafe<AuthConfig>(ConfigType.AUTH, false)!!.botToken
}

fun Server.maxEmojiSlots(): Int {
    val url = "https://discord.com/api/v6/guilds/${this.id}"
    val request = Request.Builder()
            .addHeader("Authorization", "Bot ${getAuthToken()}")
            .url(url).get().build()

    val response = OkHttpClient().newCall(request).execute()
    val jsonObject = response.body?.charStream()?.use {
        JsonParser.parseReader(it)
    } as? JsonObject

    val premiumTier = try {
        jsonObject?.get("premium_tier")?.asInt
    } catch (e: Exception) {
        log("Error getting premium tier")
        e.printStackTrace()
        0
    }

    return when (premiumTier) {
        1 -> 100
        2 -> 150
        3 -> 250
        else -> 50
    }
}