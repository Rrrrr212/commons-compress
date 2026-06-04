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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Objects;

import org.apache.commons.compress.archivers.ArchiveException;

public final class ZipCharsetDetectExtractor {

    private static final Charset GBK = Charset.forName("GBK");

    private ZipCharsetDetectExtractor() {
    }

    public static void extractWithCharsetDetect(final Path archive, final Path targetDirectory) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(targetDirectory, "targetDirectory");

        final Path normalizedTargetDirectory = targetDirectory.normalize();
        Files.createDirectories(normalizedTargetDirectory);

        try (ZipFile zipFile = ZipFile.builder().setPath(archive).get()) {
            final Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry entry = entries.nextElement();
                ZipUtil.checkRequestedFeatures(entry);

                final String entryName = detectEntryName(entry);
                final Path outputPath = resolveIn(normalizedTargetDirectory, entryName);

                if (entry.isDirectory() || entryName.endsWith("/")) {
                    Files.createDirectories(outputPath);
                    continue;
                }

                final Path parent = outputPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    Files.copy(inputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String detectEntryName(final ZipArchiveEntry entry) {
        if (entry.getNameSource() == ZipArchiveEntry.NameSource.NAME_WITH_EFS_FLAG
                || entry.getNameSource() == ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD) {
            return entry.getName();
        }

        final byte[] rawName = entry.getRawName();
        if (rawName == null || rawName.length == 0) {
            return entry.getName();
        }

        if (entry.getGeneralPurposeBit().usesUTF8ForNames()) {
            return decodeOrFallback(rawName, StandardCharsets.UTF_8, entry.getName());
        }

        final String utf8Name = tryDecode(rawName, StandardCharsets.UTF_8);
        if (utf8Name != null) {
            return utf8Name;
        }

        final String gbkName = tryDecode(rawName, GBK);
        if (gbkName != null) {
            return gbkName;
        }

        return entry.getName();
    }

    private static String decodeOrFallback(final byte[] rawName, final Charset charset, final String fallback) {
        final String decoded = tryDecode(rawName, charset);
        return decoded != null ? decoded : fallback;
    }

    private static Path resolveIn(final Path targetDirectory, final String entryName) throws IOException {
        final Path outputPath = targetDirectory.resolve(entryName).normalize();
        if (!outputPath.startsWith(targetDirectory)) {
            throw new ArchiveException("Zip slip '%s' + '%s' -> '%s'", targetDirectory, entryName, outputPath);
        }
        return outputPath;
    }

    private static String tryDecode(final byte[] rawName, final Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawName))
                    .toString();
        } catch (final CharacterCodingException ignored) {
            return null;
        }
    }
}
