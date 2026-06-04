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
import java.util.zip.CheckedInputStream;

public final class ChecksumUtils {

    public static long calculateAdler32(final InputStream input) throws IOException {
        final CheckedInputStream checkedInputStream = new CheckedInputStream(Objects.requireNonNull(input, "input"), new Adler32());
        final byte[] buffer = new byte[8192];
        while (checkedInputStream.read(buffer) != -1) {
        }
        return checkedInputStream.getChecksum().getValue();
    }

    private ChecksumUtils() {
    }
}
