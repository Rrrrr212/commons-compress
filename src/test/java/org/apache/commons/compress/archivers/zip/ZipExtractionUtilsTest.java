package org.apache.commons.compress.archivers.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ZipExtractionUtilsTest {

    @TempDir
    public Path tempDir;

    @Test
    public void testExtractWithCharsetDetect() throws IOException {
        // Create a zip with mixed encodings in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            // 1. UTF-8 entry
            String utf8Name = "测试-UTF8.txt";
            ZipArchiveEntry entry1 = new ZipArchiveEntry(utf8Name);
            zos.setEncoding(StandardCharsets.UTF_8.name());
            zos.putArchiveEntry(entry1);
            zos.write("utf8 content".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();

            // 2. GBK entry
            String gbkName = "测试-GBK.txt";
            ZipArchiveEntry entry2 = new ZipArchiveEntry(gbkName);
            zos.setEncoding("GBK");
            zos.putArchiveEntry(entry2);
            zos.write("gbk content".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }

        byte[] zipData = baos.toByteArray();

        // Extract using ZipExtractionUtils
        try (ByteArrayInputStream bis = new ByteArrayInputStream(zipData)) {
            ZipExtractionUtils.extractWithCharsetDetect(bis, tempDir);
        }

        // Verify the extracted files
        File utf8File = tempDir.resolve("测试-UTF8.txt").toFile();
        assertTrue(utf8File.exists(), "UTF-8 filename should be extracted correctly");
        assertEquals("utf8 content", new String(Files.readAllBytes(utf8File.toPath()), StandardCharsets.UTF_8));

        File gbkFile = tempDir.resolve("测试-GBK.txt").toFile();
        assertTrue(gbkFile.exists(), "GBK filename should be extracted correctly");
        assertEquals("gbk content", new String(Files.readAllBytes(gbkFile.toPath()), StandardCharsets.UTF_8));
    }
}
