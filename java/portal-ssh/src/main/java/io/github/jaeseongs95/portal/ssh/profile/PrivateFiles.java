package io.github.jaeseongs95.portal.ssh.profile;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Picks the {@link PrivateFileRule} that the file store of a path supports and creates a file, and the folder that holds it, private to their owner. The rules are injected
 * as a {@code List} in their {@code @Order}, so a rule for another kind of file store is a new bean and no change here. A file store that no rule supports is refused with an
 * {@link IOException}: nothing is created with wide rights because the store cannot narrow them. What exists is neither judged nor changed (decision D-0117).
 */
@Component
public class PrivateFiles {

    private final List<PrivateFileRule> rules;

    public PrivateFiles(List<PrivateFileRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** Creates the folder of the file, if it is missing, and the empty file, if it is missing; what is created is private to its owner. What exists is left as it is. */
    public void createPrivateFile(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        PrivateFileRule rule = ruleFor(file);
        Path folder = file.toAbsolutePath().getParent();
        if (folder != null) {
            rule.createFolder(folder);
        }
        rule.createFile(file.toAbsolutePath());
    }

    private PrivateFileRule ruleFor(Path path) throws IOException {
        Path existing = path.toAbsolutePath();
        while (existing.getParent() != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        FileStore store = Files.getFileStore(existing);
        for (PrivateFileRule rule : rules) {
            if (rule.supports(store)) {
                return rule;
            }
        }
        throw new IOException("The file store " + store + " supports neither permission bits (posix) nor an access control list (acl), so " + path
            + " cannot be made private to its owner");
    }
}
