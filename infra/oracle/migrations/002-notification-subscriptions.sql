-- Run as BINTIME_APP after the original notification_subscriptions table exists.
-- Stop the single application before applying. Existing Web Push records are retained but disabled.
-- FCM requires a fresh authenticated FID registration; legacy endpoints cannot be converted to FIDs.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  n NUMBER;
  PROCEDURE add_column(name_in VARCHAR2, definition VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns
      WHERE table_name = 'NOTIFICATION_SUBSCRIPTIONS' AND column_name = name_in;
    IF n = 0 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE notification_subscriptions ADD (' || definition || ')';
    END IF;
  END;
  PROCEDURE make_nullable(name_in VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns
      WHERE table_name = 'NOTIFICATION_SUBSCRIPTIONS' AND column_name = name_in AND nullable = 'N';
    IF n = 1 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE notification_subscriptions MODIFY (' || name_in || ' NULL)';
    END IF;
  END;
BEGIN
  add_column('FCM_PROJECT_ID', 'fcm_project_id VARCHAR2(128 BYTE)');
  add_column('INSTALLATION_ID', 'installation_id VARCHAR2(128 CHAR)');
  make_nullable('ENDPOINT');
  make_nullable('ENDPOINT_HASH');
  make_nullable('P256DH_KEY');
  make_nullable('AUTH_SECRET');
END;
/

UPDATE notification_subscriptions
  SET active_yn = 'N', updated_at = SYSTIMESTAMP
  WHERE installation_id IS NULL AND active_yn = 'Y';
COMMIT;

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'NOTIFICATION_SUBSCRIPTION_SESSIONS';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE notification_subscription_sessions (
      binding_id VARCHAR2(36 BYTE) NOT NULL,
      instance_id VARCHAR2(36 BYTE) NOT NULL,
      subscription_id VARCHAR2(64 BYTE) NOT NULL,
      CONSTRAINT pk_notification_sub_sessions PRIMARY KEY (binding_id),
      CONSTRAINT fk_notification_sub_session FOREIGN KEY (subscription_id)
        REFERENCES notification_subscriptions(subscription_id))';
  END IF;
END;
/

DECLARE
  n NUMBER;
  PROCEDURE add_index(name_in VARCHAR2, statement_in VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = name_in;
    IF n = 0 THEN EXECUTE IMMEDIATE statement_in; END IF;
  END;
BEGIN
  -- All legacy index expressions are NULL, so multiple historic Web Push records remain valid.
  add_index('UX_SUBSCRIPTION_FID_USER', 'CREATE UNIQUE INDEX ux_subscription_fid_user ON notification_subscriptions (
    CASE WHEN installation_id IS NOT NULL THEN user_id END,
    CASE WHEN installation_id IS NOT NULL THEN fcm_project_id END,
    CASE WHEN installation_id IS NOT NULL THEN installation_id END)');
  add_index('UX_SUBSCRIPTION_FID_ACTIVE', 'CREATE UNIQUE INDEX ux_subscription_fid_active ON notification_subscriptions (
    CASE WHEN active_yn = ''Y'' THEN fcm_project_id END,
    CASE WHEN active_yn = ''Y'' THEN installation_id END)');
  add_index('IX_NOTIFICATION_SUB_SESSION', 'CREATE INDEX ix_notification_sub_session
    ON notification_subscription_sessions(subscription_id, instance_id)');
END;
/

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_constraints WHERE constraint_name = 'CK_SUBSCRIPTION_FID_PAIR';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE notification_subscriptions ADD CONSTRAINT ck_subscription_fid_pair
      CHECK ((installation_id IS NULL AND fcm_project_id IS NULL)
          OR (installation_id IS NOT NULL AND fcm_project_id IS NOT NULL))';
  END IF;
END;
/

COMMENT ON COLUMN notification_subscriptions.installation_id IS 'FCM FID; registration is not delivery acknowledgement';
COMMENT ON TABLE notification_subscription_sessions IS 'Random non-bearer binding token; process instance invalidates memory-session links after restart';
