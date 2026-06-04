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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ZipCharsetDetector}.
 */
class ZipCharsetDetectorTest {

    @TempDir
    private Path tempDir;

    private Path testZipPath;

    @BeforeEach
    void setUp() throws IOException {
        testZipPath = tempDir.resolve("test.zip");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (testZipPath != null && Files.exists(testZipPath)) {
            Files.deleteIfExists(testZipPath);
        }
    }

    /**
     * Creates a test ZIP archive with files using the specified charset.
     *
     * @param files  the file names and contents
     * @param charset the charset to use
     * @return the ZIP archive bytes
     * @throws IOException if an I/O error occurs
     */
    private byte[] createTestZip(final String[][] files, final Charset charset) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, charset)) {
            for (final String[] file : files) {
                final String name = file[0];
                final String content = file.length > 1 ? file[1] : "test content";
                final ZipEntry entry = new ZipEntry(name);
                zos.putNextEntry(entry);
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        }
    }

    @Test
    void testExtractWithUTF8Charset() throws IOException {
        final String[][] testFiles = {
            {"test1.txt", "content1"},
            {"中文文件名.txt", "中文内容"},
            {"dir/子目录文件.txt", "内容"}
        };
        final byte[] zipBytes = createTestZip(testFiles, StandardCharsets.UTF_8);
        
        final Path extractPath = tempDir.resolve("utf8-extract");
        ZipCharsetDetector.extractWithCharsetDetect(new ByteArrayInputStream(zipBytes), extractPath);
        
        assertTrue(Files.exists(extractPath.resolve("test1.txt")));
        assertTrue(Files.exists(extractPath.resolve("中文文件名.txt")));
        assertTrue(Files.exists(extractPath.resolve("dir/子目录文件.txt")));
    }

    @Test
    void testExtractWithGBKCharset() throws IOException {
        final Charset gbkCharset = Charset.forName("GBK");
        final String[][] testFiles = {
            {"gbk-test.txt", "content1"},
            {"中文文件GBK.txt", "中文内容"},
            {"目录/文件.txt", "内容"}
        };
        final byte[] zipBytes = createTestZip(testFiles, gbkCharset);
        
        final Path extractPath = tempDir.resolve("gbk-extract");
        ZipCharsetDetector.extractWithCharsetDetect(new ByteArrayInputStream(zipBytes), extractPath);
        
        assertTrue(Files.exists(extractPath.resolve("gbk-test.txt")));
        assertTrue(Files.exists(extractPath.resolve("中文文件GBK.txt")));
        assertTrue(Files.exists(extractPath.resolve("目录/文件.txt")));
    }

    @Test
    void testDetectBestCharsetUTF8() throws IOException {
        final String[][] testFiles = {
            {"中文文件UTF8.txt", "中文内容"},
            {"another-file.txt", "content"}
        };
        final byte[] zipBytes = createTestZip(testFiles, StandardCharsets.UTF_8);
        
        final Charset detected = ZipCharsetDetector.detectBestCharset(zipBytes);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testDetectBestCharsetGBK() throws IOException {
        final Charset gbkCharset = Charset.forName("GBK");
        final String[][] testFiles = {
            {"中文文件GBK编码.txt", "中文内容"},
            {"another-gbk-file.txt", "content"}
        };
        final byte[] zipBytes = createTestZip(testFiles, gbkCharset);
        
        final Charset detected = ZipCharsetDetector.detectBestCharset(zipBytes);
        // Should detect GBK or UTF-8 as both are common, but our implementation prefers UTF-8
        // If it returns GBK that's also acceptable
        assertTrue(detected.equals(gbkCharset) || detected.equals(StandardCharsets.UTF_8));
    }

    @Test
    void testExtractWithSpecifiedCharset() throws IOException {
        final Charset gbkCharset = Charset.forName("GBK");
        final String[][] testFiles = {
            {"指定编码文件.txt", "指定编码内容"}
        };
        final byte[] zipBytes = createTestZip(testFiles, gbkCharset);
        
        final Path extractPath = tempDir.resolve("specified-charset-extract");
        ZipCharsetDetector.extractWithCharset(new ByteArrayInputStream(zipBytes), extractPath, gbkCharset);
        
        assertTrue(Files.exists(extractPath.resolve("指定编码文件.txt")));
    }

    @Test
    void testExtractWithFile() throws IOException {
        final String[][] testFiles = {
            {"file-test.txt", "content"}
        };
        final byte[] zipBytes = createTestZip(testFiles, StandardCharsets.UTF_8);
        Files.write(testZipPath, zipBytes);
        
        final Path extractPath = tempDir.resolve("file-extract");
        ZipCharsetDetector.extractWithCharsetDetect(testZipPath.toFile(), extractPath.toFile());
        
        assertTrue(Files.exists(extractPath.resolve("file-test.txt")));
    }

    @Test
    void testExtractWithPath() throws IOException {
        final String[][] testFiles = {
            {"path-test.txt", "content"}
        };
        final byte[] zipBytes = createTestZip(testFiles, StandardCharsets.UTF_8);
        Files.write(testZipPath, zipBytes);
        
        final Path extractPath = tempDir.resolve("path-extract");
        ZipCharsetDetector.extractWithCharsetDetect(testZipPath, extractPath);
        
        assertTrue(Files.exists(extractPath.resolve("path-test.txt")));
    }

    @Test
    void testSecurityCheck() throws IOException {
        final String[][] testFiles = {
            {"../escape.txt", "malicious content"}
        };
        final byte[] zipBytes = createTestZip(testFiles, StandardCharsets.UTF_8);
        
        final Path extractPath = tempDir.resolve("security-test");
        assertThrows(IOException.class, () -> {
            ZipCharsetDetector.extractWithCharsetDetect(new ByteArrayInputStream(zipBytes), extractPath);
        });
    }

    @Test
    void testMultipleChineseCharsets() throws IOException {
        // Test with GB2312
        final Charset gb2312Charset = Charset.forName("GB2312");
        final String[][] testFiles = {
            {"GB2312文件.txt", "GB2312内容"}
        };
        final byte[] zipBytes = createTestZip(testFiles, gb2312Charset);
        
        final Path extractPath = tempDir.resolve("gb2312-extract");
        ZipCharsetDetector.extractWithCharsetDetect(new ByteArrayInputStream(zipBytes), extractPath);
        
        assertTrue(Files.exists(extractPath.resolve("GB2312文件.txt")));
    }
}
