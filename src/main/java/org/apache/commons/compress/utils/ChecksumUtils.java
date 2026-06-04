package org.apache.commons.compress.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Adler32;

/**
 * Utility methods for Checksum.
 */
public class ChecksumUtils {

    /**
     * Calculates the Adler-32 checksum of the given InputStream.
     *
     * @param input the input stream
     * @return the Adler-32 checksum
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if the input is null
     */
    public static long calculateAdler32(final InputStream input) throws IOException {
        if (input == null) {
            throw new NullPointerException("InputStream must not be null");
        }
        final Adler32 checksum = new Adler32();
        final byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            checksum.update(buffer, 0, n);
        }
        return checksum.getValue();
    }
}
