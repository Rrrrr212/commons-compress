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
 * CompressorInputStream for a raw LZ77 stream.
 *
 * <p>
 * This implementation uses an internal sliding window in order to materialize back-references while reading from the underlying stream.
 * </p>
 *
 * <p>
 * The raw format handled by this class is a sequence of blocks without any container header or footer. Each block starts with a single control byte. If the
 * most significant bit of the control byte is clear, the remaining seven bits encode the literal length and the literal bytes follow immediately. If the most
 * significant bit is set, the remaining seven bits encode the match length and the block is followed by a two-byte little-endian distance.
 * </p>
 *
 * <p>
 * End of stream marks the end of the compressed payload.
 * </p>
 *
 * @since 1.29.0
 * @NotThreadSafe
 */
public class LZ77CompressorInputStream extends AbstractLZ77CompressorInputStream {

    private enum State {
        NO_BLOCK, IN_LITERAL, IN_BACK_REFERENCE, EOF
    }

    /** Default size of the sliding window. */
    public static final int DEFAULT_WINDOW_SIZE = 1 << 16;

    private static final int BACK_REFERENCE_FLAG = 0x80;
    private static final int LENGTH_MASK = 0x7F;

    private State state = State.NO_BLOCK;

    /**
     * Creates a new input stream using the default window size.
     *
     * @param inputStream the stream to read compressed data from.
     */
    public LZ77CompressorInputStream(final InputStream inputStream) {
        this(inputStream, DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates a new input stream using a configurable window size.
     *
     * @param inputStream the stream to read compressed data from.
     * @param windowSize the size of the sliding window used for back-references.
     */
    public LZ77CompressorInputStream(final InputStream inputStream, final int windowSize) {
        super(inputStream, windowSize);
    }

    private void initializeNextBlock() throws IOException {
        final int tag = readOneByte();
        if (tag == -1) {
            state = State.EOF;
            return;
        }

        final int length = tag & LENGTH_MASK;
        if (length == 0) {
            throw new CompressorException("Illegal block with a zero length found");
        }

        if ((tag & BACK_REFERENCE_FLAG) == 0) {
            startLiteral(length);
            state = State.IN_LITERAL;
            return;
        }

        final int offset = readBackReferenceOffset();
        try {
            startBackReference(offset, length);
        } catch (final IllegalArgumentException ex) {
            throw new CompressorException("Illegal block with bad offset found", ex);
        }
        state = State.IN_BACK_REFERENCE;
    }

    private int readBackReferenceOffset() throws IOException {
        final int offset = (int) ByteUtils.fromLittleEndian(supplier, 2);
        if (offset <= 0) {
            throw new CompressorException("Illegal block with a non-positive offset found");
        }
        return offset;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        IOUtils.checkFromIndexSize(b, off, len);
        if (len == 0) {
            return 0;
        }
        switch (state) {
        case EOF:
            return -1;
        case NO_BLOCK:
            initializeNextBlock();
            return read(b, off, len);
        case IN_LITERAL:
            final int literalLength = readLiteral(b, off, len);
            if (!hasMoreDataInBlock()) {
                state = State.NO_BLOCK;
            }
            return literalLength > 0 ? literalLength : read(b, off, len);
        case IN_BACK_REFERENCE:
            final int backReferenceLength = readBackReference(b, off, len);
            if (!hasMoreDataInBlock()) {
                state = State.NO_BLOCK;
            }
            return backReferenceLength > 0 ? backReferenceLength : read(b, off, len);
        default:
            throw new CompressorException("Unknown stream state %s", state);
        }
    }
}
