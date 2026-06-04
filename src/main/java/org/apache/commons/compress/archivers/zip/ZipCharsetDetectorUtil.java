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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.IOUtils;

/**
 * Utility class for detecting the charset of ZIP entry names and extracting
 * ZIP archives with automatic charset detection.
 * <p>
 * This is particularly useful for ZIP files created on Windows with Chinese locale
 * where file names are encoded in GBK/GB18030 but the EFS flag (UTF-8 indicator)
 * is not set in the ZIP entry headers.
 * </p>
 *
 * @Immutable
 * @since 1.30
 */
public abstract class ZipCharsetDetectorUtil {

    private static final Charset GBK = lookupCharset("GBK");

    private static Charset lookupCharset(final String name) {
        try {
            return Charset.forName(name);
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * Detects the most likely charset for the given byte array.
     * <p>
     * The detection algorithm first checks if the bytes form valid UTF-8 sequences.
     * If not, it checks if the bytes form valid GBK sequences.
     * If neither, it falls back to UTF-8.
     * </p>
     *
     * @param bytes the raw bytes to analyze.
     * @return the detected charset, never null.
     */
    public static Charset detectCharset(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }
        if (isValidUtf8(bytes)) {
            return StandardCharsets.UTF_8;
        }
        if (GBK != null && isValidGbk(bytes)) {
            return GBK;
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Extracts a ZIP archive to the specified directory with automatic charset
     * detection for entry names.
     * <p>
     * For each entry, the raw name bytes are analyzed to detect the correct charset.
     * If the detected charset differs from UTF-8, the entry name is re-decoded
     * using the detected charset before extraction.
     * </p>
     *
     * @param zipStream the input stream containing the ZIP archive.
     * @param outputDir the directory to extract files to.
     * @throws IOException if an I/O error occurs or a zip slip attack is detected.
     */
    public static void extractWithCharsetDetect(final InputStream zipStream, final Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        final Path normalizedOutputDir = outputDir.toRealPath();
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(zipStream)) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final byte[] rawName = entry.getRawName();
                if (rawName != null && rawName.length > 0) {
                    final Charset detected = detectCharset(rawName);
                    if (!StandardCharsets.UTF_8.equals(detected)) {
                        final String correctName = new String(rawName, detected);
                        entry.setName(correctName);
                    }
                }
                final Path outputPath = normalizedOutputDir.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(normalizedOutputDir)) {
                    throw new IOException("Entry is outside of the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());
                    try (OutputStream os = Files.newOutputStream(outputPath)) {
                        IOUtils.copy(zis, os);
                    }
                }
            }
        }
    }

    static boolean isValidGbk(final byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            final int b = bytes[i] & 0xFF;
            if (b <= 0x7F) {
                i++;
            } else if (b >= 0x81 && b <= 0xFE) {
                if (i + 1 >= bytes.length) {
                    return false;
                }
                final int b2 = bytes[i + 1] & 0xFF;
                if (b2 < 0x40 || b2 == 0x7F || b2 > 0xFE) {
                    return false;
                }
                i += 2;
            } else {
                return false;
            }
        }
        return true;
    }

    static boolean isValidUtf8(final byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            final int b = bytes[i] & 0xFF;
            if (b <= 0x7F) {
                i++;
            } else if (b >= 0xC2 && b <= 0xDF) {
                if (i + 1 >= bytes.length || (bytes[i + 1] & 0xC0) != 0x80) {
                    return false;
                }
                i += 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                if (i + 2 >= bytes.length) {
                    return false;
                }
                if ((bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80) {
                    return false;
                }
                if (b == 0xE0 && (bytes[i + 1] & 0xE0) == 0x80) {
                    return false;
                }
                if (b == 0xED && (bytes[i + 1] & 0xE0) == 0xA0) {
                    return false;
                }
                i += 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                if (i + 3 >= bytes.length) {
                    return false;
                }
                if ((bytes[i + 1] & 0xC0) != 0x80 || (bytes[i + 2] & 0xC0) != 0x80 || (bytes[i + 3] & 0xC0) != 0x80) {
                    return false;
                }
                if (b == 0xF0 && (bytes[i + 1] & 0xF0) == 0x80) {
                    return false;
                }
                if (b == 0xF4 && (bytes[i + 1] & 0xF0) != 0x80) {
                    return false;
                }
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Constructs a new instance.
     *
     * @deprecated Will be removed in 2.0.
     */
    @Deprecated
    public ZipCharsetDetectorUtil() {
    }
}