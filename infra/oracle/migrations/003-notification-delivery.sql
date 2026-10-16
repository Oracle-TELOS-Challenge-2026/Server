-- Apply after the initial domain schema, search-oracle.sql and 002-notification-subscriptions.sql.
-- Existing alerts and all of their publication versions are preserved. Re-running is safe.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  present NUMBER;
  PROCEDURE add_column(column_name_in VARCHAR2, definition VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO present FROM user_tab_columns
      WHERE table_name='NOTIFICATION_ALERTS' AND column_name=column_name_in;
    IF present=0 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE notification_alerts ADD (' || definition || ')';
    END IF;
  END;
BEGIN
  add_column('STORE_ID', 'store_id VARCHAR2(64 BYTE)');
  add_column('SNAPSHOT_JSON', 'snapshot_json CLOB');
  add_column('REQUEST_JSON', 'request_json CLOB');
  -- New matching waitlists are search_intent records. Keep legacy IDs, but do not bind new IDs
  -- to search_waitlists. Current ownership, consent and version are checked through search's API.
  SELECT COUNT(*) INTO present FROM user_constraints WHERE constraint_name='FK_ALERT_WAITLIST';
  IF present>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE notification_alerts DROP CONSTRAINT fk_alert_waitlist'; END IF;
  SELECT COUNT(*) INTO present FROM user_tab_columns
    WHERE table_name='NOTIFICATION_ALERTS' AND column_name='PUBLICATION_VERSION' AND nullable='N';
  IF present>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE notification_alerts MODIFY (publication_version NULL)'; END IF;
END;
/

UPDATE notification_alerts a SET store_id=(SELECT s.store_id FROM store_slots s WHERE s.slot_id=a.slot_id)
  WHERE a.store_id IS NULL;
COMMIT;

DECLARE
  PROCEDURE create_if_missing(statement VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE statement;
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE<>-955 THEN RAISE; END IF;
  END;
BEGIN
  create_if_missing('CREATE TABLE notification_dedup (
    user_id VARCHAR2(64 BYTE) NOT NULL,
    slot_id VARCHAR2(64 BYTE) NOT NULL,
    notification_id VARCHAR2(64 BYTE) NOT NULL,
    CONSTRAINT pk_notification_dedup PRIMARY KEY(user_id,slot_id),
    CONSTRAINT fk_notification_dedup_alert FOREIGN KEY(notification_id) REFERENCES notification_alerts(notification_id))');
  create_if_missing('CREATE TABLE notification_events (
    user_id VARCHAR2(64 BYTE) NOT NULL,
    sequence_no NUMBER(19,0) NOT NULL,
    event_id VARCHAR2(64 BYTE) NOT NULL,
    event_type VARCHAR2(32 BYTE) NOT NULL,
    payload_json CLOB NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_events PRIMARY KEY(user_id,sequence_no),
    CONSTRAINT uq_notification_event_id UNIQUE(event_id),
    CONSTRAINT fk_notification_event_user FOREIGN KEY(user_id) REFERENCES user_accounts(user_id))');
  create_if_missing('CREATE TABLE notification_deliveries (
    delivery_id VARCHAR2(64 BYTE) NOT NULL,
    notification_id VARCHAR2(64 BYTE) NOT NULL,
    subscription_id VARCHAR2(64 BYTE) NOT NULL,
    status VARCHAR2(16 BYTE) NOT NULL,
    attempts NUMBER(4,0) DEFAULT 0 NOT NULL,
    available_at NUMBER(19,0) NOT NULL,
    lease_owner VARCHAR2(64 BYTE),
    error_code VARCHAR2(100 BYTE),
    accepted_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_notification_deliveries PRIMARY KEY(delivery_id),
    CONSTRAINT uq_notification_delivery UNIQUE(notification_id,subscription_id),
    CONSTRAINT fk_notification_delivery_alert FOREIGN KEY(notification_id) REFERENCES notification_alerts(notification_id),
    CONSTRAINT ck_notification_delivery_status CHECK(status IN (''QUEUED'',''RUNNING'',''ACCEPTED'',''FAILED'',''SKIPPED'')),
    CONSTRAINT ck_notification_delivery_attempts CHECK(attempts>=0))');
  create_if_missing('CREATE INDEX ix_notification_delivery_due ON notification_deliveries(status,available_at)');
  create_if_missing('CREATE INDEX ix_notification_event_age ON notification_events(created_at)');
  create_if_missing('CREATE TABLE notification_delivery_clock (
    user_id VARCHAR2(64 BYTE) NOT NULL,
    last_sent_at NUMBER(19,0) NOT NULL,
    CONSTRAINT pk_notification_delivery_clock PRIMARY KEY(user_id),
    CONSTRAINT fk_notification_clock_user FOREIGN KEY(user_id) REFERENCES user_accounts(user_id))');
END;
/

-- The lifetime user+slot ledger suppresses new duplicates without deleting historical versions.
MERGE INTO notification_dedup d USING (
  SELECT user_id,slot_id,notification_id FROM (
    SELECT user_id,slot_id,notification_id,
      ROW_NUMBER() OVER(PARTITION BY user_id,slot_id ORDER BY created_at,notification_id) AS rn
    FROM notification_alerts
  ) WHERE rn=1
) a ON (d.user_id=a.user_id AND d.slot_id=a.slot_id)
WHEN NOT MATCHED THEN INSERT(user_id,slot_id,notification_id)
  VALUES(a.user_id,a.slot_id,a.notification_id);
COMMIT;
