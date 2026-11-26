package io.github.jaeseongs95.portal.migrate.target;

/** A dry run takes the store as it is: it applies no script, so a store that is behind makes the checks fail instead of being changed. */
final class CurrentSchema implements TargetSchema {

    @Override
    public void ensureCurrent() {
        // Nothing to do on purpose: a dry run writes nothing, not even the schema.
    }
}
