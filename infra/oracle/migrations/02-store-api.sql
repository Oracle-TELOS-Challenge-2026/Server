-- JDBC/IDE-compatible SQL. Connect as the application user; do not select PUBLIC.
-- SQL*Plus callers must configure SET/WHENEVER in their wrapper.
-- Apply ONCE to an existing schema created with 01ddl.sql BEFORE the store implementation.
-- Oracle DDL commits implicitly: take a backup and stop application writers first.
ALTER TABLE store_shops ADD (opening_hours_json CLOB DEFAULT '[{"dayOfWeek":"MON","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"TUE","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"WED","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"THU","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"FRI","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"SAT","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"SUN","opensAt":null,"closesAt":null,"closed":true}]' NOT NULL,
 timezone VARCHAR2(40 BYTE) DEFAULT 'Asia/Seoul' NOT NULL,
 rating_average NUMBER(3,2) DEFAULT 0 NOT NULL,
 rating_count NUMBER(10,0) DEFAULT 0 NOT NULL,
 version_no NUMBER(10,0) DEFAULT 1 NOT NULL);
ALTER TABLE store_services ADD (active_yn CHAR(1 BYTE) DEFAULT 'Y' NOT NULL, version_no NUMBER(10,0) DEFAULT 1 NOT NULL);
ALTER TABLE store_slots ADD (original_service_id VARCHAR2(64 BYTE), vacancy_original_price NUMBER(10,0), published_at TIMESTAMP(6) WITH TIME ZONE, discount_type VARCHAR2(12 BYTE), discount_value NUMBER(10,0));
ALTER TABLE store_reservations ADD (service_id VARCHAR2(64 BYTE), duration_minutes NUMBER(3,0), customer_name VARCHAR2(30 CHAR), cancel_reason_code VARCHAR2(20 BYTE), cancel_reason VARCHAR2(300 CHAR), canceled_at TIMESTAMP(6) WITH TIME ZONE);
-- A reservation can reference either legacy search_waitlists or search_intent.
-- The search public service enforces ownership and atomic fulfillment in the same transaction.
ALTER TABLE store_reservations DROP CONSTRAINT fk_reservation_waitlist;
-- Preserve historical price/time/name snapshots. Existing shops start CLOSED until their owner sets hours.
UPDATE store_reservations r SET service_id=(SELECT s.service_id FROM store_slots s WHERE s.slot_id=r.slot_id),
 duration_minutes=ROUND((CAST(SYS_EXTRACT_UTC(r.end_at) AS DATE)-CAST(SYS_EXTRACT_UTC(r.start_at) AS DATE))*1440),
 customer_name=(SELECT u.display_name FROM user_accounts u WHERE u.user_id=r.user_id);
UPDATE store_slots s SET original_service_id=(SELECT r.service_id FROM store_reservations r WHERE r.reservation_id=s.source_reservation_id),
 vacancy_original_price=(SELECT r.original_price FROM store_reservations r WHERE r.reservation_id=s.source_reservation_id),
 published_at=CASE WHEN publication_version IS NOT NULL THEN created_at ELSE NULL END
 WHERE source_type<>'REGULAR';
ALTER TABLE store_shops ADD CONSTRAINT ck_shop_hours CHECK (opening_hours_json IS JSON (STRICT));
ALTER TABLE store_shops ADD CONSTRAINT ck_shop_timezone CHECK (timezone='Asia/Seoul');
ALTER TABLE store_shops ADD CONSTRAINT ck_shop_summary CHECK (rating_average BETWEEN 0 AND 5 AND rating_count>=0 AND version_no>=1);
ALTER TABLE store_services ADD CONSTRAINT ck_service_active CHECK(active_yn IN ('Y','N') AND version_no>=1);
ALTER TABLE store_reservations MODIFY (service_id NOT NULL, duration_minutes NOT NULL, customer_name NOT NULL);
ALTER TABLE store_reservations ADD CONSTRAINT fk_reservation_service FOREIGN KEY(store_id,service_id) REFERENCES store_services(store_id,service_id);
ALTER TABLE store_reservations ADD CONSTRAINT ck_reservation_cancel CHECK(cancel_reason_code IN ('CUSTOMER_REQUEST','STORE_REQUEST','OTHER'));
CREATE TABLE store_customer_locks (
 user_id VARCHAR2(64 BYTE) NOT NULL,
 CONSTRAINT pk_store_customer_locks PRIMARY KEY(user_id),
 CONSTRAINT fk_customer_lock_user FOREIGN KEY(user_id) REFERENCES user_accounts(user_id)
);
CREATE TABLE store_idempotency (
 request_id VARCHAR2(64 BYTE) NOT NULL,
 actor_id VARCHAR2(64 BYTE) NOT NULL,
 operation VARCHAR2(200 BYTE) NOT NULL,
 key_value VARCHAR2(200 BYTE) NOT NULL,
 fingerprint VARCHAR2(64 BYTE) NOT NULL,
 result_json CLOB NOT NULL,
 http_status NUMBER(3,0) NOT NULL,
 expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
 CONSTRAINT pk_store_idempotency PRIMARY KEY(request_id),
 CONSTRAINT uq_store_idem_key UNIQUE(actor_id, operation, key_value),
 CONSTRAINT ck_store_idem_status CHECK(http_status IN (200,201)),
 CONSTRAINT ck_store_idem_json CHECK(result_json IS JSON (STRICT))
);
CREATE INDEX ix_store_idem_expiry ON store_idempotency(expires_at);
CREATE TABLE store_events (
 event_id VARCHAR2(64 BYTE) NOT NULL,
 event_type VARCHAR2(20 BYTE) NOT NULL,
 slot_id VARCHAR2(64 BYTE) NOT NULL,
 publication_version NUMBER(10,0),
 slot_version NUMBER(10,0) NOT NULL,
 payload_json CLOB NOT NULL,
 status_code VARCHAR2(10 BYTE) DEFAULT 'QUEUED' NOT NULL,
 created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
 CONSTRAINT pk_store_events PRIMARY KEY(event_id),
 CONSTRAINT fk_store_event_slot FOREIGN KEY(slot_id) REFERENCES store_slots(slot_id),
 CONSTRAINT ck_store_event_status CHECK(status_code IN ('QUEUED','PROCESSED','FAILED')),
 CONSTRAINT ck_store_event_json CHECK(payload_json IS JSON (STRICT))
);
CREATE INDEX ix_store_event_queue ON store_events(status_code,created_at);
COMMIT;

ALTER TABLE store_slots ADD CONSTRAINT ck_slot_discount CHECK (
 (discount_type IS NULL AND discount_value IS NULL)
 OR (discount_type IS NOT NULL AND discount_value IS NOT NULL AND
 ((discount_type='PERCENT' AND discount_value BETWEEN 0 AND 100)
 OR (discount_type='FIXED_PRICE' AND discount_value BETWEEN 0 AND original_price))));
