package io.github.jaeseongs95.opermand.migrate.run;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.migrate.plan.ImportPlan;
import io.github.jaeseongs95.opermand.migrate.plan.LegacyTranslator;
import io.github.jaeseongs95.opermand.migrate.plan.Translation;
import io.github.jaeseongs95.opermand.migrate.report.MigrationReport;
import io.github.jaeseongs95.opermand.migrate.source.LegacyRow;
import io.github.jaeseongs95.opermand.migrate.source.LegacySource;

/**
 * The first half of a run, which needs no 3.0 store: every row of every journal is read once, translated, and checked against the rows before it. A row that cannot be
 * written to 3.0 exactly as it is, an id that two files give different request keys, and a request key that two operations share are reported as conflicts; an operation that
 * two files both hold (the formal journal holds every operation the older request journals hold) is moved once, from the file that is read first. The rows are streamed: only
 * the id, key and digest of each operation to move stay in memory, so the length of the history decides nothing.
 */
public final class SourceScan {

    private final LegacyTranslator translator;
    private final MigrationReport report;

    public SourceScan(LegacyTranslator translator, MigrationReport report) {
        this.translator = translator;
        this.report = report;
    }

    /** The operations to move, in the order of the files and of their ids. */
    public List<PlannedRow> scan(List<LegacySource> sources) {
        Map<String, PlannedRow> byId = new LinkedHashMap<>();
        Map<String, PlannedRow> byKey = new HashMap<>();
        for (LegacySource source : sources) {
            source.forEachRow(row -> handle(row, byId, byKey));
        }
        return List.copyOf(byId.values());
    }

    private void handle(LegacyRow row, Map<String, PlannedRow> byId, Map<String, PlannedRow> byKey) {
        report.rowRead(row.source());
        switch (translator.translate(row)) {
            case Translation.Rejected rejected -> report.issue("UNREPRESENTABLE_ROW", row.reference(), rejected.reason());
            case Translation.NotMigrated skipped -> report.notMigrated(row.source(), skipped.kind(), skipped.reason());
            case Translation.Import imported -> register(row, imported.plan(), byId, byKey);
        }
    }

    private void register(LegacyRow row, ImportPlan plan, Map<String, PlannedRow> byId, Map<String, PlannedRow> byKey) {
        PlannedRow planned = new PlannedRow(row.reference(), plan.id(), plan.requestKey(), plan.contractDigest(), plan.finalState());
        PlannedRow sameId = byId.get(plan.id().value());
        if (sameId != null) {
            // A row without a key of its own (an older request journal) cannot disagree with the key the formal journal has for the same id.
            if (row.requestKey() == null || sameId.key().equals(plan.requestKey())) {
                report.coveredDuplicate();
            } else {
                report.issue("ID_WITH_TWO_KEYS", row.reference(), "operation " + plan.id().value() + " is also in " + sameId.reference() + " with another request key ("
                    + sameId.key().value() + " there, " + plan.requestKey().value() + " here); keys are never changed to make them agree");
            }
            return;
        }
        PlannedRow sameKey = byKey.get(plan.requestKey().value());
        if (sameKey != null) {
            report.issue("REQUEST_KEY_SHARED", row.reference(), "request key " + plan.requestKey().value() + " belongs to operation " + plan.id().value() + " here and to operation "
                + sameKey.id().value() + " in " + sameKey.reference() + "; keys are never changed to make them differ");
            return;
        }
        byId.put(plan.id().value(), planned);
        byKey.put(plan.requestKey().value(), planned);
        report.planned(plan.finalState() == OperationState.UNKNOWN);
        if (plan.reinterpretation() != null) {
            report.reinterpreted(row.reference(), plan.reinterpretation());
        }
    }
}
