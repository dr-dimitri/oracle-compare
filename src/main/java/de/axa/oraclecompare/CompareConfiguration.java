package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Intern geladene, unveränderliche Einstellungen; JDBC-Zugangsdaten gehören nicht in diese Datei. */
record CompareConfiguration(String referenceSchema, String targetSchema, Path outputFile, Path reportFile,
                            List<String> excludedObjects) {
    private static final String FILE_NAME = "oracle-compare.properties";

    CompareConfiguration {
        SqlText.identifier(referenceSchema);
        SqlText.identifier(targetSchema);
        Objects.requireNonNull(outputFile, "outputFile");
        if (outputFile.toAbsolutePath().normalize().getFileName() == null) {
            throw new IllegalArgumentException("output.path muss eine SQL-Datei bezeichnen.");
        }
        Objects.requireNonNull(reportFile, "reportFile");
        if (reportFile.toAbsolutePath().normalize().getFileName() == null) {
            throw new IllegalArgumentException("report.path muss eine Markdown-Datei bezeichnen.");
        }
        if (outputFile.toAbsolutePath().normalize().equals(reportFile.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("output.path und report.path müssen unterschiedliche Dateien bezeichnen.");
        }
        new ExclusionFilter(excludedObjects);
        excludedObjects = List.copyOf(excludedObjects);
    }

    /** Ohne expliziten Berichtspfad liegt der Report neben dem SQL und erhält zusätzlich .md. */
    CompareConfiguration(String referenceSchema, String targetSchema, Path outputFile, List<String> excludedObjects) {
        this(referenceSchema, targetSchema, outputFile, defaultReportFile(outputFile), excludedObjects);
    }

    /** Einheitlicher Standard für bestehende Konfigurationen und isolierte Integrationstests. */
    static Path defaultReportFile(Path outputFile) {
        Path filename = outputFile.toAbsolutePath().normalize().getFileName();
        if (filename == null) throw new IllegalArgumentException("output.path muss eine SQL-Datei bezeichnen.");
        return outputFile.resolveSibling(filename + ".md");
    }

    /** Liest die UTF-8-Properties-Datei aus dem aktuellen Arbeitsverzeichnis des Java-Prozesses. */
    static CompareConfiguration load() throws IOException {
        return load(Path.of(FILE_NAME));
    }

    /** Separater interner Ladeeinstieg für isolierte Dateitests. */
    static CompareConfiguration load(Path file) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(input);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Properties-Datei: " + file, e);
        }
        return from(properties);
    }

    /** Nummerierte Ausschlüsse bewahren auch Kommas und Leerzeichen innerhalb von Objektnamen. */
    static CompareConfiguration from(Properties properties) throws IOException {
        List<String> exclusions = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (!List.of("reference.schema", "target.schema", "output.path", "report.path").contains(key)
                    && !key.matches("exclude\\.[1-9][0-9]*")) {
                throw new IOException("Unbekannte Konfigurationseigenschaft: " + key);
            }
        }
        properties.stringPropertyNames().stream().filter(key -> key.startsWith("exclude."))
                .sorted(Comparator.comparing(key -> new java.math.BigInteger(key.substring(8))))
                .forEach(key -> exclusions.add(properties.getProperty(key)));
        try {
            Path output = Path.of(required(properties, "output.path"));
            Path report = properties.containsKey("report.path")
                    ? Path.of(required(properties, "report.path")) : defaultReportFile(output);
            return new CompareConfiguration(required(properties, "reference.schema"),
                    required(properties, "target.schema"), output, report, exclusions);
        } catch (InvalidPathException e) {
            throw new IOException("Ungültiger Ausgabepfad in " + FILE_NAME + ": " + e.getReason(), e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Konfiguration in " + FILE_NAME + ": " + e.getMessage(), e);
        }
    }

    private static String required(Properties properties, String name) throws IOException {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) throw new IOException("Pflichteigenschaft fehlt: " + name);
        return value;
    }
}
