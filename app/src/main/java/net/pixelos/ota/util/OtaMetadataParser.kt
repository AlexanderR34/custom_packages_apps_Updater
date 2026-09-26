/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.util

import android.os.Build
import android.ota.nano.OtaPackageMetadata.OtaMetadata
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Properties
import java.util.zip.ZipFile

class OtaMetadataParser @Throws(IOException::class) constructor(file: File) {
    var sdkLevel: Int = Build.VERSION.SDK_INT
        private set
    var securityPatchLevel: String = Build.VERSION.SECURITY_PATCH
        private set
    var timestamp: Long = System.currentTimeMillis() / 1000
        private set
    var isABUpdate: Boolean = true
        private set

    init {
        ZipFile(file).use { zipFile ->
            val hasPayload = zipFile.getEntry("payload.bin") != null
            isABUpdate = hasPayload

            val protoEntry = zipFile.getEntry(METADATA_PROTO_NAME)
            if (protoEntry != null) {
                try {
                    val metadata = zipFile.getInputStream(protoEntry).use { input ->
                        OtaMetadata.parseFrom(input.readBytes())
                    }
                    if (metadata.type == OtaMetadata.BLOCK) {
                        isABUpdate = false
                    }
                    val postcondition = metadata.postcondition
                    if (postcondition != null) {
                        val parsedSdk = postcondition.sdkLevel?.toIntOrNull() ?: 0
                        if (parsedSdk > 0) {
                            sdkLevel = parsedSdk
                        }
                        if (!postcondition.securityPatchLevel.isNullOrEmpty()) {
                            securityPatchLevel = postcondition.securityPatchLevel
                        }
                        if (postcondition.timestamp > 0) {
                            timestamp = postcondition.timestamp
                        }
                    }
                } catch (ignored: Exception) {
                    // Fallback to text metadata or defaults
                }
            } else {
                val textEntry = zipFile.getEntry(METADATA_TEXT_NAME)
                if (textEntry != null) {
                    try {
                        val props = Properties()
                        zipFile.getInputStream(textEntry).use { input ->
                            props.load(InputStreamReader(input))
                        }
                        val otaType = props.getProperty("ota-type")
                        if (otaType != null && otaType.equals("BLOCK", ignoreCase = true)) {
                            isABUpdate = false
                        }
                        val tsStr = props.getProperty("post-timestamp")
                        if (tsStr != null) {
                            timestamp = tsStr.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
                        }
                        val patchStr = props.getProperty("post-security-patch-level")
                        if (!patchStr.isNullOrEmpty()) {
                            securityPatchLevel = patchStr
                        }
                        val sdkStr = props.getProperty("post-sdk-level")
                        if (sdkStr != null) {
                            sdkLevel = sdkStr.toIntOrNull() ?: Build.VERSION.SDK_INT
                        }
                    } catch (ignored: Exception) {
                        // Keep safe fallback defaults
                    }
                }
            }
        }
    }

    companion object {
        private const val METADATA_PROTO_NAME = "META-INF/com/android/metadata.pb"
        private const val METADATA_TEXT_NAME = "META-INF/com/android/metadata"
    }
}
