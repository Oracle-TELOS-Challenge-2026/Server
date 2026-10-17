-- 새 앱 배포 전에 앱 쓰기를 중지하고 ADMIN 콘솔에서 전체를 한 문장으로 실행한다.
-- BINTIME_APP의 데이터는 보존한다. Oracle DDL은 자동 커밋되므로 먼저 백업한다.
-- 선점 없는 PENDING 신청은 여러 건 허용하고, 기존 확정 슬롯 유일성은 유지한다.
DECLARE
  app_owner VARCHAR2(128) := 'BINTIME_APP';
  target VARCHAR2(261);
  present NUMBER;
  PROCEDURE add_index(p_index_name VARCHAR2, definition VARCHAR2) IS
    found NUMBER;
  BEGIN
    SELECT COUNT(*) INTO found FROM all_indexes WHERE owner=app_owner AND index_name=UPPER(p_index_name);
    IF found=0 THEN EXECUTE IMMEDIATE definition; END IF;
  END;
BEGIN
  target := DBMS_ASSERT.ENQUOTE_NAME(DBMS_ASSERT.SCHEMA_NAME(app_owner), FALSE);
  SELECT COUNT(*) INTO present FROM all_tables
    WHERE owner=app_owner AND table_name IN ('STORE_SLOTS','STORE_RESERVATIONS');
  IF present<>2 THEN RAISE_APPLICATION_ERROR(-20001,'Check BINTIME_APP store tables'); END IF;

  SELECT COUNT(*) INTO present FROM all_tab_columns
    WHERE owner=app_owner AND table_name='STORE_SLOTS' AND column_name='INSTANT_BOOKING';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE '||target||'.store_slots ADD (instant_booking CHAR(1) DEFAULT ''Y'' NOT NULL)';
  END IF;
  SELECT COUNT(*) INTO present FROM all_constraints
    WHERE owner=app_owner AND table_name='STORE_SLOTS' AND constraint_name='CK_SLOT_INSTANT';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE '||target||'.store_slots ADD CONSTRAINT ck_slot_instant CHECK (instant_booking IN (''Y'',''N''))';
  END IF;

  SELECT COUNT(*) INTO present FROM all_constraints
    WHERE owner=app_owner AND table_name='STORE_RESERVATIONS' AND constraint_name='CK_RESERVATION_STATUS';
  IF present>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE '||target||'.store_reservations DROP CONSTRAINT ck_reservation_status'; END IF;
  EXECUTE IMMEDIATE 'ALTER TABLE '||target||'.store_reservations ADD CONSTRAINT ck_reservation_status CHECK
    (status_code IN (''PENDING'',''CONFIRMED'',''REJECTED'',''EXPIRED'',''WITHDRAWN'',''CANCELED'',''COMPLETED'',''NO_SHOW''))';

  SELECT COUNT(*) INTO present FROM all_constraints
    WHERE owner=app_owner AND table_name='STORE_RESERVATIONS' AND constraint_name='UQ_RESERVATION_WAITLIST';
  IF present>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE '||target||'.store_reservations DROP CONSTRAINT uq_reservation_waitlist'; END IF;
  add_index('UX_RESERVATION_WAITLIST_FINAL', 'CREATE UNIQUE INDEX '||target||'.ux_reservation_waitlist_final ON '||target||'.store_reservations
    (CASE WHEN status_code IN (''CONFIRMED'',''CANCELED'',''COMPLETED'',''NO_SHOW'') THEN waitlist_id END)');
  add_index('UX_RESERVATION_PENDING_USER', 'CREATE UNIQUE INDEX '||target||'.ux_reservation_pending_user ON '||target||'.store_reservations
    (CASE WHEN status_code=''PENDING'' THEN user_id END, CASE WHEN status_code=''PENDING'' THEN slot_id END)');
  add_index('IX_RESERVATION_REQUESTS', 'CREATE INDEX '||target||'.ix_reservation_requests ON '||target||'.store_reservations
    (store_id,status_code,created_at,reservation_id)');
END;
