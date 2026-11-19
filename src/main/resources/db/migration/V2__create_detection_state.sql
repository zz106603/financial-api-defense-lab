-- 주체·규칙별 알림 억제 상태. 알림 저장과 같은 트랜잭션에서만 갱신한다.
CREATE TABLE detection_state (
    rule_id TEXT NOT NULL,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('CUSTOMER', 'PARTNER')),
    principal_id UUID NOT NULL,
    last_alert_at TIMESTAMPTZ NOT NULL,
    last_window_end TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (rule_id, principal_type, principal_id)
);

CREATE INDEX ix_audit_detection_window ON audit_event (occurred_at, principal_type, principal_id)
    WHERE result = 'DENIED' AND reason_code = 'OBJECT_ACCESS_DENIED';
