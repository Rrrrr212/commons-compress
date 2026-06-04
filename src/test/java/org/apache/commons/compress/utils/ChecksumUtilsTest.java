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
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.Adler32;

import org.junit.jupiter.api.Test;

class ChecksumUtilsTest {

    @Test
    void testCalculateAdler32_normalInput() throws IOException {
        final String testData = "hello world";
        final InputStream input = new ByteArrayInputStream(testData.getBytes(StandardCharsets.UTF_8));
        
        // Calculate expected Adler32 using direct Adler32 implementation
        final Adler32 expectedAdler32 = new Adler32();
        expectedAdler32.update(testData.getBytes(StandardCharsets.UTF_8));
        
        final long result = ChecksumUtils.calculateAdler32(input);
        assertEquals(expectedAdler32.getValue(), result);
    }

    @Test
    void testCalculateAdler32_emptyInput() throws IOException {
        final InputStream input = new ByteArrayInputStream(new byte[0]);
        
        // Adler32 of empty data is 1
        final long result = ChecksumUtils.calculateAdler32(input);
        assertEquals(1L, result);
    }

    @Test
    void testCalculateAdler32_largeInput() throws IOException {
        // Create 10MB of random data
        final byte[] largeData = new byte[10 * 1024 * 1024];
        final Random random = new Random(42); // Fixed seed for reproducibility
        random.nextBytes(largeData);
        
        final InputStream input = new ByteArrayInputStream(largeData);
        
        // Calculate expected Adler32
        final Adler32 expectedAdler32 = new Adler32();
        expectedAdler32.update(largeData);
        
        final long result = ChecksumUtils.calculateAdler32(input);
        assertEquals(expectedAdler32.getValue(), result);
    }

    @Test
    void testCalculateAdler32_nullInput() {
        assertThrows(NullPointerException.class, () -> ChecksumUtils.calculateAdler32(null));
    }

    @Test
    void testCalculate_normalInput() throws IOException {
        final String testData = "test data";
        final InputStream input = new ByteArrayInputStream(testData.getBytes(StandardCharsets.UTF_8));
        final Adler32 adler32 = new Adler32();
        
        final long result = ChecksumUtils.calculate(adler32, input);
        
        final Adler32 expectedAdler32 = new Adler32();
        expectedAdler32.update(testData.getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedAdler32.getValue(), result);
    }

    @Test
    void testCalculate_nullChecksum() {
        final InputStream input = new ByteArrayInputStream(new byte[0]);
        assertThrows(NullPointerException.class, () -> ChecksumUtils.calculate(null, input));
    }

    @Test
    void testCalculate_nullInput() {
        final Adler32 adler32 = new Adler32();
        assertThrows(NullPointerException.class, () -> ChecksumUtils.calculate(adler32, null));
    }
}
