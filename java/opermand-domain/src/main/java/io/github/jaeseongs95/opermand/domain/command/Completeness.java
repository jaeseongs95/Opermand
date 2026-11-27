package io.github.jaeseongs95.opermand.domain.command;

/** Whether a grammar established every program name of a command line. {@link #INCOMPLETE} sends the line to the full (model) evaluation instead of a name match. */
public enum Completeness {
    COMPLETE,
    INCOMPLETE
}
