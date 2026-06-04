package org.apache.commons.compress.archivers.zip;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;

public final class SecureZipReader {

    public static final class ExtractionReport {

        private final List<String> extractedEntries;
        private final List<String> skippedEntries;
        private final String firstCorruptEntry;
        private final boolean usedStreamFallback;

        ExtractionReport(final List<String> extractedEntries, final List<String> skippedEntries, final String firstCorruptEntry,
                final boolean usedStreamFallback) {
            this.extractedEntries = Collections.unmodifiableList(new ArrayList<>(extractedEntries));
            this.skippedEntries = Collections.unmodifiableList(new ArrayList<>(skippedEntries));
            this.firstCorruptEntry = firstCorruptEntry;
            this.usedStreamFallback = usedStreamFallback;
        }

        public List<String> getExtractedEntries() {
            return extractedEntries;
        }

        public String getFirstCorruptEntry() {
            return firstCorruptEntry;
        }

        public List<String> getSkippedEntries() {
            return skippedEntries;
        }

        public boolean isUsedStreamFallback() {
            return usedStreamFallback;
        }
    }

    private static final int BUFFER_SIZE = 8192;
    private static final Logger LOG = Logger.getLogger(SecureZipReader.class.getName());

    private SecureZipReader() {
    }

    public static ExtractionReport extractSafely(final Path zipPath, final Path outputDir, final long maxEntrySize) throws IOException {
        validateLimit(maxEntrySize);
        Files.createDirectories(outputDir);
        try {
            return extractWithZipFile(zipPath, outputDir, maxEntrySize);
        } catch (final IOException ex) {
            System.out.printf("[zip-debug] zipfile-open-failed archive=%s reason=%s%n", zipPath, ex.getMessage());
            LOG.log(Level.WARNING, String.format("ZipFile failed for '%s', switching to ArchiveStreamFactory fallback", zipPath), ex);
            try {
                return extractWithArchiveStreamFactory(zipPath, outputDir, maxEntrySize);
            } catch (final ArchiveException streamEx) {
                final IOException wrapped = new IOException("Unable to open ZIP archive with ZipFile or ArchiveStreamFactory: " + zipPath, streamEx);
                wrapped.addSuppressed(ex);
                throw wrapped;
            }
        }
    }

    private static long copyEntry(final InputStream inputStream, final OutputStream outputStream, final long maxEntrySize, final ZipArchiveEntry entry)
            throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        long totalRead = 0;
        while (true) {
            final int read = inputStream.read(buffer);
            if (read == -1) {
                return totalRead;
            }
            totalRead += read;
            if (totalRead > maxEntrySize) {
                throw new IOException("Entry exceeds max allowed size: " + entry.getName() + " size=" + totalRead + " limit=" + maxEntrySize);
            }
            outputStream.write(buffer, 0, read);
        }
    }

    private static ExtractionReport extractWithArchiveStreamFactory(final Path zipPath, final Path outputDir, final long maxEntrySize)
            throws IOException, ArchiveException {
        final List<String> extractedEntries = new ArrayList<>();
        final List<String> skippedEntries = new ArrayList<>();
        String firstCorruptEntry = null;
        int index = 0;
        try (InputStream fileInputStream = Files.newInputStream(zipPath);
                BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
                ArchiveInputStream<?> archiveInputStream = ArchiveStreamFactory.DEFAULT.createArchiveInputStream(ArchiveStreamFactory.ZIP, bufferedInputStream)) {
            final ZipArchiveInputStream zipInputStream = (ZipArchiveInputStream) archiveInputStream;
            while (true) {
                final ZipArchiveEntry entry;
                try {
                    entry = zipInputStream.getNextZipEntry();
                } catch (final IOException ex) {
                    if (firstCorruptEntry == null) {
                        firstCorruptEntry = "<central-directory-or-header>";
                        System.out.printf("[zip-debug] first-corrupt-entry index=%d name=%s reason=%s%n", index + 1, firstCorruptEntry, ex.getMessage());
                    }
                    LOG.log(Level.WARNING, String.format("Stopping ZIP stream scan for '%s' after header failure", zipPath), ex);
                    break;
                }
                if (entry == null) {
                    break;
                }
                index++;
                printEntryDebug(index, entry, zipInputStream.canReadEntryData(entry));
                final String skipReason = validateEntry(entry, zipInputStream.canReadEntryData(entry), maxEntrySize);
                if (skipReason != null) {
                    logSkip(index, entry, skipReason);
                    skippedEntries.add(entry.getName() + " -> " + skipReason);
                    continue;
                }
                Path outputFile = null;
                try {
                    outputFile = entry.resolveIn(outputDir);
                    if (entry.isDirectory()) {
                        Files.createDirectories(outputFile);
                        extractedEntries.add(entry.getName());
                        continue;
                    }
                    final Path parent = outputFile.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream outputStream = Files.newOutputStream(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                        final long extractedSize = copyEntry(zipInputStream, outputStream, maxEntrySize, entry);
                        if (entry.getSize() >= 0 && extractedSize != entry.getSize()) {
                            throw new IOException("Entry size mismatch: expected " + entry.getSize() + " bytes but read " + extractedSize + " bytes");
                        }
                    }
                    extractedEntries.add(entry.getName());
                    System.out.printf("[zip-debug] extracted index=%d name=%s bytes=%d source=ArchiveStreamFactory%n", index, entry.getName(),
                            entry.getSize());
                } catch (final IOException ex) {
                    if (firstCorruptEntry == null) {
                        firstCorruptEntry = entry.getName();
                        System.out.printf("[zip-debug] first-corrupt-entry index=%d name=%s reason=%s%n", index, entry.getName(), ex.getMessage());
                    }
                    cleanupPartialOutput(outputFile);
                    skippedEntries.add(entry.getName() + " -> " + ex.getMessage());
                    LOG.log(Level.WARNING, String.format("Skipping ZIP entry '%s' after stream read failure", entry.getName()), ex);
                }
            }
        }
        return new ExtractionReport(extractedEntries, skippedEntries, firstCorruptEntry, true);
    }

    private static ExtractionReport extractWithZipFile(final Path zipPath, final Path outputDir, final long maxEntrySize) throws IOException {
        final List<String> extractedEntries = new ArrayList<>();
        final List<String> skippedEntries = new ArrayList<>();
        String firstCorruptEntry = null;
        try (ZipFile zipFile = ZipFile.builder().setPath(zipPath).get()) {
            final Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            int index = 0;
            while (entries.hasMoreElements()) {
                final ZipArchiveEntry entry = entries.nextElement();
                index++;
                printEntryDebug(index, entry, zipFile.canReadEntryData(entry));
                final String skipReason = validateEntry(entry, zipFile.canReadEntryData(entry), maxEntrySize);
                if (skipReason != null) {
                    logSkip(index, entry, skipReason);
                    skippedEntries.add(entry.getName() + " -> " + skipReason);
                    continue;
                }
                Path outputFile = null;
                try {
                    outputFile = entry.resolveIn(outputDir);
                    if (entry.isDirectory()) {
                        Files.createDirectories(outputFile);
                        extractedEntries.add(entry.getName());
                        continue;
                    }
                    final Path parent = outputFile.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (InputStream inputStream = zipFile.getInputStream(entry);
                            OutputStream outputStream = Files.newOutputStream(outputFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE)) {
                        final long extractedSize = copyEntry(inputStream, outputStream, maxEntrySize, entry);
                        if (entry.getSize() >= 0 && extractedSize != entry.getSize()) {
                            throw new IOException("Entry size mismatch: expected " + entry.getSize() + " bytes but read " + extractedSize + " bytes");
                        }
                    }
                    extractedEntries.add(entry.getName());
                    System.out.printf("[zip-debug] extracted index=%d name=%s bytes=%d source=ZipFile%n", index, entry.getName(), entry.getSize());
                } catch (final IOException ex) {
                    if (firstCorruptEntry == null) {
                        firstCorruptEntry = entry.getName();
                        System.out.printf("[zip-debug] first-corrupt-entry index=%d name=%s reason=%s%n", index, entry.getName(), ex.getMessage());
                    }
                    cleanupPartialOutput(outputFile);
                    skippedEntries.add(entry.getName() + " -> " + ex.getMessage());
                    LOG.log(Level.WARNING, String.format("Skipping ZIP entry '%s' after ZipFile read failure", entry.getName()), ex);
                }
            }
        }
        return new ExtractionReport(extractedEntries, skippedEntries, firstCorruptEntry, false);
    }

    private static void cleanupPartialOutput(final Path outputFile) {
        if (outputFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(outputFile);
        } catch (final IOException ex) {
            LOG.log(Level.FINE, String.format("Could not delete partial output '%s'", outputFile), ex);
        }
    }

    private static void logSkip(final int index, final ZipArchiveEntry entry, final String reason) {
        System.out.printf("[zip-debug] skipped index=%d name=%s reason=%s%n", index, entry.getName(), reason);
        LOG.warning(String.format("Skipping ZIP entry '%s': %s", entry.getName(), reason));
    }

    private static void printEntryDebug(final int index, final ZipArchiveEntry entry, final boolean canReadEntryData) {
        System.out.printf("[zip-debug] visiting index=%d name=%s method=%d size=%d compressedSize=%d canRead=%s%n", index, entry.getName(),
                entry.getMethod(), entry.getSize(), entry.getCompressedSize(), Boolean.toString(canReadEntryData));
    }

    private static void validateLimit(final long maxEntrySize) {
        if (maxEntrySize <= 0) {
            throw new IllegalArgumentException("maxEntrySize must be greater than zero");
        }
    }

    private static String validateEntry(final ZipArchiveEntry entry, final boolean canReadEntryData, final long maxEntrySize) {
        if (!canReadEntryData) {
            return "Unsupported ZIP feature or encrypted entry";
        }
        final long entrySize = entry.getSize();
        if (entrySize < -1) {
            return "Negative entry size: " + entrySize;
        }
        if (entrySize > maxEntrySize) {
            return "Entry declared size exceeds limit: " + entrySize + " > " + maxEntrySize;
        }
        final long compressedSize = entry.getCompressedSize();
        if (compressedSize < -1) {
            return "Negative compressed size: " + compressedSize;
        }
        return null;
    }
}
