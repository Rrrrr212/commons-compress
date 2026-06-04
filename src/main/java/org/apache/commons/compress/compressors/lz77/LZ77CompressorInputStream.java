package org.apache.commons.compress.compressors.lz77;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.compress.compressors.CompressorInputStream;

/**
 * A simple LZ77 compressor input stream for raw LZ77 compressed data (no container format).
 * <p>
 * This class implements a basic LZ77 decompression logic with a sliding window
 * and length-distance pair parsing. As there is no universal standard for "raw" LZ77
 * streams, this implementation uses a simple custom binary format:
 * </p>
 * <ul>
 * <li><b>Type byte:</b> 0 = EOF, 1 = Literal, 2 = Back-Reference</li>
 * <li><b>Literal block:</b> Type byte (1) followed by 1 byte length (1-255), then the literal bytes.</li>
 * <li><b>Back-Reference block:</b> Type byte (2) followed by 2 bytes distance (big-endian, 1-65535), then 1 byte length (1-255).</li>
 * </ul>
 * 
 * @since 1.28.0
 */
public class LZ77CompressorInputStream extends CompressorInputStream {

    private static final int STATE_READ_BLOCK_HEADER = 0;
    private static final int STATE_LITERAL = 1;
    private static final int STATE_BACK_REFERENCE = 2;
    private static final int STATE_EOF = 3;

    private final InputStream in;
    private final byte[] window;
    
    private int windowPos = 0;
    private int state = STATE_READ_BLOCK_HEADER;
    private int remainingInBlock = 0;
    private int backRefDistance = 0;

    /**
     * Constructs a new LZ77CompressorInputStream with a default window size of 32 KB.
     * 
     * @param in the InputStream from which to read the compressed data
     */
    public LZ77CompressorInputStream(final InputStream in) {
        this(in, 32768);
    }

    /**
     * Constructs a new LZ77CompressorInputStream with a specified window size.
     * 
     * @param in         the InputStream from which to read the compressed data
     * @param windowSize the size of the sliding window
     */
    public LZ77CompressorInputStream(final InputStream in, final int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("Window size must be greater than 0");
        }
        this.in = in;
        this.window = new byte[windowSize];
    }

    @Override
    public int read() throws IOException {
        final byte[] b = new byte[1];
        if (read(b, 0, 1) == 1) {
            return b[0] & 0xFF;
        }
        return -1;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (state == STATE_EOF) {
            return -1;
        }

        int bytesRead = 0;
        while (bytesRead < len) {
            if (state == STATE_READ_BLOCK_HEADER) {
                final int type = in.read();
                if (type == -1 || type == 0) {
                    state = STATE_EOF;
                    break;
                } else if (type == 1) {
                    // Literal
                    final int length = in.read();
                    if (length == -1) {
                        throw new IOException("Unexpected end of stream while reading literal length");
                    }
                    remainingInBlock = length & 0xFF;
                    if (remainingInBlock == 0) {
                        continue; // skip empty blocks
                    }
                    state = STATE_LITERAL;
                } else if (type == 2) {
                    // Back-reference
                    final int distHigh = in.read();
                    final int distLow = in.read();
                    final int length = in.read();
                    if (distHigh == -1 || distLow == -1 || length == -1) {
                        throw new IOException("Unexpected end of stream while reading back-reference");
                    }
                    backRefDistance = ((distHigh & 0xFF) << 8) | (distLow & 0xFF);
                    remainingInBlock = length & 0xFF;
                    if (backRefDistance <= 0) {
                        throw new IOException("Invalid back-reference distance: " + backRefDistance);
                    }
                    if (remainingInBlock == 0) {
                        continue; // skip empty blocks
                    }
                    state = STATE_BACK_REFERENCE;
                } else {
                    throw new IOException("Unknown block type: " + type);
                }
            }

            if (state == STATE_LITERAL) {
                final int toRead = Math.min(len - bytesRead, remainingInBlock);
                final int readFromIn = in.read(b, off + bytesRead, toRead);
                if (readFromIn == -1) {
                    throw new IOException("Unexpected end of stream in literal data");
                }
                
                for (int i = 0; i < readFromIn; i++) {
                    window[windowPos] = b[off + bytesRead + i];
                    windowPos = (windowPos + 1) % window.length;
                }
                
                bytesRead += readFromIn;
                count(readFromIn);
                remainingInBlock -= readFromIn;
                if (remainingInBlock == 0) {
                    state = STATE_READ_BLOCK_HEADER;
                }
            } else if (state == STATE_BACK_REFERENCE) {
                final int toRead = Math.min(len - bytesRead, remainingInBlock);
                for (int i = 0; i < toRead; i++) {
                    // Calculate read position in sliding window
                    final int readPos = (windowPos - backRefDistance + window.length) % window.length;
                    final byte val = window[readPos];
                    b[off + bytesRead] = val;
                    window[windowPos] = val;
                    windowPos = (windowPos + 1) % window.length;
                    bytesRead++;
                    count(1);
                }
                remainingInBlock -= toRead;
                if (remainingInBlock == 0) {
                    state = STATE_READ_BLOCK_HEADER;
                }
            }
        }
        
        if (bytesRead == 0 && state == STATE_EOF) {
            return -1;
        }
        return bytesRead;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}