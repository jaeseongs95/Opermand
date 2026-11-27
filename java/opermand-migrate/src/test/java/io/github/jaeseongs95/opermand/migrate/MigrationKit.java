package io.github.jaeseongs95.opermand.migrate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationEvent;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.migrate.cli.MigrateCommand;
import io.github.jaeseongs95.opermand.migrate.target.MigrationTarget;
import io.github.jaeseongs95.opermand.migrate.target.WritableTargetConfiguration;
import io.github.jaeseongs95.opermand.store.journal.JdbcClaimStore;
import io.github.jaeseongs95.opermand.store.journal.JdbcOperationJournal;
import io.github.jaeseongs95.opermand.store.journal.JdbcOperationQueueView;

/** What the tests share: run the command, look into a 3.0 store the way the server would, and take a fingerprint of a folder. */
final class MigrationKit {

    /** The exit code and everything the command printed. */
    record Run(int exitCode, String output) {
    }

    /** The command, as a user runs it: arguments in, exit code and text out. */
    Run run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int exitCode = new MigrateCommand(out).run(args);
        return new Run(exitCode, bytes.toString(StandardCharsets.UTF_8));
    }

    /** The arguments of a real run into the embedded store below {@code target}. */
    String[] importArgs(Path source, Path target, String... more) {
        return args(source, target, false, more);
    }

    String[] dryRunArgs(Path source, Path target, String... more) {
        return args(source, target, true, more);
    }

    String[] args(Path source, Path target, boolean dryRun, String... more) {
        ArrayList<String> args = new ArrayList<>(List.of("--source=" + source, "--target=" + target));
        if (dryRun) {
            args.add("--dry-run");
        }
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }

    /** An open 3.0 store, read through the store's own journal (the journal the server uses, including encrypted attachments and keyring under {@code target}); close it before the command runs again (the store file is locked while open). */
    Store open(Path target) {
        String location = target.toAbsolutePath().resolve("portal").toString().replace('\\', '/');
        ConfigurableApplicationContext context = new SpringApplicationBuilder(WritableTargetConfiguration.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .initializers(ctx -> ((org.springframework.context.support.GenericApplicationContext) ctx)
                .registerBean(MigrationTarget.class, () -> new MigrationTarget(target)))
            .properties("spring.datasource.url=jdbc:h2:file:" + location, "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=never",
                "logging.level.root=WARN")
            .run();
        return new Store(context, context.getBean(JdbcOperationJournal.class));
    }

    /** SHA-256 of every file below the folder, by relative path; two equal maps mean no byte of the folder changed and no file appeared or vanished. */
    Map<String, String> fingerprint(Path folder) {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(path).toString().replace('\\', '/'), sha256(path));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return files;
    }

    private String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The store of a 3.0 data folder, opened for the test. */
    static final class Store implements AutoCloseable {

        private final ConfigurableApplicationContext context;
        private final JdbcOperationJournal journal;

        private Store(ConfigurableApplicationContext context, JdbcOperationJournal journal) {
            this.context = context;
            this.journal = journal;
        }

        JdbcOperationJournal journal() {
            return journal;
        }

        JdbcClaimStore claims() {
            return context.getBean(JdbcClaimStore.class);
        }

        /** How many operations the store holds in each state. */
        Map<OperationState, Integer> counts() {
            return context.getBean(JdbcOperationQueueView.class).counts().counts();
        }

        int total() {
            return counts().values().stream().mapToInt(Integer::intValue).sum();
        }

        Operation byId(String id) {
            return journal.find(new OperationId(id)).orElseThrow(() -> new AssertionError("operation " + id + " is not in the store"));
        }

        boolean has(String id) {
            return journal.find(new OperationId(id)).isPresent();
        }

        Operation byKey(String key) {
            return journal.findByRequestKey(new RequestKey(key)).orElseThrow(() -> new AssertionError("request key " + key + " is not in the store"));
        }

        List<OperationEvent> events(String id) {
            return journal.events(new OperationId(id));
        }

        String commandOf(String id) {
            return ((CommandInput) byId(id).input()).command().value();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
