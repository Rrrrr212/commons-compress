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
package org.apache.commons.compress.compressors.lz77support;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.commons.compress.compressors.CompressorException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LZ77CompressorInputStream}.
 */
class LZ77CompressorInputStreamTest {

    /**
     * Encodes "abcabc" using LZ77: literal "abc" followed by a back-reference to "abc".
     *
     * <pre>
     * Header byte 0x02: bit7=0 (literal), bits0-6=2 → length=2+1=3
     * Literal data: 'a'(0x61), 'b'(0x62), 'c'(0x63)
     * Header byte 0x81: bit7=1 (back-reference), bits0-6=1 → length=1+2=3
     * Offset (little-endian): 0x03, 0x00 → offset=3
     * </pre>
     */
    private static final byte[] ABCABC_COMPRESSED = {
        0x02, 'a', 'b', 'c',
        (byte) 0x81, 0x03, 0x00
    };

    @Test
    void testBackReferenceWithOverlap() throws IOException {
        final byte[] compressed = {
            // literal "ab": header=1, data='a','b'
            0x01, 'a', 'b',
            // back-reference length=3 (header=0x81), offset=1 → "bbb"
            (byte) 0x81, 0x01, 0x00
        };
        final byte[] expected = "abbbb".getBytes(US_ASCII);

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] result = readAll(in);
            assertArrayEquals(expected, result);
        }
    }

    @Test
    void testEmptyStream() throws IOException {
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(new byte[0]))) {
            assertEquals(-1, in.read());
        }
    }

    @Test
    void testInvalidBackReferenceOffset() {
        final byte[] compressed = {
            // literal "a"
            0x00, 'a',
            // back-reference with offset 0 (invalid)
            (byte) 0x80, 0x00, 0x00
        };
        assertThrows(CompressorException.class, () -> {
            try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
                readAll(in);
            }
        });
    }

    @Test
    void testLiteralOnly() throws IOException {
        final byte[] compressed = {
            // literal "Hello": header=4, data='H','e','l','l','o'
            0x04, 'H', 'e', 'l', 'l', 'o'
        };
        final byte[] expected = "Hello".getBytes(US_ASCII);

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] result = readAll(in);
            assertArrayEquals(expected, result);
        }
    }

    @Test
    void testMaxLengthLiteral() throws IOException {
        final byte[] expected = new byte[128];
        for (int i = 0; i < 128; i++) {
            expected[i] = (byte) i;
        }
        final byte[] compressed = new byte[1 + 128];
        compressed[0] = 0x7F; // literal, length=127+1=128
        System.arraycopy(expected, 0, compressed, 1, 128);

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] result = readAll(in);
            assertArrayEquals(expected, result);
        }
    }

    @Test
    void testMultipleBlocks() throws IOException {
        // "abcabcabc" = literal "abc" + back-reference to "abc" + back-reference to "abc"
        final byte[] compressed = {
            // literal "abc"
            0x02, 'a', 'b', 'c',
            // back-reference length=3, offset=3
            (byte) 0x81, 0x03, 0x00,
            // back-reference length=3, offset=3
            (byte) 0x81, 0x03, 0x00
        };
        final byte[] expected = "abcabcabc".getBytes(US_ASCII);

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] result = readAll(in);
            assertArrayEquals(expected, result);
        }
    }

    @Test
    void testSingleByteLiteral() throws IOException {
        final byte[] compressed = { 0x00, 'X' }; // literal length=0+1=1

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            assertEquals('X', in.read());
            assertEquals(-1, in.read());
        }
    }

    /**
     * Tests that "abcabc" encoded with LZ77 (literal + back-reference) is correctly decompressed.
     */
    @Test
    void testAbcAbcDecompression() throws IOException {
        final byte[] expected = "abcabc".getBytes(US_ASCII);

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(ABCABC_COMPRESSED))) {
            final byte[] result = readAll(in);
            assertArrayEquals(expected, result);
        }
    }

    /**
     * Tests that "abcabc" can be read byte by byte.
     */
    @Test
    void testAbcAbcSingleByteRead() throws IOException {
        final byte[] expected = "abcabc".getBytes(US_ASCII);

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(ABCABC_COMPRESSED))) {
            for (final byte b : expected) {
                assertEquals(b & 0xFF, in.read());
            }
            assertEquals(-1, in.read());
        }
    }

    /**
     * Tests that available returns the correct number of bytes.
     */
    @Test
    void testAvailable() throws IOException {
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(ABCABC_COMPRESSED))) {
            assertEquals(0, in.available());
            in.read();
            assertEquals(2, in.available());
            in.read();
            assertEquals(1, in.available());
        }
    }

    /**
     * Tests that getBytesRead tracks decompressed bytes.
     */
    @Test
    void testGetBytesRead() throws IOException {
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(ABCABC_COMPRESSED))) {
            assertEquals(0, in.getBytesRead());
            in.read();
            assertEquals(1, in.getBytesRead());
            final byte[] buf = new byte[5];
            in.read(buf);
            assertEquals(6, in.getBytesRead());
        }
    }

    /**
     * Tests with a larger repeating pattern.
     */
    @Test
    void testRepeatingPattern() throws IOException {
        final byte[] expected = "abcabcabcabcabcabc".getBytes(US_ASCII);
        final byte[] compressed = {
            // literal "abc"
            0x02, 'a', 'b', 'c',
            // five back-references to "abc"
            (byte) 0x81, 0x03, 0x00,
            (byte) 0x81, 0x03, 0x00,
            (byte) 0x81, 0x03, 0x00,
            (byte) 0x81, 0x03, 0x00,
            (byte) 0x81, 0x03, 0x00,
        };

        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] result = readAll(in);
            assertArrayEquals(expected, result);
        }
    }

    private static byte[] readAll(final LZ77CompressorInputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }
}