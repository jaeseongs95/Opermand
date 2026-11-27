package io.github.jaeseongs95.opermand.migrate.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Writes the settings file of a plan, once and never over something that is there. A file that holds exactly the text is what an earlier run wrote (a rerun leaves it as it is); a
 * file with other content is the operator's (they have completed it by hand, as the report asked) and is left alone too: the command never replaces a settings file.
 */
public final class SettingsWriter {

    private final OwnerOnlyFile files = new OwnerOnlyFile();

    /** What the write did. */
    public enum Result {
        /** The file was made now. */
        WRITTEN,
        /** The file was there with exactly this text. */
        ALREADY_THERE,
        /** The file was there with other content and was not touched. */
        LEFT_AS_IT_IS
    }

    /** Writes the plan's text to its file, owner only; the plan must have no problem. */
    public Result write(SettingsPlan plan) throws IOException {
        if (plan.hasProblems()) {
            throw new IllegalArgumentException("a plan with a problem is never written");
        }
        byte[] content = plan.text().getBytes(StandardCharsets.UTF_8);
        Path out = plan.out();
        try {
            files.createNew(out, content);
            return Result.WRITTEN;
        } catch (FileAlreadyExistsException there) {
            return Arrays.equals(Files.readAllBytes(out), content) ? Result.ALREADY_THERE : Result.LEFT_AS_IT_IS;
        }
    }

    /** What {@link #write} would do, without writing: a dry run says what a real run would find. */
    public Result probe(SettingsPlan plan) throws IOException {
        if (!Files.exists(plan.out())) {
            return Result.WRITTEN;
        }
        return Arrays.equals(Files.readAllBytes(plan.out()), plan.text().getBytes(StandardCharsets.UTF_8)) ? Result.ALREADY_THERE : Result.LEFT_AS_IT_IS;
    }
}
