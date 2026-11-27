package io.github.jaeseongs95.opermand.migrate.target;

import io.github.jaeseongs95.opermand.store.migration.Migrations;

/** The schema is brought up to date with the store's own expand scripts, the same runner the server runs at its start; a new store is created by it. */
final class ExpandSchema implements TargetSchema {

    private final Migrations migrations;

    ExpandSchema(Migrations migrations) {
        this.migrations = migrations;
    }

    @Override
    public void ensureCurrent() {
        migrations.expand();
    }
}
