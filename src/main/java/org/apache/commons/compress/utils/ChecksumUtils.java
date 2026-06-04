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
package org.apache.commons.compress.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

/**
 * Utility class for working with checksums.
 *
 * @since 1.29.0
 */
public final class ChecksumUtils {

    private ChecksumUtils() {
        // Utility class
    }

    /**
     * Calculates the Adler-32 checksum of the data read from the given InputStream.
     *
     * @param input the InputStream to read from
     * @return the calculated Adler-32 checksum
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if input is null
     */
    public static long calculateAdler32(final InputStream input) throws IOException {
        return calculate(new Adler32(), input);
    }

    /**
     * Calculates the checksum of the data read from the given InputStream using the provided Checksum.
     *
     * @param checksum the Checksum to use
     * @param input the InputStream to read from
     * @return the calculated checksum
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if checksum or input is null
     */
    public static long calculate(final Checksum checksum, final InputStream input) throws IOException {
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(input, "input");

        final byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            checksum.update(buffer, 0, bytesRead);
        }
        return checksum.getValue();
    }
}
