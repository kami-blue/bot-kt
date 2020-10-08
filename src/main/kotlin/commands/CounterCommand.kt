package commands

import Command
import ConfigManager.readConfigSafe
import ConfigType
import CounterConfig
import Main
import PermissionTypes.UPDATE_COUNTERS
import Send.error
import Send.success
import UserConfig
import authenticatedRequest
import doesLaterIfHas
import getGithubToken
import literal
import org.l1ving.api.download.Asset
import org.l1ving.api.download.Download
import request

object CounterCommand : Command("counter") {
    init {
        literal("update") {
            literal("downloads") {
                doesLaterIfHas(UPDATE_COUNTERS) {
                    val path = ConfigType.COUNTER.configPath.substring(7)
                    val userPath = ConfigType.USER.configPath.substring(7)
                    val config = readConfigSafe<CounterConfig>(ConfigType.COUNTER, false)

                    if (config?.downloadEnabled != true) {
                        message.error("Download counter is not enabled in the `$path` config!")
                    }

                    if (updateChannel()) {
                        message.success("Successfully updated download counters!")
                    } else {
                        message.error("Both total and latest counts failed to update. Make sure `$path` is configured correctly, and `primaryServerId` is set in `$userPath`!")
                    }
                }
            }
            literal("members") {
                doesLaterIfHas(UPDATE_COUNTERS) {
                    // TODO: Add members counter
                    message.error("Member counter is not supported yet!")
                }
            }
        }

    }

    /**
     * @author sourTaste000
     * @since 9/22/2020
     */
    suspend fun updateChannel(): Boolean {
        val config = readConfigSafe<CounterConfig>(ConfigType.COUNTER, false) ?: return false
        if (config.downloadEnabled != true) return false
        val server = readConfigSafe<UserConfig>(ConfigType.USER, false)?.primaryServerId?.let {
            Main.client?.servers?.find(it)
        } ?: run {
            return false
        }

        val totalChannel = config.downloadChannelTotal?.let { server.voiceChannels.find(it) }
        val latestChannel = config.downloadChannelLatest?.let { server.voiceChannels.find(it) }

        var downloadStable: Download? = null
        var downloadNightly: Download? = null

        var updated = false
        val perPage = config.perPage ?: 200

        getGithubToken(null)?.let {
            downloadStable = config.downloadStableUrl?.let { it1 -> authenticatedRequest<Download>("token", it, formatApiUrl(it1, perPage)) }
            downloadNightly = config.downloadNightlyUrl?.let { it1 -> authenticatedRequest<Download>("token", it, formatApiUrl(it1, perPage)) }
        } ?: run {
            downloadStable = config.downloadStableUrl?.let { request<Download>(formatApiUrl(it, perPage)) }
            downloadNightly = config.downloadNightlyUrl?.let { request<Download>(formatApiUrl(it, perPage)) }
        }

        var totalCount = 0
        var latestCount = 0

        downloadStable?.let {
            totalCount += countedDownloads(it)
            latestCount = it[0].assets.count()
        }

        downloadNightly?.let {
            totalCount += countedDownloads(it)
            latestCount = it[0].assets.count() // nightly will be newer, so we assign it again if nightly isn't null
        }


        if (totalCount != 0 || latestCount != 0) {
            totalChannel?.let { it.edit { name = "$totalCount Downloads" } }
            latestChannel?.let { it.edit { name = "$latestCount Nightly DLs" } }
            updated = true
        }
        return updated
    }

    private fun formatApiUrl(repo: String, perPage: Int) = "https://api.github.com/repos/$repo/releases?per_page=$perPage"

    private fun countedDownloads(download: Download): Int {
        var total = 0
        download.forEach { release ->
            total += release.assets.count()
        }
        return total
    }

    private fun List<Asset>.count(): Int {
        var total = 0
        this.forEach { total += it.download_count }
        return total
    }
}