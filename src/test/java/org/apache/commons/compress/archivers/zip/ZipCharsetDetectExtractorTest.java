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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.AbstractTempDirTest;
import org.junit.jupiter.api.Test;

class ZipCharsetDetectExtractorTest extends AbstractTempDirTest {

    private static final Charset GBK = Charset.forName("GBK");

    private static final class TestEntrySpec {

        private final byte[] data;
        private final boolean utf8Flag;
        private final byte[] rawName;

        private TestEntrySpec(final String name, final Charset charset, final boolean utf8Flag, final String data) {
            this.rawName = name.getBytes(charset);
            this.utf8Flag = utf8Flag;
            this.data = data.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    void testExtractWithCharsetDetectExtractsMixedEncodedNames() throws IOException {
        final Path archive = createTempPath("mixed-charset", ".zip");
        final Path outputDirectory = newTempPath("output");

        createMixedEncodingZip(archive);
        ZipCharsetDetectExtractor.extractWithCharsetDetect(archive, outputDirectory);

        assertTrue(Files.exists(outputDirectory.resolve("gbk/中文.txt")));
        assertTrue(Files.exists(outputDirectory.resolve("utf8/世界.txt")));
        assertTrue(Files.exists(outputDirectory.resolve("utf8-no-flag/你好.txt")));

        assertEquals("gbk-content", new String(Files.readAllBytes(outputDirectory.resolve("gbk/中文.txt")), StandardCharsets.UTF_8));
        assertEquals("utf8-content", new String(Files.readAllBytes(outputDirectory.resolve("utf8/世界.txt")), StandardCharsets.UTF_8));
        assertEquals("utf8-no-flag-content", new String(Files.readAllBytes(outputDirectory.resolve("utf8-no-flag/你好.txt")), StandardCharsets.UTF_8));

        try (ZipArchiveInputStream inputStream = ZipArchiveInputStream.builder().setPath(archive).get()) {
            final ZipArchiveEntry gbkEntry = inputStream.getNextEntry();
            assertFalse("gbk/中文.txt".equals(gbkEntry.getName()));
        }
    }

    private void createMixedEncodingZip(final Path archive) throws IOException {
        final List<TestEntrySpec> entries = new ArrayList<>();
        entries.add(new TestEntrySpec("gbk/中文.txt", GBK, false, "gbk-content"));
        entries.add(new TestEntrySpec("utf8/世界.txt", StandardCharsets.UTF_8, true, "utf8-content"));
        entries.add(new TestEntrySpec("utf8-no-flag/你好.txt", StandardCharsets.UTF_8, false, "utf8-no-flag-content"));
        Files.write(archive, buildStoredZip(entries));
    }

    private byte[] buildStoredZip(final List<TestEntrySpec> entries) throws IOException {
        final ByteArrayOutputStream archive = new ByteArrayOutputStream();
        final List<Integer> localHeaderOffsets = new ArrayList<>();
        final List<Long> crcValues = new ArrayList<>();

        for (final TestEntrySpec entry : entries) {
            localHeaderOffsets.add(Integer.valueOf(archive.size()));
            final CRC32 crc32 = new CRC32();
            crc32.update(entry.data);
            crcValues.add(Long.valueOf(crc32.getValue()));

            writeInt(archive, 0x04034b50L);
            writeShort(archive, 20);
            writeShort(archive, entry.utf8Flag ? GeneralPurposeBit.UFT8_NAMES_FLAG : 0);
            writeShort(archive, ZipEntry.STORED);
            writeShort(archive, 0);
            writeShort(archive, 0);
            writeInt(archive, crc32.getValue());
            writeInt(archive, entry.data.length);
            writeInt(archive, entry.data.length);
            writeShort(archive, entry.rawName.length);
            writeShort(archive, 0);
            archive.write(entry.rawName);
            archive.write(entry.data);
        }

        final int centralDirectoryOffset = archive.size();
        for (int i = 0; i < entries.size(); i++) {
            final TestEntrySpec entry = entries.get(i);
            writeInt(archive, 0x02014b50L);
            writeShort(archive, 20);
            writeShort(archive, 20);
            writeShort(archive, entry.utf8Flag ? GeneralPurposeBit.UFT8_NAMES_FLAG : 0);
            writeShort(archive, ZipEntry.STORED);
            writeShort(archive, 0);
            writeShort(archive, 0);
            writeInt(archive, crcValues.get(i).longValue());
            writeInt(archive, entry.data.length);
            writeInt(archive, entry.data.length);
            writeShort(archive, entry.rawName.length);
            writeShort(archive, 0);
            writeShort(archive, 0);
            writeShort(archive, 0);
            writeShort(archive, 0);
            writeInt(archive, 0);
            writeInt(archive, localHeaderOffsets.get(i).intValue());
            archive.write(entry.rawName);
        }

        final int centralDirectorySize = archive.size() - centralDirectoryOffset;
        writeInt(archive, 0x06054b50L);
        writeShort(archive, 0);
        writeShort(archive, 0);
        writeShort(archive, entries.size());
        writeShort(archive, entries.size());
        writeInt(archive, centralDirectorySize);
        writeInt(archive, centralDirectoryOffset);
        writeShort(archive, 0);
        return archive.toByteArray();
    }

    private void writeInt(final OutputStream outputStream, final long value) throws IOException {
        outputStream.write((int) (value & 0xff));
        outputStream.write((int) (value >>> 8 & 0xff));
        outputStream.write((int) (value >>> 16 & 0xff));
        outputStream.write((int) (value >>> 24 & 0xff));
    }

    private void writeShort(final OutputStream outputStream, final int value) throws IOException {
        outputStream.write(value & 0xff);
        outputStream.write(value >>> 8 & 0xff);
    }
}
