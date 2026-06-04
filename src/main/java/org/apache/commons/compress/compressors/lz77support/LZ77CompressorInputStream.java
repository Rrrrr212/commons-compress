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

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.utils.ByteUtils;
import org.apache.commons.io.IOUtils;

/**
 * Decompressor for raw LZ77 compressed streams without container format.
 *
 * <p>
 * The stream format is a simple sequence of commands, each consisting of:
 * </p>
 * <ul>
 * <li>A one-byte header:
 * <ul>
 * <li>Bit 7: 0 for a literal block, 1 for a back-reference.</li>
 * <li>Bits 0-6: length field. For literals, actual length = (value &amp; 0x7F) + 1 (1..128).
 * For back-references, actual length = (value &amp; 0x7F) + 2 (2..129).</li>
 * </ul>
 * </li>
 * <li>For a literal block, {@code length} bytes of literal data follow immediately.</li>
 * <li>For a back-reference, 2 bytes of little-endian unsigned 16-bit offset follow the header,
 * indicating the distance backwards from the current output position.</li>
 * </ul>
 *
 * <p>
 * The sliding window is 65536 bytes (64 KiB), supporting offsets up to 65535.
 * </p>
 *
 * <p>
 * This class extends {@link AbstractLZ77CompressorInputStream} which inherits from
 * {@link org.apache.commons.compress.compressors.CompressorInputStream}, providing the
 * sliding window buffer and back-reference resolution logic.
 * </p>
 *
 * <h2>Example usage</h2>
 *
 * <pre>{@code
 * try (InputStream in = new ByteArrayInputStream(compressedData);
 *      LZ77CompressorInputStream lz77In = new LZ77CompressorInputStream(in)) {
 *     byte[] decompressed = IOUtils.toByteArray(lz77In);
 * }
 * }</pre>
 *
 * @see AbstractLZ77CompressorInputStream
 * @since 1.29.0
 */
public class LZ77CompressorInputStream extends AbstractLZ77CompressorInputStream {

    static final int WINDOW_SIZE = 1 << 16;

    static final int HEADER_LENGTH_MASK = 0x7F;

    static final int BACK_REFERENCE_FLAG = 0x80;

    static final int MIN_LITERAL_LENGTH = 1;

    static final int MIN_BACK_REFERENCE_LENGTH = 2;

    static final int OFFSET_BYTES = 2;

    private enum State {
        NO_BLOCK, IN_LITERAL, IN_BACK_REFERENCE, EOF
    }

    private State state = State.NO_BLOCK;

    /**
     * Creates a new LZ77 decompressor input stream.
     *
     * @param is the InputStream to read the compressed data from.
     */
    public LZ77CompressorInputStream(final InputStream is) {
        super(is, WINDOW_SIZE);
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
            readNextCommand();
            if (state == State.EOF) {
                return -1;
            }
            return read(b, off, len);
        case IN_LITERAL:
            final int litLen = readLiteral(b, off, len);
            if (!hasMoreDataInBlock()) {
                state = State.NO_BLOCK;
            }
            return litLen > 0 ? litLen : read(b, off, len);
        case IN_BACK_REFERENCE:
            final int backRefLen = readBackReference(b, off, len);
            if (!hasMoreDataInBlock()) {
                state = State.NO_BLOCK;
            }
            return backRefLen > 0 ? backRefLen : read(b, off, len);
        default:
            throw new CompressorException("Unknown stream state %s", state);
        }
    }

    private void readNextCommand() throws IOException {
        final int header = readOneByte();
        if (header == -1) {
            state = State.EOF;
            return;
        }
        final boolean isBackReference = (header & BACK_REFERENCE_FLAG) != 0;
        final int lengthField = header & HEADER_LENGTH_MASK;
        if (isBackReference) {
            final long length = lengthField + MIN_BACK_REFERENCE_LENGTH;
            final int offset = (int) ByteUtils.fromLittleEndian(supplier, OFFSET_BYTES);
            if (offset <= 0 || offset > WINDOW_SIZE) {
                throw new CompressorException("Illegal back-reference offset: %d", offset);
            }
            startBackReference(offset, length);
            state = State.IN_BACK_REFERENCE;
        } else {
            final long length = lengthField + MIN_LITERAL_LENGTH;
            startLiteral(length);
            state = State.IN_LITERAL;
        }
    }
}