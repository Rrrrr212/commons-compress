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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LZ77CompressorInputStream}.
 */
class LZ77CompressorInputStreamTest {

    @Test
    void testAbcAbc() throws IOException {
        final byte[] original = "abcabc".getBytes();
        final byte[] compressed = compress(original);
        final byte[] decompressed = decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void testEmptyString() throws IOException {
        final byte[] original = new byte[0];
        final byte[] compressed = compress(original);
        final byte[] decompressed = decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void testLongRepeatingString() throws IOException {
        final byte[] original = "aaaaabbbbbcccccdddddeeeee".getBytes();
        final byte[] compressed = compress(original);
        final byte[] decompressed = decompress(compressed);
        assertArrayEquals(original, decompressed);
    }

    @Test
    void testSingleByteReadConsistentlyReturnsMinusOneAtEof() throws IOException {
        final byte[] original = "test".getBytes();
        final byte[] compressed = compress(original);
        try (InputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            IOUtils.toByteArray(in);
            assertEquals(-1, in.read());
            assertEquals(-1, in.read());
        }
    }

    private byte[] compress(final byte[] data) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                LZ77CompressorOutputStream out = new LZ77CompressorOutputStream(baos)) {
            out.write(data);
            out.finish();
            return baos.toByteArray();
        }
    }

    private byte[] decompress(final byte[] data) throws IOException {
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(data))) {
            return IOUtils.toByteArray(in);
        }
    }
}
