CREATE TABLE IF NOT EXISTS notification_subscriptions (
  subscription_id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  endpoint VARCHAR(2048), endpoint_hash VARBINARY(32), p256dh_key VARCHAR(200), auth_secret VARCHAR(100),
  fcm_project_id VARCHAR(128), installation_id VARCHAR(128),
  active_yn CHAR(1) DEFAULT 'Y' NOT NULL CHECK (active_yn IN ('Y', 'N')),
  expires_at TIMESTAMP(6) WITH TIME ZONE,
  created_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
  active_project VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN active_yn = 'Y' THEN fcm_project_id END),
  active_fid VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN active_yn = 'Y' THEN installation_id END),
  CONSTRAINT uq_test_fid_user UNIQUE(user_id, fcm_project_id, installation_id),
  CONSTRAINT uq_test_fid_active UNIQUE(active_project, active_fid),
  CONSTRAINT fk_test_subscription_user FOREIGN KEY(user_id) REFERENCES user_accounts(user_id)
);

CREATE TABLE IF NOT EXISTS notification_subscription_sessions (
  binding_id VARCHAR(36) PRIMARY KEY,
  instance_id VARCHAR(36) NOT NULL,
  subscription_id VARCHAR(64) NOT NULL REFERENCES notification_subscriptions(subscription_id)
);
CREATE INDEX IF NOT EXISTS ix_test_sub_sessions ON notification_subscription_sessions(subscription_id, instance_id);
