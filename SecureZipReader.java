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

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;

/**
 * Secure ZIP file reader that handles errors gracefully and recovers from corrupted entries.
 * Features:
 * - Reads ZIP files using both ZipFile (random access) and ArchiveStreamFactory (streaming)
 * - Skips corrupted entries and continues processing
 * - Validates entry sizes
 * - Provides detailed debugging information
 */
public class SecureZipReader {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java SecureZipReader <zip-file-path> [output-directory]");
            System.out.println("Example: java SecureZipReader test.zip ./output");
            return;
        }

        String zipPath = args[0];
        String outputDir = args.length > 1 ? args[1] : "./output";
        File zipFile = new File(zipPath);

        if (!zipFile.exists()) {
            System.err.println("Error: ZIP file does not exist: " + zipPath);
            return;
        }

        try {
            System.out.println("=== Reading ZIP file with ZipFile (random access) ===");
            readWithZipFile(zipPath, outputDir);
            
            System.out.println("\n=== Reading ZIP file with ArchiveStreamFactory (streaming) ===");
            readWithArchiveStreamFactory(zipPath, outputDir);
        } catch (Exception e) {
            System.err.println("Error reading ZIP file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Reads a ZIP file using ZipFile for random access.
     */
    public static void readWithZipFile(String zipPath, String outputDir) {
        File zipFile = new File(zipPath);
        
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            int totalEntries = 0;
            int successEntries = 0;
            int skippedEntries = 0;
            String firstCorruptedEntry = null;

            System.out.println("ZipFile: Starting to read entries...");
            
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                totalEntries++;
                String entryName = entry.getName();
                
                System.out.println("\n=== Processing entry: " + entryName + " ===");
                
                try {
                    // Debugging information
                    printEntryDebugInfo(entry);
                    
                    // Validate entry size
                    if (!validateEntrySize(entry)) {
                        if (firstCorruptedEntry == null) {
                            firstCorruptedEntry = entryName;
                        }
                        skippedEntries++;
                        System.out.println("Warning: Skipping invalid entry due to size check: " + entryName);
                        continue;
                    }
                    
                    // Extract the entry
                    if (!entry.isDirectory()) {
                        extractEntry(zip, entry, outputDir);
                    }
                    
                    successEntries++;
                    System.out.println("Successfully processed: " + entryName);
                    
                } catch (IOException | RuntimeException e) {
                    if (firstCorruptedEntry == null) {
                        firstCorruptedEntry = entryName;
                    }
                    skippedEntries++;
                    System.err.println("Error processing entry " + entryName + ": " + e.getMessage());
                    System.err.println("Skipping and continuing...");
                    // Continue to next entry
                }
            }
            
            // Summary
            System.out.println("\n=== ZipFile Summary ===");
            System.out.println("Total entries: " + totalEntries);
            System.out.println("Successfully processed: " + successEntries);
            System.out.println("Skipped/corrupted entries: " + skippedEntries);
            if (firstCorruptedEntry != null) {
                System.out.println("First corrupted entry: " + firstCorruptedEntry);
            }
            
        } catch (IOException e) {
            System.err.println("Failed to open ZIP file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Reads a ZIP file using ArchiveStreamFactory for streaming access.
     */
    public static void readWithArchiveStreamFactory(String zipPath, String outputDir) {
        try (InputStream is = new BufferedInputStream(new FileInputStream(zipPath));
             ArchiveInputStream ais = new ArchiveStreamFactory().createArchiveInputStream(is)) {
            
            ArchiveEntry entry;
            int totalEntries = 0;
            int successEntries = 0;
            int skippedEntries = 0;
            String firstCorruptedEntry = null;

            System.out.println("ArchiveStreamFactory: Starting to read entries...");
            
            while ((entry = ais.getNextEntry()) != null) {
                if (entry instanceof ZipArchiveEntry) {
                    ZipArchiveEntry zipEntry = (ZipArchiveEntry) entry;
                    totalEntries++;
                    String entryName = zipEntry.getName();
                    
                    System.out.println("\n=== Processing entry: " + entryName + " ===");
                    
                    try {
                        // Debugging information
                        printEntryDebugInfo(zipEntry);
                        
                        // Validate entry size
                        if (!validateEntrySize(zipEntry)) {
                            if (firstCorruptedEntry == null) {
                                firstCorruptedEntry = entryName;
                            }
                            skippedEntries++;
                            System.out.println("Warning: Skipping invalid entry due to size check: " + entryName);
                            continue;
                        }
                        
                        // Extract the entry
                        if (!zipEntry.isDirectory()) {
                            extractEntry(ais, zipEntry, outputDir);
                        }
                        
                        successEntries++;
                        System.out.println("Successfully processed: " + entryName);
                        
                    } catch (IOException | RuntimeException e) {
                        if (firstCorruptedEntry == null) {
                            firstCorruptedEntry = entryName;
                        }
                        skippedEntries++;
                        System.err.println("Error processing entry " + entryName + ": " + e.getMessage());
                        System.err.println("Skipping and continuing...");
                        // Continue to next entry
                    }
                }
            }
            
            // Summary
            System.out.println("\n=== ArchiveStreamFactory Summary ===");
            System.out.println("Total entries: " + totalEntries);
            System.out.println("Successfully processed: " + successEntries);
            System.out.println("Skipped/corrupted entries: " + skippedEntries);
            if (firstCorruptedEntry != null) {
                System.out.println("First corrupted entry: " + firstCorruptedEntry);
            }
            
        } catch (IOException | ArchiveException e) {
            System.err.println("Failed to open ZIP file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Prints detailed debug information about an entry.
     */
    private static void printEntryDebugInfo(ZipArchiveEntry entry) {
        System.out.println("  Debug info:");
        System.out.println("    Name: " + entry.getName());
        System.out.println("    Is directory: " + entry.isDirectory());
        System.out.println("    Compressed size: " + entry.getCompressedSize());
        System.out.println("    Uncompressed size: " + entry.getSize());
        System.out.println("    CRC: " + Long.toHexString(entry.getCrc()));
        System.out.println("    Compression method: " + entry.getMethod());
        System.out.println("    Last modified: " + entry.getLastModifiedDate());
        System.out.println("    Size unknown: " + (entry.getSize() == ArchiveEntry.SIZE_UNKNOWN));
        System.out.println("    Compressed size unknown: " + (entry.getCompressedSize() == ArchiveEntry.SIZE_UNKNOWN));
    }

    /**
     * Validates an entry's size for consistency.
     */
    private static boolean validateEntrySize(ZipArchiveEntry entry) {
        long uncompressedSize = entry.getSize();
        long compressedSize = entry.getCompressedSize();
        
        // Check for negative sizes
        if (uncompressedSize < -1) {
            System.err.println("    Invalid: Uncompressed size is negative: " + uncompressedSize);
            return false;
        }
        if (compressedSize < -1) {
            System.err.println("    Invalid: Compressed size is negative: " + compressedSize);
            return false;
        }
        
        // For non-stored entries, compressed size should be smaller
        if (entry.getMethod() != ZipArchiveEntry.STORED 
                && compressedSize != ArchiveEntry.SIZE_UNKNOWN
                && uncompressedSize != ArchiveEntry.SIZE_UNKNOWN
                && compressedSize > uncompressedSize * 2) {
            System.err.println("    Warning: Compressed size seems unexpectedly large: " + 
                    compressedSize + " > " + uncompressedSize + " * 2");
            // Don't fail, just warn
        }
        
        return true;
    }

    /**
     * Extracts a single entry from a ZipFile.
     */
    private static void extractEntry(ZipFile zip, ZipArchiveEntry entry, String outputDir) throws IOException {
        Path outputPath = Paths.get(outputDir, entry.getName());
        
        // Ensure parent directory exists
        Files.createDirectories(outputPath.getParent());
        
        // Only extract files, not directories
        if (!entry.isDirectory()) {
            try (InputStream is = zip.getInputStream(entry)) {
                Files.copy(is, outputPath);
                System.out.println("    Extracted to: " + outputPath);
            }
        }
    }

    /**
     * Extracts a single entry from an ArchiveInputStream.
     */
    private static void extractEntry(ArchiveInputStream ais, ZipArchiveEntry entry, String outputDir) throws IOException {
        Path outputPath = Paths.get(outputDir, entry.getName());
        
        // Ensure parent directory exists
        Files.createDirectories(outputPath.getParent());
        
        // Only extract files, not directories
        if (!entry.isDirectory()) {
            Files.copy(ais, outputPath);
            System.out.println("    Extracted to: " + outputPath);
        }
    }
}
