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
package org.apache.commons.compress.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.Adler32;

import org.junit.jupiter.api.Test;

/**
 * Tests for class {@link ChecksumUtils}.
 *
 * @see ChecksumUtils
 */
class ChecksumUtilsTest {

    @Test
    void testCalculateAdler32WithHelloWorld() throws IOException {
        final byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32WithEmptyStream() throws IOException {
        final byte[] emptyData = new byte[0];
        final InputStream inputStream = new ByteArrayInputStream(emptyData);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(emptyData);
        assertEquals(expected.getValue(), actualChecksum);
        assertEquals(1L, actualChecksum);
    }

    @Test
    void testCalculateAdler32WithLargeData() throws IOException {
        final int size = 10 * 1024 * 1024;
        final byte[] largeData = new byte[size];
        new Random(42).nextBytes(largeData);
        final InputStream inputStream = new ByteArrayInputStream(largeData);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(largeData);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32WithNullInputStream() {
        assertThrows(NullPointerException.class, () -> ChecksumUtils.calculateAdler32(null));
    }

    @Test
    void testCalculateAdler32WithSingleByte() throws IOException {
        final byte[] data = { 0x42 };
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32WithMultipleReads() throws IOException {
        final byte[] data = new byte[20000];
        new Random(123).nextBytes(data);
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32Deterministic() throws IOException {
        final byte[] data = "deterministic test data".getBytes(StandardCharsets.UTF_8);

        final long checksum1 = ChecksumUtils.calculateAdler32(new ByteArrayInputStream(data));
        final long checksum2 = ChecksumUtils.calculateAdler32(new ByteArrayInputStream(data));

        assertEquals(checksum1, checksum2);
    }

    @Test
    void testCalculateAdler32WithAllZeroBytes() throws IOException {
        final byte[] data = new byte[1024];
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32WithAllMaxBytes() throws IOException {
        final byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) 0xFF;
        }
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32BufferSizeBoundary() throws IOException {
        final int bufferSize = 8192;
        final byte[] data = new byte[bufferSize];
        new Random(999).nextBytes(data);
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32JustAboveBufferSize() throws IOException {
        final int size = 8193;
        final byte[] data = new byte[size];
        new Random(888).nextBytes(data);
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }

    @Test
    void testCalculateAdler32WithAlternatingBytes() throws IOException {
        final byte[] data = new byte[5000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 2 == 0 ? 0xAA : 0x55);
        }
        final InputStream inputStream = new ByteArrayInputStream(data);

        final long actualChecksum = ChecksumUtils.calculateAdler32(inputStream);

        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), actualChecksum);
    }
}
