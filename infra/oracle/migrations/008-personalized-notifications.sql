-- DataGrip ADMIN 콘솔에서 파일 전체를 복사해 하나의 문장으로 실행한다.
-- 변경 대상은 백엔드 DB_USERNAME과 동일한 BINTIME_APP으로 고정한다.
-- ADMIN 소유 정보가 아닌 ALL_* 뷰의 OWNER 조건으로 이미 반영된 변경을 건너뛴다.
-- SET/WHENEVER 및 마지막 /는 사용하지 않는다. Oracle DDL은 자동 커밋된다.
-- 기존 사용자·알림·예약을 유지하며, 기존 계정의 맞춤 알림 동의는 기본 N이다.
DECLARE
  app_owner VARCHAR2(128) := 'BINTIME_APP';
  target_schema VARCHAR2(261);
  present NUMBER;
BEGIN
  target_schema := DBMS_ASSERT.ENQUOTE_NAME(DBMS_ASSERT.SCHEMA_NAME(app_owner), FALSE);

  -- 잘못된 스키마에 일부 변경부터 실행하지 않도록 두 테이블을 먼저 확인한다.
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
