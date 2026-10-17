-- Additive only. Apply to BINTIME_APP before deploying the new app. Re-runnable.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE
DECLARE
  PROCEDURE create_if_missing(statement VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE statement;
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE<>-955 THEN RAISE; END IF;
  END;
BEGIN
  create_if_missing('CREATE TABLE notification_push_history (
  history_id VARCHAR2(64 BYTE) NOT NULL,
  notification_type VARCHAR2(32 BYTE) NOT NULL,
  notification_id VARCHAR2(64 BYTE) NOT NULL,
  user_id VARCHAR2(64 BYTE) NOT NULL,
  subscription_id VARCHAR2(64 BYTE),
  attempt_no NUMBER(6,0) DEFAULT 0 NOT NULL,
  status VARCHAR2(16 BYTE) NOT NULL,
  error_code VARCHAR2(100 BYTE),
  title VARCHAR2(200 CHAR) NOT NULL,
  body_text VARCHAR2(1000 CHAR),
  action_path VARCHAR2(2048 BYTE) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_notification_push_history PRIMARY KEY(history_id),
  CONSTRAINT ck_push_history_attempt CHECK(attempt_no>=0),
  CONSTRAINT ck_push_history_status CHECK(status IN (''QUEUED'',''RUNNING'',''ACCEPTED'',''FAILED'',''SKIPPED''))
)');
  create_if_missing('CREATE INDEX ix_push_history_notification ON notification_push_history(notification_type,notification_id,created_at)');
  create_if_missing('CREATE INDEX ix_push_history_user ON notification_push_history(user_id,created_at)');
END;
/
