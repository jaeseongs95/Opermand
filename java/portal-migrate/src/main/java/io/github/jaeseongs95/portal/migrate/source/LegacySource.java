package io.github.jaeseongs95.portal.migrate.source;

/** One 2.x file that holds an operation history, read row by row (the history may be long: nothing keeps all rows in memory). Each call opens the file again, read only. */
public interface LegacySource {

    /** The file name, such as {@code operations.sqlite}. */
    String fileName();

    /** Reads every row in id order into the sink. */
    void forEachRow(RowSink sink);

    /** Receives the rows of one read. */
    @FunctionalInterface
    interface RowSink {

        void accept(LegacyRow row);
    }
}
