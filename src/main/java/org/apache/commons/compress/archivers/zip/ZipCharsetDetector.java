/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.commons.compress.archivers.zip;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.IOUtils;

/**
 * Utility class for detecting and handling ZIP archive character set encoding issues,
 * particularly for Chinese filenames encoded in GBK/UTF-8 mixed encoding.
 *
 * <p>
 * ZIP archives created on Windows Chinese systems often use GBK encoding for filenames
 * without setting the UTF-8 flag (EFS bit) in the general purpose bit flag. This utility
 * provides automatic charset detection to correctly decode such filenames.
 * </p>
 *
 * <p>
 * The detection strategy works as follows:
 * </p>
 * <ol>
 *   <li>If the UTF-8 flag is set in the ZIP entry, use UTF-8 directly.</li>
 *   <li>If the raw filename bytes are valid UTF-8, decode as UTF-8.</li>
 *   <li>Otherwise, fall back to GBK encoding (common for Chinese Windows systems).</li>
 * </ol>
 *
 * @since 1.29.0
 */
public final class ZipCharsetDetector {

    private static final Charset GBK_CHARSET;

    static {
        Charset gbk;
        try {
            gbk = Charset.forName("GBK");
        } catch (final Exception e) {
            gbk = StandardCharsets.UTF_8;
        }
        GBK_CHARSET = gbk;
    }

    private ZipCharsetDetector() {
    }

    /**
     * Detects the character set used to encode the given raw filename bytes.
     *
     * <p>
     * The detection logic:
     * </p>
     * <ol>
     *   <li>If {@code hasUtf8Flag} is true, returns UTF-8.</li>
     *   <li>If the bytes form valid UTF-8 (no replacement characters when decoded), returns UTF-8.</li>
     *   <li>Otherwise, returns GBK as the fallback encoding.</li>
     * </ol>
     *
     * @param rawName     the raw filename bytes from the ZIP entry.
     * @param hasUtf8Flag whether the UTF-8 flag (EFS bit) is set in the general purpose bit flag.
     * @return the detected charset, never null.
     */
    public static Charset detectCharset(final byte[] rawName, final boolean hasUtf8Flag) {
        if (hasUtf8Flag) {
            return StandardCharsets.UTF_8;
        }
        if (isValidUtf8(rawName)) {
            return StandardCharsets.UTF_8;
        }
        return GBK_CHARSET;
    }

    /**
     * Decodes the raw filename bytes using the detected character set.
     *
     * @param rawName     the raw filename bytes from the ZIP entry.
     * @param hasUtf8Flag whether the UTF-8 flag (EFS bit) is set in the general purpose bit flag.
     * @return the decoded filename string.
     */
    public static String decodeName(final byte[] rawName, final boolean hasUtf8Flag) {
        final Charset charset = detectCharset(rawName, hasUtf8Flag);
        return new String(rawName, charset);
    }

    /**
     * Extracts all entries from a ZIP archive to the specified output directory,
     * automatically detecting the character set for each entry's filename.
     *
     * <p>
     * This method handles ZIP archives with mixed GBK/UTF-8 encoded filenames by
     * detecting the encoding of each entry individually.
     * </p>
     *
     * @param zipFile    the ZIP archive file to extract.
     * @param outputDir  the directory to extract files to.
     * @throws IOException if an I/O error occurs.
     */
    public static void extractWithCharsetDetect(final File zipFile, final File outputDir) throws IOException {
        extractWithCharsetDetect(zipFile, outputDir, null);
    }

    /**
     * Extracts all entries from a ZIP archive to the specified output directory,
     * automatically detecting the character set for each entry's filename.
     *
     * <p>
     * This method handles ZIP archives with mixed GBK/UTF-8 encoded filenames by
     * detecting the encoding of each entry individually.
     * </p>
     *
     * @param zipFile       the ZIP archive file to extract.
     * @param outputDir     the directory to extract files to.
     * @param entryCallback optional callback invoked for each extracted entry; may be null.
     * @throws IOException if an I/O error occurs.
     */
    public static void extractWithCharsetDetect(final File zipFile, final File outputDir,
            final EntryCallback entryCallback) throws IOException {
        try (ZipFile zip = ZipFile.builder().setFile(zipFile).get()) {
            final java.util.Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry entry = entries.nextElement();
                extractEntry(zip, entry, outputDir, entryCallback);
            }
        }
    }

    /**
     * Extracts all entries from a ZIP archive input stream to the specified output directory,
     * automatically detecting the character set for each entry's filename.
     *
     * <p>
     * This method handles ZIP archives with mixed GBK/UTF-8 encoded filenames by
     * detecting the encoding of each entry individually.
     * </p>
     *
     * @param inputStream the ZIP archive input stream.
     * @param outputDir   the directory to extract files to.
     * @throws IOException if an I/O error occurs.
     */
    public static void extractWithCharsetDetect(final InputStream inputStream, final File outputDir) throws IOException {
        extractWithCharsetDetect(inputStream, outputDir, null);
    }

    /**
     * Extracts all entries from a ZIP archive input stream to the specified output directory,
     * automatically detecting the character set for each entry's filename.
     *
     * <p>
     * This method handles ZIP archives with mixed GBK/UTF-8 encoded filenames by
     * detecting the encoding of each entry individually.
     * </p>
     *
     * @param inputStream   the ZIP archive input stream.
     * @param outputDir     the directory to extract files to.
     * @param entryCallback optional callback invoked for each extracted entry; may be null.
     * @throws IOException if an I/O error occurs.
     */
    public static void extractWithCharsetDetect(final InputStream inputStream, final File outputDir,
            final EntryCallback entryCallback) throws IOException {
        try (ZipArchiveInputStream zis = ZipArchiveInputStream.builder()
                .setInputStream(inputStream)
                .setCharset(StandardCharsets.UTF_8)
                .get()) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextZipEntry()) != null) {
                extractEntryFromStream(zis, entry, outputDir, entryCallback);
            }
        }
    }

    /**
     * Extracts all entries from a ZIP archive to the specified output path,
     * automatically detecting the character set for each entry's filename.
     *
     * @param zipPath    the ZIP archive path to extract.
     * @param outputPath the directory to extract files to.
     * @throws IOException if an I/O error occurs.
     * @since 1.29.0
     */
    public static void extractWithCharsetDetect(final Path zipPath, final Path outputPath) throws IOException {
        extractWithCharsetDetect(zipPath.toFile(), outputPath.toFile());
    }

    /**
     * Extracts all entries from a ZIP archive to the specified output path,
     * automatically detecting the character set for each entry's filename.
     *
     * @param zipPath       the ZIP archive path to extract.
     * @param outputPath    the directory to extract files to.
     * @param entryCallback optional callback invoked for each extracted entry; may be null.
     * @throws IOException if an I/O error occurs.
     * @since 1.29.0
     */
    public static void extractWithCharsetDetect(final Path zipPath, final Path outputPath,
            final EntryCallback entryCallback) throws IOException {
        extractWithCharsetDetect(zipPath.toFile(), outputPath.toFile(), entryCallback);
    }

    private static void extractEntry(final ZipFile zip, final ZipArchiveEntry entry, final File outputDir,
            final EntryCallback entryCallback) throws IOException {
        final String decodedName = decodeEntryName(entry);
        final File outputFile = resolveOutputFile(outputDir, decodedName);

        if (entry.isDirectory()) {
            if (!outputFile.exists()) {
                Files.createDirectories(outputFile.toPath());
            }
        } else {
            final File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            try (InputStream in = zip.getInputStream(entry);
                    OutputStream out = new FileOutputStream(outputFile)) {
                IOUtils.copy(in, out);
            }
        }

        if (entryCallback != null) {
            entryCallback.onEntryExtracted(entry, decodedName, outputFile);
        }
    }

    private static void extractEntryFromStream(final ZipArchiveInputStream zis, final ZipArchiveEntry entry,
            final File outputDir, final EntryCallback entryCallback) throws IOException {
        final String decodedName = decodeEntryName(entry);
        final File outputFile = resolveOutputFile(outputDir, decodedName);

        if (entry.isDirectory()) {
            if (!outputFile.exists()) {
                Files.createDirectories(outputFile.toPath());
            }
        } else {
            final File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            try (OutputStream out = new FileOutputStream(outputFile)) {
                IOUtils.copy(zis, out);
            }
        }

        if (entryCallback != null) {
            entryCallback.onEntryExtracted(entry, decodedName, outputFile);
        }
    }

    private static String decodeEntryName(final ZipArchiveEntry entry) {
        final byte[] rawName = entry.getRawName();
        if (rawName == null || rawName.length == 0) {
            return entry.getName();
        }
        final boolean hasUtf8Flag = entry.getGeneralPurposeBit() != null
                && entry.getGeneralPurposeBit().usesUTF8ForNames();
        return decodeName(rawName, hasUtf8Flag);
    }

    private static File resolveOutputFile(final File outputDir, final String entryName) {
        final File outputFile = new File(outputDir, entryName);
        final String canonicalDir = outputDir.getAbsolutePath();
        final String canonicalOutput;
        try {
            canonicalOutput = outputFile.getCanonicalPath();
        } catch (final IOException e) {
            throw new IllegalArgumentException("Invalid entry name: " + entryName, e);
        }
        if (!canonicalOutput.startsWith(canonicalDir + File.separator)
                && !canonicalOutput.equals(canonicalDir)) {
            throw new IllegalArgumentException("Bad entry name: " + entryName);
        }
        return outputFile;
    }

    private static boolean isValidUtf8(final byte[] data) {
        if (data == null || data.length == 0) {
            return true;
        }
        final String decoded = new String(data, StandardCharsets.UTF_8);
        final byte[] reencoded = decoded.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != data.length) {
            return false;
        }
        for (int i = 0; i < data.length; i++) {
            if (data[i] != reencoded[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Callback interface for monitoring entry extraction progress.
     *
     * @since 1.29.0
     */
    public interface EntryCallback {

        /**
         * Called after an entry has been successfully extracted.
         *
         * @param originalEntry the original ZIP archive entry.
         * @param decodedName   the decoded filename after charset detection.
         * @param outputFile    the output file that was created.
         * @throws IOException if an I/O error occurs during callback processing.
         */
        void onEntryExtracted(ZipArchiveEntry originalEntry, String decodedName, File outputFile) throws IOException;
    }
}
