-- Apply as the app schema before deploying personalized vacancy notifications.
-- Existing accounts remain opted out. Existing alerts and reservations are preserved.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  present NUMBER;
BEGIN
  SELECT COUNT(*) INTO present FROM user_tab_columns
    WHERE table_name='USER_ACCOUNTS' AND column_name='RECOMMENDATION_OPT_IN';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE user_accounts ADD (recommendation_opt_in CHAR(1) DEFAULT ''N'' NOT NULL)';
  END IF;
  SELECT COUNT(*) INTO present FROM user_tab_columns
    WHERE table_name='USER_ACCOUNTS' AND column_name='RECOMMENDATION_CONSENT_AT';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE user_accounts ADD (recommendation_consent_at TIMESTAMP(6) WITH TIME ZONE)';
  END IF;
  SELECT COUNT(*) INTO present FROM user_constraints WHERE constraint_name='CK_USER_RECOMMENDATION';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE user_accounts ADD CONSTRAINT ck_user_recommendation CHECK
      ((recommendation_opt_in=''N'' AND recommendation_consent_at IS NULL)
       OR (recommendation_opt_in=''Y'' AND recommendation_consent_at IS NOT NULL))';
  END IF;
  SELECT COUNT(*) INTO present FROM user_tab_columns
    WHERE table_name='NOTIFICATION_ALERTS' AND column_name='WAITLIST_ID' AND nullable='N';
  IF present>0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE notification_alerts MODIFY (waitlist_id NULL)';
  END IF;
END;
/
COMMIT;
