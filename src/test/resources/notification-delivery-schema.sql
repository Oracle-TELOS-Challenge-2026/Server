create table user_accounts(user_id varchar(64) primary key);
create table notification_alerts(
  notification_id varchar(64) primary key,user_id varchar(64) not null,waitlist_id varchar(64),
  slot_id varchar(64) not null,publication_version bigint,store_id varchar(64),title varchar(100) not null,
  body_text varchar(500) not null,expires_at timestamp with time zone not null,
  push_status varchar(10) default 'PENDING' not null,push_attempts integer default 0 not null,
  push_attempted_at timestamp with time zone,push_accepted_at timestamp with time zone,
  push_error_code varchar(100),read_at timestamp with time zone,created_at timestamp with time zone not null,
  snapshot_json clob,request_json clob,
  unique(user_id,slot_id,publication_version),foreign key(user_id) references user_accounts(user_id),
  check(expires_at>created_at),check(publication_version>=1)
);
create table notification_dedup(user_id varchar(64) not null,slot_id varchar(64) not null,
  notification_id varchar(64) not null,primary key(user_id,slot_id),
  foreign key(notification_id) references notification_alerts(notification_id));
create table notification_events(user_id varchar(64) not null,sequence_no bigint not null,
  event_id varchar(64) not null unique,event_type varchar(32) not null,payload_json clob not null,
  created_at timestamp with time zone not null,primary key(user_id,sequence_no));
create table notification_deliveries(delivery_id varchar(64) primary key,notification_id varchar(64) not null,
  subscription_id varchar(64) not null,status varchar(16) not null,attempts integer not null,
  available_at bigint not null,lease_owner varchar(64),error_code varchar(100),accepted_at timestamp with time zone,
  unique(notification_id,subscription_id),check(attempts>=0));
create table notification_delivery_clock(user_id varchar(64) primary key,last_sent_at bigint not null);

-- Push audit history; FCM acceptance is not a device delivery receipt.
CREATE TABLE notification_push_history (
  history_id VARCHAR(64) NOT NULL,
  notification_type VARCHAR(32) NOT NULL,
  notification_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  subscription_id VARCHAR(64),
  attempt_no NUMBER(6,0) DEFAULT 0 NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_code VARCHAR(100),
  title VARCHAR(200) NOT NULL,
  body_text VARCHAR(1000),
  action_path VARCHAR(2048) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  CONSTRAINT pk_notification_push_history PRIMARY KEY(history_id),
  CONSTRAINT ck_push_history_attempt CHECK(attempt_no>=0),
  -- H2 IN-set checks retain a closed DDL session; CASE enforces the same values.
  CONSTRAINT ck_push_history_status CHECK(CASE status WHEN 'QUEUED' THEN 1 WHEN 'RUNNING' THEN 1 WHEN 'ACCEPTED' THEN 1 WHEN 'FAILED' THEN 1 WHEN 'SKIPPED' THEN 1 ELSE 0 END=1)
);
CREATE INDEX ix_push_history_notification ON notification_push_history(notification_type,notification_id,created_at);
CREATE INDEX ix_push_history_user ON notification_push_history(user_id,created_at);
