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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipCharsetDetectorUtilTest {

    private static final String CHINESE_FILENAME = "\u4e2d\u6587\u6587\u4ef6\u540d.txt";
    private static final String CHINESE_DIRNAME = "\u4e2d\u6587\u76ee\u5f55";

    // GBK bytes for "中文文件名.txt"
    private static byte[] gbkBytes(final String s) {
        return s.getBytes(Charset.forName("GBK"));
    }

    @Test
    void testDetectCharsetNull() {
        assertEquals(StandardCharsets.UTF_8, ZipCharsetDetectorUtil.detectCharset(null));
    }

    @Test
    void testDetectCharsetEmpty() {
        assertEquals(StandardCharsets.UTF_8, ZipCharsetDetectorUtil.detectCharset(new byte[0]));
    }

    @Test
    void testDetectCharsetAsciiOnly() {
        final byte[] asciiBytes = "hello.txt".getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, ZipCharsetDetectorUtil.detectCharset(asciiBytes));
    }

    @Test
    void testDetectCharsetValidUtf8() {
        final byte[] utf8Bytes = CHINESE_FILENAME.getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, ZipCharsetDetectorUtil.detectCharset(utf8Bytes));
    }

    @Test
    void testDetectCharsetGbkBytes() {
        final byte[] gbkBytes = gbkBytes(CHINESE_FILENAME);
        final Charset detected = ZipCharsetDetectorUtil.detectCharset(gbkBytes);
        assertNotNull(detected);
        assertEquals(CHINESE_FILENAME, new String(gbkBytes, detected));
    }

    @Test
    void testDetectCharsetMixedAsciiAndChinese() {
        final String mixedName = "report_\u62a5\u8868_2024.xlsx";
        final byte[] gbkBytes = gbkBytes(mixedName);
        final Charset detected = ZipCharsetDetectorUtil.detectCharset(gbkBytes);
        assertEquals(mixedName, new String(gbkBytes, detected));
    }

    @Test
    void testIsValidUtf8PureAscii() {
        final byte[] bytes = "HelloWorld123".getBytes(StandardCharsets.UTF_8);
        assertTrue(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidUtf8ChineseUtf8() {
        final byte[] bytes = CHINESE_FILENAME.getBytes(StandardCharsets.UTF_8);
        assertTrue(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidUtf8GbkBytes() {
        final byte[] bytes = gbkBytes(CHINESE_FILENAME);
        assertFalse(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidUtf8TruncatedSequence() {
        final byte[] bytes = new byte[] { (byte) 0xE4, (byte) 0xB8 };
        assertFalse(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidUtf8InvalidContinuation() {
        final byte[] bytes = new byte[] { (byte) 0xE4, (byte) 0xB8, (byte) 0x20 };
        assertFalse(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidUtf8OverlongEncoding() {
        final byte[] bytes = new byte[] { (byte) 0xC0, (byte) 0x80 };
        assertFalse(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidUtf8Surrogate() {
        final byte[] bytes = new byte[] { (byte) 0xED, (byte) 0xA0, (byte) 0x80 };
        assertFalse(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidUtf8FourByteSequence() {
        final byte[] bytes = new byte[] { (byte) 0xF0, (byte) 0x90, (byte) 0x80, (byte) 0x80 };
        assertTrue(ZipCharsetDetectorUtil.isValidUtf8(bytes));
    }

    @Test
    void testIsValidGbkPureAscii() {
        final byte[] bytes = "HelloWorld".getBytes(StandardCharsets.UTF_8);
        assertTrue(ZipCharsetDetectorUtil.isValidGbk(bytes));
    }

    @Test
    void testIsValidGbkChineseBytes() {
        final byte[] bytes = gbkBytes(CHINESE_FILENAME);
        assertTrue(ZipCharsetDetectorUtil.isValidGbk(bytes));
    }

    @Test
    void testIsValidGbkInvalidSecondByte() {
        final byte[] bytes = new byte[] { (byte) 0x81, (byte) 0x30 };
        assertFalse(ZipCharsetDetectorUtil.isValidGbk(bytes));
    }

    @Test
    void testIsValidGbkTruncatedSequence() {
        final byte[] bytes = new byte[] { (byte) 0x81 };
        assertFalse(ZipCharsetDetectorUtil.isValidGbk(bytes));
    }

    @Test
    void testIsValidGbkInvalidFirstByte() {
        final byte[] bytes = new byte[] { (byte) 0x80, (byte) 0x40 };
        assertFalse(ZipCharsetDetectorUtil.isValidGbk(bytes));
    }

    @Test
    void testIsValidGbkSecondByte0x7F() {
        final byte[] bytes = new byte[] { (byte) 0x81, (byte) 0x7F };
        assertFalse(ZipCharsetDetectorUtil.isValidGbk(bytes));
    }

    @Test
    void testExtractWithCharsetDetectGbkZip(@TempDir final Path tempDir) throws IOException {
        final byte[] zipData = createZipWithGbkNames();
        try (InputStream is = new ByteArrayInputStream(zipData)) {
            ZipCharsetDetectorUtil.extractWithCharsetDetect(is, tempDir);
        }
        final List<String> names = Files.walk(tempDir)
                .filter(p -> !p.equals(tempDir))
                .map(p -> tempDir.relativize(p).toString())
                .sorted()
                .collect(Collectors.toList());
        assertTrue(names.contains(CHINESE_FILENAME), "Expected: " + CHINESE_FILENAME + " but got: " + names);
        assertTrue(names.contains(CHINESE_DIRNAME), "Expected: " + CHINESE_DIRNAME + " but got: " + names);
        final Path nestedFile = tempDir.resolve(CHINESE_DIRNAME).resolve(CHINESE_FILENAME);
        assertTrue(Files.exists(nestedFile), "Nested file should exist");
        assertEquals("content", new String(Files.readAllBytes(tempDir.resolve(CHINESE_FILENAME)), StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithCharsetDetectUtf8Zip(@TempDir final Path tempDir) throws IOException {
        final byte[] zipData = createZipWithUtf8Names();
        try (InputStream is = new ByteArrayInputStream(zipData)) {
            ZipCharsetDetectorUtil.extractWithCharsetDetect(is, tempDir);
        }
        final List<String> names = Files.walk(tempDir)
                .filter(p -> !p.equals(tempDir))
                .map(p -> tempDir.relativize(p).toString())
                .sorted()
                .collect(Collectors.toList());
        assertTrue(names.contains(CHINESE_FILENAME), "Expected: " + CHINESE_FILENAME + " but got: " + names);
    }

    @Test
    void testExtractWithCharsetDetectEmptyZip(@TempDir final Path tempDir) throws IOException {
        final byte[] emptyZip = createEmptyZip();
        try (InputStream is = new ByteArrayInputStream(emptyZip)) {
            ZipCharsetDetectorUtil.extractWithCharsetDetect(is, tempDir);
        }
        final List<String> names = Files.walk(tempDir)
                .filter(p -> !p.equals(tempDir))
                .collect(Collectors.toList());
        assertTrue(names.isEmpty());
    }

    @Test
    void testExtractWithCharsetDetectZipSlip(@TempDir final Path tempDir) throws IOException {
        final byte[] zipData = createZipSlipData();
        try (InputStream is = new ByteArrayInputStream(zipData)) {
            assertThrows(IOException.class, () -> ZipCharsetDetectorUtil.extractWithCharsetDetect(is, tempDir));
        }
    }

    @Test
    void testExtractWithCharsetDetectDirectory(@TempDir final Path tempDir) throws IOException {
        final byte[] zipData = createZipWithDirectory();
        try (InputStream is = new ByteArrayInputStream(zipData)) {
            ZipCharsetDetectorUtil.extractWithCharsetDetect(is, tempDir);
        }
        final Path dir = tempDir.resolve(CHINESE_DIRNAME);
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void testConstructorDeprecated() {
        final ZipCharsetDetectorUtil instance = new ZipCharsetDetectorUtil();
        assertNotNull(instance);
    }

    private static byte[] createZipWithGbkNames() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.setEncoding("GBK");
            zos.setUseLanguageEncodingFlag(false);
            zos.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);

            ZipArchiveEntry entry = new ZipArchiveEntry(CHINESE_FILENAME);
            zos.putArchiveEntry(entry);
            zos.write("content".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();

            entry = new ZipArchiveEntry(CHINESE_DIRNAME + "/");
            zos.putArchiveEntry(entry);
            zos.closeArchiveEntry();

            entry = new ZipArchiveEntry(CHINESE_DIRNAME + "/" + CHINESE_FILENAME);
            zos.putArchiveEntry(entry);
            zos.write("nested".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] createZipWithUtf8Names() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.setEncoding(StandardCharsets.UTF_8.name());
            zos.setUseLanguageEncodingFlag(true);

            final ZipArchiveEntry entry = new ZipArchiveEntry(CHINESE_FILENAME);
            zos.putArchiveEntry(entry);
            zos.write("content".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] createEmptyZip() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.finish();
        }
        return baos.toByteArray();
    }

    private static byte[] createZipSlipData() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.setEncoding(StandardCharsets.UTF_8.name());

            final ZipArchiveEntry entry = new ZipArchiveEntry("../evil.txt");
            zos.putArchiveEntry(entry);
            zos.write("evil".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] createZipWithDirectory() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            zos.setEncoding("GBK");
            zos.setUseLanguageEncodingFlag(false);
            zos.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);

            final ZipArchiveEntry entry = new ZipArchiveEntry(CHINESE_DIRNAME + "/");
            zos.putArchiveEntry(entry);
            zos.closeArchiveEntry();
        }
        return baos.toByteArray();
    }
}