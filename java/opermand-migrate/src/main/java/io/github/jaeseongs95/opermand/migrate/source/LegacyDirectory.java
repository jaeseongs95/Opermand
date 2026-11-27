package io.github.jaeseongs95.opermand.migrate.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.jaeseongs95.opermand.migrate.source.InventoryEntry.Classification;
import io.github.jaeseongs95.opermand.migrate.source.SourceUnusableException.Problem;

/**
 * The folder a 2.x service kept its state in (2.x {@code BRIDGE_TRANSFER_ROOT}, by default {@code /var/lib/ssh-mcp/transfers}). It knows which names 2.x wrote there and what
 * becomes of each (the scope table of the guide): it lists the folder completely, hands over the journals that are moved, and takes a fingerprint (SHA-256) of each of them
 * so that a run can prove that it did not change the files it read and that nobody else did while it ran.
 */
public final class LegacyDirectory {

    static final String OPERATIONS = "operations.sqlite";
    static final String COMMANDS = "command-automation.sqlite";
    static final String AGENT_CLI = "agent-cli.sqlite";
    private static final Pattern ARTIFACT_FILE = Pattern.compile("[a-f0-9]{48}\\.(json|part)");
    private static final int BUFFER_BYTES = 64 * 1024;

    private final Path root;

    public LegacyDirectory(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new SourceUnusableException(Problem.NOT_A_DIRECTORY, "The 2.x data folder " + root + " is not a folder (or it is a link); it is not read");
        }
        this.root = root;
    }

    /** The journals that are moved, in the order they are read: the formal journal first, then the two older request journals. Only the files that exist. */
    public List<LegacySource> sources() {
        List<LegacySource> sources = new ArrayList<>();
        if (Files.exists(root.resolve(OPERATIONS), LinkOption.NOFOLLOW_LINKS)) {
            sources.add(new OperationsTableSource(root.resolve(OPERATIONS)));
        }
        for (String journal : List.of(COMMANDS, AGENT_CLI)) {
            if (Files.exists(root.resolve(journal), LinkOption.NOFOLLOW_LINKS)) {
                sources.add(new RequestJournalSource(root.resolve(journal)));
            }
        }
        return sources;
    }

    /** Everything in the folder, by name, with what the migration does with it. */
    public List<InventoryEntry> inventory() {
        List<InventoryEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> names = Files.newDirectoryStream(root)) {
            for (Path path : names) {
                entries.add(classify(path.getFileName().toString()));
            }
        } catch (IOException e) {
            throw new SourceUnusableException(Problem.UNREADABLE, "The 2.x data folder cannot be listed: " + e.getMessage(), e);
        }
        entries.sort(Comparator.comparing(InventoryEntry::name));
        return entries;
    }

    /** The SHA-256 of each journal that is moved, by file name. */
    public Map<String, String> fingerprints() {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (LegacySource source : sources()) {
            fingerprints.put(source.fileName(), sha256(root.resolve(source.fileName())));
        }
        return fingerprints;
    }

    private InventoryEntry classify(String name) {
        return switch (name) {
            case OPERATIONS -> new InventoryEntry(name, Classification.MIGRATED, "the operation journal: command operations are moved; operations of other kinds are listed as not moved");
            case COMMANDS -> new InventoryEntry(name, Classification.MIGRATED,
                "the command approval journal: requests are moved as commands; the policy table is not moved (its rules are bound to 2.x target revisions)");
            case AGENT_CLI -> new InventoryEntry(name, Classification.MIGRATED,
                "the agent CLI request journal: requests are moved as commands; the remote install status column is not moved");
            case "maintenance.sqlite" -> new InventoryEntry(name, Classification.NOT_MIGRATED, "a maintenance reservation of the 2.x preservation procedure; it has no meaning in 3.0");
            case "capacity.sqlite" -> new InventoryEntry(name, Classification.NOT_MIGRATED, "the runtime capacity override of 2.x; set the capacity again in 3.0");
            case "command-outputs" -> new InventoryEntry(name, Classification.NOT_MIGRATED, "command output files; 2.x kept them for one hour only, so they are long expired");
            case "orphans" -> new InventoryEntry(name, Classification.NOT_MIGRATED, "transfer files that 2.x had quarantined; they are staging data that expires");
            default -> ARTIFACT_FILE.matcher(name).matches()
                ? new InventoryEntry(name, Classification.NOT_MIGRATED, "a staged transfer file (artifact); 2.x deleted these after their time to live, so they are not history")
                : new InventoryEntry(name, Classification.UNRECOGNIZED, "not a file this tool knows; nothing is done with it");
        };
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_BYTES];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new SourceUnusableException(Problem.UNREADABLE, file.getFileName() + " cannot be read for its fingerprint: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
