-- One row per budget that is judged by reading a sum and then inserting a row (SSH_PORTAL-D-0058). ANSI types only, see V1.
-- A reservation first changes the row of its budget with one UPDATE and keeps the row lock until its transaction ends, so two reservations of the same budget are
-- judged one after the other: the second one reads the sum after the first one's row is committed, and the budget is never exceeded by two requests that read the same sum.
-- No trigger, no function, no isolation level: the primary key and the row lock of an UPDATE are the same on every DBMS.

CREATE TABLE budget_gate (
    scope   VARCHAR(16) NOT NULL,
    version BIGINT      NOT NULL,
    CONSTRAINT pk_budget_gate PRIMARY KEY (scope),
    CONSTRAINT ck_budget_gate_scope CHECK (CASE WHEN scope = 'ARTIFACT' THEN 1 WHEN scope = 'OUTPUT' THEN 1 ELSE 0 END = 1)
);

INSERT INTO budget_gate (scope, version) VALUES ('ARTIFACT', 0);
INSERT INTO budget_gate (scope, version) VALUES ('OUTPUT', 0);
