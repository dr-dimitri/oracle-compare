package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Schreibt SQL in CP1252 und Markdown in UTF-8, jeweils mit Windows-Zeilenenden. */
final class SqlScriptWriter {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private SqlScriptWriter() { }

    /** Schreibt vollständig in eine temporäre Datei und ersetzt erst danach die SQL-Datei. */
    static Path write(Path outputFile, String script) throws IOException {
        Path output = normalizedFile(outputFile);
        validateFile(output);
        return write(List.of(new Output(output, script, WINDOWS_1252)), SqlScriptWriter::replace);
    }

    /**
     * Bereitet beide Dateien vollständig vor, bevor bestehende Ergebnisse ersetzt werden.
     * Normale Fehler beim Ersetzen lösen eine Wiederherstellung bereits ersetzter Dateien aus.
     * Zwei Dateinamen bilden jedoch keine Dateisystemtransaktion: Ein Prozessabbruch oder eine
     * gescheiterte Wiederherstellung kann einen unterschiedlichen Stand hinterlassen. Eine dann
     * benötigte Sicherungsdatei bleibt erhalten und wird in der Fehlermeldung genannt.
     */
    static Path write(Path outputFile, String script, Path reportFile, String markdown) throws IOException {
        return write(outputFile, script, reportFile, markdown, SqlScriptWriter::replace);
    }

    /** Austauschbarer Verschiebevorgang ermöglicht reproduzierbare Tests von Dateisystemfehlern. */
    static Path write(Path outputFile, String script, Path reportFile, String markdown,
                      FileMove move) throws IOException {
        validateOutputs(outputFile, reportFile);
        return write(List.of(new Output(normalizedFile(outputFile), script, WINDOWS_1252),
                new Output(normalizedFile(reportFile), markdown, StandardCharsets.UTF_8)), move);
    }

    /**
     * Prüft beide Ausgaben ohne Schreibzugriff. Verzeichnislinks werden aufgelöst, Datei-Links
     * als Endziele abgelehnt. Ein Ziel darf weder das andere Ziel noch dessen Elternpfad sein.
     * Dateinamen im selben realen Verzeichnis müssen sich auch unabhängig von Groß-/Kleinschreibung
     * unterscheiden; dies schützt neue Ausgaben auf Dateisystemen ohne diese Unterscheidung.
     */
    static void validateOutputs(Path outputFile, Path reportFile) throws IOException {
        Path output = normalizedFile(outputFile);
        Path report = normalizedFile(reportFile);
        Path realOutput = validateFile(output);
        Path realReport = validateFile(report);
        if (realOutput.startsWith(realReport) || realReport.startsWith(realOutput)
                || (realOutput.getParent().equals(realReport.getParent())
                    && realOutput.getFileName().toString().equalsIgnoreCase(realReport.getFileName().toString()))
                || (Files.exists(output) && Files.exists(report) && Files.isSameFile(output, report))) {
            throw new IOException("SQL-Datei und Markdown-Report benötigen getrennte Dateipfade: "
                    + output + " / " + report);
        }
    }

    private static Path normalizedFile(Path file) throws IOException {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (normalized.getFileName() == null) {
            throw new IOException("Der Ausgabepfad muss eine Datei bezeichnen: " + file);
        }
        return normalized;
    }

    /** Liefert auch für noch fehlende Unterverzeichnisse den physischen Zielpfad. */
    private static Path validateFile(Path output) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(output, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Das Ausgabeziel ist keine reguläre Datei: " + output);
            }
        } catch (NoSuchFileException missing) {
            // Eine neue Ausgabedatei ist erlaubt; ihr vorhandener Vorfahr muss ein Verzeichnis sein.
        }
        Path parent = output.getParent();
        List<Path> missingParts = new ArrayList<>();
        while (true) {
            try {
                Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                break;
            } catch (NoSuchFileException missing) {
                missingParts.add(parent.getFileName());
                parent = parent.getParent();
            }
        }
        if (!Files.isDirectory(parent)) {
            throw new IOException("Das Ausgabeverzeichnis ist kein Verzeichnis: " + parent);
        }
        Path realParent = parent.toRealPath();
        for (int i = missingParts.size() - 1; i >= 0; i--) realParent = realParent.resolve(missingParts.get(i));
        return realParent.resolve(output.getFileName());
    }

    private static Path write(List<Output> outputs, FileMove move) throws IOException {
        List<PreparedOutput> prepared = new ArrayList<>();
        Throwable failure = null;
        try {
            for (Output output : outputs) {
                PreparedOutput item = new PreparedOutput(output.path());
                prepared.add(item);
                Path parent = output.path().getParent();
                if (!Files.isDirectory(parent)) Files.createDirectories(parent);
                item.temporary = Files.createTempFile(parent, ".oracle-compare-", ".tmp");
                String text = output.text().replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
                Files.writeString(item.temporary, text, output.charset());
            }
            // Noch einmal prüfen, bevor Sicherungen oder Ausgabedateien angefasst werden.
            if (outputs.size() == 2) validateOutputs(outputs.get(0).path(), outputs.get(1).path());
            else validateFile(outputs.get(0).path());
            if (outputs.size() > 1) {
                for (PreparedOutput item : prepared) {
                    if (Files.exists(item.output, LinkOption.NOFOLLOW_LINKS)) {
                        item.backup = Files.createTempFile(item.output.getParent(), ".oracle-compare-", ".bak");
                        Files.copy(item.output, item.backup, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            for (PreparedOutput item : prepared) {
                // Nach der ersten Anlage können weitere, dateisystemspezifische Namensaliasse
                // sichtbar werden (z. B. normalisierte Dateinamen auf Windows).
                if (outputs.size() == 2) validateOutputs(outputs.get(0).path(), outputs.get(1).path());
                // Auch ein fehlgeschlagener, nicht atomarer Move kann sein Ziel bereits ändern.
                if (outputs.size() > 1) item.restoreOnFailure = true;
                move.move(item.temporary, item.output);
                item.restoreOnFailure = true;
            }
            return outputs.get(0).path();
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            for (int i = prepared.size() - 1; i >= 0; i--) {
                PreparedOutput item = prepared.get(i);
                if (!item.restoreOnFailure) continue;
                try {
                    if (item.backup == null) Files.deleteIfExists(item.output);
                    else replace(item.backup, item.output);
                } catch (IOException | RuntimeException restoreFailure) {
                    item.keepBackup = true;
                    e.addSuppressed(new IOException("Ausgabe konnte nicht wiederhergestellt werden: "
                            + item.output + (item.backup == null ? "" : "; Sicherung: " + item.backup), restoreFailure));
                }
            }
            throw e;
        } finally {
            IOException cleanupFailure = null;
            for (PreparedOutput item : prepared) {
                for (Path temporary : new Path[]{item.temporary, item.keepBackup ? null : item.backup}) {
                    if (temporary == null) continue;
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        if (cleanupFailure == null) cleanupFailure = e;
                        else cleanupFailure.addSuppressed(e);
                    }
                }
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface FileMove {
        void move(Path source, Path target) throws IOException;
    }

    private record Output(Path path, String text, Charset charset) { }

    private static final class PreparedOutput {
        private final Path output;
        private Path temporary;
        private Path backup;
        private boolean restoreOnFailure;
        private boolean keepBackup;

        private PreparedOutput(Path output) { this.output = output; }
    }
}
