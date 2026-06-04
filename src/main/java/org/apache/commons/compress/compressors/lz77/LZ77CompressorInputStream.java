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

import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.utils.InputStreamStatistics;
import org.apache.commons.io.IOUtils;

/**
 * CompressorInputStream for the raw LZ77 compression format (no container).
 *
 * <p>
 * This stream decompresses data that was compressed using the LZ77 algorithm with a simple binary encoding. The format uses flag bytes to distinguish between
 * literal bytes and back-references (length-distance pairs), implementing the core LZ77 sliding-window decompression logic.
 * </p>
 *
 * <h2>Stream Format</h2>
 * <p>
 * The compressed stream consists of groups of up to 8 tokens. Each group starts with a 1-byte flag byte where each bit (LSB first) indicates the token type:
 * </p>
 * <ul>
 * <li>Bit = 0: literal token — 1 byte of uncompressed data follows</li>
 * <li>Bit = 1: back-reference token — a 2-byte little-endian offset followed by a 1-byte encoded length follows. The offset ranges from 1 to 65535 and the
 * length is stored as {@code length - 3}, supporting match lengths from 3 to 258.</li>
 * </ul>
 * <p>
 * The stream ends when the underlying input stream is exhausted (EOF on flag byte read).
 * </p>
 *
 * <h2>Sliding Window</h2>
 * <p>
 * A circular buffer of configurable size is used as the sliding window. Back-references may overlap with the current write position, which correctly handles
 * run-length encoding patterns (e.g., a back-reference with offset 1 and length N repeats the last byte N times).
 * </p>
 *
 * @see <a href="https://en.wikipedia.org/wiki/LZ77_and_LZ78">LZ77 and LZ78</a>
 * @since 1.28.0
 */
public class LZ77CompressorInputStream extends CompressorInputStream implements InputStreamStatistics {

    /** Default window size: 64 KiB. */
    public static final int DEFAULT_WINDOW_SIZE = 1 << 16;

    /** Minimum back-reference match length (hard-coded as per classic LZ77). */
    static final int MIN_MATCH_LENGTH = 3;

    /** Number of tokens per flag byte. */
    private static final int TOKENS_PER_FLAG = 8;

    private enum State {
        READ_FLAG, READ_LITERAL, READ_BACKREF_OFFSET_LOW, READ_BACKREF_OFFSET_HIGH, READ_BACKREF_LENGTH, COPY_BACKREF, EOF
    }

    /** Circular buffer acting as the sliding window. */
    private final byte[] window;

    /** Mask for modular indexing into the circular buffer. */
    private final int windowMask;

    /** Current write position in the circular buffer. */
    private int writePos;

    /** The underlying compressed input stream. */
    private final InputStream in;

    /** Current parser state. */
    private State state;

    /** Current flag byte being processed. */
    private int flagByte;

    /** Index of the next bit to process in the current flag byte (0..7). */
    private int flagBitIndex;

    /** Offset of the current back-reference being decoded. */
    private int backRefOffset;

    /** Remaining length of the current back-reference being copied. */
    private int backRefRemaining;

    /** Temporary storage for the low byte of a 2-byte offset. */
    private int offsetLow;

    /** Total number of compressed bytes read from the underlying stream. */
    private long compressedCount;

    /** Total number of decompressed bytes returned to the caller. */
    private long uncompressedCount;

    /** Single-byte buffer for the no-arg {@link #read()} method. */
    private final byte[] oneByte = new byte[1];

    /**
     * Creates a new LZ77 input stream with the default window size of 64 KiB.
     *
     * @param in An InputStream to read compressed data from.
     */
    public LZ77CompressorInputStream(final InputStream in) {
        this(in, DEFAULT_WINDOW_SIZE);
    }

    /**
     * Creates a new LZ77 input stream with a configurable window size.
     *
     * @param in         An InputStream to read compressed data from.
     * @param windowSize Size of the sliding window. Must be a power of two and at least 1.
     * @throws IllegalArgumentException if windowSize is not a power of two or is less than 1.
     */
    public LZ77CompressorInputStream(final InputStream in, final int windowSize) {
        if (windowSize < 1 || (windowSize & windowSize - 1) != 0) {
            throw new IllegalArgumentException("windowSize must be a power of two and at least 1");
        }
        this.in = in;
        this.window = new byte[windowSize];
        this.windowMask = windowSize - 1;
        this.writePos = 0;
        this.state = State.READ_FLAG;
        this.flagBitIndex = TOKENS_PER_FLAG;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public long getCompressedCount() {
        return compressedCount;
    }

    @Override
    public long getUncompressedCount() {
        return uncompressedCount;
    }

    @Override
    public int read() throws IOException {
        return read(oneByte, 0, 1) == -1 ? -1 : oneByte[0] & 0xFF;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        IOUtils.checkFromIndexSize(b, off, len);
        if (len == 0) {
            return 0;
        }
        int totalRead = 0;
        while (totalRead < len) {
            final int n = readOneStep(b, off + totalRead, len - totalRead);
            if (n == -1) {
                return totalRead > 0 ? totalRead : -1;
            }
            totalRead += n;
        }
        return totalRead;
    }

    /**
     * Reads a single byte from the underlying stream and tracks the compressed byte count.
     *
     * @return the byte read (0..255), or -1 if EOF.
     * @throws IOException if the underlying stream throws.
     */
    private int readCompressedByte() throws IOException {
        final int b = in.read();
        if (b != -1) {
            compressedCount++;
        }
        return b;
    }

    /**
     * Emits a single literal byte into the output buffer and writes it into the sliding window.
     *
     * @param b   the output buffer.
     * @param off the offset in the output buffer.
     * @param lit the literal byte value.
     * @return the number of bytes written (always 1).
     */
    private int emitLiteral(final byte[] b, final int off, final int lit) {
        window[writePos] = (byte) lit;
        writePos = writePos + 1 & windowMask;
        b[off] = (byte) lit;
        uncompressedCount++;
        return 1;
    }

    /**
     * Performs one step of the state machine, reading and decompressing data.
     *
     * <p>
     * This method may read from the underlying stream and write up to {@code len} bytes into the output buffer. It returns the number of bytes written, or -1
     * if EOF has been reached and no bytes were written.
     * </p>
     *
     * @param b   the output buffer.
     * @param off the starting offset in the buffer.
     * @param len the maximum number of bytes to write.
     * @return the number of bytes written, or -1 on EOF with nothing written.
     * @throws IOException if the underlying stream throws or if the stream contains invalid data.
     */
    private int readOneStep(final byte[] b, final int off, final int len) throws IOException {
        switch (state) {
        case EOF:
            return -1;
        case READ_FLAG:
            return readFlagByte();
        case READ_LITERAL:
            return readLiteralToken(b, off);
        case READ_BACKREF_OFFSET_LOW:
            return readBackRefOffsetLow();
        case READ_BACKREF_OFFSET_HIGH:
            return readBackRefOffsetHigh();
        case READ_BACKREF_LENGTH:
            return readBackRefLength();
        case COPY_BACKREF:
            return copyBackRef(b, off, len);
        default:
            throw new IllegalStateException("Unknown state: " + state);
        }
    }

    private int readFlagByte() throws IOException {
        flagByte = readCompressedByte();
        if (flagByte == -1) {
            state = State.EOF;
            return -1;
        }
        flagBitIndex = 0;
        advanceToNextToken();
        return 0;
    }

    private void advanceToNextToken() {
        if (flagBitIndex >= TOKENS_PER_FLAG) {
            state = State.READ_FLAG;
            return;
        }
        if ((flagByte >>> flagBitIndex & 1) == 0) {
            state = State.READ_LITERAL;
        } else {
            state = State.READ_BACKREF_OFFSET_LOW;
        }
    }

    private int readLiteralToken(final byte[] b, final int off) throws IOException {
        final int lit = readCompressedByte();
        if (lit == -1) {
            state = State.EOF;
            return -1;
        }
        flagBitIndex++;
        final int written = emitLiteral(b, off, lit);
        advanceToNextToken();
        return written;
    }

    private int readBackRefOffsetLow() throws IOException {
        offsetLow = readCompressedByte();
        if (offsetLow == -1) {
            state = State.EOF;
            return -1;
        }
        state = State.READ_BACKREF_OFFSET_HIGH;
        return 0;
    }

    private int readBackRefOffsetHigh() throws IOException {
        final int offsetHigh = readCompressedByte();
        if (offsetHigh == -1) {
            state = State.EOF;
            return -1;
        }
        backRefOffset = offsetLow | offsetHigh << 8;
        if (backRefOffset < 1) {
            throw new IOException("Invalid back-reference offset: " + backRefOffset);
        }
        state = State.READ_BACKREF_LENGTH;
        return 0;
    }

    private int readBackRefLength() throws IOException {
        final int lenCode = readCompressedByte();
        if (lenCode == -1) {
            state = State.EOF;
            return -1;
        }
        backRefRemaining = lenCode + MIN_MATCH_LENGTH;
        if (backRefRemaining < MIN_MATCH_LENGTH) {
            throw new IOException("Invalid back-reference length: " + backRefRemaining);
        }
        flagBitIndex++;
        state = State.COPY_BACKREF;
        return 0;
    }

    private int copyBackRef(final byte[] b, final int off, final int len) {
        final int toCopy = Math.min(len, backRefRemaining);
        for (int i = 0; i < toCopy; i++) {
            final int srcPos = (writePos - backRefOffset) & windowMask;
            final byte value = window[srcPos];
            window[writePos] = value;
            writePos = writePos + 1 & windowMask;
            b[off + i] = value;
        }
        backRefRemaining -= toCopy;
        uncompressedCount += toCopy;
        if (backRefRemaining == 0) {
            advanceToNextToken();
        }
        return toCopy;
    }
}
