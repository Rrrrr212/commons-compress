package org.apache.commons.compress.archivers.zip;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.IOUtils;

/**
 * Utility class for ZIP extraction with charset detection.
 *
 * @since 1.27.0
 */
public class ZipExtractionUtils {

    /**
     * Extracts a ZIP stream to a target directory, automatically detecting the charset of entry names
     * to avoid corrupted names (e.g., mixed GBK and UTF-8).
     *
     * @param zipStream The ZIP input stream.
     * @param outputDir The target output directory.
     * @throws IOException If an I/O error occurs or a ZIP slip is detected.
     */
    public static void extractWithCharsetDetect(final InputStream zipStream, final File outputDir) throws IOException {
        extractWithCharsetDetect(zipStream, outputDir.toPath());
    }

    /**
     * Extracts a ZIP stream to a target directory, automatically detecting the charset of entry names
     * to avoid corrupted names (e.g., mixed GBK and UTF-8).
     *
     * @param zipStream The ZIP input stream.
     * @param outputDir The target output directory path.
     * @throws IOException If an I/O error occurs or a ZIP slip is detected.
     */
    public static void extractWithCharsetDetect(final InputStream zipStream, final Path outputDir) throws IOException {
        final Path absoluteOutputDir = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(absoluteOutputDir);

        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(zipStream, StandardCharsets.UTF_8.name(), true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextZipEntry()) != null) {
                if (!zis.canReadEntryData(entry)) {
                    continue;
                }

                final byte[] rawName = entry.getRawName();
                String decodedName = entry.getName();

                if (rawName != null) {
                    final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
                    try {
                        decoder.decode(ByteBuffer.wrap(rawName));
                        decodedName = new String(rawName, StandardCharsets.UTF_8);
                    } catch (final CharacterCodingException e) {
                        decodedName = new String(rawName, Charset.forName("GBK"));
                    }
                }

                final Path outputFile = absoluteOutputDir.resolve(decodedName).normalize();
                if (!outputFile.startsWith(absoluteOutputDir)) {
                    throw new IOException("Zip slip '" + decodedName + "' -> '" + outputFile + "'");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outputFile);
                } else {
                    final Path parent = outputFile.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream os = Files.newOutputStream(outputFile)) {
                        IOUtils.copy(zis, os);
                    }
                }
            }
        }
    }
}
