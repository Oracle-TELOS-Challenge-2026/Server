-- Chatting schema v1. Run as BINTIME_APP in BOTH local Oracle and shared Autonomous DB.
-- Existing rooms/messages and other domains are preserved. Re-running is safe.
-- Chatting must have no active writers while this migration runs.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  present NUMBER;
  PROCEDURE add_column(column_name_in VARCHAR2, definition VARCHAR2) IS
  BEGIN
    SELECT COUNT(*) INTO present FROM user_tab_columns
      WHERE table_name='CHATTING_ROOMS' AND column_name=column_name_in;
    IF present=0 THEN
      EXECUTE IMMEDIATE 'ALTER TABLE chatting_rooms ADD (' || definition || ')';
    END IF;
  END;
BEGIN
  add_column('STATUS_CODE', 'status_code VARCHAR2(9 BYTE) DEFAULT ''OPEN'' NOT NULL');
  add_column('LAST_MESSAGE_AT', 'last_message_at TIMESTAMP(6) WITH TIME ZONE');
  SELECT COUNT(*) INTO present FROM user_constraints WHERE constraint_name='CK_CHAT_ROOM_STATUS';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE chatting_rooms ADD CONSTRAINT ck_chat_room_status
      CHECK (status_code IN (''OPEN'',''READ_ONLY''))';
  END IF;
END;
/

-- Preserve message IDs, contents, sequence numbers and read cursors on every run.
UPDATE chatting_rooms r SET last_message_at=(
  SELECT m.created_at FROM chatting_messages m
  WHERE m.room_id=r.room_id AND m.message_sequence=r.last_sequence
)
WHERE r.last_message_at IS NULL AND r.last_sequence>0;
COMMIT;

DECLARE
  PROCEDURE create_if_missing(statement VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE statement;
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE<>-955 THEN RAISE; END IF;
  END;
BEGIN
  create_if_missing('CREATE TABLE chatting_read_positions (
    room_id VARCHAR2(64 BYTE) NOT NULL,
    user_id VARCHAR2(64 BYTE) NOT NULL,
    last_read_sequence NUMBER(16,0) DEFAULT 0 NOT NULL,
    read_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_chat_read_positions PRIMARY KEY (room_id,user_id),
    CONSTRAINT fk_chat_read_room FOREIGN KEY (room_id) REFERENCES chatting_rooms(room_id),
    CONSTRAINT fk_chat_read_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id),
    CONSTRAINT ck_chat_read_sequence CHECK (last_read_sequence BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_chat_read_time CHECK (last_read_sequence=0 OR read_at IS NOT NULL))');
  create_if_missing('CREATE TABLE chatting_idempotency (
    actor_id VARCHAR2(64 BYTE) NOT NULL,
    operation VARCHAR2(200 BYTE) NOT NULL,
    key_value VARCHAR2(200 BYTE) NOT NULL,
    fingerprint VARCHAR2(64 BYTE) NOT NULL,
    result_json CLOB NOT NULL,
    http_status NUMBER(3,0) NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_chat_idempotency PRIMARY KEY (actor_id,operation,key_value),
    CONSTRAINT fk_chat_idem_actor FOREIGN KEY (actor_id) REFERENCES user_accounts(user_id),
    CONSTRAINT ck_chat_idem_hash CHECK (LENGTH(fingerprint)=64),
    CONSTRAINT ck_chat_idem_status CHECK (http_status IN (200,201)),
    CONSTRAINT ck_chat_idem_json CHECK (result_json IS JSON (STRICT)))');
  create_if_missing('CREATE INDEX ix_chat_room_customer_time ON chatting_rooms(customer_id,last_message_at DESC,room_id)');
  create_if_missing('CREATE INDEX ix_chat_room_store_time ON chatting_rooms(store_id,last_message_at DESC,room_id)');
  create_if_missing('CREATE INDEX ix_chat_read_user ON chatting_read_positions(user_id)');
  create_if_missing('CREATE INDEX ix_chat_idem_expiry ON chatting_idempotency(expires_at)');
END;
/

COMMENT ON TABLE chatting_rooms IS 'chatting schema v1: one customer-store room; owner comes from store_shops';
COMMENT ON COLUMN chatting_rooms.last_sequence IS 'Allocate under a room row lock in the same transaction as the message';
COMMENT ON COLUMN chatting_rooms.last_message_at IS 'Server timestamp of the message at last_sequence; NULL for an empty room';
COMMENT ON TABLE chatting_read_positions IS 'Participant read cursor; absent row means sequence 0; service verifies membership and monotonicity';
COMMENT ON TABLE chatting_idempotency IS '24-hour HTTP replay; operation is POST: plus the concrete request path';
