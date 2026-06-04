package org.apache.commons.compress.archivers.zip;

import static org.apache.commons.compress.AbstractTest.getPath;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.AbstractTempDirTest;
import org.junit.jupiter.api.Test;

class SecureZipReaderTest extends AbstractTempDirTest {

    @Test
    void testExtractSafelyReadsDeflate64EntryWithZipFile() throws Exception {
        final Path zipPath = getPath("COMPRESS-380/COMPRESS-380.zip");
        final Path outputDir = createTempDirectory("secure-zip-reader-deflate64");

        final SecureZipReader.ExtractionReport report = SecureZipReader.extractSafely(zipPath, outputDir, 1024 * 1024);

        assertFalse(report.isUsedStreamFallback());
        assertNull(report.getFirstCorruptEntry());
        assertEquals(1, report.getExtractedEntries().size());
        assertTrue(report.getExtractedEntries().contains("input2"));
        assertTrue(report.getSkippedEntries().isEmpty());
        assertArrayEquals(Files.readAllBytes(getPath("COMPRESS-380/COMPRESS-380-input")), Files.readAllBytes(outputDir.resolve("input2")));
    }

    @Test
    void testExtractSafelySkipsOversizedEntry() throws Exception {
        final Path zipPath = getPath("COMPRESS-380/COMPRESS-380.zip");
        final Path outputDir = createTempDirectory("secure-zip-reader-skip");

        final SecureZipReader.ExtractionReport report = SecureZipReader.extractSafely(zipPath, outputDir, 10);

        assertTrue(report.getExtractedEntries().isEmpty());
        assertEquals(1, report.getSkippedEntries().size());
        assertTrue(report.getSkippedEntries().get(0).contains("Entry declared size exceeds limit"));
        assertFalse(Files.exists(outputDir.resolve("input2")));
    }
}
