-- JDBC/IDE-compatible SQL. Connect as the application user; do not select PUBLIC.
-- SQL*Plus callers must configure SET/WHENEVER in their wrapper.
-- BinTime HACKATHON MVP / Oracle Autonomous Database / 19c+ SQL subset
-- 15 tables, 5 domains. Web push is REQUIRED. One owner and one concurrent appointment per store.
-- Run ONCE in an EMPTY application schema. Not a migration from the 30-table draft.

--------------------------------------------------------------------------------
-- 1. USER: login identity, role, location and preferences in one table
--------------------------------------------------------------------------------
CREATE TABLE user_accounts (
  recommendation_opt_in CHAR(1) DEFAULT 'N' NOT NULL,
  recommendation_consent_at TIMESTAMP(6) WITH TIME ZONE,
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
 active_yn CHAR(1 BYTE) DEFAULT 'Y' NOT NULL, version_no NUMBER(10,0) DEFAULT 1 NOT NULL,
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
 original_service_id VARCHAR2(64 BYTE), vacancy_original_price NUMBER(10,0), published_at TIMESTAMP(6) WITH TIME ZONE, discount_type VARCHAR2(12 BYTE), discount_value NUMBER(10,0),
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
 service_id VARCHAR2(64 BYTE), duration_minutes NUMBER(3,0), customer_name VARCHAR2(30 CHAR), cancel_reason_code VARCHAR2(20 BYTE), cancel_reason VARCHAR2(300 CHAR), canceled_at TIMESTAMP(6) WITH TIME ZONE,
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
-- 7. NOTIFICATION: saved alerts plus aggregate web-push delivery status
--------------------------------------------------------------------------------
CREATE TABLE notification_alerts (
                                     notification_id    VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
                                     user_id            VARCHAR2(64 BYTE) NOT NULL,
                                     waitlist_id        VARCHAR2(64 BYTE),
                                     slot_id            VARCHAR2(64 BYTE) NOT NULL,
                                     publication_version NUMBER(10,0) NOT NULL,
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
                                     CONSTRAINT fk_alert_waitlist FOREIGN KEY (waitlist_id, user_id)
                                         REFERENCES search_waitlists(waitlist_id, user_id),
                                     CONSTRAINT fk_alert_slot FOREIGN KEY (slot_id) REFERENCES store_slots(slot_id),
                                     CONSTRAINT ck_alert_pubver CHECK (publication_version >= 1),
                                     CONSTRAINT ck_alert_expires CHECK (expires_at > created_at),
                                     CONSTRAINT ck_alert_push_status CHECK (
                                         push_status IN ('PENDING','SENT','PARTIAL','FAILED','NO_SUB','EXPIRED')),
                                     CONSTRAINT ck_alert_attempts CHECK (push_attempts >= 0)
);

--------------------------------------------------------------------------------
-- 8. NOTIFICATION: browser PushSubscription data; multiple devices per user
-- endpoint_hash = SHA-256(endpoint), calculated by the application.
--------------------------------------------------------------------------------
CREATE TABLE notification_subscriptions (
                                            subscription_id VARCHAR2(64 BYTE) DEFAULT LOWER(RAWTOHEX(SYS_GUID())) NOT NULL,
                                            user_id         VARCHAR2(64 BYTE) NOT NULL,
                                            endpoint        VARCHAR2(2048 BYTE) NOT NULL,
                                            endpoint_hash   RAW(32) NOT NULL,
                                            p256dh_key      VARCHAR2(200 BYTE) NOT NULL,
                                            auth_secret     VARCHAR2(100 BYTE) NOT NULL,
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
                                status_code   VARCHAR2(9 BYTE) DEFAULT 'OPEN' NOT NULL,
                                last_sequence NUMBER(16,0) DEFAULT 0 NOT NULL,
                                last_message_at TIMESTAMP(6) WITH TIME ZONE,
                                created_at    TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
                                CONSTRAINT pk_chatting_rooms PRIMARY KEY (room_id),
                                CONSTRAINT uq_room_pair UNIQUE (customer_id, store_id),
                                CONSTRAINT fk_room_customer FOREIGN KEY (customer_id) REFERENCES user_accounts(user_id),
                                CONSTRAINT fk_room_store FOREIGN KEY (store_id) REFERENCES store_shops(store_id),
                                CONSTRAINT ck_chat_room_status CHECK (status_code IN ('OPEN','READ_ONLY')),
                                CONSTRAINT ck_room_sequence CHECK (last_sequence BETWEEN 0 AND 9007199254740991)
);

--------------------------------------------------------------------------------
-- 10. CHATTING: text messages; no attachments or per-message read receipts
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

-- A missing participant cursor means unread (sequence 0); this is not a membership table.
CREATE TABLE chatting_read_positions (
    room_id VARCHAR2(64 BYTE) NOT NULL,
    user_id VARCHAR2(64 BYTE) NOT NULL,
    last_read_sequence NUMBER(16,0) DEFAULT 0 NOT NULL,
    read_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_chat_read_positions PRIMARY KEY (room_id,user_id),
    CONSTRAINT fk_chat_read_room FOREIGN KEY (room_id) REFERENCES chatting_rooms(room_id),
    CONSTRAINT fk_chat_read_user FOREIGN KEY (user_id) REFERENCES user_accounts(user_id),
    CONSTRAINT ck_chat_read_sequence CHECK (last_read_sequence BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_chat_read_time CHECK (last_read_sequence=0 OR read_at IS NOT NULL)
);

-- HTTP request replay is separate from the permanent clientMessageId deduplication.
CREATE TABLE chatting_idempotency (
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
    CONSTRAINT ck_chat_idem_json CHECK (result_json IS JSON (STRICT))
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
CREATE INDEX ix_chat_room_customer_time ON chatting_rooms(customer_id,last_message_at DESC,room_id);
CREATE INDEX ix_chat_room_store_time ON chatting_rooms(store_id,last_message_at DESC,room_id);
CREATE INDEX ix_chat_read_user ON chatting_read_positions(user_id);
CREATE INDEX ix_chat_idem_expiry ON chatting_idempotency(expires_at);

COMMENT ON TABLE chatting_rooms IS 'chatting schema v1: one customer-store room; owner comes from store_shops';
COMMENT ON COLUMN chatting_rooms.last_sequence IS 'Allocate under a room row lock in the same transaction as the message';
COMMENT ON COLUMN chatting_rooms.last_message_at IS 'Server timestamp of the message at last_sequence; NULL for an empty room';
COMMENT ON TABLE chatting_read_positions IS 'Participant read cursor; absent row means sequence 0; service verifies membership and monotonicity';
COMMENT ON TABLE chatting_idempotency IS '24-hour HTTP replay; operation is POST: plus the concrete request path';

COMMENT ON TABLE store_slots IS '해커톤: 일반 슬롯과 취소표 통합. DRAFT 발행 시 AVAILABLE';
COMMENT ON TABLE search_waitlists IS 'search: 자연어에서 추출하고 사용자가 확인한 예약 희망 조건';
COMMENT ON TABLE notification_alerts IS '필수 웹 푸시 알림. 저장 후 발송하며 집계 상태와 시도 횟수 기록';
COMMENT ON COLUMN store_slots.source_reservation_id IS '취소/노쇼 원예약. 원예약당 취소표 1개';
COMMENT ON COLUMN store_slots.available_until IS '노쇼 시작시각 조정 때도 유지하는 원래 빈 구간 종료';
COMMENT ON COLUMN notification_alerts.waitlist_id IS '같은 사용자의 복수 조건 일치 시 대표 대기 1개';
COMMENT ON TABLE notification_subscriptions IS 'notification: 웹 푸시 구독 endpoint/p256dh/auth, 계정당 여러 브라우저';
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
