package io.github.jaeseongs95.opermand.migrate.target;

import java.nio.file.Path;
import java.util.Objects;

/** The 3.0 data folder ({@code BRIDGE_DATA_DIR}) that holds encrypted operation attachments and the attachment keyring during a write or full store read. */
public record MigrationTarget(Path dataDir) {

    public MigrationTarget {
        Objects.requireNonNull(dataDir, "dataDir");
        dataDir = dataDir.toAbsolutePath();
    }
}
