-- DataGrip: run this entire DECLARE ... END; block as one statement while logged in as ADMIN.
-- Change app_owner to the backend DB_USERNAME (Oracle's usual unquoted name is uppercase).
-- Equivalent to migration 008, with explicit owner-qualified DDL and dictionary lookups.
DECLARE
  app_owner VARCHAR2(128) := 'BINTIME_APP';
  target_schema VARCHAR2(261);
  present NUMBER;
BEGIN
  target_schema := DBMS_ASSERT.ENQUOTE_NAME(DBMS_ASSERT.SCHEMA_NAME(app_owner), FALSE);

  -- Check both required tables before executing any DDL against the selected schema.
  SELECT COUNT(*) INTO present FROM all_tables
    WHERE owner=app_owner AND table_name IN ('USER_ACCOUNTS','NOTIFICATION_ALERTS');
  IF present<>2 THEN
    RAISE_APPLICATION_ERROR(-20001, 'Check app_owner, backend DB_USERNAME and ADMIN table privileges');
  END IF;

  SELECT COUNT(*) INTO present FROM all_tab_columns
    WHERE owner=app_owner AND table_name='USER_ACCOUNTS' AND column_name='RECOMMENDATION_OPT_IN';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE ' || target_schema || '.user_accounts ADD (recommendation_opt_in CHAR(1) DEFAULT ''N'' NOT NULL)';
  END IF;

  SELECT COUNT(*) INTO present FROM all_tab_columns
    WHERE owner=app_owner AND table_name='USER_ACCOUNTS' AND column_name='RECOMMENDATION_CONSENT_AT';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE ' || target_schema || '.user_accounts ADD (recommendation_consent_at TIMESTAMP(6) WITH TIME ZONE)';
  END IF;

  SELECT COUNT(*) INTO present FROM all_constraints
    WHERE owner=app_owner AND table_name='USER_ACCOUNTS' AND constraint_name='CK_USER_RECOMMENDATION';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE ' || target_schema || '.user_accounts ADD CONSTRAINT ck_user_recommendation CHECK
      ((recommendation_opt_in=''N'' AND recommendation_consent_at IS NULL)
       OR (recommendation_opt_in=''Y'' AND recommendation_consent_at IS NOT NULL))';
  END IF;

  SELECT COUNT(*) INTO present FROM all_tab_columns
    WHERE owner=app_owner AND table_name='NOTIFICATION_ALERTS' AND column_name='WAITLIST_ID' AND nullable='N';
  IF present>0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE ' || target_schema || '.notification_alerts MODIFY (waitlist_id NULL)';
  END IF;
END;
