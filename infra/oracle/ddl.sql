-- BinTime unified Oracle application schema, 2026-10-16.
-- Run ONCE in an EMPTY application schema (19c+ SQL subset).
-- This file is self-contained: do not run local-setup, search DDL or migrations after it.
-- Existing databases must continue to use the incremental migrations; this is NOT an upgrade script.
-- Includes final effects of migrations 001/002/003/008 and 02/03/04/05/06/07.
-- 25 application tables; the only initial data is search_user_lock buckets 0..255.
-- Application account / tablespace / grants must already exist (see infra/README.md).
-- JDBC/IDE-compatible SQL. SQL*Plus callers should set DEFINE OFF and WHENEVER SQLERROR EXIT.
-- No DROP, sample business data, credentials, environment-specific tablespaces or account grants.
-- See docs/unified-ddl.md for source mapping, verification and migration-only data conversions.

--------------------------------------------------------------------------------
-- 1. USER: login identity, role, location and preferences in one table
--------------------------------------------------------------------------------
CREATE TABLE user_accounts (
  recommendation_opt_in CHAR(1) DEFAULT 'N' NOT NULL,
  recommendation_consent_at TIMESTAMP(6) WITH TIME ZONE,
  version_no NUMBER(19,0) DEFAULT 1 NOT NULL,
  accuracy_meters NUMBER(10,0),
  location_source VARCHAR2(6 BYTE),
  location_saved_at TIMESTAMP(6) WITH TIME ZONE,
  user_id          VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  kakao_subject    VARCHAR2(100 BYTE),
  display_name     VARCHAR2(30 CHAR) NOT NULL,
  role_code        VARCHAR2(8 BYTE) DEFAULT 'CUSTOMER' NOT NULL,
  latitude         NUMBER(9,6),
  longitude        NUMBER(10,6),
  location_at      TIMESTAMP(6) WITH TIME ZONE,
  preferences_json CLOB DEFAULT '{"categories":[],"keywords":[],"maxPrice":null}' NOT NULL,
  created_at       TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_user_accounts PRIMARY KEY (user_id),
  CONSTRAINT uq_user_kakao UNIQUE (kakao_subject),
  CONSTRAINT ck_user_name CHECK (TRIM(display_name) IS NOT NULL),
  CONSTRAINT ck_user_recommendation CHECK (
    (recommendation_opt_in='N' AND recommendation_consent_at IS NULL)
    OR (recommendation_opt_in='Y' AND recommendation_consent_at IS NOT NULL)),
  CONSTRAINT ck_user_role CHECK (role_code IN ('CUSTOMER','OWNER')),
  CONSTRAINT ck_user_location CHECK (
    (latitude IS NULL AND longitude IS NULL AND location_at IS NULL)
    OR (latitude IS NOT NULL AND longitude IS NOT NULL AND location_at IS NOT NULL
    AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
  CONSTRAINT ck_user_preferences CHECK (preferences_json IS JSON (STRICT))
);

--------------------------------------------------------------------------------
-- 2. STORE: one owner, one reservable resource per store
--------------------------------------------------------------------------------
CREATE TABLE store_shops (
  opening_hours_json CLOB DEFAULT '[{"dayOfWeek":"MON","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"TUE","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"WED","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"THU","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"FRI","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"SAT","opensAt":null,"closesAt":null,"closed":true},{"dayOfWeek":"SUN","opensAt":null,"closesAt":null,"closed":true}]' NOT NULL,
  timezone VARCHAR2(40 BYTE) DEFAULT 'Asia/Seoul' NOT NULL,
  rating_average NUMBER(3,2) DEFAULT 0 NOT NULL,
  rating_count NUMBER(10,0) DEFAULT 0 NOT NULL,
  version_no NUMBER(10,0) DEFAULT 1 NOT NULL,
  search_version NUMBER(19,0) DEFAULT 1 NOT NULL,
  store_id      VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  owner_user_id VARCHAR2(64 BYTE) NOT NULL,
  name          VARCHAR2(100 CHAR) NOT NULL,
  category_code VARCHAR2(12 BYTE) NOT NULL,
  address       VARCHAR2(300 CHAR) NOT NULL,
  latitude      NUMBER(9,6) NOT NULL,
  longitude     NUMBER(10,6) NOT NULL,
  description   VARCHAR2(1000 CHAR),
  image_url     VARCHAR2(2048 BYTE),
  created_at    TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_store_shops PRIMARY KEY (store_id),
  CONSTRAINT fk_shop_owner FOREIGN KEY (owner_user_id) REFERENCES user_accounts(user_id),
  CONSTRAINT ck_shop_name CHECK (TRIM(name) IS NOT NULL),
  CONSTRAINT ck_shop_category CHECK (category_code IN ('NAIL','HAIR','EYELASH','SKIN')),
  CONSTRAINT ck_shop_lat CHECK (latitude BETWEEN -90 AND 90),
  CONSTRAINT ck_shop_lon CHECK (longitude BETWEEN -180 AND 180)
);

--------------------------------------------------------------------------------
-- 3. STORE: owner-managed services
--------------------------------------------------------------------------------
CREATE TABLE store_services (
  active_yn CHAR(1 BYTE) DEFAULT 'Y' NOT NULL,
  version_no NUMBER(10,0) DEFAULT 1 NOT NULL,
  service_id       VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  store_id         VARCHAR2(64 BYTE) NOT NULL,
  name             VARCHAR2(100 CHAR) NOT NULL,
  duration_minutes NUMBER(3,0) NOT NULL,
  base_price       NUMBER(10,0) NOT NULL,
  CONSTRAINT pk_store_services PRIMARY KEY (service_id),
  CONSTRAINT uq_service_store UNIQUE (store_id, service_id),
  CONSTRAINT fk_service_store FOREIGN KEY (store_id) REFERENCES store_shops(store_id),
  CONSTRAINT ck_service_name CHECK (TRIM(name) IS NOT NULL),
  CONSTRAINT ck_service_duration CHECK (duration_minutes BETWEEN 15 AND 480
    AND MOD(duration_minutes,5) = 0),
  CONSTRAINT ck_service_price CHECK (base_price BETWEEN 0 AND 10000000)
);

--------------------------------------------------------------------------------
-- 4. STORE: regular slots AND cancellation/no-show tickets in the same table
-- source_reservation_id FK is added after reservations have been created.
--------------------------------------------------------------------------------
CREATE TABLE store_slots (
  instant_booking CHAR(1) DEFAULT 'Y' NOT NULL CONSTRAINT ck_slot_instant CHECK (instant_booking IN ('Y','N')),
  original_service_id VARCHAR2(64 BYTE),
  vacancy_original_price NUMBER(10,0),
  published_at TIMESTAMP(6) WITH TIME ZONE,
  discount_type VARCHAR2(12 BYTE),
  discount_value NUMBER(10,0),
  slot_id               VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  store_id              VARCHAR2(64 BYTE) NOT NULL,
  service_id            VARCHAR2(64 BYTE) NOT NULL,
  start_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  end_at                TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  available_from        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  available_until       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  original_price        NUMBER(10,0) NOT NULL,
  payable_price         NUMBER(10,0) NOT NULL,
  status_code           VARCHAR2(10 BYTE) DEFAULT 'AVAILABLE' NOT NULL,
  source_type           VARCHAR2(12 BYTE) DEFAULT 'REGULAR' NOT NULL,
  source_reservation_id VARCHAR2(64 BYTE),
  publication_version   NUMBER(10,0),
  version_no            NUMBER(10,0) DEFAULT 1 NOT NULL,
  created_at            TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_store_slots PRIMARY KEY (slot_id),
  CONSTRAINT uq_slot_store UNIQUE (slot_id, store_id),
  CONSTRAINT uq_slot_source UNIQUE (source_reservation_id),
  CONSTRAINT fk_slot_service FOREIGN KEY (store_id, service_id)
    REFERENCES store_services(store_id, service_id),
  CONSTRAINT ck_slot_time CHECK (available_from <= start_at AND start_at < end_at
    AND end_at <= available_until),
  CONSTRAINT ck_slot_price CHECK (original_price BETWEEN 0 AND 10000000
    AND payable_price BETWEEN 0 AND original_price),
  CONSTRAINT ck_slot_status CHECK (status_code IN ('DRAFT','AVAILABLE','BOOKED','CLOSED','EXPIRED')),
  CONSTRAINT ck_slot_source CHECK (
    (source_type = 'REGULAR' AND source_reservation_id IS NULL AND publication_version IS NULL)
    OR (source_type IN ('CANCELLATION','NO_SHOW') AND source_reservation_id IS NOT NULL)),
  CONSTRAINT ck_slot_publication CHECK (source_type = 'REGULAR'
    OR status_code IN ('DRAFT','EXPIRED') OR publication_version IS NOT NULL),
  CONSTRAINT ck_slot_pubver CHECK (publication_version BETWEEN 1 AND version_no),
  CONSTRAINT ck_slot_version CHECK (version_no >= 1)
);

--------------------------------------------------------------------------------
-- 5. SEARCH: confirmed structured conditions, not raw AI conversations
--------------------------------------------------------------------------------
CREATE TABLE search_waitlists (
  waitlist_id   VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  user_id       VARCHAR2(64 BYTE) NOT NULL,
  store_id      VARCHAR2(64 BYTE),
  service_id    VARCHAR2(64 BYTE),
  category_code VARCHAR2(12 BYTE) NOT NULL,
  window_start  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  window_end    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  latitude      NUMBER(9,6),
  longitude     NUMBER(10,6),
  radius_meters NUMBER(5,0),
  max_price     NUMBER(10,0),
  keywords_json CLOB DEFAULT '[]' NOT NULL,
  status_code   VARCHAR2(10 BYTE) DEFAULT 'ACTIVE' NOT NULL,
  version_no    NUMBER(10,0) DEFAULT 1 NOT NULL,
  created_at    TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_search_waitlists PRIMARY KEY (waitlist_id),
  CONSTRAINT uq_waitlist_user UNIQUE (waitlist_id, user_id),
  CONSTRAINT fk_waitlist_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id),
  CONSTRAINT fk_waitlist_store FOREIGN KEY (store_id) REFERENCES store_shops(store_id),
  CONSTRAINT fk_waitlist_service FOREIGN KEY (store_id, service_id)
    REFERENCES store_services(store_id, service_id),
  CONSTRAINT ck_waitlist_service CHECK (service_id IS NULL OR store_id IS NOT NULL),
  CONSTRAINT ck_waitlist_category CHECK (category_code IN ('NAIL','HAIR','EYELASH','SKIN')),
  CONSTRAINT ck_waitlist_time CHECK (window_start < window_end
    AND window_end <= window_start + INTERVAL '7' DAY),
  CONSTRAINT ck_waitlist_location CHECK (
    (store_id IS NOT NULL AND latitude IS NULL AND longitude IS NULL AND radius_meters IS NULL)
    OR (latitude IS NOT NULL AND longitude IS NOT NULL AND radius_meters IS NOT NULL
    AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
    AND radius_meters BETWEEN 100 AND 10000)),
  CONSTRAINT ck_waitlist_price CHECK (max_price BETWEEN 0 AND 10000000),
  CONSTRAINT ck_waitlist_keywords CHECK (keywords_json IS JSON (STRICT)),
  CONSTRAINT ck_waitlist_status CHECK (status_code IN ('ACTIVE','FULFILLED','CANCELED','EXPIRED')),
  CONSTRAINT ck_waitlist_version CHECK (version_no >= 1)
);

--------------------------------------------------------------------------------
-- 6. STORE: reservation snapshots survive later publication/price changes
--------------------------------------------------------------------------------
CREATE TABLE store_reservations (
  service_id VARCHAR2(64 BYTE),
  duration_minutes NUMBER(3,0),
  customer_name VARCHAR2(30 CHAR),
  cancel_reason_code VARCHAR2(20 BYTE),
  cancel_reason VARCHAR2(300 CHAR),
  canceled_at TIMESTAMP(6) WITH TIME ZONE,
  reservation_id  VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  user_id         VARCHAR2(64 BYTE) NOT NULL,
  store_id        VARCHAR2(64 BYTE) NOT NULL,
  slot_id         VARCHAR2(64 BYTE) NOT NULL,
  waitlist_id     VARCHAR2(64 BYTE),
  service_name    VARCHAR2(100 CHAR) NOT NULL,
  start_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  end_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  original_price  NUMBER(10,0) NOT NULL,
  payable_price   NUMBER(10,0) NOT NULL,
  status_code     VARCHAR2(10 BYTE) DEFAULT 'CONFIRMED' NOT NULL,
  version_no      NUMBER(10,0) DEFAULT 1 NOT NULL,
  created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_store_reservations PRIMARY KEY (reservation_id),
  CONSTRAINT uq_reservation_store UNIQUE (reservation_id, store_id),
  CONSTRAINT fk_reservation_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id),
  CONSTRAINT fk_reservation_slot FOREIGN KEY (slot_id, store_id) REFERENCES store_slots(slot_id, store_id),
  CONSTRAINT ck_reservation_time CHECK (start_at < end_at),
  CONSTRAINT ck_reservation_price CHECK (original_price BETWEEN 0 AND 10000000
    AND payable_price BETWEEN 0 AND original_price),
  CONSTRAINT ck_reservation_status CHECK (status_code IN ('PENDING','CONFIRMED','REJECTED','EXPIRED','WITHDRAWN','CANCELED','COMPLETED','NO_SHOW')),
  CONSTRAINT ck_reservation_version CHECK (version_no >= 1)
);

CREATE UNIQUE INDEX ux_reservation_confirmed_slot ON store_reservations (
                                                                         CASE WHEN status_code = 'CONFIRMED' THEN slot_id END
    );

CREATE UNIQUE INDEX ux_reservation_waitlist_final ON store_reservations (
  CASE WHEN status_code IN ('CONFIRMED','CANCELED','COMPLETED','NO_SHOW') THEN waitlist_id END);
CREATE UNIQUE INDEX ux_reservation_pending_user ON store_reservations (
  CASE WHEN status_code='PENDING' THEN user_id END,
  CASE WHEN status_code='PENDING' THEN slot_id END);
CREATE INDEX ix_reservation_requests ON store_reservations(store_id,status_code,created_at,reservation_id);

ALTER TABLE store_slots ADD CONSTRAINT fk_slot_source
    FOREIGN KEY (source_reservation_id, store_id)
        REFERENCES store_reservations(reservation_id, store_id);

--------------------------------------------------------------------------------
-- 7. NOTIFICATION: saved alerts, FCM snapshots and aggregate delivery status
--------------------------------------------------------------------------------
CREATE TABLE notification_alerts (
  store_id VARCHAR2(64 BYTE),
  snapshot_json CLOB,
  request_json CLOB,
  notification_id    VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  user_id            VARCHAR2(64 BYTE) NOT NULL,
  waitlist_id        VARCHAR2(64 BYTE),
  slot_id            VARCHAR2(64 BYTE) NOT NULL,
  publication_version NUMBER(10,0),
  title              VARCHAR2(100 CHAR) NOT NULL,
  body_text          VARCHAR2(500 CHAR) NOT NULL,
  expires_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  push_status        VARCHAR2(10 BYTE) DEFAULT 'PENDING' NOT NULL,
  push_attempts      NUMBER(4,0) DEFAULT 0 NOT NULL,
  push_attempted_at  TIMESTAMP(6) WITH TIME ZONE,
  push_accepted_at   TIMESTAMP(6) WITH TIME ZONE,
  push_error_code    VARCHAR2(100 BYTE),
  read_at            TIMESTAMP(6) WITH TIME ZONE,
  created_at         TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_notification_alerts PRIMARY KEY (notification_id),
  CONSTRAINT uq_alert_match UNIQUE (user_id, slot_id, publication_version),
  CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id),
  CONSTRAINT fk_alert_slot FOREIGN KEY (slot_id) REFERENCES store_slots(slot_id),
  CONSTRAINT ck_alert_pubver CHECK (publication_version >= 1),
  CONSTRAINT ck_alert_expires CHECK (expires_at > created_at),
  CONSTRAINT ck_alert_push_status CHECK (
    push_status IN ('PENDING','SENT','PARTIAL','FAILED','NO_SUB','EXPIRED')),
  CONSTRAINT ck_alert_attempts CHECK (push_attempts >= 0)
);

--------------------------------------------------------------------------------
-- 8. NOTIFICATION: FCM registrations; nullable legacy Web Push fields are retained
--------------------------------------------------------------------------------
CREATE TABLE notification_subscriptions (
  fcm_project_id VARCHAR2(128 BYTE),
  installation_id VARCHAR2(128 CHAR),
  subscription_id VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  user_id         VARCHAR2(64 BYTE) NOT NULL,
  endpoint        VARCHAR2(2048 BYTE),
  endpoint_hash   RAW(32),
  p256dh_key      VARCHAR2(200 BYTE),
  auth_secret     VARCHAR2(100 BYTE),
  active_yn       CHAR(1 BYTE) DEFAULT 'Y' NOT NULL,
  expires_at      TIMESTAMP(6) WITH TIME ZONE,
  created_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at      TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_notification_subscriptions PRIMARY KEY (subscription_id),
  CONSTRAINT fk_subscription_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id),
  CONSTRAINT ck_subscription_endpoint CHECK (SUBSTR(endpoint,1,8) = 'https://'),
  CONSTRAINT ck_subscription_hash CHECK (LENGTH(RAWTOHEX(endpoint_hash)) = 64),
  CONSTRAINT ck_subscription_active CHECK (active_yn IN ('Y','N'))
);

-- An ACTIVE endpoint belongs to one account; deactivation permits reassignment.
CREATE UNIQUE INDEX ux_subscription_endpoint ON notification_subscriptions (
                                                                            CASE WHEN active_yn = 'Y' THEN endpoint_hash END
    );

--------------------------------------------------------------------------------
-- 9. CHATTING: one customer-store room; store owner is the other participant
--------------------------------------------------------------------------------
CREATE TABLE chatting_rooms (
  room_id       VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  customer_id   VARCHAR2(64 BYTE) NOT NULL,
  store_id      VARCHAR2(64 BYTE) NOT NULL,
  last_sequence NUMBER(16,0) DEFAULT 0 NOT NULL,
  created_at    TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_chatting_rooms PRIMARY KEY (room_id),
  CONSTRAINT uq_room_pair UNIQUE (customer_id, store_id),
  CONSTRAINT fk_room_customer FOREIGN KEY (customer_id) REFERENCES user_accounts(user_id),
  CONSTRAINT fk_room_store FOREIGN KEY (store_id) REFERENCES store_shops(store_id),
  CONSTRAINT ck_room_sequence CHECK (last_sequence BETWEEN 0 AND 9007199254740991)
);

--------------------------------------------------------------------------------
-- 10. CHATTING: text messages; no attachments/read receipts/member table
--------------------------------------------------------------------------------
CREATE TABLE chatting_messages (
  message_id       VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
  room_id          VARCHAR2(64 BYTE) NOT NULL,
  sender_id        VARCHAR2(64 BYTE) NOT NULL,
  message_sequence NUMBER(16,0) NOT NULL,
  client_message_id VARCHAR2(64 BYTE) NOT NULL,
  content_text     CLOB NOT NULL,
  created_at       TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_chatting_messages PRIMARY KEY (message_id),
  CONSTRAINT uq_message_sequence UNIQUE (room_id, message_sequence),
  CONSTRAINT uq_message_client UNIQUE (room_id, sender_id, client_message_id),
  CONSTRAINT fk_message_room FOREIGN KEY (room_id) REFERENCES chatting_rooms(room_id),
  CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES user_accounts(user_id),
  CONSTRAINT ck_message_sequence CHECK (message_sequence BETWEEN 1 AND 9007199254740991)
);

-- Core list, timetable and FK access paths only.
CREATE INDEX ix_shop_owner ON store_shops(owner_user_id);
CREATE INDEX ix_slot_service ON store_slots(store_id, service_id);
CREATE INDEX ix_slot_public ON store_slots(store_id, status_code, start_at);
CREATE INDEX ix_slot_source ON store_slots(source_reservation_id, store_id);
CREATE INDEX ix_waitlist_user ON search_waitlists(user_id, status_code, created_at);
CREATE INDEX ix_waitlist_service ON search_waitlists(store_id, service_id);
CREATE INDEX ix_waitlist_matching ON search_waitlists(status_code, category_code, window_end);
CREATE INDEX ix_reservation_user ON store_reservations(user_id, status_code, start_at);
CREATE INDEX ix_reservation_slot ON store_reservations(slot_id, store_id);
CREATE INDEX ix_reservation_waitlist ON store_reservations(waitlist_id, user_id);
CREATE INDEX ix_reservation_timetable ON store_reservations(store_id, status_code, start_at, end_at);
CREATE INDEX ix_alert_user ON notification_alerts(user_id, created_at);
CREATE INDEX ix_alert_push ON notification_alerts(push_status, created_at);
CREATE INDEX ix_subscription_user ON notification_subscriptions(user_id, active_yn);
CREATE INDEX ix_alert_waitlist ON notification_alerts(waitlist_id, user_id);
CREATE INDEX ix_alert_slot ON notification_alerts(slot_id);
CREATE INDEX ix_room_store ON chatting_rooms(store_id);
CREATE INDEX ix_message_sender ON chatting_messages(sender_id);

COMMENT ON TABLE store_slots IS '해커톤: 일반 슬롯과 취소표 통합. DRAFT 발행 시 AVAILABLE';
COMMENT ON TABLE search_waitlists IS 'search: 자연어에서 추출하고 사용자가 확인한 예약 희망 조건';
COMMENT ON TABLE notification_alerts IS '필수 웹 푸시 알림. 저장 후 발송하며 집계 상태와 시도 횟수 기록';
COMMENT ON COLUMN store_slots.source_reservation_id IS '취소/노쇼 원예약. 원예약당 취소표 1개';
COMMENT ON COLUMN store_slots.available_until IS '노쇼 시작시각 조정 때도 유지하는 원래 빈 구간 종료';
COMMENT ON COLUMN notification_alerts.waitlist_id IS '대표 예약 대기 ID. 대기 없는 맞춤 알림은 NULL';
COMMENT ON TABLE notification_subscriptions IS 'notification: FCM FID 구독과 하위 호환용 Web Push 필드';
COMMENT ON COLUMN notification_alerts.push_status IS 'SENT는 푸시 서비스 접수 성공, 기기 도착/읽음 보장은 아님';
COMMENT ON COLUMN notification_alerts.expires_at IS '제안 유효기한. 일반적으로 슬롯 시작 시각';
COMMENT ON COLUMN notification_subscriptions.auth_secret IS '브라우저 PushSubscription auth 값. VAPID 개인키가 아님';

-- Store API persistence extensions (included for a fresh schema).
ALTER TABLE store_shops ADD CONSTRAINT ck_shop_hours CHECK (opening_hours_json IS JSON (STRICT));
ALTER TABLE store_shops ADD CONSTRAINT ck_shop_search_version CHECK (search_version>=1);
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
  store_id VARCHAR2(64 BYTE) NOT NULL,
  slot_id VARCHAR2(64 BYTE),
  publication_version NUMBER(10,0),
  slot_version NUMBER(10,0),
  payload_json CLOB NOT NULL,
  status_code VARCHAR2(10 BYTE) DEFAULT 'QUEUED' NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  attempts NUMBER(10,0) DEFAULT 0 NOT NULL,
  available_at TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  lease_token VARCHAR2(64 BYTE),
  lease_until TIMESTAMP(6) WITH TIME ZONE,
  last_error VARCHAR2(500 BYTE),
  CONSTRAINT pk_store_events PRIMARY KEY(event_id),
  CONSTRAINT fk_store_event_shop FOREIGN KEY(store_id) REFERENCES store_shops(store_id),
  CONSTRAINT fk_store_event_slot FOREIGN KEY(slot_id) REFERENCES store_slots(slot_id),
  CONSTRAINT ck_store_event_status CHECK(status_code IN ('QUEUED','PROCESSED','FAILED')),
  CONSTRAINT ck_store_event_json CHECK(payload_json IS JSON (STRICT))
);
CREATE INDEX ix_store_event_delivery ON store_events(status_code,available_at,lease_until);

ALTER TABLE store_slots ADD CONSTRAINT ck_slot_discount CHECK (
 (discount_type IS NULL AND discount_value IS NULL)
 OR (discount_type IS NOT NULL AND discount_value IS NOT NULL AND
 ((discount_type='PERCENT' AND discount_value BETWEEN 0 AND 100)
 OR (discount_type='FIXED_PRICE' AND discount_value BETWEEN 0 AND original_price))));

-- Retained by migration 02; migration 07 adds a second delivery access path.
CREATE INDEX ix_store_event_queue ON store_events(status_code,created_at);

-- User profile: migration 001, with no historical rows to backfill on fresh install.
ALTER TABLE user_accounts ADD CONSTRAINT ck_user_version CHECK (version_no >= 1);
ALTER TABLE user_accounts ADD CONSTRAINT ck_user_accuracy CHECK (accuracy_meters IS NULL OR accuracy_meters >= 0);
ALTER TABLE user_accounts ADD CONSTRAINT ck_user_location_details CHECK (
 (latitude IS NULL AND longitude IS NULL AND location_at IS NULL
  AND accuracy_meters IS NULL AND location_source IS NULL AND location_saved_at IS NULL)
 OR (latitude IS NOT NULL AND longitude IS NOT NULL AND location_at IS NOT NULL
  AND location_source IS NOT NULL AND location_source IN ('DEVICE','MANUAL')
  AND location_saved_at IS NOT NULL));

-- FCM subscriptions and process/session binding: migration 002.
CREATE TABLE notification_subscription_sessions (
  binding_id VARCHAR2(36 BYTE) NOT NULL,
  instance_id VARCHAR2(36 BYTE) NOT NULL,
  subscription_id VARCHAR2(64 BYTE) NOT NULL,
  CONSTRAINT pk_notification_sub_sessions PRIMARY KEY (binding_id),
  CONSTRAINT fk_notification_sub_session FOREIGN KEY (subscription_id)
    REFERENCES notification_subscriptions(subscription_id)
);
CREATE UNIQUE INDEX ux_subscription_fid_user ON notification_subscriptions (
    CASE WHEN installation_id IS NOT NULL THEN user_id END,
    CASE WHEN installation_id IS NOT NULL THEN fcm_project_id END,
    CASE WHEN installation_id IS NOT NULL THEN installation_id END);
CREATE UNIQUE INDEX ux_subscription_fid_active ON notification_subscriptions (
    CASE WHEN active_yn = 'Y' THEN fcm_project_id END,
    CASE WHEN active_yn = 'Y' THEN installation_id END);
CREATE INDEX ix_notification_sub_session
    ON notification_subscription_sessions(subscription_id, instance_id);
ALTER TABLE notification_subscriptions ADD CONSTRAINT ck_subscription_fid_pair
 CHECK ((installation_id IS NULL AND fcm_project_id IS NULL)
 OR (installation_id IS NOT NULL AND fcm_project_id IS NOT NULL));
COMMENT ON COLUMN notification_subscriptions.installation_id IS 'FCM FID; registration is not delivery acknowledgement';
COMMENT ON TABLE notification_subscription_sessions IS 'Random non-bearer binding token; process instance invalidates memory-session links after restart';

-- Notification deduplication, SSE log and durable delivery: migration 003.
CREATE TABLE notification_dedup (
  user_id VARCHAR2(64 BYTE) NOT NULL,
  slot_id VARCHAR2(64 BYTE) NOT NULL,
  notification_id VARCHAR2(64 BYTE) NOT NULL,
  CONSTRAINT pk_notification_dedup PRIMARY KEY(user_id,slot_id),
  CONSTRAINT fk_notification_dedup_alert FOREIGN KEY(notification_id) REFERENCES notification_alerts(notification_id)
);
CREATE TABLE notification_events (
  user_id VARCHAR2(64 BYTE) NOT NULL,
  sequence_no NUMBER(19,0) NOT NULL,
  event_id VARCHAR2(64 BYTE) NOT NULL,
  event_type VARCHAR2(32 BYTE) NOT NULL,
  payload_json CLOB NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_notification_events PRIMARY KEY(user_id,sequence_no),
  CONSTRAINT uq_notification_event_id UNIQUE(event_id),
  CONSTRAINT fk_notification_event_user FOREIGN KEY(user_id) REFERENCES user_accounts(user_id)
);
CREATE TABLE notification_deliveries (
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
  CONSTRAINT ck_notification_delivery_status CHECK(status IN ('QUEUED','RUNNING','ACCEPTED','FAILED','SKIPPED')),
  CONSTRAINT ck_notification_delivery_attempts CHECK(attempts>=0)
);
CREATE INDEX ix_notification_delivery_due ON notification_deliveries(status,available_at);
CREATE INDEX ix_notification_event_age ON notification_events(created_at);
CREATE TABLE notification_delivery_clock (
  user_id VARCHAR2(64 BYTE) NOT NULL,
  last_sent_at NUMBER(19,0) NOT NULL,
  CONSTRAINT pk_notification_delivery_clock PRIMARY KEY(user_id),
  CONSTRAINT fk_notification_clock_user FOREIGN KEY(user_id) REFERENCES user_accounts(user_id)
);

-- Search v2 final schema: includes migrations 03/04/05 results, not obsolete v1 tables.
-- Search v2: NEW EMPTY schema only. Existing v1 schemas use migration 04 and 05.
-- Plain Oracle SQL for IDE/JDBC. Run as the application schema, never PUBLIC.
-- Times are epoch milliseconds. COMMIT is explicit for lock seed rows.

-- 1. Serialize per-user changes, including the very first waitlist registration.
CREATE TABLE search_user_lock (
  bucket_id NUMBER(10) PRIMARY KEY,
  CONSTRAINT search_lock_range CHECK (bucket_id BETWEEN 0 AND 255)
);

-- 2. Only the current conditions and their consent are retained.
CREATE TABLE search_intent (
  id VARCHAR2(64) PRIMARY KEY,
  user_id VARCHAR2(64) NOT NULL,
  source VARCHAR2(32) NOT NULL,
  conditions_json CLOB NOT NULL,
  status VARCHAR2(16) NOT NULL,
  resource_version NUMBER(19) NOT NULL,
  created_at NUMBER(19) NOT NULL,
  consent_at NUMBER(19) NOT NULL,
  consent_version NUMBER(19) NOT NULL,
  window_end NUMBER(19) NOT NULL,
  reservation_id VARCHAR2(64),
  CONSTRAINT search_intent_status CHECK (status='ACTIVE' OR status='CANCELED' OR status='EXPIRED' OR status='FULFILLED'),
  CONSTRAINT search_intent_version CHECK (resource_version >= 1 AND consent_version >= 1),
  CONSTRAINT search_intent_consent CHECK (
    status <> 'ACTIVE' OR (consent_at > 0 AND consent_version = resource_version))
);
CREATE INDEX search_intent_user ON search_intent(user_id,status);
CREATE INDEX search_intent_expiry ON search_intent(status,window_end);

-- 3. Original HTTP response replay is distinct from background task deduplication.
CREATE TABLE search_idempotency (
  user_id VARCHAR2(64) NOT NULL,
  operation VARCHAR2(16) NOT NULL,
  idem_key VARCHAR2(128) NOT NULL,
  request_hash VARCHAR2(64) NOT NULL,
  response_json CLOB NOT NULL,
  expires_at NUMBER(19) NOT NULL,
  PRIMARY KEY(user_id,operation,idem_key)
);

-- 4. Durable outbox/worker queue, including S-08 event identity and public job ID.
-- SLOT rows must be retained while event deduplication is required.
-- payload for INDEX/STORE_VECTOR must carry a committed source snapshot or
-- a reference that can retrieve the current store document (no Oracle document mirror).
CREATE TABLE search_work (
  id VARCHAR2(64) PRIMARY KEY,
  kind VARCHAR2(16) NOT NULL,
  payload CLOB NOT NULL,
  status VARCHAR2(16) NOT NULL,
  attempts NUMBER(10) NOT NULL,
  available_at NUMBER(19) NOT NULL,
  lease_owner VARCHAR2(64),
  last_error VARCHAR2(256),
  event_id VARCHAR2(64),
  job_id VARCHAR2(64),
  slot_id VARCHAR2(64),
  publication_version NUMBER(19),
  CONSTRAINT search_work_event_uq UNIQUE(event_id),
  CONSTRAINT search_work_job_uq UNIQUE(job_id),
  CONSTRAINT search_work_status CHECK (status='QUEUED' OR status='RUNNING' OR status='SUCCEEDED' OR status='FAILED'),
  CONSTRAINT search_work_attempts CHECK (attempts >= 0),
  CONSTRAINT search_work_publication CHECK (publication_version IS NULL OR publication_version >= 1),
  CONSTRAINT search_work_slot CHECK (
    (kind = 'SLOT' AND event_id IS NOT NULL AND job_id IS NOT NULL AND slot_id IS NOT NULL)
    OR (kind <> 'SLOT' AND event_id IS NULL AND job_id IS NULL
    AND slot_id IS NULL AND publication_version IS NULL))
);
CREATE INDEX search_work_ready ON search_work(status,available_at);

-- 5. A proposal is neither a slot hold nor a confirmed reservation.
CREATE TABLE search_offer (
  intent_id VARCHAR2(64) NOT NULL,
  intent_version NUMBER(19) NOT NULL,
  slot_id VARCHAR2(64) NOT NULL,
  publication_version NUMBER(19),
  PRIMARY KEY(intent_id,intent_version,slot_id)
);

-- 6. Keep this row even when preferences are empty: revision and lease survive.
-- revision = current preference revision; source_version = cached vector revision.
-- On empty input, clear cache columns as a group; do not delete the state row.
CREATE TABLE search_user_vector (
  user_id VARCHAR2(64) PRIMARY KEY,
  revision NUMBER(19) DEFAULT 0 NOT NULL,
  source_version NUMBER(19),
  input_hash VARCHAR2(64),
  model VARCHAR2(64),
  dimensions NUMBER(10),
  vector_json CLOB,
  lease_owner VARCHAR2(64),
  lease_until NUMBER(19),
  CONSTRAINT search_uv_revision CHECK (revision >= 0),
  CONSTRAINT search_uv_lease CHECK (
    (lease_owner IS NULL AND lease_until IS NULL)
    OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
  CONSTRAINT search_uv_cache CHECK (
    (source_version IS NULL AND input_hash IS NULL AND model IS NULL
    AND dimensions IS NULL AND vector_json IS NULL)
    OR (source_version IS NOT NULL AND source_version >= 0 AND input_hash IS NOT NULL
    AND model IS NOT NULL AND dimensions IS NOT NULL
    AND dimensions = 1536 AND vector_json IS NOT NULL))
);

-- 7. Cache the embedding only. Store owns shop/service/slot source data;
-- Elasticsearch owns the derived search document.
CREATE TABLE search_store_vector (
  store_id VARCHAR2(64) PRIMARY KEY,
  source_version NUMBER(19) NOT NULL,
  input_hash VARCHAR2(64) NOT NULL,
  model VARCHAR2(64) NOT NULL,
  dimensions NUMBER(10) NOT NULL,
  vector_json CLOB NOT NULL,
  CONSTRAINT search_sv_version CHECK (source_version >= 0),
  CONSTRAINT search_sv_dimensions CHECK (dimensions = 1536)
);

INSERT INTO search_user_lock(bucket_id) VALUES(0);
INSERT INTO search_user_lock(bucket_id) VALUES(1);
INSERT INTO search_user_lock(bucket_id) VALUES(2);
INSERT INTO search_user_lock(bucket_id) VALUES(3);
INSERT INTO search_user_lock(bucket_id) VALUES(4);
INSERT INTO search_user_lock(bucket_id) VALUES(5);
INSERT INTO search_user_lock(bucket_id) VALUES(6);
INSERT INTO search_user_lock(bucket_id) VALUES(7);
INSERT INTO search_user_lock(bucket_id) VALUES(8);
INSERT INTO search_user_lock(bucket_id) VALUES(9);
INSERT INTO search_user_lock(bucket_id) VALUES(10);
INSERT INTO search_user_lock(bucket_id) VALUES(11);
INSERT INTO search_user_lock(bucket_id) VALUES(12);
INSERT INTO search_user_lock(bucket_id) VALUES(13);
INSERT INTO search_user_lock(bucket_id) VALUES(14);
INSERT INTO search_user_lock(bucket_id) VALUES(15);
INSERT INTO search_user_lock(bucket_id) VALUES(16);
INSERT INTO search_user_lock(bucket_id) VALUES(17);
INSERT INTO search_user_lock(bucket_id) VALUES(18);
INSERT INTO search_user_lock(bucket_id) VALUES(19);
INSERT INTO search_user_lock(bucket_id) VALUES(20);
INSERT INTO search_user_lock(bucket_id) VALUES(21);
INSERT INTO search_user_lock(bucket_id) VALUES(22);
INSERT INTO search_user_lock(bucket_id) VALUES(23);
INSERT INTO search_user_lock(bucket_id) VALUES(24);
INSERT INTO search_user_lock(bucket_id) VALUES(25);
INSERT INTO search_user_lock(bucket_id) VALUES(26);
INSERT INTO search_user_lock(bucket_id) VALUES(27);
INSERT INTO search_user_lock(bucket_id) VALUES(28);
INSERT INTO search_user_lock(bucket_id) VALUES(29);
INSERT INTO search_user_lock(bucket_id) VALUES(30);
INSERT INTO search_user_lock(bucket_id) VALUES(31);
INSERT INTO search_user_lock(bucket_id) VALUES(32);
INSERT INTO search_user_lock(bucket_id) VALUES(33);
INSERT INTO search_user_lock(bucket_id) VALUES(34);
INSERT INTO search_user_lock(bucket_id) VALUES(35);
INSERT INTO search_user_lock(bucket_id) VALUES(36);
INSERT INTO search_user_lock(bucket_id) VALUES(37);
INSERT INTO search_user_lock(bucket_id) VALUES(38);
INSERT INTO search_user_lock(bucket_id) VALUES(39);
INSERT INTO search_user_lock(bucket_id) VALUES(40);
INSERT INTO search_user_lock(bucket_id) VALUES(41);
INSERT INTO search_user_lock(bucket_id) VALUES(42);
INSERT INTO search_user_lock(bucket_id) VALUES(43);
INSERT INTO search_user_lock(bucket_id) VALUES(44);
INSERT INTO search_user_lock(bucket_id) VALUES(45);
INSERT INTO search_user_lock(bucket_id) VALUES(46);
INSERT INTO search_user_lock(bucket_id) VALUES(47);
INSERT INTO search_user_lock(bucket_id) VALUES(48);
INSERT INTO search_user_lock(bucket_id) VALUES(49);
INSERT INTO search_user_lock(bucket_id) VALUES(50);
INSERT INTO search_user_lock(bucket_id) VALUES(51);
INSERT INTO search_user_lock(bucket_id) VALUES(52);
INSERT INTO search_user_lock(bucket_id) VALUES(53);
INSERT INTO search_user_lock(bucket_id) VALUES(54);
INSERT INTO search_user_lock(bucket_id) VALUES(55);
INSERT INTO search_user_lock(bucket_id) VALUES(56);
INSERT INTO search_user_lock(bucket_id) VALUES(57);
INSERT INTO search_user_lock(bucket_id) VALUES(58);
INSERT INTO search_user_lock(bucket_id) VALUES(59);
INSERT INTO search_user_lock(bucket_id) VALUES(60);
INSERT INTO search_user_lock(bucket_id) VALUES(61);
INSERT INTO search_user_lock(bucket_id) VALUES(62);
INSERT INTO search_user_lock(bucket_id) VALUES(63);
INSERT INTO search_user_lock(bucket_id) VALUES(64);
INSERT INTO search_user_lock(bucket_id) VALUES(65);
INSERT INTO search_user_lock(bucket_id) VALUES(66);
INSERT INTO search_user_lock(bucket_id) VALUES(67);
INSERT INTO search_user_lock(bucket_id) VALUES(68);
INSERT INTO search_user_lock(bucket_id) VALUES(69);
INSERT INTO search_user_lock(bucket_id) VALUES(70);
INSERT INTO search_user_lock(bucket_id) VALUES(71);
INSERT INTO search_user_lock(bucket_id) VALUES(72);
INSERT INTO search_user_lock(bucket_id) VALUES(73);
INSERT INTO search_user_lock(bucket_id) VALUES(74);
INSERT INTO search_user_lock(bucket_id) VALUES(75);
INSERT INTO search_user_lock(bucket_id) VALUES(76);
INSERT INTO search_user_lock(bucket_id) VALUES(77);
INSERT INTO search_user_lock(bucket_id) VALUES(78);
INSERT INTO search_user_lock(bucket_id) VALUES(79);
INSERT INTO search_user_lock(bucket_id) VALUES(80);
INSERT INTO search_user_lock(bucket_id) VALUES(81);
INSERT INTO search_user_lock(bucket_id) VALUES(82);
INSERT INTO search_user_lock(bucket_id) VALUES(83);
INSERT INTO search_user_lock(bucket_id) VALUES(84);
INSERT INTO search_user_lock(bucket_id) VALUES(85);
INSERT INTO search_user_lock(bucket_id) VALUES(86);
INSERT INTO search_user_lock(bucket_id) VALUES(87);
INSERT INTO search_user_lock(bucket_id) VALUES(88);
INSERT INTO search_user_lock(bucket_id) VALUES(89);
INSERT INTO search_user_lock(bucket_id) VALUES(90);
INSERT INTO search_user_lock(bucket_id) VALUES(91);
INSERT INTO search_user_lock(bucket_id) VALUES(92);
INSERT INTO search_user_lock(bucket_id) VALUES(93);
INSERT INTO search_user_lock(bucket_id) VALUES(94);
INSERT INTO search_user_lock(bucket_id) VALUES(95);
INSERT INTO search_user_lock(bucket_id) VALUES(96);
INSERT INTO search_user_lock(bucket_id) VALUES(97);
INSERT INTO search_user_lock(bucket_id) VALUES(98);
INSERT INTO search_user_lock(bucket_id) VALUES(99);
INSERT INTO search_user_lock(bucket_id) VALUES(100);
INSERT INTO search_user_lock(bucket_id) VALUES(101);
INSERT INTO search_user_lock(bucket_id) VALUES(102);
INSERT INTO search_user_lock(bucket_id) VALUES(103);
INSERT INTO search_user_lock(bucket_id) VALUES(104);
INSERT INTO search_user_lock(bucket_id) VALUES(105);
INSERT INTO search_user_lock(bucket_id) VALUES(106);
INSERT INTO search_user_lock(bucket_id) VALUES(107);
INSERT INTO search_user_lock(bucket_id) VALUES(108);
INSERT INTO search_user_lock(bucket_id) VALUES(109);
INSERT INTO search_user_lock(bucket_id) VALUES(110);
INSERT INTO search_user_lock(bucket_id) VALUES(111);
INSERT INTO search_user_lock(bucket_id) VALUES(112);
INSERT INTO search_user_lock(bucket_id) VALUES(113);
INSERT INTO search_user_lock(bucket_id) VALUES(114);
INSERT INTO search_user_lock(bucket_id) VALUES(115);
INSERT INTO search_user_lock(bucket_id) VALUES(116);
INSERT INTO search_user_lock(bucket_id) VALUES(117);
INSERT INTO search_user_lock(bucket_id) VALUES(118);
INSERT INTO search_user_lock(bucket_id) VALUES(119);
INSERT INTO search_user_lock(bucket_id) VALUES(120);
INSERT INTO search_user_lock(bucket_id) VALUES(121);
INSERT INTO search_user_lock(bucket_id) VALUES(122);
INSERT INTO search_user_lock(bucket_id) VALUES(123);
INSERT INTO search_user_lock(bucket_id) VALUES(124);
INSERT INTO search_user_lock(bucket_id) VALUES(125);
INSERT INTO search_user_lock(bucket_id) VALUES(126);
INSERT INTO search_user_lock(bucket_id) VALUES(127);
INSERT INTO search_user_lock(bucket_id) VALUES(128);
INSERT INTO search_user_lock(bucket_id) VALUES(129);
INSERT INTO search_user_lock(bucket_id) VALUES(130);
INSERT INTO search_user_lock(bucket_id) VALUES(131);
INSERT INTO search_user_lock(bucket_id) VALUES(132);
INSERT INTO search_user_lock(bucket_id) VALUES(133);
INSERT INTO search_user_lock(bucket_id) VALUES(134);
INSERT INTO search_user_lock(bucket_id) VALUES(135);
INSERT INTO search_user_lock(bucket_id) VALUES(136);
INSERT INTO search_user_lock(bucket_id) VALUES(137);
INSERT INTO search_user_lock(bucket_id) VALUES(138);
INSERT INTO search_user_lock(bucket_id) VALUES(139);
INSERT INTO search_user_lock(bucket_id) VALUES(140);
INSERT INTO search_user_lock(bucket_id) VALUES(141);
INSERT INTO search_user_lock(bucket_id) VALUES(142);
INSERT INTO search_user_lock(bucket_id) VALUES(143);
INSERT INTO search_user_lock(bucket_id) VALUES(144);
INSERT INTO search_user_lock(bucket_id) VALUES(145);
INSERT INTO search_user_lock(bucket_id) VALUES(146);
INSERT INTO search_user_lock(bucket_id) VALUES(147);
INSERT INTO search_user_lock(bucket_id) VALUES(148);
INSERT INTO search_user_lock(bucket_id) VALUES(149);
INSERT INTO search_user_lock(bucket_id) VALUES(150);
INSERT INTO search_user_lock(bucket_id) VALUES(151);
INSERT INTO search_user_lock(bucket_id) VALUES(152);
INSERT INTO search_user_lock(bucket_id) VALUES(153);
INSERT INTO search_user_lock(bucket_id) VALUES(154);
INSERT INTO search_user_lock(bucket_id) VALUES(155);
INSERT INTO search_user_lock(bucket_id) VALUES(156);
INSERT INTO search_user_lock(bucket_id) VALUES(157);
INSERT INTO search_user_lock(bucket_id) VALUES(158);
INSERT INTO search_user_lock(bucket_id) VALUES(159);
INSERT INTO search_user_lock(bucket_id) VALUES(160);
INSERT INTO search_user_lock(bucket_id) VALUES(161);
INSERT INTO search_user_lock(bucket_id) VALUES(162);
INSERT INTO search_user_lock(bucket_id) VALUES(163);
INSERT INTO search_user_lock(bucket_id) VALUES(164);
INSERT INTO search_user_lock(bucket_id) VALUES(165);
INSERT INTO search_user_lock(bucket_id) VALUES(166);
INSERT INTO search_user_lock(bucket_id) VALUES(167);
INSERT INTO search_user_lock(bucket_id) VALUES(168);
INSERT INTO search_user_lock(bucket_id) VALUES(169);
INSERT INTO search_user_lock(bucket_id) VALUES(170);
INSERT INTO search_user_lock(bucket_id) VALUES(171);
INSERT INTO search_user_lock(bucket_id) VALUES(172);
INSERT INTO search_user_lock(bucket_id) VALUES(173);
INSERT INTO search_user_lock(bucket_id) VALUES(174);
INSERT INTO search_user_lock(bucket_id) VALUES(175);
INSERT INTO search_user_lock(bucket_id) VALUES(176);
INSERT INTO search_user_lock(bucket_id) VALUES(177);
INSERT INTO search_user_lock(bucket_id) VALUES(178);
INSERT INTO search_user_lock(bucket_id) VALUES(179);
INSERT INTO search_user_lock(bucket_id) VALUES(180);
INSERT INTO search_user_lock(bucket_id) VALUES(181);
INSERT INTO search_user_lock(bucket_id) VALUES(182);
INSERT INTO search_user_lock(bucket_id) VALUES(183);
INSERT INTO search_user_lock(bucket_id) VALUES(184);
INSERT INTO search_user_lock(bucket_id) VALUES(185);
INSERT INTO search_user_lock(bucket_id) VALUES(186);
INSERT INTO search_user_lock(bucket_id) VALUES(187);
INSERT INTO search_user_lock(bucket_id) VALUES(188);
INSERT INTO search_user_lock(bucket_id) VALUES(189);
INSERT INTO search_user_lock(bucket_id) VALUES(190);
INSERT INTO search_user_lock(bucket_id) VALUES(191);
INSERT INTO search_user_lock(bucket_id) VALUES(192);
INSERT INTO search_user_lock(bucket_id) VALUES(193);
INSERT INTO search_user_lock(bucket_id) VALUES(194);
INSERT INTO search_user_lock(bucket_id) VALUES(195);
INSERT INTO search_user_lock(bucket_id) VALUES(196);
INSERT INTO search_user_lock(bucket_id) VALUES(197);
INSERT INTO search_user_lock(bucket_id) VALUES(198);
INSERT INTO search_user_lock(bucket_id) VALUES(199);
INSERT INTO search_user_lock(bucket_id) VALUES(200);
INSERT INTO search_user_lock(bucket_id) VALUES(201);
INSERT INTO search_user_lock(bucket_id) VALUES(202);
INSERT INTO search_user_lock(bucket_id) VALUES(203);
INSERT INTO search_user_lock(bucket_id) VALUES(204);
INSERT INTO search_user_lock(bucket_id) VALUES(205);
INSERT INTO search_user_lock(bucket_id) VALUES(206);
INSERT INTO search_user_lock(bucket_id) VALUES(207);
INSERT INTO search_user_lock(bucket_id) VALUES(208);
INSERT INTO search_user_lock(bucket_id) VALUES(209);
INSERT INTO search_user_lock(bucket_id) VALUES(210);
INSERT INTO search_user_lock(bucket_id) VALUES(211);
INSERT INTO search_user_lock(bucket_id) VALUES(212);
INSERT INTO search_user_lock(bucket_id) VALUES(213);
INSERT INTO search_user_lock(bucket_id) VALUES(214);
INSERT INTO search_user_lock(bucket_id) VALUES(215);
INSERT INTO search_user_lock(bucket_id) VALUES(216);
INSERT INTO search_user_lock(bucket_id) VALUES(217);
INSERT INTO search_user_lock(bucket_id) VALUES(218);
INSERT INTO search_user_lock(bucket_id) VALUES(219);
INSERT INTO search_user_lock(bucket_id) VALUES(220);
INSERT INTO search_user_lock(bucket_id) VALUES(221);
INSERT INTO search_user_lock(bucket_id) VALUES(222);
INSERT INTO search_user_lock(bucket_id) VALUES(223);
INSERT INTO search_user_lock(bucket_id) VALUES(224);
INSERT INTO search_user_lock(bucket_id) VALUES(225);
INSERT INTO search_user_lock(bucket_id) VALUES(226);
INSERT INTO search_user_lock(bucket_id) VALUES(227);
INSERT INTO search_user_lock(bucket_id) VALUES(228);
INSERT INTO search_user_lock(bucket_id) VALUES(229);
INSERT INTO search_user_lock(bucket_id) VALUES(230);
INSERT INTO search_user_lock(bucket_id) VALUES(231);
INSERT INTO search_user_lock(bucket_id) VALUES(232);
INSERT INTO search_user_lock(bucket_id) VALUES(233);
INSERT INTO search_user_lock(bucket_id) VALUES(234);
INSERT INTO search_user_lock(bucket_id) VALUES(235);
INSERT INTO search_user_lock(bucket_id) VALUES(236);
INSERT INTO search_user_lock(bucket_id) VALUES(237);
INSERT INTO search_user_lock(bucket_id) VALUES(238);
INSERT INTO search_user_lock(bucket_id) VALUES(239);
INSERT INTO search_user_lock(bucket_id) VALUES(240);
INSERT INTO search_user_lock(bucket_id) VALUES(241);
INSERT INTO search_user_lock(bucket_id) VALUES(242);
INSERT INTO search_user_lock(bucket_id) VALUES(243);
INSERT INTO search_user_lock(bucket_id) VALUES(244);
INSERT INTO search_user_lock(bucket_id) VALUES(245);
INSERT INTO search_user_lock(bucket_id) VALUES(246);
INSERT INTO search_user_lock(bucket_id) VALUES(247);
INSERT INTO search_user_lock(bucket_id) VALUES(248);
INSERT INTO search_user_lock(bucket_id) VALUES(249);
INSERT INTO search_user_lock(bucket_id) VALUES(250);
INSERT INTO search_user_lock(bucket_id) VALUES(251);
INSERT INTO search_user_lock(bucket_id) VALUES(252);
INSERT INTO search_user_lock(bucket_id) VALUES(253);
INSERT INTO search_user_lock(bucket_id) VALUES(254);
INSERT INTO search_user_lock(bucket_id) VALUES(255);
COMMIT;

-- Push audit history; FCM acceptance is not a device delivery receipt.
CREATE TABLE notification_push_history (
  history_id VARCHAR2(64 BYTE) NOT NULL,
  notification_type VARCHAR2(32 BYTE) NOT NULL,
  notification_id VARCHAR2(64 BYTE) NOT NULL,
  user_id VARCHAR2(64 BYTE) NOT NULL,
  subscription_id VARCHAR2(64 BYTE),
  attempt_no NUMBER(6,0) DEFAULT 0 NOT NULL,
  status VARCHAR2(16 BYTE) NOT NULL,
  error_code VARCHAR2(100 BYTE),
  title VARCHAR2(200 CHAR) NOT NULL,
  body_text VARCHAR2(1000 CHAR),
  action_path VARCHAR2(2048 BYTE) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_notification_push_history PRIMARY KEY(history_id),
  CONSTRAINT ck_push_history_attempt CHECK(attempt_no>=0),
  CONSTRAINT ck_push_history_status CHECK(status IN ('QUEUED','RUNNING','ACCEPTED','FAILED','SKIPPED'))
);
CREATE INDEX ix_push_history_notification ON notification_push_history(notification_type,notification_id,created_at);
CREATE INDEX ix_push_history_user ON notification_push_history(user_id,created_at);
