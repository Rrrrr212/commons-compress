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
package org.apache.commons.compress.compressors.lz77;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

class LZ77CompressorInputStreamTest {

    private static final int MIN_MATCH_LENGTH = LZ77CompressorInputStream.MIN_MATCH_LENGTH;

    private byte[] encodeLZ77(final Object... tokens) {
        int tokenCount = 0;
        for (final Object token : tokens) {
            if (token instanceof Byte || token instanceof Integer) {
                tokenCount++;
            } else if (token instanceof int[]) {
                tokenCount++;
            }
        }
        final int flagCount = (tokenCount + 7) / 8;
        final byte[] buf = new byte[tokenCount * 4 + flagCount];
        int pos = 0;
        int tokenIdx = 0;
        for (int flagGroup = 0; flagGroup < flagCount; flagGroup++) {
            int flag = 0;
            final int flagPos = pos;
            pos++;
            for (int bit = 0; bit < 8 && tokenIdx < tokens.length; bit++, tokenIdx++) {
                final Object token = tokens[tokenIdx];
                if (token instanceof Byte) {
                    buf[pos++] = (Byte) token;
                } else if (token instanceof Integer) {
                    buf[pos++] = ((Integer) token).byteValue();
                } else if (token instanceof int[]) {
                    final int[] ref = (int[]) token;
                    flag |= 1 << bit;
                    buf[pos++] = (byte) (ref[0] & 0xFF);
                    buf[pos++] = (byte) (ref[0] >>> 8 & 0xFF);
                    buf[pos++] = (byte) (ref[1] - MIN_MATCH_LENGTH);
                }
            }
            buf[flagPos] = (byte) flag;
        }
        final byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    private byte[] decompress(final byte[] compressed) throws IOException {
        try (InputStream is = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            return IOUtils.toByteArray(is);
        }
    }

    @Test
    void testAbcAbcRoundtrip() throws IOException {
        final byte[] compressed = encodeLZ77(
                (byte) 'a', (byte) 'b', (byte) 'c',
                new int[]{3, 3});
        final byte[] decompressed = decompress(compressed);
        assertArrayEquals("abcabc".getBytes(), decompressed);
    }

    @Test
    void testAllLiterals() throws IOException {
        final byte[] compressed = encodeLZ77(
                (byte) 'H', (byte) 'e', (byte) 'l', (byte) 'l', (byte) 'o');
        final byte[] decompressed = decompress(compressed);
        assertArrayEquals("Hello".getBytes(), decompressed);
    }

    @Test
    void testEmptyStream() throws IOException {
        final byte[] decompressed = decompress(new byte[0]);
        assertEquals(0, decompressed.length);
    }

    @Test
    void testOverlappingBackReference() throws IOException {
        final byte[] compressed = encodeLZ77(
                (byte) 'A',
                new int[]{1, 10});
        final byte[] decompressed = decompress(compressed);
        final byte[] expected = new byte[11];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = 'A';
        }
        assertArrayEquals(expected, decompressed);
    }

    @Test
    void testMultipleFlagBytes() throws IOException {
        final byte[] literals = new byte[10];
        for (int i = 0; i < literals.length; i++) {
            literals[i] = (byte) ('0' + i);
        }
        final byte[] compressed = encodeLZ77(
                (byte) literals[0], (byte) literals[1], (byte) literals[2],
                (byte) literals[3], (byte) literals[4], (byte) literals[5],
                (byte) literals[6], (byte) literals[7],
                (byte) literals[8], (byte) literals[9]);
        final byte[] decompressed = decompress(compressed);
        assertArrayEquals(literals, decompressed);
    }

    @Test
    void testSingleByteReadConsistentlyReturnsMinusOneAtEof() throws IOException {
        final byte[] compressed = encodeLZ77((byte) 'X');
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            assertEquals('X', in.read());
            assertEquals(-1, in.read());
            assertEquals(-1, in.read());
        }
    }

    @Test
    void testMultiByteReadConsistentlyReturnsMinusOneAtEof() throws IOException {
        final byte[] compressed = encodeLZ77((byte) 'X');
        final byte[] buf = new byte[2];
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            in.read(buf);
            assertEquals(-1, in.read(buf));
            assertEquals(-1, in.read(buf));
        }
    }

    @Test
    void testCompressedAndUncompressedCounts() throws IOException {
        final byte[] compressed = encodeLZ77(
                (byte) 'a', (byte) 'b', (byte) 'c',
                new int[]{3, 3});
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] result = IOUtils.toByteArray(in);
            assertArrayEquals("abcabc".getBytes(), result);
            assertEquals(compressed.length, in.getCompressedCount());
            assertEquals(6, in.getUncompressedCount());
        }
    }

    @Test
    void testInvalidBackReferenceOffsetZero() {
        final byte[] compressed = encodeLZ77(
                (byte) 'a',
                new int[]{0, 3});
        assertThrows(IOException.class, () -> decompress(compressed));
    }

    @Test
    void testCustomWindowSize() throws IOException {
        final byte[] compressed = encodeLZ77(
                (byte) 'a', (byte) 'b', (byte) 'c',
                new int[]{3, 3});
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(
                new ByteArrayInputStream(compressed), 1024)) {
            final byte[] result = IOUtils.toByteArray(in);
            assertArrayEquals("abcabc".getBytes(), result);
        }
    }

    @Test
    void testInvalidWindowSizeNotPowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> new LZ77CompressorInputStream(new ByteArrayInputStream(new byte[0]), 100));
    }

    @Test
    void testInvalidWindowSizeZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new LZ77CompressorInputStream(new ByteArrayInputStream(new byte[0]), 0));
    }

    @Test
    void testLongerRepeatedPattern() throws IOException {
        final byte[] compressed = encodeLZ77(
                (byte) 'a', (byte) 'b', (byte) 'c', (byte) 'd',
                new int[]{4, 8});
        final byte[] decompressed = decompress(compressed);
        assertArrayEquals("abcdabcdabcd".getBytes(), decompressed);
    }
}
