/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package net.pixelos.ota.misc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.storage.StorageManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import net.pixelos.ota.R;
import net.pixelos.ota.controller.UpdaterService;
import net.pixelos.ota.data.UserPreferencesRepository;
import net.pixelos.ota.data.Update;
import net.pixelos.ota.data.source.local.UpdatesLocalDataSource;
import net.pixelos.ota.data.source.local.UpdatesDatabase;
import net.pixelos.ota.deviceinfo.DeviceInfoUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Utils {

    private static final String TAG = "Utils";

    private Utils() {
    }

    public static File getDownloadPath(Context context) {
        return new File(context.getString(R.string.download_path));
    }

    public static void triggerUpdate(Context context, String downloadId) {
        final Intent intent = new Intent(context, UpdaterService.class);
        intent.setAction(UpdaterService.ACTION_INSTALL_UPDATE);
        intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId);
        context.startService(intent);
    }

    /**
     * Get the exact byte offset to the uncompressed payload data of an entry inside the given zip file.
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the uncompressed data in bytes
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath) {
        long off = getZipEntryOffsetFromMetadata(zipFile, entryPath);
        if (off > 0) {
            return off;
        }
        return getZipEntryOffset(new File(zipFile.getName()), entryPath);
    }

    /**
     * Get the exact byte offset to the uncompressed payload data of an entry inside the given zip file.
     *
     * @param file input file
     * @param entryPath full path of the entry
     * @return the offset of the uncompressed data in bytes
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(File file, String entryPath) {
        try (ZipFile zipFile = new ZipFile(file)) {
            long off = getZipEntryOffsetFromMetadata(zipFile, entryPath);
            if (off > 0) {
                return off;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get offset from metadata: " + e.getMessage());
        }

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long len = raf.length();
            if (len < 22) {
                throw new IllegalArgumentException("File too short to be a valid ZIP");
            }

            long searchLimit = Math.max(0, len - 65557);
            long eocdOffset = -1;
            for (long pos = len - 22; pos >= searchLimit; pos--) {
                raf.seek(pos);
                if (readIntLE(raf) == 0x06054b50L) {
                    eocdOffset = pos;
                    break;
                }
            }

            if (eocdOffset == -1) {
                throw new IllegalArgumentException("EOCD record not found in ZIP");
            }

            raf.seek(eocdOffset + 10);
            int totalEntries = readShortLE(raf);
            raf.seek(eocdOffset + 16);
            long cdOffset = readIntLE(raf);

            raf.seek(cdOffset);
            for (int i = 0; i < totalEntries; i++) {
                long currentPos = raf.getFilePointer();
                long sig = readIntLE(raf);
                if (sig != 0x02014b50L) {
                    break;
                }

                raf.seek(currentPos + 28);
                int fnLen = readShortLE(raf);
                int extraLen = readShortLE(raf);
                int commentLen = readShortLE(raf);

                raf.seek(currentPos + 42);
                long localHeaderOffset = readIntLE(raf);

                byte[] fnBytes = new byte[fnLen];
                raf.readFully(fnBytes);
                String name = new String(fnBytes, java.nio.charset.StandardCharsets.UTF_8);

                if (name.equals(entryPath)) {
                    raf.seek(localHeaderOffset);
                    long localSig = readIntLE(raf);
                    if (localSig != 0x04034b50L) {
                        throw new IllegalArgumentException("Invalid Local File Header signature at offset " + localHeaderOffset);
                    }

                    raf.seek(localHeaderOffset + 26);
                    int localFnLen = readShortLE(raf);
                    int localExtraLen = readShortLE(raf);

                    return localHeaderOffset + 30 + localFnLen + localExtraLen;
                }

                raf.seek(currentPos + 46 + fnLen + extraLen + commentLen);
            }
        } catch (java.io.IOException e) {
            Log.e(TAG, "Error parsing ZIP structure for " + entryPath, e);
        }

        Log.e(TAG, "Entry " + entryPath + " not found in " + file);
        throw new IllegalArgumentException("The given entry was not found: " + entryPath);
    }

    private static long getZipEntryOffsetFromMetadata(ZipFile zipFile, String entryPath) {
        ZipEntry metadataEntry = zipFile.getEntry("META-INF/com/android/metadata");
        if (metadataEntry != null) {
            try (java.io.InputStream is = zipFile.getInputStream(metadataEntry);
                 java.io.InputStreamReader isr = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
                 java.io.BufferedReader br = new java.io.BufferedReader(isr)) {
                for (String line; (line = br.readLine()) != null;) {
                    if (line.startsWith("ota-property-files=")) {
                        String[] entries = line.substring("ota-property-files=".length()).split(",");
                        for (String e : entries) {
                            String[] parts = e.trim().split(":");
                            if (parts.length >= 2 && parts[0].equals(entryPath)) {
                                long off = Long.parseLong(parts[1]);
                                if (off > 0) {
                                    return off;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed reading ota-property-files: " + e.getMessage());
            }
        }
        return -1;
    }

    private static int readShortLE(java.io.RandomAccessFile raf) throws java.io.IOException {
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        return (b2 << 8) | b1;
    }

    private static long readIntLE(java.io.RandomAccessFile raf) throws java.io.IOException {
        long b1 = raf.readUnsignedByte();
        long b2 = raf.readUnsignedByte();
        long b3 = raf.readUnsignedByte();
        long b4 = raf.readUnsignedByte();
        return (b4 << 24) | (b3 << 16) | (b2 << 8) | b1;
    }

    public static void removeUncryptFiles(File downloadPath) {
        File[] uncryptFiles = downloadPath.listFiles(
                (dir, name) -> name.endsWith(Constants.UNCRYPT_FILE_EXT));
        if (uncryptFiles == null) {
            return;
        }
        for (File file : uncryptFiles) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * Cleanup the download directory, which is assumed to be a privileged location
     * the user can't access and that might have stale files. This can happen if
     * the data of the application are wiped.
     *
     */
    public static void cleanupDownloadsDir(Context context,
            UserPreferencesRepository userPreferencesRepository) {
        File downloadPath = getDownloadPath(context);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        long buildTimestamp = DeviceInfoUtils.getBuildDateTimestamp();
        long prevTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, 0);
        String lastUpdatePath = preferences.getString(Constants.PREF_INSTALL_PACKAGE_PATH, null);
        boolean reinstalling = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false);
        boolean deleteUpdates = userPreferencesRepository.getAutoDeleteBlocking();
        if ((buildTimestamp != prevTimestamp || reinstalling) && deleteUpdates &&
                lastUpdatePath != null) {
            File lastUpdate = new File(lastUpdatePath);
            if (lastUpdate.exists()) {
                //noinspection ResultOfMethodCallIgnored
                lastUpdate.delete();
                // Remove the pref not to delete the file if re-downloaded
                preferences.edit().remove(Constants.PREF_INSTALL_PACKAGE_PATH).apply();
            }
        }

        final String DOWNLOADS_CLEANUP_DONE = "cleanup_done";
        if (preferences.getBoolean(DOWNLOADS_CLEANUP_DONE, false)) {
            return;
        }

        Log.d(TAG, "Cleaning " + downloadPath);
        if (!downloadPath.isDirectory()) {
            return;
        }
        File[] files = downloadPath.listFiles();
        if (files == null) {
            return;
        }

        // Ideally the database is empty when we get here
        List<String> knownPaths = new ArrayList<>();
        UpdatesLocalDataSource db =
                new UpdatesLocalDataSource(UpdatesDatabase.getInstance(context).updateDao());
        for (Update update : db.getUpdates()) {
            if (update.getFile() != null) {
                knownPaths.add(update.getFile().getAbsolutePath());
            }
        }
        for (File file : files) {
            if (!knownPaths.contains(file.getAbsolutePath())) {
                Log.d(TAG, "Deleting " + file.getAbsolutePath());
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        preferences.edit().putBoolean(DOWNLOADS_CLEANUP_DONE, true).apply();
    }

    public static File appendSequentialNumber(final File file) {
        String name;
        String extension;
        int extensionPosition = file.getName().lastIndexOf(".");
        if (extensionPosition > 0) {
            name = file.getName().substring(0, extensionPosition);
            extension = file.getName().substring(extensionPosition);
        } else {
            name = file.getName();
            extension = "";
        }
        final File parent = file.getParentFile();
        for (int i = 1; i < Integer.MAX_VALUE; i++) {
            File newFile = new File(parent, name + "-" + i + extension);
            if (!newFile.exists()) {
                return newFile;
            }
        }
        throw new IllegalStateException();
    }

    public static boolean isEncrypted(Context context, File file) {
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        return sm.isEncrypted(file);
    }
}
