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

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.lz77support.AbstractLZ77CompressorInputStream;
import org.apache.commons.compress.utils.ByteUtils;
import org.apache.commons.io.IOUtils;

/**
 * CompressorInputStream for raw LZ77 streams without container format.
 * <p>
 * This class implements a simple LZ77 decompression algorithm that works on raw streams
 * without additional headers or footers. The stream format is as follows:
 * </p>
 * <ul>
 *   <li>Each element starts with a type indicator byte (0 for literal, 1 for back reference)</li>
 *   <li>For literals: followed by a 4-byte little-endian length, then the literal bytes</li>
 *   <li>For back references: followed by 4-byte little-endian offset, then 4-byte little-endian length</li>
 * </ul>
 * <p>
 * The stream ends with a type indicator byte of 0xFF.
 * </p>
 *
 * @since 1.28.0
 * @NotThreadSafe
 */
public class LZ77CompressorInputStream extends AbstractLZ77CompressorInputStream {

    private enum State {
        NO_BLOCK,
        IN_LITERAL,
        IN_BACK_REFERENCE,
        EOF
    }

    private static final int TYPE_LITERAL = 0;
    private static final int TYPE_BACK_REFERENCE = 1;
    private static final int TYPE_EOF = 0xFF;

    static final int DEFAULT_WINDOW_SIZE = 1 << 16;

    private State state = State.NO_BLOCK;

    /**
     * Creates a new LZ77 input stream with default window size.
     *
     * @param is An InputStream to read compressed data from.
     */
    public LZ77CompressorInputStream(final InputStream is) {
        this(is, DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates a new LZ77 input stream with specified window size.
     *
     * @param is An InputStream to read compressed data from.
     * @param windowSize Size of the window kept for back-references.
     */
    public LZ77CompressorInputStream(final InputStream is, final int windowSize) {
        super(is, windowSize);
    }

    private void readNextBlock() throws IOException {
        final int type = readOneByte();
        if (type == -1) {
            state = State.EOF;
            return;
        }

        switch (type) {
            case TYPE_LITERAL:
                readLiteralBlock();
                break;
            case TYPE_BACK_REFERENCE:
                readBackReferenceBlock();
                break;
            case TYPE_EOF:
                state = State.EOF;
                break;
            default:
                throw new CompressorException("Invalid block type: " + type);
        }
    }

    private void readLiteralBlock() throws IOException {
        final long length = ByteUtils.fromLittleEndian(supplier, 4);
        if (length < 0) {
            throw new CompressorException("Invalid literal length: " + length);
        }
        startLiteral(length);
        state = State.IN_LITERAL;
    }

    private void readBackReferenceBlock() throws IOException {
        final int offset = (int) ByteUtils.fromLittleEndian(supplier, 4);
        final long length = ByteUtils.fromLittleEndian(supplier, 4);
        if (offset <= 0) {
            throw new CompressorException("Invalid back reference offset: " + offset);
        }
        if (length < 0) {
            throw new CompressorException("Invalid back reference length: " + length);
        }
        startBackReference(offset, length);
        state = State.IN_BACK_REFERENCE;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        IOUtils.checkFromIndexSize(b, off, len);
        if (len == 0) {
            return 0;
        }

        while (true) {
            switch (state) {
                case EOF:
                    return -1;
                case NO_BLOCK:
                    readNextBlock();
                    if (state == State.EOF) {
                        return -1;
                    }
                    break;
                case IN_LITERAL:
                    final int litLen = readLiteral(b, off, len);
                    if (!hasMoreDataInBlock()) {
                        state = State.NO_BLOCK;
                    }
                    if (litLen > 0) {
                        return litLen;
                    }
                    break;
                case IN_BACK_REFERENCE:
                    final int refLen = readBackReference(b, off, len);
                    if (!hasMoreDataInBlock()) {
                        state = State.NO_BLOCK;
                    }
                    if (refLen > 0) {
                        return refLen;
                    }
                    break;
            }
        }
    }
}
