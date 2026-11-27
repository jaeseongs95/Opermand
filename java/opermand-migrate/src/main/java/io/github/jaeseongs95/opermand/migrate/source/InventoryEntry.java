package io.github.jaeseongs95.opermand.migrate.source;

import java.util.Objects;

/**
 * One thing found in the 2.x data folder and what the migration does with it. The folder is listed completely: a file the tool does not know is reported as
 * {@link Classification#UNRECOGNIZED}, never skipped silently.
 *
 * @param name the file or folder name
 * @param classification what happens to it
 * @param note one sentence that says why
 */
public record InventoryEntry(String name, Classification classification, String note) {

    /** What the migration does with an entry. */
    public enum Classification {
        /** Read and moved into the 3.0 store. */
        MIGRATED,
        /** Recognized and deliberately not moved; the note says why. */
        NOT_MIGRATED,
        /** Not known to this tool; nothing is done with it. */
        UNRECOGNIZED
    }

    public InventoryEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(note, "note");
    }
}
