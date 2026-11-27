-- Command policy (2.x policy(id=1, revision, body), command-state.mjs:16). ANSI types only, see V1
-- (including why a list of values is checked with CASE and not with IN).
-- The row with id 1 is static seed data, not logic: the policy always exists, revision 0 is the empty policy.

CREATE TABLE command_policy (
    id       INTEGER NOT NULL,
    revision BIGINT  NOT NULL,
    CONSTRAINT pk_command_policy PRIMARY KEY (id),
    CONSTRAINT ck_command_policy_single CHECK (id = 1),
    CONSTRAINT ck_command_policy_revision CHECK (revision >= 0)
);
INSERT INTO command_policy (id, revision) VALUES (1, 0);

CREATE TABLE command_policy_rule (
    target_id             VARCHAR(32) NOT NULL,
    target_connection     CHAR(64)    NOT NULL,
    rule_revision         BIGINT      NOT NULL,
    profile               VARCHAR(80) NOT NULL,
    os                    VARCHAR(7)  NOT NULL,
    automatic_uploads     VARCHAR(3)  NOT NULL,
    automatic_downloads   VARCHAR(3)  NOT NULL,
    automatic_sudo        VARCHAR(3)  NOT NULL,
    test_server           VARCHAR(3)  NOT NULL,
    source_contract       VARCHAR(16) NOT NULL,
    CONSTRAINT pk_command_policy_rule PRIMARY KEY (target_id),
    CONSTRAINT ck_policy_rule_revision CHECK (rule_revision >= 1),
    CONSTRAINT ck_policy_rule_os CHECK (CASE WHEN os = 'LINUX' THEN 1 WHEN os = 'WINDOWS' THEN 1 ELSE 0 END = 1),
    CONSTRAINT ck_policy_rule_uploads CHECK (CASE WHEN automatic_uploads = 'ON' THEN 1 WHEN automatic_uploads = 'OFF' THEN 1 ELSE 0 END = 1),
    CONSTRAINT ck_policy_rule_downloads CHECK (CASE WHEN automatic_downloads = 'ON' THEN 1 WHEN automatic_downloads = 'OFF' THEN 1 ELSE 0 END = 1),
    CONSTRAINT ck_policy_rule_sudo CHECK (CASE WHEN automatic_sudo = 'ON' THEN 1 WHEN automatic_sudo = 'OFF' THEN 1 ELSE 0 END = 1),
    CONSTRAINT ck_policy_rule_test CHECK (CASE WHEN test_server = 'ON' THEN 1 WHEN test_server = 'OFF' THEN 1 ELSE 0 END = 1),
    CONSTRAINT ck_policy_rule_source CHECK (CASE WHEN source_contract = 'NONE' THEN 1 WHEN source_contract = 'PYTHON_SOURCE_V1' THEN 1 ELSE 0 END = 1)
);

CREATE TABLE command_policy_name (
    target_id    VARCHAR(32)  NOT NULL,
    list_kind    VARCHAR(7)   NOT NULL,
    position     INTEGER      NOT NULL,
    command_name VARCHAR(512) NOT NULL,
    CONSTRAINT pk_command_policy_name PRIMARY KEY (target_id, list_kind, position),
    CONSTRAINT uq_command_policy_name UNIQUE (target_id, list_kind, command_name),
    CONSTRAINT fk_command_policy_name FOREIGN KEY (target_id) REFERENCES command_policy_rule (target_id),
    CONSTRAINT ck_command_policy_kind CHECK (CASE WHEN list_kind = 'ALLOWED' THEN 1 WHEN list_kind = 'DENIED' THEN 1 ELSE 0 END = 1)
);
