package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
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

    @Test
    void writesBothFilesInTheirSpecifiedEncodingAndCreatesSeparateDirectories() throws Exception {
        Path sql = directory.resolve("sql/sync.sql");
        Path report = directory.resolve("reports/sync.md");
        assertEquals(sql, SqlScriptWriter.write(sql, "-- Größe €\n", report, "# Änderungen 漢\n"));
        assertArrayEquals("-- Größe €\r\n".getBytes(WINDOWS_1252), Files.readAllBytes(sql));
        assertArrayEquals("# Änderungen 漢\r\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(report));
        assertOnly(sql, sql.getParent());
        assertOnly(report, report.getParent());
    }

    @Test
    void sqlEncodingFailurePreservesBothPreviousOutputs() throws Exception {
        Path sql = Files.writeString(directory.resolve("sync.sql"), "old SQL");
        Path report = Files.writeString(directory.resolve("sync.md"), "old report");
        assertThrows(CharacterCodingException.class,
                () -> SqlScriptWriter.write(sql, "漢", report, "new report"));
        assertEquals("old SQL", Files.readString(sql));
        assertEquals("old report", Files.readString(report));
        assertFiles(directory, sql, report);
    }

    @Test
    void reportEncodingFailurePreservesBothPreviousOutputsAndRemovesBothTemporaries() throws Exception {
        Path sql = Files.writeString(directory.resolve("sync.sql"), "old SQL");
        Path report = Files.writeString(directory.resolve("sync.md"), "old report");
        assertThrows(CharacterCodingException.class,
                () -> SqlScriptWriter.write(sql, "new SQL", report, "unpaired surrogate \uD800"));
        assertEquals("old SQL", Files.readString(sql));
        assertEquals("old report", Files.readString(report));
        assertFiles(directory, sql, report);
    }

    @Test
    void rejectsReportDirectoryBeforeCreatingSqlDirectories() throws Exception {
        Path report = Files.createDirectory(directory.resolve("report.md"));
        Path child = Files.writeString(report.resolve("keep.txt"), "keep");
        Path sql = directory.resolve("not-created/sync.sql");
        assertThrows(IOException.class, () -> SqlScriptWriter.write(sql, "new SQL", report, "new report"));
        assertFalse(Files.exists(sql.getParent()));
        assertEquals("keep", Files.readString(child));
        assertOnly(report, directory);
    }

    @Test
    void rejectsNormalizedAliasesBeforeWritingAnything() throws Exception {
        Path sql = directory.resolve("sync.sql");
        Path alias = directory.resolve("missing/../sync.sql");
        assertThrows(IOException.class, () -> SqlScriptWriter.write(sql, "SQL", alias, "report"));
        assertFiles(directory);
    }

    @Test
    void rejectsMissingOutputNamesWhichOnlyDifferByCaseBeforeWritingAnything() throws Exception {
        Path sql = directory.resolve("new/Sync.sql");
        Path report = directory.resolve("new/sync.sql");
        assertThrows(IOException.class, () -> SqlScriptWriter.write(sql, "SQL", report, "report"));
        assertFiles(directory);
    }

    @Test
    void rejectsAliasesThroughDirectoryLinksEvenWithMissingParents() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path link = symlink(real);
        Path sql = real.resolve("not-created/sync.sql");
        Path report = link.resolve("not-created/sync.sql");
        assertThrows(IOException.class, () -> SqlScriptWriter.validateOutputs(sql, report));
        assertFalse(Files.exists(sql.getParent()));
        assertFiles(real);
    }

    @Test
    void rejectsExistingHardLinkAliasesAndPreservesContents() throws Exception {
        Path sql = Files.writeString(directory.resolve("sync.sql"), "old");
        Path report;
        try {
            report = Files.createLink(directory.resolve("sync.md"), sql);
        } catch (UnsupportedOperationException | IOException failure) {
            assumeTrue(false, "Hardlinks werden in dieser Umgebung nicht unterstützt: " + failure);
            return;
        }
        assertThrows(IOException.class, () -> SqlScriptWriter.write(sql, "SQL", report, "report"));
        assertEquals("old", Files.readString(sql));
        assertEquals("old", Files.readString(report));
        assertTrue(Files.isSameFile(sql, report));
        assertFiles(directory, sql, report);
    }

    @Test
    void rejectsOutputFilesWhichAreAlsoAParentOfTheOtherOutput() throws Exception {
        Path sql = directory.resolve("not-created");
        Path report = sql.resolve("report.md");
        assertThrows(IOException.class, () -> SqlScriptWriter.validateOutputs(sql, report));
        assertThrows(IOException.class, () -> SqlScriptWriter.validateOutputs(report, sql));
        assertFiles(directory);
    }

    @Test
    void rejectsFinalFileSymlinksWithoutReplacingTheirTargets() throws Exception {
        Path sql = Files.writeString(directory.resolve("sync.sql"), "old SQL");
        Path originalReport = Files.writeString(directory.resolve("original.md"), "old report");
        Path report = symlink(originalReport);
        assertThrows(IOException.class, () -> SqlScriptWriter.write(sql, "SQL", report, "report"));
        assertEquals("old SQL", Files.readString(sql));
        assertEquals("old report", Files.readString(originalReport));
        assertTrue(Files.isSymbolicLink(report));
        assertFiles(directory, sql, originalReport, report);
    }

    @Test
    void restoresExistingSqlWhenTheSecondPublicationFails() throws Exception {
        Path sql = Files.writeString(directory.resolve("sync.sql"), "old SQL");
        Path report = Files.writeString(directory.resolve("sync.md"), "old report");
        IOException failure = assertThrows(IOException.class,
                () -> SqlScriptWriter.write(sql, "new SQL", report, "new report", (source, target) -> {
                    if (target.equals(report)) throw new IOException("Simulierter Fehler der zweiten Publikation");
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }));
        assertTrue(failure.getMessage().contains("zweiten Publikation"));
        assertEquals("old SQL", Files.readString(sql));
        assertEquals("old report", Files.readString(report));
        assertFiles(directory, sql, report);
    }

    @Test
    void removesNewSqlWhenTheSecondPublicationFails() throws Exception {
        Path sql = directory.resolve("sync.sql");
        Path report = Files.writeString(directory.resolve("sync.md"), "old report");
        assertThrows(IOException.class,
                () -> SqlScriptWriter.write(sql, "new SQL", report, "new report", (source, target) -> {
                    if (target.equals(report)) throw new IOException("Simulierter Fehler");
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }));
        assertFalse(Files.exists(sql));
        assertEquals("old report", Files.readString(report));
        assertOnly(report, directory);
    }

    @Test
    void restoresBothOutputsIfAFailedMoveHasAlreadyRemovedItsTarget() throws Exception {
        Path sql = Files.writeString(directory.resolve("sync.sql"), "old SQL");
        Path report = Files.writeString(directory.resolve("sync.md"), "old report");
        assertThrows(IOException.class,
                () -> SqlScriptWriter.write(sql, "new SQL", report, "new report", (source, target) -> {
                    if (target.equals(report)) {
                        Files.delete(target);
                        throw new IOException("Simulierter Fehler nach Entfernen des alten Ziels");
                    }
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }));
        assertEquals("old SQL", Files.readString(sql));
        assertEquals("old report", Files.readString(report));
        assertFiles(directory, sql, report);
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

    private static void assertFiles(Path parent, Path... expected) throws IOException {
        try (var files = Files.list(parent)) { assertEquals(Set.of(expected), Set.copyOf(files.toList())); }
    }
}
