package de.axa.oraclecompare;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Schreibt erst vollständig und ersetzt danach das Ergebnis: CP1252, CRLF und Bestandsschutz. */
final class SqlScriptWriter {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private SqlScriptWriter() { }

    /**
     * Nicht darstellbare Zeichen und Schreibfehler lassen eine vorhandene Ausgabedatei bestehen.
     * Die temporäre Datei liegt im selben Verzeichnis; auch Verzeichnislinks werden unterstützt.
     */
    static Path write(Path outputFile, String script) throws IOException {
        Path output = outputFile.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (!Files.isDirectory(parent)) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".oracle-compare-", ".sql.tmp");
        try {
            String windowsText = script.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
            Files.writeString(temporary, windowsText, WINDOWS_1252);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return output;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
