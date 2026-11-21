package io.github.jaeseongs95.portal.migrate.settings;

import java.nio.file.Path;
import java.util.List;

/**
 * What the 2.x environment file becomes: the text of the 3.0 settings file, where it goes, what the report says about it (key names only, never a value) and what the
 * operator still has to do by hand. A plan with a problem has no text and is never written.
 *
 * @param out the file the text is written to
 * @param text the 3.0 settings file, or null when there is a problem
 * @param notes what became of each setting, in report words
 * @param actions what the operator has to decide or set that this command cannot
 * @param problems what stops the conversion
 */
public record SettingsPlan(Path out, String text, List<String> notes, List<String> actions, List<Problem> problems) {

    /** A reason the 2.x file cannot be converted as it is: a code, the place (the file, not a value of it) and the message. */
    public record Problem(String code, String reference, String message) {
    }

    public SettingsPlan {
        notes = List.copyOf(notes);
        actions = List.copyOf(actions);
        problems = List.copyOf(problems);
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }
}
