CREATE TABLE customer (
    id UUID PRIMARY KEY,
    auth_subject TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE partner (
    id UUID PRIMARY KEY,
    auth_subject TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE account (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customer(id),
    bank_name TEXT NOT NULL,
    masked_account_number TEXT NOT NULL,
    balance NUMERIC(19, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_account_owner UNIQUE (id, customer_id)
);
CREATE INDEX ix_account_customer ON account(customer_id);

CREATE TABLE transaction_record (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    description TEXT NOT NULL
);
CREATE INDEX ix_transaction_account_time ON transaction_record(account_id, occurred_at DESC, id DESC);

CREATE TABLE access_grant (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    partner_id UUID NOT NULL REFERENCES partner(id),
    permission TEXT NOT NULL CHECK (permission IN ('ACCOUNT_READ', 'TRANSACTION_READ')),
    granted_by UUID NOT NULL REFERENCES customer(id),
    granted_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_grant_account_owner FOREIGN KEY (account_id, granted_by) REFERENCES account(id, customer_id),
    CONSTRAINT ck_grant_revocation_time CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE UNIQUE INDEX uq_active_access_grant ON access_grant(account_id, partner_id, permission)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_grant_partner ON access_grant(partner_id);
CREATE INDEX ix_grant_granted_by ON access_grant(granted_by);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('CUSTOMER', 'PARTNER', 'ANONYMOUS')),
    principal_id UUID,
    action TEXT NOT NULL,
    resource_ref TEXT,
    result TEXT NOT NULL CHECK (result IN ('ALLOWED', 'DENIED')),
    reason_code TEXT NOT NULL,
    request_id TEXT NOT NULL,
    CONSTRAINT ck_audit_principal CHECK (
        (principal_type = 'ANONYMOUS' AND principal_id IS NULL) OR
        (principal_type IN ('CUSTOMER', 'PARTNER') AND principal_id IS NOT NULL)
    )
);
CREATE INDEX ix_audit_principal_time_reason ON audit_event(principal_type, principal_id, occurred_at, reason_code);

CREATE TABLE security_alert (
    id UUID PRIMARY KEY,
    rule_id TEXT NOT NULL,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('CUSTOMER', 'PARTNER')),
    principal_id UUID NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    violation_count INTEGER NOT NULL CHECK (violation_count >= 0),
    distinct_resource_count INTEGER NOT NULL CHECK (distinct_resource_count >= 0),
    detected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_alert_window CHECK (window_end >= window_start),
    CONSTRAINT ck_alert_resource_count CHECK (distinct_resource_count <= violation_count)
);
CREATE INDEX ix_alert_principal_rule_time ON security_alert(principal_type, principal_id, rule_id, detected_at);
