-- Domain storage quota accounting table (SSH_PORTAL-D-0058). ANSI types only, see V1.
-- Each scope row holds the live charged byte total and item count for its storage domain.
-- Reservations atomically verify and charge the budget in a single conditional UPDATE (first write of the transaction),
-- preventing concurrent oversubscription without dummy lock tables, triggers, or vendor isolation levels.

CREATE TABLE storage_budget (
    scope         VARCHAR(16) NOT NULL,
    charged_bytes BIGINT      NOT NULL,
    item_count    BIGINT      NOT NULL,
    CONSTRAINT pk_storage_budget PRIMARY KEY (scope),
    CONSTRAINT ck_storage_budget_scope CHECK (CASE WHEN scope = 'ARTIFACT' THEN 1 WHEN scope = 'OUTPUT' THEN 1 ELSE 0 END = 1),
    CONSTRAINT ck_storage_budget_bytes CHECK (charged_bytes >= 0),
    CONSTRAINT ck_storage_budget_count CHECK (item_count >= 0)
);

INSERT INTO storage_budget (scope, charged_bytes, item_count) VALUES ('ARTIFACT', 0, 0);
INSERT INTO storage_budget (scope, charged_bytes, item_count) VALUES ('OUTPUT', 0, 0);
