package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static de.axa.oraclecompare.SchemaModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prüft die sichere Dateiausgabe über symbolische Verzeichnislinks ohne Oracle-Instanz. */
class OracleSchemaComparatorOutputTest {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private final OracleSchemaComparator comparator = new OracleSchemaComparator();
    private final Metadata metadata = new EmptyMetadata();

    @TempDir
    Path directory;

    @Test
    void writesThroughAnExistingRelativeDirectoryLink() throws Exception {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path link = createRelativeDirectoryLink(realDirectory);

        comparator.writeSynchronizationScript(metadata, "REFERENCE", "TARGET", link.resolve("sync.sql"));

        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.readString(realDirectory.resolve("sync.sql"), WINDOWS_1252)
                .contains("ALTER SESSION SET CURRENT_SCHEMA = \"TARGET\";"));
    }

    @Test
    void createsMissingSubdirectoriesBelowADirectoryLink() throws Exception {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path link = createRelativeDirectoryLink(realDirectory);

        comparator.writeSynchronizationScript(metadata, "REFERENCE", "TARGET", link.resolve("new/nested/sync.sql"));

        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.isRegularFile(realDirectory.resolve("new/nested/sync.sql")));
    }

    @Test
    void refusesARegularFileAsParentWithoutOverwritingIt() throws Exception {
        Path parent = Files.writeString(directory.resolve("existing-file"), "unrelated contents");

        assertThrows(IOException.class, () -> comparator.writeSynchronizationScript(
                metadata, "REFERENCE", "TARGET", parent.resolve("sync.sql")));

        assertEquals("unrelated contents", Files.readString(parent));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(parent), files.toList());
        }
    }

    @Test
    void encodingFailureThroughALinkPreservesThePreviousOutputAndRemovesTemporaryFiles() throws Exception {
        Path realDirectory = Files.createDirectory(directory.resolve("real"));
        Path link = createRelativeDirectoryLink(realDirectory);
        Path previous = Files.writeString(realDirectory.resolve("sync.sql"), "previous complete script");

        assertThrows(CharacterCodingException.class, () -> comparator.writeSynchronizationScript(
                metadata, "REFERENCE", "TARGET_漢", link.resolve("sync.sql")));

        assertEquals("previous complete script", Files.readString(previous));
        assertTrue(Files.isSymbolicLink(link));
        try (var files = Files.list(realDirectory)) {
            assertEquals(List.of(previous), files.toList());
        }
    }

    /** Verzeichnislinks benötigen unter manchen Betriebssystemen zusätzliche Benutzerrechte. */
    private Path createRelativeDirectoryLink(Path target) throws IOException {
        try {
            return Files.createSymbolicLink(directory.resolve("link"), target.getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Symbolische Links sind in dieser Testumgebung nicht verfügbar: " + e);
            throw e;
        }
    }

    /** Leere Inventare isolieren die Dateiausgabe von Metadatenabfragen und Schemaänderungen. */
    private static final class EmptyMetadata implements Metadata {
        @Override
        public Snapshot snapshot(String schema) {
            return new Snapshot(Map.of(), List.of(), Map.of());
        }

        @Override
        public String sxml(String schema, String target, DbObject object) {
            throw new AssertionError("Leere Inventare benötigen kein SXML.");
        }

        @Override
        public String ddl(String schema, String target, DbObject object) {
            throw new AssertionError("Leere Inventare benötigen keine DDL.");
        }

        @Override
        public String foreignKeyDdl(String schema, String target, ForeignKey key) {
            throw new AssertionError("Leere Inventare enthalten keine Fremdschlüssel.");
        }

        @Override
        public String alterXml(Type type, String actual, String desired) {
            throw new AssertionError("Leere Inventare benötigen keinen Metadatenvergleich.");
        }

        @Override
        public String alterDdl(Type type, String alterXml) {
            throw new AssertionError("Leere Inventare benötigen keine DDL-Konvertierung.");
        }

        @Override
        public void checkExternalForeignKeys(String target, Set<String> changedTables) {
            assertTrue(changedTables.isEmpty());
        }
    }
}
