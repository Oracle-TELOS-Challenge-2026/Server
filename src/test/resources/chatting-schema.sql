-- Isolated chat service fixture. Production uses the frozen Oracle schema v1.
CREATE TABLE user_accounts (
 user_id VARCHAR(64) PRIMARY KEY,
 display_name VARCHAR(100) NOT NULL,
 role_code VARCHAR(16) NOT NULL
);
CREATE TABLE store_shops (
 store_id VARCHAR(64) PRIMARY KEY,
 owner_user_id VARCHAR(64) NOT NULL REFERENCES user_accounts(user_id),
 name VARCHAR(100) NOT NULL,
 image_url VARCHAR(500)
);
CREATE TABLE chatting_rooms (
 room_id VARCHAR(64) PRIMARY KEY,
 customer_id VARCHAR(64) NOT NULL REFERENCES user_accounts(user_id),
 store_id VARCHAR(64) NOT NULL REFERENCES store_shops(store_id),
 status_code VARCHAR(9) DEFAULT 'OPEN' NOT NULL CHECK(status_code IN ('OPEN','READ_ONLY')),
 last_sequence NUMBER(16,0) DEFAULT 0 NOT NULL CHECK(last_sequence BETWEEN 0 AND 9007199254740991),
 last_message_at TIMESTAMP(6) WITH TIME ZONE,
 created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
 UNIQUE(customer_id,store_id)
);
CREATE TABLE chatting_messages (
 message_id VARCHAR(64) PRIMARY KEY,
 room_id VARCHAR(64) NOT NULL REFERENCES chatting_rooms(room_id),
 sender_id VARCHAR(64) NOT NULL REFERENCES user_accounts(user_id),
 message_sequence NUMBER(16,0) NOT NULL CHECK(message_sequence BETWEEN 1 AND 9007199254740991),
 client_message_id VARCHAR(64) NOT NULL,
 content_text CLOB NOT NULL,
 created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
 UNIQUE(room_id,message_sequence),
 UNIQUE(room_id,sender_id,client_message_id)
);
CREATE TABLE chatting_read_positions (
 room_id VARCHAR(64) NOT NULL REFERENCES chatting_rooms(room_id),
 user_id VARCHAR(64) NOT NULL REFERENCES user_accounts(user_id),
 last_read_sequence NUMBER(16,0) DEFAULT 0 NOT NULL CHECK(last_read_sequence BETWEEN 0 AND 9007199254740991),
 read_at TIMESTAMP(6) WITH TIME ZONE,
 PRIMARY KEY(room_id,user_id),
 CHECK(last_read_sequence=0 OR read_at IS NOT NULL)
);
CREATE TABLE chatting_idempotency (
 actor_id VARCHAR(64) NOT NULL REFERENCES user_accounts(user_id),
 operation VARCHAR(200) NOT NULL,
 key_value VARCHAR(200) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL CHECK(LENGTH(fingerprint)=64),
 result_json CLOB NOT NULL,
 http_status NUMBER(3,0) NOT NULL CHECK(http_status IN (200,201)),
 expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
 PRIMARY KEY(actor_id,operation,key_value)
);
