package io.github.jaeseongs95.portal.migrate.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** The entry point of the {@code portal-migrate.jar}: {@code java -jar portal-migrate.jar --source=<2.x folder> --target=<3.0 folder> [--dry-run]}. */
public final class MigrateMain {

    private MigrateMain() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        int exitCode = new MigrateCommand(out).run(args);
        out.flush();
        System.exit(exitCode);
    }
}
