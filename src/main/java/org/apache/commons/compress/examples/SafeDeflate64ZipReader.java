package org.apache.commons.compress.examples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.IOUtils;

public final class SafeDeflate64ZipReader {

    private SafeDeflate64ZipReader() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java SafeDeflate64ZipReader <zip-file> [output-dir]");
            System.exit(1);
        }
        Path zipPath = Paths.get(args[0]);
        Path outputDir = args.length >= 2 ? Paths.get(args[1]) : Paths.get("output");
        new SafeDeflate64ZipReader().processZip(zipPath, outputDir);
    }

    public void processZip(Path zipPath, Path outputDir) {
        System.out.println("[INFO] Opening ZIP: " + zipPath.toAbsolutePath());

        try {
            try (ZipFile zipFile = ZipFile.builder().setPath(zipPath).get()) {
                processWithZipFile(zipFile, zipPath, outputDir);
            }
        } catch (IOException e) {
            System.err.println("[WARN] ZipFile failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("[INFO] Falling back to ZipArchiveInputStream via ArchiveStreamFactory...");
            processWithArchiveStreamFactory(zipPath, outputDir);
        }
    }

    private void processWithZipFile(ZipFile zipFile, Path zipPath, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            System.err.println("[FATAL] Cannot create output directory '" + outputDir + "': " + e.getMessage());
            return;
        }

        Enumeration<? extends ZipArchiveEntry> entries = zipFile.getEntries();
        int totalEntries = 0;
        int successCount = 0;
        int skipCount = 0;
        ZipArchiveEntry firstCorrupt = null;

        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            totalEntries++;

            System.out.println();
            System.out.println("--- Entry #" + totalEntries + " ---");
            System.out.println("  Name:             " + entry.getName());
            System.out.println("  Compressed size:  " + entry.getCompressedSize());
            System.out.println("  Uncompressed size:" + entry.getSize());
            System.out.println("  CRC:              " + Long.toHexString(entry.getCrc()).toUpperCase());
            System.out.println("  Method:           " + entry.getMethod()
                    + " (" + getMethodName(entry.getMethod()) + ")");
            System.out.println("  Data descriptor:  " + entry.getGeneralPurposeBit().usesDataDescriptor());

            if (!zipFile.canReadEntryData(entry)) {
                System.err.println("[SKIP] Cannot read entry data for: " + entry.getName());
                if (firstCorrupt == null) {
                    firstCorrupt = entry;
                }
                skipCount++;
                continue;
            }

            try {
                byte[] entryData = readEntryWithValidation(zipFile, entry);
                if (entryData == null) {
                    if (firstCorrupt == null) {
                        firstCorrupt = entry;
                    }
                    skipCount++;
                    continue;
                }

                Path outputFile = outputDir.resolve(sanitizeName(entry.getName()));
                if (entry.isDirectory()) {
                    Files.createDirectories(outputFile);
                } else {
                    Files.createDirectories(outputFile.getParent());
                    Files.write(outputFile, entryData, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                System.out.println("[OK]   Extracted: " + entry.getName() + " -> " + outputFile);
                successCount++;
            } catch (UnsupportedZipFeatureException e) {
                System.err.println("[SKIP] Unsupported feature in entry '" + entry.getName() + "': " + e.getMessage());
                if (firstCorrupt == null) {
                    firstCorrupt = entry;
                }
                skipCount++;
            } catch (IOException e) {
                System.err.println("[SKIP] I/O error reading entry '" + entry.getName() + "': "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (firstCorrupt == null) {
                    firstCorrupt = entry;
                }
                skipCount++;
            } catch (RuntimeException e) {
                System.err.println("[SKIP] Runtime error reading entry '" + entry.getName() + "': "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (firstCorrupt == null) {
                    firstCorrupt = entry;
                }
                skipCount++;
            }
        }

        printSummary(totalEntries, successCount, skipCount, firstCorrupt);
    }

    private void processWithArchiveStreamFactory(Path zipPath, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            System.err.println("[FATAL] Cannot create output directory: " + e.getMessage());
            return;
        }

        int totalEntries = 0;
        int successCount = 0;
        int skipCount = 0;
        ZipArchiveEntry firstCorrupt = null;

        try (InputStream fis = Files.newInputStream(zipPath);
             ArchiveInputStream<?> ais =
                     new ArchiveStreamFactory().createArchiveInputStream("zip", fis)) {

            ArchiveEntry ae;
            ZipArchiveInputStream zis = null;
            if (ais instanceof ZipArchiveInputStream) {
                zis = (ZipArchiveInputStream) ais;
            }

            while ((ae = ais.getNextEntry()) != null) {
                if (!(ae instanceof ZipArchiveEntry)) {
                    System.err.println("[WARN] Unexpected entry type: " + ae.getClass().getName());
                    continue;
                }
                ZipArchiveEntry entry = (ZipArchiveEntry) ae;
                totalEntries++;

                System.out.println();
                System.out.println("--- Entry #" + totalEntries + " ---");
                System.out.println("  Name:             " + entry.getName());
                System.out.println("  Compressed size:  " + entry.getCompressedSize());
                System.out.println("  Uncompressed size:" + entry.getSize());
                System.out.println("  CRC:              " + Long.toHexString(entry.getCrc()).toUpperCase());
                System.out.println("  Method:           " + entry.getMethod()
                        + " (" + getMethodName(entry.getMethod()) + ")");

                if (zis != null && !zis.canReadEntryData(entry)) {
                    System.err.println("[SKIP] Cannot read entry data for: " + entry.getName());
                    if (firstCorrupt == null) {
                        firstCorrupt = entry;
                    }
                    skipCount++;
                    continue;
                }

                try {
                    byte[] entryData = IOUtils.toByteArray(ais);
                    if (entryData == null || entryData.length == 0 && entry.getSize() > 0) {
                        System.err.println("[SKIP] Entry '" + entry.getName() + "' returned no data but expected "
                                + entry.getSize() + " bytes");
                        if (firstCorrupt == null) {
                            firstCorrupt = entry;
                        }
                        skipCount++;
                        continue;
                    }

                    long expectedSize = entry.getSize();
                    if (expectedSize != ZipArchiveEntry.SIZE_UNKNOWN && entryData.length != expectedSize) {
                        System.err.println("[WARN] Size mismatch for entry '" + entry.getName() + "': "
                                + "expected " + expectedSize + " bytes, got " + entryData.length + " bytes");
                    }

                    Path outputFile = outputDir.resolve(sanitizeName(entry.getName()));
                    if (entry.isDirectory()) {
                        Files.createDirectories(outputFile);
                    } else {
                        Files.createDirectories(outputFile.getParent());
                        Files.write(outputFile, entryData, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                    System.out.println("[OK]   Extracted: " + entry.getName() + " -> " + outputFile);
                    successCount++;
                } catch (IOException e) {
                    System.err.println("[SKIP] I/O error reading entry '" + entry.getName() + "': "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (firstCorrupt == null) {
                        firstCorrupt = entry;
                    }
                    skipCount++;
                } catch (RuntimeException e) {
                    System.err.println("[SKIP] Runtime error reading entry '" + entry.getName() + "': "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (firstCorrupt == null) {
                        firstCorrupt = entry;
                    }
                    skipCount++;
                }
            }
        } catch (ArchiveException e) {
            System.err.println("[FATAL] Archive error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[FATAL] I/O error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        printSummary(totalEntries, successCount, skipCount, firstCorrupt);
    }

    private byte[] readEntryWithValidation(ZipFile zipFile, ZipArchiveEntry entry) throws IOException {
        try (InputStream in = zipFile.getInputStream(entry)) {
            byte[] data = IOUtils.toByteArray(in);
            int actualSize = data.length;
            long expectedSize = entry.getSize();

            System.out.println("  Bytes read:       " + actualSize);

            if (expectedSize != ZipArchiveEntry.SIZE_UNKNOWN && actualSize != expectedSize) {
                System.err.println("[WARN] Size mismatch for entry '" + entry.getName() + "': "
                        + "expected " + expectedSize + " bytes, got " + actualSize + " bytes");
            }

            if (expectedSize != ZipArchiveEntry.SIZE_UNKNOWN
                    && expectedSize > 10L * 1024 * 1024 * 1024) {
                System.err.println("[WARN] Entry '" + entry.getName() + "' has suspiciously large size: "
                        + expectedSize + " bytes (>10 GB), skipping to avoid OOM");
                return null;
            }

            return data;
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to read entry '" + entry.getName() + "': "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private static String getMethodName(int method) {
        switch (method) {
        case 0:  return "STORED";
        case 1:  return "UNSHRINKING";
        case 6:  return "IMPLODING";
        case 8:  return "DEFLATED";
        case 9:  return "ENHANCED_DEFLATED (DEFLATE64)";
        case 10: return "PKWARE_IMPLODING";
        case 12: return "BZIP2";
        case 14: return "LZMA";
        case 20: return "ZSTD (deprecated)";
        case 93: return "ZSTD";
        case 95: return "XZ";
        case 99: return "AES_ENCRYPTED";
        default: return "UNKNOWN";
        }
    }

    private static String sanitizeName(String name) {
        return name.replace("..", "_").replace("/", java.io.File.separator).replace("\\", java.io.File.separator);
    }

    private static void printSummary(int total, int success, int skip, ZipArchiveEntry firstCorrupt) {
        System.out.println();
        System.out.println("========== SUMMARY ==========");
        System.out.println("Total entries:   " + total);
        System.out.println("Success:         " + success);
        System.out.println("Skipped/failed:  " + skip);
        if (firstCorrupt != null) {
            System.out.println("First corrupt entry: " + firstCorrupt.getName());
            System.out.println("    Method:              " + getMethodName(firstCorrupt.getMethod()));
            System.out.println("    Compressed size:     " + firstCorrupt.getCompressedSize());
            System.out.println("    Uncompressed size:   " + firstCorrupt.getSize());
            System.out.println("    CRC:                 " + Long.toHexString(firstCorrupt.getCrc()).toUpperCase());
            System.out.println("    Data descriptor:     " + firstCorrupt.getGeneralPurposeBit().usesDataDescriptor());
        }
        System.out.println("=============================");
    }
}