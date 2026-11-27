package io.github.jaeseongs95.opermand.domain.operation;

/** The executor's own verdict on a result: it reports itself failed or not. The operation's state is decided from the whole outcome, not from this flag alone. */
public enum ResultFlag {
    OK, FAILED
}
