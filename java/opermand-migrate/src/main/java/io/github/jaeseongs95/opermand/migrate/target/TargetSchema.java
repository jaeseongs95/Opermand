package io.github.jaeseongs95.opermand.migrate.target;

/** Makes the schema of the 3.0 store ready for the run (or, for a dry run, leaves it exactly as it is). */
public interface TargetSchema {

    void ensureCurrent();
}
