package com.bintime.search.config;

import com.bintime.search.repository.SearchRepository;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** JDBC 테이블도 ddl-auto=validate와 같은 배포 전 검증 경계에 포함한다. */
@Component
@Profile("!api-docs")
@org.springframework.core.annotation.Order(0)
public class SearchSchemaVerifier implements ApplicationRunner {
  private final SearchRepository db;

  public SearchSchemaVerifier(SearchRepository db) {
    this.db = db;
  }

  public void run(ApplicationArguments args) {
    var columns =
        Map.of(
            "search_intent",
                "id,user_id,source,conditions_json,status,resource_version,created_at,consent_at,consent_version,window_end,reservation_id",
            "search_idempotency",
                "user_id,operation,idem_key,request_hash,response_json,expires_at",
            "search_work",
                "id,kind,payload,status,attempts,available_at,lease_owner,last_error,event_id,job_id,slot_id,publication_version",
            "search_offer", "intent_id,intent_version,slot_id,publication_version",
            "search_user_vector",
                "user_id,revision,source_version,input_hash,model,dimensions,vector_json,lease_owner,lease_until",
            "search_store_vector",
                "store_id,source_version,input_hash,model,dimensions,vector_json");
    columns.forEach(
        (table, fields) ->
            db.jdbc().queryForList("select " + fields + " from " + table + " where 1=0"));
    if (db.jdbc()
            .queryForObject(
                "select count(*) from search_user_lock where bucket_id between 0 and 255",
                Integer.class)
        != 256)
      throw new IllegalStateException(
          "Apply docs/sql/search-oracle.sql: 256 search lock rows required");
  }
}
