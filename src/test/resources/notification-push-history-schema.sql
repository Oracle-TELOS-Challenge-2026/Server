-- Push audit history; FCM acceptance is not a device delivery receipt.
CREATE TABLE notification_push_history (
  history_id VARCHAR(64) NOT NULL,
  notification_type VARCHAR(32) NOT NULL,
  notification_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  subscription_id VARCHAR(64),
  attempt_no NUMBER(6,0) DEFAULT 0 NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_code VARCHAR(100),
  title VARCHAR(200) NOT NULL,
  body_text VARCHAR(1000),
  action_path VARCHAR(2048) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_notification_push_history PRIMARY KEY(history_id),
  CONSTRAINT ck_push_history_attempt CHECK(attempt_no>=0),
  -- H2 IN-set checks retain a closed DDL session; CASE enforces the same values.
  CONSTRAINT ck_push_history_status CHECK(CASE status WHEN 'QUEUED' THEN 1 WHEN 'RUNNING' THEN 1 WHEN 'ACCEPTED' THEN 1 WHEN 'FAILED' THEN 1 WHEN 'SKIPPED' THEN 1 ELSE 0 END=1)
);
CREATE INDEX ix_push_history_notification ON notification_push_history(notification_type,notification_id,created_at);
CREATE INDEX ix_push_history_user ON notification_push_history(user_id,created_at);
