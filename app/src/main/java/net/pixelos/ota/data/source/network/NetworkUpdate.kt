/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalSerializationApi::class)

package net.pixelos.ota.data.source.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import net.pixelos.ota.data.Update
import net.pixelos.ota.misc.Constants
import java.net.URI

// Commented out fields below are available in production, but not needed in runtime.
// If you wish to uncomment any of them, please update the README.md to indicate that.

@Suppress("PROVIDED_RUNTIME_TOO_LOW")
@Serializable
@JsonIgnoreUnknownKeys
data class NetworkUpdate(
    // @SerialName("date") val date: String,
    @SerialName("datetime") val datetime: Long,
    @SerialName("files") val files: List<NetworkUpdateFile>,
    @SerialName("incremental") val incremental: List<NetworkUpdateFile>? = null,
    @SerialName("version") val version: String,
)

@Suppress("PROVIDED_RUNTIME_TOO_LOW")
@Serializable
@JsonIgnoreUnknownKeys
data class NetworkUpdateFile(
    // @SerialName("date") val date: String? = null,
    // @SerialName("datetime") val datetime: Long? = null,
    @SerialName("filename") val filename: String,
    // @SerialName("filepath") val filepath: String,
    @SerialName("os_patch_level") val osPatchLevel: String,
    @SerialName("os_sdk_level") val osSdkLevel: Int,
    @SerialName("ota_property_files") val otaPropertyFiles: String? = null,
    // @SerialName("sha1") val sha1: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("size") val size: Long,
    // @SerialName("type") val type: String? = null,
    @SerialName("url") val url: String,
)

data class PackageFileRange(
    val offset: Long,
    val size: Long,
)

fun String.parsePackageFileRanges(packageSize: Long) =
    split(",").associate { token ->
        val parts = token.trim().split(":", limit = 3)
        require(parts.size == 3) { "Malformed ota_property_files entry: $token" }
        val name = parts[0].trim()
        val offset = parts[1].trim().toLong()
        val size = parts[2].trim().toLong()
        require(name.isNotEmpty()) { "Empty ota_property_files name" }
        require(offset >= 0 && size > 0 && offset <= Long.MAX_VALUE - size) {
            "Invalid range for $name"
        }
        require(offset + size <= packageSize) { "Range for $name exceeds package size" }
        name to PackageFileRange(offset = offset, size = size)
    }

fun NetworkUpdateFile.validate(label: String) {
    require(filename.isNotBlank()) { "$label.filename must not be blank" }
    require(osPatchLevel.isNotBlank()) { "$label.os_patch_level must not be blank" }
    require(osSdkLevel > 0) { "$label.os_sdk_level must be positive" }
    require(sha256.matches(Regex("[0-9a-f]{64}"))) { "$label.sha256 must be lowercase hex" }
    require(size > 0) { "$label.size must be positive" }
    require(URI(url).scheme.equals("https", ignoreCase = true)) {
        "$label URL must use HTTPS"
    }
    otaPropertyFiles?.parsePackageFileRanges(size)?.let { ranges ->
        require(Constants.AB_PAYLOAD_METADATA_PATH in ranges) {
            "ota_property_files is missing ${Constants.AB_PAYLOAD_METADATA_PATH}"
        }
        require(Constants.AB_PAYLOAD_BIN_PATH in ranges) {
            "ota_property_files is missing ${Constants.AB_PAYLOAD_BIN_PATH}"
        }
        require(Constants.AB_PAYLOAD_PROPERTIES_PATH in ranges) {
            "ota_property_files is missing ${Constants.AB_PAYLOAD_PROPERTIES_PATH}"
        }
    }
}

fun NetworkUpdate.validate() {
    require(datetime > 0) { "datetime must be positive" }
    require(files.size == 1) { "Each update must contain exactly one file" }
    require(version.isNotBlank()) { "version must not be blank" }

    files.single().validate("file")
    incremental?.let {
        require(it.size == 1) { "Each update must contain exactly one incremental file" }
        it.single().validate("incremental file")
    }
}

private fun NetworkUpdate.toUpdate(file: NetworkUpdateFile): Update {
    val packageFileRanges = file.otaPropertyFiles?.parsePackageFileRanges(file.size).orEmpty()
    val payloadMetadataRange = packageFileRanges[Constants.AB_PAYLOAD_METADATA_PATH]
    val payloadRange = packageFileRanges[Constants.AB_PAYLOAD_BIN_PATH]
    val payloadPropertiesRange = packageFileRanges[Constants.AB_PAYLOAD_PROPERTIES_PATH]

    return Update(
        downloadId = file.sha256,
        name = file.filename,
        timestamp = datetime,
        fileSize = file.size,
        downloadUrl = file.url,
        version = version,
        osPatchLevel = file.osPatchLevel,
        osSdkLevel = file.osSdkLevel,
        payloadMetadataOffset = payloadMetadataRange?.offset,
        payloadMetadataSize = payloadMetadataRange?.size,
        payloadOffset = payloadRange?.offset,
        payloadSize = payloadRange?.size,
        payloadPropertiesOffset = payloadPropertiesRange?.offset,
        payloadPropertiesSize = payloadPropertiesRange?.size,
        isAvailableOnline = true,
    )
}

fun NetworkUpdate.toUpdate(): Update = toUpdate(files[0])

fun NetworkUpdate.toIncrementalUpdate(): Update? {
    val file = incremental?.firstOrNull() ?: return null
    return toUpdate(file)
}
