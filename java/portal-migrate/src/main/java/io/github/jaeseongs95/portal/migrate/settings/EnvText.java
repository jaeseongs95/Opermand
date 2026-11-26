package io.github.jaeseongs95.portal.migrate.settings;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The 2.x environment file as 2.4.2 read it ({@code src/adapters/config/secrets.mjs}, {@code scripts/settings_contract.py}): lines {@code KEY=value} with a key of
 * {@code [A-Z_][A-Z0-9_]*}, blank lines and lines that start with {@code #} ignored, lines ended by LF or CRLF, a value trimmed and, when one pair of equal quotes encloses it,
 * unquoted; a value with a CR, an LF or a NUL, a key that 2.x does not accept, a line of another shape and a file above 64 KiB were refused by 2.x and are refused here, never
 * guessed at. A key given twice keeps its last value, as it did in 2.x.
 *
 * <p>The values are secrets (tokens, keys). They are kept exactly as written and are never put into a message: a problem names the line number and the key, nothing of a value.
 */
final class EnvText {

    private final SettingsContract contract;

    EnvText(SettingsContract contract) {
        this.contract = contract;
    }

    /** 2.x refused an environment file larger than this. */
    static final int MAX_BYTES = 65536;

    private static final Pattern LINE = Pattern.compile("([A-Z_][A-Z0-9_]*)=(.*)", Pattern.DOTALL);

    /** One setting: the line it is on, its key, the text after the {@code =} as it was written (trimmed, quotes kept) and the value 2.x took from it (quotes removed). */
    record Entry(int line, String key, String written, String value) {
    }

    /**
     * What the file says.
     *
     * @param entries the settings by key, in the order of their last line
     * @param duplicates the keys that stand on more than one line
     * @param problems what 2.x would have refused, by line, without any value
     */
    record Parsed(Map<String, Entry> entries, List<String> duplicates, List<String> problems) {
    }

    Parsed parse(byte[] bytes) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (bytes.length > MAX_BYTES) {
            problems.add("the file is larger than " + MAX_BYTES + " bytes, which 2.x refused too");
            return new Parsed(entries, duplicates, problems);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            problems.add("the file is not well-formed UTF-8");
            return new Parsed(entries, duplicates, problems);
        }
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].endsWith("\r") ? lines[index].substring(0, lines[index].length() - 1) : lines[index];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            read(index + 1, line, entries, duplicates, problems);
        }
        return new Parsed(entries, duplicates, problems);
    }

    private void read(int number, String line, Map<String, Entry> entries, List<String> duplicates, List<String> problems) {
        Matcher match = LINE.matcher(line);
        if (!match.matches()) {
            problems.add("line " + number + " is not a KEY=value entry, which 2.x refused too");
            return;
        }
        String key = match.group(1);
        if (!contract.legacyAccepts(key)) {
            problems.add("line " + number + ": " + key + " is not a setting of 2.x, which refused it too");
            return;
        }
        String written = match.group(2).strip();
        String value = unquoted(written);
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            problems.add("line " + number + ": the value of " + key + " holds a control character, which 2.x refused too");
            return;
        }
        if (entries.containsKey(key)) {
            duplicates.add(key);
            entries.remove(key);
        }
        entries.put(key, new Entry(number, key, written, value));
    }

    private static String unquoted(String written) {
        if (written.length() >= 2) {
            char first = written.charAt(0);
            if ((first == '\'' || first == '"') && written.charAt(written.length() - 1) == first) {
                return written.substring(1, written.length() - 1);
            }
        }
        return written;
    }
}
