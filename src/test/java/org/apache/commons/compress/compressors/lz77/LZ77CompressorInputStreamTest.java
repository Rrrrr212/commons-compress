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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

public class LZ77CompressorInputStreamTest {

    @Test
    public void testAbcabc() throws Exception {
        // We will construct a compressed representation of "abcabc".
        // Format:
        // Type 1 (Literal), Length 3, Data "abc"
        // Type 2 (Back-ref), Distance 3, Length 3
        // Type 0 (EOF)
        
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // Block 1: Literal "abc"
        baos.write(1); // Type Literal
        baos.write(3); // Length 3
        baos.write(new byte[] {'a', 'b', 'c'});
        
        // Block 2: Back-reference to "abc"
        baos.write(2); // Type Back-reference
        baos.write(0); // Distance high byte
        baos.write(3); // Distance low byte
        baos.write(3); // Length 3
        
        // Block 3: EOF
        baos.write(0);
        
        final byte[] compressed = baos.toByteArray();
        
        try (LZ77CompressorInputStream in = new LZ77CompressorInputStream(new ByteArrayInputStream(compressed))) {
            final byte[] decompressed = IOUtils.toByteArray(in);
            assertEquals("abcabc", new String(decompressed, "UTF-8"));
        }
    }
}