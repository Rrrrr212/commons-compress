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

/**
 * Utility methods for computing checksums.
 *
 * @since 1.29.0
 */
public final class ChecksumUtils {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Calculates the Adler-32 checksum of the data read from the given input stream.
     *
     * @param input the input stream to read data from
     * @return the Adler-32 checksum value
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if input is null
     */
    public static long calculateAdler32(final InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        final Adler32 adler32 = new Adler32();
        final byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            adler32.update(buffer, 0, bytesRead);
        }
        return adler32.getValue();
    }

    private ChecksumUtils() {
    }
}
