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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.compress.compressors.CompressorOutputStream;
import org.apache.commons.compress.compressors.lz77support.LZ77Compressor;
import org.apache.commons.compress.compressors.lz77support.Parameters;
import org.apache.commons.compress.utils.ByteUtils;

/**
 * CompressorOutputStream for raw LZ77 streams without container format.
 * <p>
 * This class provides a basic LZ77 compression implementation that produces streams
 * compatible with {@link LZ77CompressorInputStream}.
 * </p>
 *
 * @see LZ77CompressorInputStream
 * @since 1.28.0
 * @NotThreadSafe
 */
public class LZ77CompressorOutputStream extends CompressorOutputStream<OutputStream> {

    private static final int TYPE_LITERAL = 0;
    private static final int TYPE_BACK_REFERENCE = 1;
    private static final int TYPE_EOF = 0xFF;

    private final LZ77Compressor compressor;
    private final List<LZ77Compressor.Block> blocks = new ArrayList<>();
    private boolean finished = false;

    /**
     * Creates a new LZ77 output stream with default parameters.
     *
     * @param out The output stream to write compressed data to.
     */
    public LZ77CompressorOutputStream(final OutputStream out) {
        this(out, Parameters.builder().withWindowSize(LZ77CompressorInputStream.DEFAULT_WINDOW_SIZE).build());
    }

    /**
     * Creates a new LZ77 output stream with specified parameters.
     *
     * @param out The output stream to write compressed data to.
     * @param params The LZ77 compression parameters.
     */
    public LZ77CompressorOutputStream(final OutputStream out, final Parameters params) {
        super(out);
        this.compressor = new LZ77Compressor(params, blocks::add);
    }

    @Override
    public void write(final int b) throws IOException {
        write(new byte[] { (byte) b }, 0, 1);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if (finished) {
            throw new IOException("Stream already finished");
        }
        compressor.compress(b, off, len);
    }

    @Override
    public void finish() throws IOException {
        if (!finished) {
            compressor.finish();
            finished = true;
            writeBlocks();
        }
    }

    private void writeBlocks() throws IOException {
        for (final LZ77Compressor.Block block : blocks) {
            switch (block.getType()) {
                case LITERAL:
                    writeLiteral((LZ77Compressor.LiteralBlock) block);
                    break;
                case BACK_REFERENCE:
                    writeBackReference((LZ77Compressor.BackReference) block);
                    break;
                case EOD:
                    out.write(TYPE_EOF);
                    break;
            }
        }
    }

    private void writeLiteral(final LZ77Compressor.LiteralBlock block) throws IOException {
        out.write(TYPE_LITERAL);
        final int length = block.getLength();
        writeInt(length);
        out.write(block.getData(), block.getOffset(), length);
    }

    private void writeBackReference(final LZ77Compressor.BackReference block) throws IOException {
        out.write(TYPE_BACK_REFERENCE);
        writeInt(block.getOffset());
        writeInt(block.getLength());
    }

    private void writeInt(final int value) throws IOException {
        final byte[] buf = new byte[4];
        ByteUtils.toLittleEndian(buf, value, 0, 4);
        out.write(buf);
    }

    @Override
    public void close() throws IOException {
        try {
            if (!finished) {
                finish();
            }
        } finally {
            out.close();
        }
    }
}
