package io.github.jaeseongs95.portal.server.assembly;

import java.nio.file.Path;
import java.util.Objects;

import io.github.jaeseongs95.portal.app.artifact.StagingTimes;
import io.github.jaeseongs95.portal.store.artifact.ArtifactLimits;

/**
 * What the file stores need to know from the installation: the folders of the artifact bytes, command output bytes, encrypted operation-body attachments, attachment
 * keyring, optional explicit keyring file override, the audit file, the limits of the artifact store and how long an artifact that nobody used stays.
 *
 * @param artifactsFolder the folder of the artifact payload files
 * @param outputsFolder the folder of the command output files
 * @param attachmentsFolder the folder of the encrypted operation-body attachments
 * @param keyringFolder the folder of the attachment encryption keyring
 * @param keyFile optional explicit path to the keyring file (nullable; when null, {@code <keyringFolder>/attachment.keys})
 * @param auditFile the JSONL audit file
 * @param artifactLimits the budget, the per-file limit, the chunk size and the disk margin of the artifact store
 * @param stagingTimes how long an unsubmitted upload and a ready download stay
 */
public record StorageSettings(Path artifactsFolder, Path outputsFolder, Path attachmentsFolder, Path keyringFolder, Path keyFile, Path auditFile,
                              ArtifactLimits artifactLimits, StagingTimes stagingTimes) {

    public StorageSettings {
        Objects.requireNonNull(artifactsFolder, "artifactsFolder");
        Objects.requireNonNull(outputsFolder, "outputsFolder");
        Objects.requireNonNull(attachmentsFolder, "attachmentsFolder");
        Objects.requireNonNull(keyringFolder, "keyringFolder");
        Objects.requireNonNull(auditFile, "auditFile");
        Objects.requireNonNull(artifactLimits, "artifactLimits");
        Objects.requireNonNull(stagingTimes, "stagingTimes");
    }

    /** Convenience constructor deriving sibling {@code attachments} and {@code keyring} folders alongside {@code artifactsFolder}. */
    public StorageSettings(Path artifactsFolder, Path outputsFolder, Path auditFile, ArtifactLimits artifactLimits, StagingTimes stagingTimes) {
        this(artifactsFolder, outputsFolder, deriveSibling(artifactsFolder, "attachments"), deriveSibling(artifactsFolder, "keyring"), null,
            auditFile, artifactLimits, stagingTimes);
    }

    private static Path deriveSibling(Path artifactsFolder, String name) {
        Objects.requireNonNull(artifactsFolder, "artifactsFolder");
        Path parent = artifactsFolder.toAbsolutePath().getParent();
        return parent != null ? parent.resolve(name) : artifactsFolder.resolve(name);
    }
}
