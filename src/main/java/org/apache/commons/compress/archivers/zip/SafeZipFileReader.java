package org.apache.commons.compress.archivers.zip;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;

public class SafeZipFileReader {

    public static final class CorruptEntryInfo {
        private final String entryName;
        private final String reason;
        private final Exception exception;

        public CorruptEntryInfo(final String entryName, final String reason, final Exception exception) {
            this.entryName = entryName;
            this.reason = reason;
            this.exception = exception;
        }

        public String getEntryName() {
            return entryName;
        }

        public String getReason() {
            return reason;
        }

        public Exception getException() {
            return exception;
        }

        @Override
        public String toString() {
            return "CorruptEntry{name='" + entryName + "', reason='" + reason + "'}";
        }
    }

    public static final class EntryResult {
        private final String entryName;
        private final byte[] data;
        private final long expectedSize;
        private final long actualSize;
        private final long expectedCrc;
        private final long actualCrc;
        private final boolean sizeValid;
        private final boolean crcValid;

        public EntryResult(final String entryName, final byte[] data, final long expectedSize, final long actualSize,
                final long expectedCrc, final long actualCrc, final boolean sizeValid, final boolean crcValid) {
            this.entryName = entryName;
            this.data = data;
            this.expectedSize = expectedSize;
            this.actualSize = actualSize;
            this.expectedCrc = expectedCrc;
            this.actualCrc = actualCrc;
            this.sizeValid = sizeValid;
            this.crcValid = crcValid;
        }

        public String getEntryName() {
            return entryName;
        }

        public byte[] getData() {
            return data;
        }

        public long getExpectedSize() {
            return expectedSize;
        }

        public long getActualSize() {
            return actualSize;
        }

        public long getExpectedCrc() {
            return expectedCrc;
        }

        public long getActualCrc() {
            return actualCrc;
        }

        public boolean isSizeValid() {
            return sizeValid;
        }

        public boolean isCrcValid() {
            return crcValid;
        }

        @Override
        public String toString() {
            return "EntryResult{name='" + entryName + "', sizeValid=" + sizeValid + ", crcValid=" + crcValid +
                    ", expectedSize=" + expectedSize + ", actualSize=" + actualSize + "}";
        }
    }

    private static final int BUFFER_SIZE = 8192;

    private boolean skipCorruptEntries = true;
    private boolean validateCrc = true;
    private boolean validateSize = true;
    private int firstCorruptEntryIndex = -1;
    private String firstCorruptEntryName;

    public SafeZipFileReader setSkipCorruptEntries(final boolean skipCorruptEntries) {
        this.skipCorruptEntries = skipCorruptEntries;
        return this;
    }

    public SafeZipFileReader setValidateCrc(final boolean validateCrc) {
        this.validateCrc = validateCrc;
        return this;
    }

    public SafeZipFileReader setValidateSize(final boolean validateSize) {
        this.validateSize = validateSize;
        return this;
    }

    public int getFirstCorruptEntryIndex() {
        return firstCorruptEntryIndex;
    }

    public String getFirstCorruptEntryName() {
        return firstCorruptEntryName;
    }

    public Map<String, EntryResult> readZipFile(final File zipFile) throws IOException {
        System.out.println("[SafeZipFileReader] Opening ZIP file: " + zipFile.getAbsolutePath() +
                " (size=" + zipFile.length() + " bytes)");
        try (ZipFile zf = new ZipFile(zipFile)) {
            return readFromZipFile(zf);
        }
    }

    public Map<String, EntryResult> readZipFile(final Path zipPath) throws IOException {
        System.out.println("[SafeZipFileReader] Opening ZIP path: " + zipPath.toAbsolutePath() +
                " (size=" + Files.size(zipPath) + " bytes)");
        try (ZipFile zf = new ZipFile(zipPath)) {
            return readFromZipFile(zf);
        }
    }

    public Map<String, EntryResult> readZipStream(final InputStream inputStream) throws IOException {
        System.out.println("[SafeZipFileReader] Opening ZIP from InputStream");
        final Map<String, EntryResult> results = new LinkedHashMap<>();
        final ArchiveStreamFactory factory = ArchiveStreamFactory.DEFAULT;
        try (ArchiveInputStream<ZipArchiveEntry> ais = factory.createArchiveInputStream("zip", inputStream)) {
            int entryIndex = 0;
            ZipArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                System.out.println("[SafeZipFileReader] Entry #" + entryIndex + ": name='" + entry.getName() +
                        "', method=" + getMethodDescription(entry.getMethod()) +
                        ", compressedSize=" + entry.getCompressedSize() +
                        ", size=" + entry.getSize() +
                        ", crc=0x" + Long.toHexString(entry.getCrc()));
                try {
                    final EntryResult result = readEntryWithValidation(ais, entry);
                    results.put(entry.getName(), result);
                    if (!result.isSizeValid()) {
                        System.out.println("[SafeZipFileReader] WARNING: Size mismatch for entry '" +
                                entry.getName() + "': expected=" + result.getExpectedSize() +
                                ", actual=" + result.getActualSize());
                    }
                    if (!result.isCrcValid()) {
                        System.out.println("[SafeZipFileReader] WARNING: CRC mismatch for entry '" +
                                entry.getName() + "': expected=0x" + Long.toHexString(result.getExpectedCrc()) +
                                ", actual=0x" + Long.toHexString(result.getActualCrc()));
                    }
                } catch (final IOException e) {
                    System.out.println("[SafeZipFileReader] ERROR: Failed to read entry '" + entry.getName() +
                            "': " + e.getMessage());
                    if (firstCorruptEntryIndex == -1) {
                        firstCorruptEntryIndex = entryIndex;
                        firstCorruptEntryName = entry.getName();
                        System.out.println("[SafeZipFileReader] >>> FIRST CORRUPT ENTRY DETECTED: #" +
                                entryIndex + " name='" + entry.getName() + "' <<<");
                    }
                    if (!skipCorruptEntries) {
                        throw e;
                    }
                    System.out.println("[SafeZipFileReader] Skipping corrupt entry: '" + entry.getName() + "'");
                }
                entryIndex++;
            }
        } catch (final ArchiveException e) {
            throw new IOException("Failed to create archive input stream", e);
        }
        return results;
    }

    private Map<String, EntryResult> readFromZipFile(final ZipFile zf) throws IOException {
        final Map<String, EntryResult> results = new LinkedHashMap<>();
        final Enumeration<ZipArchiveEntry> entries = zf.getEntries();
        int entryIndex = 0;
        while (entries.hasMoreElements()) {
            final ZipArchiveEntry entry = entries.nextElement();
            System.out.println("[SafeZipFileReader] Entry #" + entryIndex + ": name='" + entry.getName() +
                    "', method=" + getMethodDescription(entry.getMethod()) +
                    ", compressedSize=" + entry.getCompressedSize() +
                    ", size=" + entry.getSize() +
                    ", crc=0x" + Long.toHexString(entry.getCrc()));
            try {
                final EntryResult result = readZipFileEntry(zf, entry);
                results.put(entry.getName(), result);
                if (!result.isSizeValid()) {
                    System.out.println("[SafeZipFileReader] WARNING: Size mismatch for entry '" +
                            entry.getName() + "': expected=" + result.getExpectedSize() +
                            ", actual=" + result.getActualSize());
                }
                if (!result.isCrcValid()) {
                    System.out.println("[SafeZipFileReader] WARNING: CRC mismatch for entry '" +
                            entry.getName() + "': expected=0x" + Long.toHexString(result.getExpectedCrc()) +
                            ", actual=0x" + Long.toHexString(result.getActualCrc()));
                }
            } catch (final IOException e) {
                System.out.println("[SafeZipFileReader] ERROR: Failed to read entry '" + entry.getName() +
                        "': " + e.getMessage());
                if (firstCorruptEntryIndex == -1) {
                    firstCorruptEntryIndex = entryIndex;
                    firstCorruptEntryName = entry.getName();
                    System.out.println("[SafeZipFileReader] >>> FIRST CORRUPT ENTRY DETECTED: #" +
                            entryIndex + " name='" + entry.getName() + "' <<<");
                }
                if (!skipCorruptEntries) {
                    throw e;
                }
                System.out.println("[SafeZipFileReader] Skipping corrupt entry: '" + entry.getName() + "'");
            }
            entryIndex++;
        }
        return results;
    }

    private EntryResult readZipFileEntry(final ZipFile zf, final ZipArchiveEntry entry) throws IOException {
        final long expectedSize = entry.getSize();
        final long expectedCrc = entry.getCrc();
        try (InputStream is = zf.getInputStream(entry)) {
            if (is == null) {
                throw new ZipException("Cannot get input stream for entry: " + entry.getName());
            }
            return readAndValidate(is, entry.getName(), expectedSize, expectedCrc);
        }
    }

    private EntryResult readEntryWithValidation(final ArchiveInputStream<ZipArchiveEntry> ais,
            final ZipArchiveEntry entry) throws IOException {
        final long expectedSize = entry.getSize();
        final long expectedCrc = entry.getCrc();
        return readAndValidate(ais, entry.getName(), expectedSize, expectedCrc);
    }

    private EntryResult readAndValidate(final InputStream is, final String entryName,
            final long expectedSize, final long expectedCrc) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final CRC32 crc32 = new CRC32();
        final byte[] buffer = new byte[BUFFER_SIZE];
        long totalRead = 0;
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
            crc32.update(buffer, 0, bytesRead);
            totalRead += bytesRead;
        }
        final byte[] data = baos.toByteArray();
        final long actualSize = totalRead;
        final long actualCrc = crc32.getValue();
        boolean sizeValid = true;
        if (validateSize && expectedSize >= 0) {
            sizeValid = actualSize == expectedSize;
            if (!sizeValid) {
                System.out.println("[SafeZipFileReader] Size validation FAILED for '" + entryName +
                        "': expected=" + expectedSize + ", actual=" + actualSize);
            }
        }
        boolean crcValid = true;
        if (validateCrc && expectedCrc >= 0) {
            crcValid = actualCrc == expectedCrc;
            if (!crcValid) {
                System.out.println("[SafeZipFileReader] CRC validation FAILED for '" + entryName +
                        "': expected=0x" + Long.toHexString(expectedCrc) +
                        ", actual=0x" + Long.toHexString(actualCrc));
            }
        }
        return new EntryResult(entryName, data, expectedSize, actualSize, expectedCrc, actualCrc, sizeValid, crcValid);
    }

    private static String getMethodDescription(final int method) {
        final ZipMethod m = ZipMethod.getMethodByCode(method);
        if (m != null) {
            return m.name() + "(" + method + ")";
        }
        return "UNKNOWN(" + method + ")";
    }

    public static Map<String, EntryResult> safeRead(final File zipFile) throws IOException {
        return new SafeZipFileReader().readZipFile(zipFile);
    }

    public static Map<String, EntryResult> safeRead(final Path zipPath) throws IOException {
        return new SafeZipFileReader().readZipFile(zipPath);
    }

    public static Map<String, EntryResult> safeRead(final InputStream inputStream) throws IOException {
        return new SafeZipFileReader().readZipStream(inputStream);
    }

    public static Map<String, EntryResult> safeReadWithRecovery(final File zipFile) throws IOException {
        return new SafeZipFileReader()
                .setSkipCorruptEntries(true)
                .setValidateSize(true)
                .setValidateCrc(true)
                .readZipFile(zipFile);
    }

    public static Map<String, EntryResult> safeReadWithRecovery(final InputStream inputStream) throws IOException {
        return new SafeZipFileReader()
                .setSkipCorruptEntries(true)
                .setValidateSize(true)
                .setValidateCrc(true)
                .readZipStream(inputStream);
    }
}
