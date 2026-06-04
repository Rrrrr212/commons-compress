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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.compress.AbstractTest;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipCharsetDetectorTest extends AbstractTest {

    private static final String CHINESE_FILENAME_UTF8 = "\u4e2d\u6587\u6587\u4ef6.txt";
    private static final String CHINESE_DIR_UTF8 = "\u4e2d\u6587\u76ee\u5f55/";
    private static final String MIXED_FILENAME = "\u4e2d\u6587English\u6587\u4ef6.txt";

    private byte[] createZipWithEncoding(final String filename, final String content, final Charset charset,
            final boolean setUtf8Flag) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.setEncoding(charset.name());
            zos.setUseLanguageEncodingFlag(setUtf8Flag);
            zos.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);

            final ZipArchiveEntry entry = new ZipArchiveEntry(filename);
            zos.putArchiveEntry(entry);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
            zos.finish();
            return baos.toByteArray();
        }
    }

    private byte[] createZipWithMultipleEntries(final List<EntrySpec> entries, final Charset charset,
            final boolean setUtf8Flag) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.setEncoding(charset.name());
            zos.setUseLanguageEncodingFlag(setUtf8Flag);
            zos.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);

            for (final EntrySpec spec : entries) {
                final ZipArchiveEntry entry = new ZipArchiveEntry(spec.name);
                zos.putArchiveEntry(entry);
                zos.write(spec.content.getBytes(StandardCharsets.UTF_8));
                zos.closeArchiveEntry();
            }
            zos.finish();
            return baos.toByteArray();
        }
    }

    private static final class EntrySpec {
        final String name;
        final String content;

        EntrySpec(final String name, final String content) {
            this.name = name;
            this.content = content;
        }
    }

    @Test
    void testDetectCharsetWithUtf8Flag() {
        final byte[] rawName = CHINESE_FILENAME_UTF8.getBytes(StandardCharsets.UTF_8);
        final Charset detected = ZipCharsetDetector.detectCharset(rawName, true);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testDetectCharsetWithValidUtf8Bytes() {
        final byte[] rawName = CHINESE_FILENAME_UTF8.getBytes(StandardCharsets.UTF_8);
        final Charset detected = ZipCharsetDetector.detectCharset(rawName, false);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testDetectCharsetWithGbkBytes() throws Exception {
        final Charset gbk = Charset.forName("GBK");
        final byte[] rawName = CHINESE_FILENAME_UTF8.getBytes(gbk);
        final Charset detected = ZipCharsetDetector.detectCharset(rawName, false);
        assertEquals(gbk, detected);
    }

    @Test
    void testDecodeNameWithUtf8Flag() {
        final byte[] rawName = CHINESE_FILENAME_UTF8.getBytes(StandardCharsets.UTF_8);
        final String decoded = ZipCharsetDetector.decodeName(rawName, true);
        assertEquals(CHINESE_FILENAME_UTF8, decoded);
    }

    @Test
    void testDecodeNameWithValidUtf8Bytes() {
        final byte[] rawName = CHINESE_FILENAME_UTF8.getBytes(StandardCharsets.UTF_8);
        final String decoded = ZipCharsetDetector.decodeName(rawName, false);
        assertEquals(CHINESE_FILENAME_UTF8, decoded);
    }

    @Test
    void testDecodeNameWithGbkBytes() throws Exception {
        final Charset gbk = Charset.forName("GBK");
        final byte[] rawName = CHINESE_FILENAME_UTF8.getBytes(gbk);
        final String decoded = ZipCharsetDetector.decodeName(rawName, false);
        assertEquals(CHINESE_FILENAME_UTF8, decoded);
    }

    @Test
    void testDecodeMixedChineseEnglishUtf8() {
        final byte[] rawName = MIXED_FILENAME.getBytes(StandardCharsets.UTF_8);
        final String decoded = ZipCharsetDetector.decodeName(rawName, false);
        assertEquals(MIXED_FILENAME, decoded);
    }

    @Test
    void testExtractWithCharsetDetectFromInputStream(@TempDir final File tempDir) throws Exception {
        final List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec(CHINESE_FILENAME_UTF8, "Chinese content \u4e2d\u6587"));
        entries.add(new EntrySpec("english.txt", "English content"));
        entries.add(new EntrySpec(MIXED_FILENAME, "Mixed content"));

        final byte[] zipData = createZipWithMultipleEntries(entries, StandardCharsets.UTF_8, false);

        final File outputDir = new File(tempDir, "output1");
        outputDir.mkdirs();

        try (InputStream is = new ByteArrayInputStream(zipData)) {
            ZipCharsetDetector.extractWithCharsetDetect(is, outputDir);
        }

        final File chineseFile = new File(outputDir, CHINESE_FILENAME_UTF8);
        assertTrue(chineseFile.exists(), "Chinese filename should be correctly decoded");
        assertEquals("Chinese content \u4e2d\u6587", new String(Files.readAllBytes(chineseFile.toPath()), StandardCharsets.UTF_8));

        final File englishFile = new File(outputDir, "english.txt");
        assertTrue(englishFile.exists());
        assertEquals("English content", new String(Files.readAllBytes(englishFile.toPath()), StandardCharsets.UTF_8));

        final File mixedFile = new File(outputDir, MIXED_FILENAME);
        assertTrue(mixedFile.exists());
        assertEquals("Mixed content", new String(Files.readAllBytes(mixedFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithCharsetDetectFromFile(@TempDir final File tempDir) throws Exception {
        final List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec(CHINESE_FILENAME_UTF8, "Content 1"));
        entries.add(new EntrySpec("readme.txt", "Content 2"));

        final byte[] zipData = createZipWithMultipleEntries(entries, StandardCharsets.UTF_8, false);

        final File zipFile = new File(tempDir, "test.zip");
        try (FileOutputStream fos = new FileOutputStream(zipFile)) {
            fos.write(zipData);
        }

        final File outputDir = new File(tempDir, "output2");
        outputDir.mkdirs();

        ZipCharsetDetector.extractWithCharsetDetect(zipFile, outputDir);

        final File chineseFile = new File(outputDir, CHINESE_FILENAME_UTF8);
        assertTrue(chineseFile.exists());
        assertEquals("Content 1", new String(Files.readAllBytes(chineseFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithCharsetDetectWithDirectory(@TempDir final File tempDir) throws Exception {
        final List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec(CHINESE_DIR_UTF8, ""));
        entries.add(new EntrySpec(CHINESE_DIR_UTF8 + "file.txt", "In directory"));

        final byte[] zipData = createZipWithMultipleEntries(entries, StandardCharsets.UTF_8, false);

        final File outputDir = new File(tempDir, "output3");
        outputDir.mkdirs();

        try (InputStream is = new ByteArrayInputStream(zipData)) {
            ZipCharsetDetector.extractWithCharsetDetect(is, outputDir);
        }

        final File dir = new File(outputDir, CHINESE_DIR_UTF8);
        assertTrue(dir.exists() && dir.isDirectory());

        final File fileInDir = new File(outputDir, CHINESE_DIR_UTF8 + "file.txt");
        assertTrue(fileInDir.exists());
        assertEquals("In directory", new String(Files.readAllBytes(fileInDir.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithCharsetDetectWithCallback(@TempDir final File tempDir) throws Exception {
        final List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec(CHINESE_FILENAME_UTF8, "Content"));
        entries.add(new EntrySpec("plain.txt", "Plain"));

        final byte[] zipData = createZipWithMultipleEntries(entries, StandardCharsets.UTF_8, false);

        final File outputDir = new File(tempDir, "output4");
        outputDir.mkdirs();

        final List<String> extractedNames = new ArrayList<>();
        final List<File> extractedFiles = new ArrayList<>();

        ZipCharsetDetector.extractWithCharsetDetect(new ByteArrayInputStream(zipData), outputDir,
                (originalEntry, decodedName, outputFile) -> {
                    extractedNames.add(decodedName);
                    extractedFiles.add(outputFile);
                });

        assertEquals(2, extractedNames.size());
        assertTrue(extractedNames.contains(CHINESE_FILENAME_UTF8));
        assertTrue(extractedNames.contains("plain.txt"));
        assertEquals(2, extractedFiles.size());
        for (final File f : extractedFiles) {
            assertTrue(f.exists());
        }
    }

    @Test
    void testExtractWithCharsetDetectFromGbkEncodedZip(@TempDir final File tempDir) throws Exception {
        final Charset gbk = Charset.forName("GBK");
        final byte[] rawName = CHINESE_FILENAME_UTF8.getBytes(gbk);
        final String gbkFilename = new String(rawName, gbk);

        final byte[] zipData = createZipWithEncoding(gbkFilename, "GBK content", gbk, false);

        final File outputDir = new File(tempDir, "output5");
        outputDir.mkdirs();

        try (InputStream is = new ByteArrayInputStream(zipData)) {
            ZipCharsetDetector.extractWithCharsetDetect(is, outputDir);
        }

        final File[] files = outputDir.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertEquals(CHINESE_FILENAME_UTF8, files[0].getName());
        assertEquals("GBK content", new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithCharsetDetectWithPath(@TempDir final File tempDir) throws Exception {
        final List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec(CHINESE_FILENAME_UTF8, "Path test"));

        final byte[] zipData = createZipWithMultipleEntries(entries, StandardCharsets.UTF_8, false);

        final java.nio.file.Path zipPath = tempDir.toPath().resolve("test.zip");
        Files.write(zipPath, zipData);

        final java.nio.file.Path outputPath = tempDir.toPath().resolve("output6");
        Files.createDirectories(outputPath);

        ZipCharsetDetector.extractWithCharsetDetect(zipPath, outputPath);

        final java.nio.file.Path extractedFile = outputPath.resolve(CHINESE_FILENAME_UTF8);
        assertTrue(Files.exists(extractedFile));
        assertEquals("Path test", new String(Files.readAllBytes(extractedFile), StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithCharsetDetectWithPathAndCallback(@TempDir final File tempDir) throws Exception {
        final List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec(CHINESE_FILENAME_UTF8, "Callback test"));

        final byte[] zipData = createZipWithMultipleEntries(entries, StandardCharsets.UTF_8, false);

        final java.nio.file.Path zipPath = tempDir.toPath().resolve("test2.zip");
        Files.write(zipPath, zipData);

        final java.nio.file.Path outputPath = tempDir.toPath().resolve("output7");
        Files.createDirectories(outputPath);

        final List<String> extractedNames = new ArrayList<>();
        ZipCharsetDetector.extractWithCharsetDetect(zipPath, outputPath,
                (originalEntry, decodedName, outputFile) -> extractedNames.add(decodedName));

        assertEquals(1, extractedNames.size());
        assertEquals(CHINESE_FILENAME_UTF8, extractedNames.get(0));
    }

    @Test
    void testExtractWithCharsetDetectPreservesNestedDirectories(@TempDir final File tempDir) throws Exception {
        final List<EntrySpec> entries = new ArrayList<>();
        entries.add(new EntrySpec("\u4e2d\u6587/", ""));
        entries.add(new EntrySpec("\u4e2d\u6587/\u5b50\u76ee\u5f55/", ""));
        entries.add(new EntrySpec("\u4e2d\u6587/\u5b50\u76ee\u5f55/\u6587\u4ef6.txt", "Nested"));

        final byte[] zipData = createZipWithMultipleEntries(entries, StandardCharsets.UTF_8, false);

        final File outputDir = new File(tempDir, "output8");
        outputDir.mkdirs();

        try (InputStream is = new ByteArrayInputStream(zipData)) {
            ZipCharsetDetector.extractWithCharsetDetect(is, outputDir);
        }

        final File nestedFile = new File(outputDir, "\u4e2d\u6587/\u5b50\u76ee\u5f55/\u6587\u4ef6.txt");
        assertTrue(nestedFile.exists());
        assertEquals("Nested", new String(Files.readAllBytes(nestedFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithCharsetDetectFromActualFile(@TempDir final File tempDir) throws Exception {
        final File zipFile = getFile("bla.zip");
        final File outputDir = new File(tempDir, "output9");
        outputDir.mkdirs();

        ZipCharsetDetector.extractWithCharsetDetect(zipFile, outputDir);

        final File[] files = outputDir.listFiles();
        assertNotNull(files);
        assertTrue(files.length > 0);
    }

    @Test
    void testExtractWithCharsetDetectInputStreamFromActualFile(@TempDir final File tempDir) throws Exception {
        final File zipFile = getFile("bla.zip");
        final File outputDir = new File(tempDir, "output10");
        outputDir.mkdirs();

        try (InputStream is = new FileInputStream(zipFile)) {
            ZipCharsetDetector.extractWithCharsetDetect(is, outputDir);
        }

        final File[] files = outputDir.listFiles();
        assertNotNull(files);
        assertTrue(files.length > 0);
    }
}
