/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.data

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import net.pixelos.ota.data.source.local.UpdatesLocalDataSource
import net.pixelos.ota.data.source.network.NetworkUpdate
import net.pixelos.ota.data.source.network.UpdatesNetworkDataSource
import net.pixelos.ota.data.source.network.toIncrementalUpdate
import net.pixelos.ota.data.source.network.toUpdate
import net.pixelos.ota.deviceinfo.DeviceInfoUtils
import net.pixelos.ota.misc.Constants
import net.pixelos.ota.notifications.NotificationHelper
import net.pixelos.ota.util.NetworkMonitor
import org.json.JSONObject
import java.io.IOException

private const val TAG = "UpdatesRepository"

class UpdatesRepository(
    private val context: Context,
    private val networkMonitor: NetworkMonitor,
    private val notificationHelper: NotificationHelper,
    private val networkDataSource: UpdatesNetworkDataSource,
    private val localDataSource: UpdatesLocalDataSource,
) {
    fun observeLocalUpdates(): Flow<List<Update>> = localDataSource.observeUpdates()

    /**
     * Fetches available updates from the server, syncs the local database, and posts a
     * notification if new updates are found. Callers observe [observeLocalUpdates] for results —
     * Room only emits when the stored data actually changes.
     *
     * @return the timestamp of the fetch, or null if skipped due to no network.
     * @throws IOException on network or HTTP errors.
     * @throws SerializationException if the response cannot be parsed.
     */
    suspend fun fetchUpdates(): Long? {
        if (!networkMonitor.currentNetworkState.isOnline) return null

        val networkUpdates = withContext(Dispatchers.IO) {
            val network = networkDataSource.fetchUpdates()
            persistIncrementalLinks(network)
            val deltaUrls = network.mapNotNull { it.incremental?.firstOrNull()?.url }.toSet()
            network.flatMap { listOfNotNull(it.toUpdate(), it.toIncrementalUpdate()) }
                .filter { filterUpdates(it, deltaUrls) }
        }

        val networkIds = networkUpdates.map { it.downloadId }.toSet()

        val localUpdates = withContext(Dispatchers.IO) {
            localDataSource.getUpdates()
        }.associateBy { it.downloadId }

        if (localUpdates.isNotEmpty() && networkUpdates.any { it.downloadId !in localUpdates }) {
            notificationHelper.showNewUpdatesNotification()
        }

        withContext(Dispatchers.IO) {
            // Merge local state into each network update and upsert into the DB.
            // Room's observeUpdates() Flow will emit automatically if anything changed.
            networkUpdates.forEach { networkUpdate ->
                val local = localUpdates[networkUpdate.downloadId]
                val update = if (local != null && local.status.persistentStatus > 0) {
                    networkUpdate.copy(status = local.status, file = local.file)
                } else {
                    networkUpdate
                }
                localDataSource.addUpdate(update)
            }

            // Delete temp files and DB entries for updates no longer advertised by the server.
            localUpdates.values.filter {
                it.downloadId !in networkIds && it.downloadId != Update.LOCAL_ID &&
                        it.downloadUrl != null
            }.forEach {
                it.file?.delete()
                localDataSource.removeUpdate(it.downloadId)
            }
        }

        return System.currentTimeMillis()
    }

    private fun persistIncrementalLinks(network: List<NetworkUpdate>) {
        val links = JSONObject()
        network.forEach { update ->
            update.incremental?.firstOrNull()?.let { delta ->
                links.put(update.files[0].sha256, delta.url)
            }
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Constants.PREF_INCREMENTAL_LINKS, links.toString()).apply()
    }

    private fun filterUpdates(update: Update, deltaUrls: Set<String>): Boolean {
        val isCurrentBuild = update.timestamp == DeviceInfoUtils.buildDateTimestamp
        val isOlderBuild = update.timestamp < DeviceInfoUtils.buildDateTimestamp

        if (!DeviceInfoUtils.isDowngradingAllowed && (isOlderBuild || isCurrentBuild)) {
            Log.d(TAG, "${update.name} is not newer than the current build")
            return false
        }

        if (update.downloadUrl in deltaUrls && !DeviceInfoUtils.isABDevice) {
            Log.d(TAG, "${update.name} is incremental but this device is not A/B")
            return false
        }

        if (update.osSdkLevel < DeviceInfoUtils.sdkLevel) {
            Log.d(TAG, "${update.name} is older than current Android version")
            return false
        }

        return true
    }
}
