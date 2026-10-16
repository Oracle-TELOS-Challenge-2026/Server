-- Booking proposals v1. Apply AFTER 004-chatting.sql with chat/store writers stopped.
-- Run in BOTH local Oracle and shared Autonomous DB as the app schema owner.
-- No existing rows are deleted, rewritten, or reserved by this migration.
SET DEFINE OFF
WHENEVER SQLERROR EXIT SQL.SQLCODE

DECLARE
  present NUMBER;
BEGIN
  SELECT COUNT(*) INTO present FROM user_tab_columns
    WHERE table_name='STORE_RESERVATIONS' AND column_name='SERVICE_DESCRIPTION';
  IF present=0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE store_reservations ADD (service_description VARCHAR2(1000 CHAR))';
  END IF;
END;
/

DECLARE
  PROCEDURE create_if_missing(statement VARCHAR2) IS
  BEGIN
    EXECUTE IMMEDIATE statement;
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE<>-955 THEN RAISE; END IF;
  END;
BEGIN
  create_if_missing('CREATE TABLE chatting_booking_proposals (
    proposal_id VARCHAR2(64 BYTE) NOT NULL,
    room_id VARCHAR2(64 BYTE) NOT NULL,
    card_message_id VARCHAR2(64 BYTE) NOT NULL,
    source_message_id VARCHAR2(64 BYTE) NOT NULL,
    service_id VARCHAR2(64 BYTE) NOT NULL,
    service_description VARCHAR2(1000 CHAR) NOT NULL,
    start_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    duration_minutes NUMBER(4,0) NOT NULL,
    price NUMBER(10,0) NOT NULL,
    status_code VARCHAR2(16 BYTE) NOT NULL,
    version_no NUMBER(16,0) DEFAULT 1 NOT NULL,
    reservation_id VARCHAR2(64 BYTE),
    waitlist_id VARCHAR2(64 BYTE),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_chat_booking_proposals PRIMARY KEY (proposal_id),
    CONSTRAINT uq_chat_proposal_card UNIQUE (card_message_id),
    CONSTRAINT uq_chat_proposal_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_chat_proposal_room FOREIGN KEY (room_id) REFERENCES chatting_rooms(room_id),
    CONSTRAINT fk_chat_proposal_card FOREIGN KEY (card_message_id) REFERENCES chatting_messages(message_id),
    CONSTRAINT fk_chat_proposal_source FOREIGN KEY (source_message_id) REFERENCES chatting_messages(message_id),
    CONSTRAINT fk_chat_proposal_service FOREIGN KEY (service_id) REFERENCES store_services(service_id),
    CONSTRAINT fk_chat_proposal_reservation FOREIGN KEY (reservation_id) REFERENCES store_reservations(reservation_id),
    CONSTRAINT ck_chat_proposal_description CHECK (TRIM(service_description) IS NOT NULL),
    CONSTRAINT ck_chat_proposal_status CHECK (status_code IN (''PENDING'',''CONFIRMED'',''SUPERSEDED'',''REVIEW_REQUIRED'')),
    CONSTRAINT ck_chat_proposal_version CHECK (version_no BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_chat_proposal_duration CHECK (duration_minutes BETWEEN 15 AND 480 AND MOD(duration_minutes,5)=0),
    CONSTRAINT ck_chat_proposal_price CHECK (price BETWEEN 0 AND 10000000),
    CONSTRAINT ck_chat_proposal_times CHECK (end_at>start_at),
    CONSTRAINT ck_chat_proposal_confirmed CHECK ((status_code=''CONFIRMED'' AND reservation_id IS NOT NULL)
      OR (status_code<>''CONFIRMED'' AND reservation_id IS NULL)))');
  create_if_missing('CREATE INDEX ix_chat_proposal_room_status ON chatting_booking_proposals(room_id,status_code)');
  create_if_missing('CREATE INDEX ix_chat_proposal_source ON chatting_booking_proposals(source_message_id)');
  create_if_missing('CREATE INDEX ix_chat_proposal_service ON chatting_booking_proposals(service_id)');
  create_if_missing('CREATE INDEX ix_chat_proposal_waitlist ON chatting_booking_proposals(waitlist_id)');
END;
/

COMMENT ON TABLE chatting_booking_proposals IS 'Quoted snapshots; no time held until customer confirmation; mutate under chat room lock';
COMMENT ON COLUMN chatting_booking_proposals.updated_at IS 'Status revision timestamp; message_sequence does not advance on revision';
COMMENT ON COLUMN store_reservations.service_description IS 'Immutable custom service/options quote; NULL for legacy standard reservations';
-- waitlist_id intentionally has no legacy-only FK: store validates both search_intent and search_waitlists IDs.
