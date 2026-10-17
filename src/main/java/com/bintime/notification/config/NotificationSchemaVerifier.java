package com.bintime.notification.config;

import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 알림도 JDBC를 사용하므로 누락된 배포 DDL을 기동 시 확인한다. */
@Component
@Profile("!api-docs")
@Order(0)
@ConditionalOnProperty(
    name = "notification.schema-validation-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NotificationSchemaVerifier implements ApplicationRunner {
  private final JdbcTemplate jdbc;

  public NotificationSchemaVerifier(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void run(ApplicationArguments args) {
    Map.of(
            "notification_alerts",
                "notification_id,user_id,waitlist_id,slot_id,publication_version,store_id,title,body_text,expires_at,push_status,push_attempts,push_attempted_at,push_accepted_at,push_error_code,read_at,created_at,snapshot_json,request_json",
            "notification_subscriptions",
                "subscription_id,user_id,fcm_project_id,installation_id,active_yn,created_at,updated_at",
            "notification_subscription_sessions", "binding_id,instance_id,subscription_id",
            "notification_dedup", "user_id,slot_id,notification_id",
            "notification_events",
                "user_id,sequence_no,event_id,event_type,payload_json,created_at",
            "notification_deliveries",
                "delivery_id,notification_id,subscription_id,status,attempts,available_at,lease_owner,error_code,accepted_at",
            "notification_delivery_clock", "user_id,last_sent_at",
            "notification_push_history",
                "history_id,notification_type,notification_id,user_id,subscription_id,attempt_no,status,error_code,title,body_text,action_path,created_at")
        .forEach(
            (table, columns) ->
                jdbc.queryForList("select " + columns + " from " + table + " where 1=0"));
  }
}
