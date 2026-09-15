package de.axa.oraclecompare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unveränderlicher Ausschlussfilter für vollständige Oracle-Dictionary-Namen.
 * Groß-/Kleinschreibung wird ignoriert. {@code *} steht für beliebig viele Zeichen,
 * {@code ?} für genau ein Unicode-Zeichen; ein Backslash maskiert das nächste Zeichen.
 * Alle übrigen Zeichen werden wörtlich verglichen, auch SQL-LIKE- und Regex-Zeichen.
 */
final class ExclusionFilter {
    private static final int MANY = -1;
    private static final int ONE = -2;
    private final List<int[]> patterns;

    /** Prüft und kopiert die Muster; spätere Änderungen der übergebenen Liste wirken sich nicht aus. */
    ExclusionFilter(List<String> patterns) {
        if (patterns == null) {
            throw new IllegalArgumentException("Die Liste der Ausschlussmuster darf nicht null sein.");
        }
        List<int[]> compiled = new ArrayList<>(patterns.size());
        for (int i = 0; i < patterns.size(); i++) {
            compiled.add(compile(patterns.get(i), i));
        }
        this.patterns = List.copyOf(compiled);
    }

    /** Liefert einen Filter ohne Ausschlüsse. */
    static ExclusionFilter none() {
        return new ExclusionFilter(List.of());
    }

    /** Liefert true, sobald eines der Muster den gesamten Objektnamen abdeckt. */
    boolean excludes(String objectName) {
        if (objectName == null) {
            throw new IllegalArgumentException("Der zu prüfende Objektname darf nicht null sein.");
        }
        int[] name = objectName.codePoints().map(ExclusionFilter::foldCase).toArray();
        return patterns.stream().anyMatch(pattern -> matches(pattern, name));
    }

    /** Zeigt an, ob keine Ausschlussmuster konfiguriert wurden. */
    boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** Übersetzt Joker in interne Token und belässt alle maskierten Zeichen als Literale. */
    private static int[] compile(String pattern, int index) {
        if (pattern == null || pattern.isBlank()
                || pattern.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Ungültiges Ausschlussmuster an Position " + (index + 1)
                    + ": Muster müssen nichtleer sein und dürfen keine Kontrollzeichen enthalten.");
        }
        int[] source = pattern.codePoints().toArray();
        int[] tokens = new int[source.length];
        int length = 0;
        for (int i = 0; i < source.length; i++) {
            int character = source[i];
            if (character == '\\') {
                if (++i == source.length) {
                    throw new IllegalArgumentException("Ungültiges Ausschlussmuster an Position " + (index + 1)
                            + ": Nach einem Backslash muss ein zu maskierendes Zeichen folgen.");
                }
                tokens[length++] = foldCase(source[i]);
            } else if (character == '*') {
                // Mehrere benachbarte Sterne haben dieselbe Bedeutung wie ein einzelner Stern.
                if (length == 0 || tokens[length - 1] != MANY) tokens[length++] = MANY;
            } else {
                tokens[length++] = character == '?' ? ONE : foldCase(character);
            }
        }
        return Arrays.copyOf(tokens, length);
    }

    /** Einfache Unicode-Faltung ohne gebietsschemaabhängige oder mehrstellige Ersetzungen. */
    private static int foldCase(int codePoint) {
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }

    /**
     * Vergleicht Unicode-Codepoints ohne Regex-Backtracking. Bei einem Fehlschlag wird
     * der zuletzt gelesene Stern schrittweise um ein weiteres Zeichen erweitert.
     */
    private static boolean matches(int[] pattern, int[] name) {
        int token = 0;
        int character = 0;
        int lastStar = -1;
        int starEnd = 0;
        while (character < name.length) {
            if (token < pattern.length && (pattern[token] == ONE || pattern[token] == name[character])) {
                token++;
                character++;
            } else if (token < pattern.length && pattern[token] == MANY) {
                lastStar = token++;
                starEnd = character;
            } else if (lastStar >= 0) {
                token = lastStar + 1;
                character = ++starEnd;
            } else {
                return false;
            }
        }
        while (token < pattern.length && pattern[token] == MANY) token++;
        return token == pattern.length;
    }
}
