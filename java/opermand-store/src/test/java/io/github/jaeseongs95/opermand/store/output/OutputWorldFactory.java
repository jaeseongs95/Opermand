package io.github.jaeseongs95.opermand.store.output;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.store.storage.StorageBudgetRowRepository;

/** Builds {@link OutputWorld}s from the beans of the test slice (constructor injection of the package-private repositories). */
@Component
class OutputWorldFactory {

    private final CommandOutputRowRepository rows;
    private final CommandOutputFormatRowRepository formats;
    private final CommandOutputPageRowRepository pages;
    private final StorageBudgetRowRepository budgets;
    private final JdbcAggregateOperations aggregates;
    private final OutputMapper mapper;

    OutputWorldFactory(CommandOutputRowRepository rows, CommandOutputFormatRowRepository formats, CommandOutputPageRowRepository pages,
                       StorageBudgetRowRepository budgets, JdbcAggregateOperations aggregates, OutputMapper mapper) {
        this.rows = rows;
        this.formats = formats;
        this.pages = pages;
        this.budgets = budgets;
        this.aggregates = aggregates;
        this.mapper = mapper;
    }

    /** A world whose manifest store takes the time from a clock fixed at the start of the cases, 2026-11-12T00:00:00Z, which is the {@code T0} of the output tests. */
    OutputWorld create(Path folder, OutputLimits limits) {
        return create(folder, limits, Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC));
    }

    OutputWorld create(Path folder, OutputLimits limits, Clock clock) {
        return new OutputWorld(folder, limits, clock, rows, formats, pages, budgets, aggregates, mapper);
    }
}
