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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.zip.Adler32;

import org.junit.jupiter.api.Test;

class ChecksumUtilsTest {

    private static final String HELLO_WORLD = "hello world";

    @Test
    void testCalculateAdler32WithNormalInput() throws IOException {
        final InputStream input = new ByteArrayInputStream(HELLO_WORLD.getBytes("UTF-8"));
        final long checksum = ChecksumUtils.calculateAdler32(input);
        final Adler32 expected = new Adler32();
        expected.update(HELLO_WORLD.getBytes("UTF-8"));
        assertEquals(expected.getValue(), checksum);
    }

    @Test
    void testCalculateAdler32WithEmptyInput() throws IOException {
        final InputStream input = new ByteArrayInputStream(new byte[0]);
        final long checksum = ChecksumUtils.calculateAdler32(input);
        assertEquals(1L, checksum);
    }

    @Test
    void testCalculateAdler32WithLargeData() throws IOException {
        final int dataSize = 10 * 1024 * 1024;
        final byte[] data = new byte[dataSize];
        new SecureRandom().nextBytes(data);
        final Adler32 expected = new Adler32();
        expected.update(data);
        final InputStream input = new ByteArrayInputStream(data);
        final long checksum = ChecksumUtils.calculateAdler32(input);
        assertEquals(expected.getValue(), checksum);
    }

    @Test
    void testCalculateAdler32WithNullInput() {
        assertThrows(NullPointerException.class, () -> ChecksumUtils.calculateAdler32(null));
    }

    @Test
    void testCalculateAdler32WithSingleByte() throws IOException {
        final byte[] data = { (byte) 0xFF };
        final InputStream input = new ByteArrayInputStream(data);
        final long checksum = ChecksumUtils.calculateAdler32(input);
        final Adler32 expected = new Adler32();
        expected.update(data);
        assertEquals(expected.getValue(), checksum);
    }

    @Test
    void testCalculateAdler32ConstructorIsPrivate() {
        final java.lang.reflect.Constructor<?>[] constructors = ChecksumUtils.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        final java.lang.reflect.Constructor<?> constructor = constructors[0];
        assertEquals(false, constructor.isAccessible());
    }
}