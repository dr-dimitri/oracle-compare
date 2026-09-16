package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prüft das tatsächliche Dateisystem: Windowsformat, sichere Ersetzung und Verzeichnislinks. */
class SqlScriptWriterTest {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    @TempDir Path directory;

    @Test
    void writesWindowsBytesAndNormalizesMixedLineEndings() throws Exception {
        Path file = SqlScriptWriter.write(directory.resolve("new/nested/sync.sql"), "-- Größe €\r\nA\nB\r");
        String expected = "-- Größe €\r\nA\r\nB\r\n";
        assertArrayEquals(expected.getBytes(WINDOWS_1252), Files.readAllBytes(file));
        assertTrue(file.isAbsolute());
    }

    @Test
    void replacesExistingFileOnlyWithTheCompleteScript() throws Exception {
        Path file = Files.writeString(directory.resolve("sync.sql"), "old complete script");
        SqlScriptWriter.write(file, "new complete script\n");
        assertEquals("new complete script\r\n", Files.readString(file));
        assertOnly(file, directory);
    }

    @Test
    void encodingFailurePreservesExistingFileAndRemovesTemporaryFile() throws Exception {
        Path file = Files.writeString(directory.resolve("sync.sql"), "old complete script");
        assertThrows(CharacterCodingException.class, () -> SqlScriptWriter.write(file, "unrepresentable 漢"));
        assertEquals("old complete script", Files.readString(file));
        assertOnly(file, directory);
    }

    @Test
    void followsRelativeDirectoryLinksAndCreatesMissingChildren() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path link = symlink(real);
        SqlScriptWriter.write(link.resolve("sync.sql"), "one\n");
        SqlScriptWriter.write(link.resolve("new/nested/sync.sql"), "two\n");
        assertEquals("one\r\n", Files.readString(real.resolve("sync.sql")));
        assertEquals("two\r\n", Files.readString(real.resolve("new/nested/sync.sql")));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void encodingFailureThroughLinkPreservesPreviousFile() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path link = symlink(real);
        Path file = Files.writeString(real.resolve("sync.sql"), "old complete script");
        assertThrows(CharacterCodingException.class, () -> SqlScriptWriter.write(link.resolve("sync.sql"), "漢"));
        assertEquals("old complete script", Files.readString(file));
        assertOnly(file, real);
    }

    @Test
    void doesNotOverwriteAFileUsedAsTheParentDirectory() throws Exception {
        Path file = Files.writeString(directory.resolve("parent"), "unrelated contents");
        assertThrows(IOException.class, () -> SqlScriptWriter.write(file.resolve("sync.sql"), "new"));
        assertEquals("unrelated contents", Files.readString(file));
        assertOnly(file, directory);
    }

    private Path symlink(Path real) throws IOException {
        try {
            return Files.createSymbolicLink(directory.resolve("link"), real.getFileName());
        } catch (UnsupportedOperationException | IOException failure) {
            assumeTrue(false, "Verzeichnislinks werden in dieser Umgebung nicht unterstützt: " + failure);
            throw failure;
        }
    }

    private static void assertOnly(Path file, Path parent) throws IOException {
        try (var files = Files.list(parent)) { assertEquals(List.of(file), files.toList()); }
    }
}
