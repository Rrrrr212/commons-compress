package org.apache.commons.compress.utils;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

/**
 * JUnit 4 Tests for ChecksumUtils calculateAdler32.
 */
public class ChecksumUtilsTest {

    @Test
    public void testCalculateAdler32NormalInput() throws IOException {
        final String inputStr = "hello world";
        final byte[] bytes = inputStr.getBytes(StandardCharsets.UTF_8);
        
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            final Checksum adler32 = new Adler32();
            adler32.update(bytes, 0, bytes.length);
            final long expected = adler32.getValue();

            final long actual = ChecksumUtils.calculateAdler32(is);
            Assert.assertEquals("Adler-32 checksum should match for normal input", expected, actual);
        }
    }

    @Test
    public void testCalculateAdler32EmptyInput() throws IOException {
        final byte[] bytes = new byte[0];
        
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            final Checksum adler32 = new Adler32();
            final long expected = adler32.getValue();

            final long actual = ChecksumUtils.calculateAdler32(is);
            Assert.assertEquals("Adler-32 checksum for empty input should match", expected, actual);
        }
    }

    @Test
    public void testCalculateAdler32LargeFile() throws IOException {
        final int size = 10 * 1024 * 1024; // 10MB
        final byte[] data = new byte[size];
        new Random(42).nextBytes(data);

        try (InputStream is = new ByteArrayInputStream(data)) {
            final Checksum adler32 = new Adler32();
            adler32.update(data, 0, data.length);
            final long expected = adler32.getValue();

            final long actual = ChecksumUtils.calculateAdler32(is);
            Assert.assertEquals("Adler-32 checksum for 10MB input should match", expected, actual);
        }
    }

    @Test
    public void testCalculateAdler32NullInput() throws IOException {
        try {
            ChecksumUtils.calculateAdler32(null);
            Assert.fail("Expected an exception (NullPointerException or IllegalArgumentException) when input is null");
        } catch (final NullPointerException | IllegalArgumentException e) {
            // Expected behavior
        }
    }
}
