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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A utility class for extracting ZIP archives with automatic charset detection.
 * This class provides methods to detect and decode ZIP file names that might
 * be encoded in various charsets, particularly handling mixed GBK and UTF-8
 * encoded Chinese file names.
 *
 * @since 1.29.0
 */
public class ZipCharsetDetector {

    private static final Charset[] COMMON_CHINESE_CHARSETS = {
        StandardCharsets.UTF_8,
        Charset.forName("GBK"),
        Charset.forName("GB2312"),
        Charset.forName("GB18030"),
        Charset.forName("Big5")
    };

    /**
     * Private constructor to prevent instantiation.
     */
    private ZipCharsetDetector() {
        // Utility class
    }

    /**
     * Extracts a ZIP archive with automatic charset detection for file names.
     * This method tries multiple common charsets (UTF-8, GBK, GB2312, GB18030, Big5)
     * to decode file names and extracts the files to the specified destination directory.
     *
     * @param zipFile the ZIP file to extract
     * @param destDir the destination directory
     * @throws IOException if an I/O error occurs
     */
    public static void extractWithCharsetDetect(final File zipFile, final File destDir) throws IOException {
        extractWithCharsetDetect(zipFile.toPath(), destDir.toPath());
    }

    /**
     * Extracts a ZIP archive with automatic charset detection for file names.
     * This method tries multiple common charsets (UTF-8, GBK, GB2312, GB18030, Big5)
     * to decode file names and extracts the files to the specified destination directory.
     *
     * @param zipPath the path to the ZIP file
     * @param destPath the destination path
     * @throws IOException if an I/O error occurs
     */
    public static void extractWithCharsetDetect(final Path zipPath, final Path destPath) throws IOException {
        try (InputStream is = Files.newInputStream(zipPath)) {
            extractWithCharsetDetect(is, destPath);
        }
    }

    /**
     * Extracts a ZIP archive with automatic charset detection for file names.
     * This method tries multiple common charsets (UTF-8, GBK, GB2312, GB18030, Big5)
     * to decode file names and extracts the files to the specified destination directory.
     *
     * @param inputStream the input stream of the ZIP archive
     * @param destPath the destination path
     * @throws IOException if an I/O error occurs
     */
    public static void extractWithCharsetDetect(final InputStream inputStream, final Path destPath) throws IOException {
        // Read the entire input stream into a byte array first
        final byte[] zipBytes = readAllBytes(inputStream);
        
        // Try each charset and determine the best one
        final Charset bestCharset = detectBestCharset(zipBytes);
        
        // Extract using the best charset
        extractWithCharset(new ByteArrayInputStream(zipBytes), destPath, bestCharset);
    }

    /**
     * Extracts a ZIP archive with a specified charset for file names.
     *
     * @param inputStream the input stream of the ZIP archive
     * @param destPath the destination path
     * @param charset the charset to use for decoding file names
     * @throws IOException if an I/O error occurs
     */
    public static void extractWithCharset(final InputStream inputStream, final Path destPath, final Charset charset) throws IOException {
        try (ZipArchiveInputStream zis = ZipArchiveInputStream.builder()
                .setInputStream(inputStream)
                .setCharset(charset)
                .get()) {
            
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    final Path outputPath = destPath.resolve(entry.getName()).normalize();
                    
                    // Security check: ensure the output path is within the destination directory
                    if (!outputPath.startsWith(destPath.normalize())) {
                        throw new IOException("Entry is outside of the target directory: " + entry.getName());
                    }
                    
                    // Create parent directories if they don't exist
                    Files.createDirectories(outputPath.getParent());
                    
                    // Write the file content
                    Files.copy(zis, outputPath);
                }
            }
        }
    }

    /**
     * Detects the best charset for decoding file names in a ZIP archive.
     * This method tries multiple charsets and selects the one that produces
     * the most valid-looking file names.
     *
     * @param zipBytes the ZIP file bytes
     * @return the best detected charset
     * @throws IOException if an I/O error occurs
     */
    public static Charset detectBestCharset(final byte[] zipBytes) throws IOException {
        Charset bestCharset = StandardCharsets.UTF_8;
        int bestScore = -1;

        for (final Charset charset : COMMON_CHINESE_CHARSETS) {
            final int score = evaluateCharset(zipBytes, charset);
            if (score > bestScore) {
                bestScore = score;
                bestCharset = charset;
            }
        }

        return bestCharset;
    }

    /**
     * Evaluates how well a charset decodes the file names in a ZIP archive.
     * Returns a score based on the number of valid-looking characters.
     *
     * @param zipBytes the ZIP file bytes
     * @param charset the charset to evaluate
     * @return the evaluation score (higher is better)
     * @throws IOException if an I/O error occurs
     */
    private static int evaluateCharset(final byte[] zipBytes, final Charset charset) throws IOException {
        int score = 0;
        try (ZipArchiveInputStream zis = ZipArchiveInputStream.builder()
                .setInputStream(new ByteArrayInputStream(zipBytes))
                .setCharset(charset)
                .get()) {

            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final String name = entry.getName();
                score += evaluateString(name);
            }
        }
        return score;
    }

    /**
     * Evaluates a string to determine if it looks like a valid file name.
     * Returns a positive score if the string looks valid, negative if it contains
     * many replacement characters.
     *
     * @param s the string to evaluate
     * @return the evaluation score
     */
    private static int evaluateString(final String s) {
        int score = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\uFFFD') { // Unicode replacement character
                score -= 10; // Penalty for invalid characters
            } else if (isCommonChineseCharacter(c) || (c >= ' ' && c <= '~')) {
                score += 1; // Valid character
            } else if (Character.isLetterOrDigit(c)) {
                score += 1;
            } else {
                // Other characters are neutral
            }
        }
        return score;
    }

    /**
     * Checks if a character is a common Chinese character.
     * This is a simple heuristic based on Unicode ranges.
     *
     * @param c the character to check
     * @return true if the character is likely a Chinese character
     */
    private static boolean isCommonChineseCharacter(final char c) {
        // Basic CJK Unified Ideographs
        return (c >= '\u4E00' && c <= '\u9FFF') ||
               // CJK Unified Ideographs Extension A
               (c >= '\u3400' && c <= '\u4DBF') ||
               // CJK Unified Ideographs Extension B
               (c >= '\u20000' && c <= '\u2A6DF') ||
               // CJK Compatibility Ideographs
               (c >= '\uF900' && c <= '\uFAFF');
    }

    /**
     * Reads all bytes from an input stream into a byte array.
     *
     * @param inputStream the input stream to read
     * @return the byte array containing all bytes from the input stream
     * @throws IOException if an I/O error occurs
     */
    private static byte[] readAllBytes(final InputStream inputStream) throws IOException {
        final List<byte[]> buffers = new ArrayList<>();
        int totalSize = 0;
        byte[] buffer = new byte[8192];
        int bytesRead;
        
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            if (bytesRead > 0) {
                final byte[] copy = new byte[bytesRead];
                System.arraycopy(buffer, 0, copy, 0, bytesRead);
                buffers.add(copy);
                totalSize += bytesRead;
            }
        }
        
        final byte[] result = new byte[totalSize];
        int offset = 0;
        for (final byte[] buf : buffers) {
            System.arraycopy(buf, 0, result, offset, buf.length);
            offset += buf.length;
        }
        
        return result;
    }
}
