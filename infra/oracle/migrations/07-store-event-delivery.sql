-- Run as application schema after 06. Stop all application writers first.
-- Oracle DDL commits implicitly. Back up and inspect the result before restarting.
-- Additive/backfill migration; rerunnable after a partial failure.
DECLARE
  n NUMBER;
  PROCEDURE add_column_if_missing(column_name_in VARCHAR2, definition VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO n FROM all_tab_columns
    WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AND table_name='STORE_EVENTS' AND column_name=column_name_in;
    IF n=0 THEN EXECUTE IMMEDIATE 'ALTER TABLE store_events ADD (' || definition || ')'; END IF;
  END;
BEGIN
  add_column_if_missing('STORE_ID', 'store_id VARCHAR2(64 BYTE)');
  add_column_if_missing('ATTEMPTS', 'attempts NUMBER(10,0) DEFAULT 0 NOT NULL');
  add_column_if_missing('AVAILABLE_AT', 'available_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL');
  add_column_if_missing('LEASE_TOKEN', 'lease_token VARCHAR2(64 BYTE)');
  add_column_if_missing('LEASE_UNTIL', 'lease_until TIMESTAMP(6) WITH TIME ZONE');
  add_column_if_missing('LAST_ERROR', 'last_error VARCHAR2(500 BYTE)');
  -- Column does not exist when this block is first compiled on an old schema.
  EXECUTE IMMEDIATE 'UPDATE store_events e SET store_id=(SELECT s.store_id FROM store_slots s WHERE s.slot_id=e.slot_id) WHERE e.store_id IS NULL';
  COMMIT;
  SELECT COUNT(*) INTO n FROM all_tab_columns
  WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AND table_name='STORE_EVENTS' AND column_name='STORE_ID' AND nullable='Y';
  IF n>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE store_events MODIFY (store_id NOT NULL)'; END IF;
  SELECT COUNT(*) INTO n FROM all_tab_columns
  WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AND table_name='STORE_EVENTS' AND column_name='SLOT_ID' AND nullable='N';
  IF n>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE store_events MODIFY (slot_id NULL)'; END IF;
  SELECT COUNT(*) INTO n FROM all_tab_columns
  WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AND table_name='STORE_EVENTS' AND column_name='SLOT_VERSION' AND nullable='N';
  IF n>0 THEN EXECUTE IMMEDIATE 'ALTER TABLE store_events MODIFY (slot_version NULL)'; END IF;
  SELECT COUNT(*) INTO n FROM all_constraints WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AND constraint_name='FK_STORE_EVENT_SHOP';
  IF n=0 THEN EXECUTE IMMEDIATE 'ALTER TABLE store_events ADD CONSTRAINT fk_store_event_shop FOREIGN KEY(store_id) REFERENCES store_shops(store_id)'; END IF;
  SELECT COUNT(*) INTO n FROM all_indexes WHERE owner=SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AND index_name='IX_STORE_EVENT_DELIVERY';
  IF n=0 THEN EXECUTE IMMEDIATE 'CREATE INDEX ix_store_event_delivery ON store_events(status_code,available_at,lease_until)'; END IF;
END;
/
