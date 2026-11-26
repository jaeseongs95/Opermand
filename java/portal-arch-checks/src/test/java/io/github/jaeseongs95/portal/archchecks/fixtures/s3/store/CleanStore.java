package io.github.jaeseongs95.portal.archchecks.fixtures.s3.store;

/** Clean fixture for A-TX-BOUNDARY (D-0114): a store class with no transaction boundary of its own. */
public class CleanStore {

    private int writes;

    public int write() {
        return ++writes;
    }
}
