package com.bintime.search.service;

import com.bintime.search.port.*;
import com.bintime.search.repository.SearchRepository;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.contract.SearchStoreCatalog.StoreDocument;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** store 전체 문서의 단조 증가 버전으로 증분 이벤트의 역전을 막는다. */
@Service
@Profile("!api-docs")
public class SearchIndexService {
  private final StoreIndexPort index;
  private final SearchStoreCatalog stores;
  private final IntentAiPort ai;
  private final Clock clock;
  private final SearchRepository db;
  private final TransactionTemplate tx;
  private final String alias;
  private final String model;

  public SearchIndexService(
      StoreIndexPort index,
      SearchStoreCatalog stores,
      IntentAiPort ai,
      Clock clock,
      SearchRepository db,
      TransactionTemplate tx,
      @Value("${search.elasticsearch.alias:bintime-stores}") String alias,
      @Value("${search.ai.embedding-model:text-embedding-3-small}") String model) {
    this.model = model;
    this.index = index;
    this.stores = stores;
    this.ai = ai;
    this.clock = clock;
    this.db = db;
    this.tx = tx;
    this.alias = alias;
  }

  public String rebuild() {
    String name = "bintime-stores-" + clock.millis();
    index.create(name);
    String cursor = null;
    do {
      var page = stores.export(cursor, 100);
      for (var doc : page.items()) {
        changed(doc);
        index.upsert(name, doc, vector(doc));
      }
      cursor = page.nextCursor();
    } while (cursor != null);
    index.activate(name);
    return name;
  }

  /** 전체 검색 문서는 작업 payload로 전달하고, Oracle에는 벡터만 캐시한다. */
  public void changed(StoreDocument doc) {
    tx.executeWithoutResult(
        t -> {
          db.lockUser("store:" + doc.id());
          db.enqueue("INDEX", db.encode(doc), clock.millis());
          if (vector(doc) == null) db.enqueue("STORE_VECTOR", db.encode(doc), clock.millis());
        });
  }

  private StoreDocument current(String id) {
    String cursor = null;
    do {
      var page = stores.export(cursor, 100);
      if (page == null) throw SearchFailure.unavailable();
      for (var doc : page.items()) if (doc.id().equals(id)) return doc;
      cursor = page.nextCursor();
    } while (cursor != null);
    return null;
  }

  private StoreDocument document(String payload) {
    // v1 큐에 남은 store ID도 원장의 공개 조회로 복구한다.
    return payload.stripLeading().startsWith("{")
        ? db.decode(payload, StoreDocument.class)
        : current(payload);
  }

  private float[] vector(StoreDocument doc) {
    var data =
        db.jdbc()
            .queryForList(
                "select vector_json from search_store_vector where store_id=? and input_hash=? and"
                    + " model=? and dimensions=1536",
                String.class,
                doc.id(),
                contentHash(doc),
                model);
    return data.isEmpty() ? null : db.decode(data.getFirst(), float[].class);
  }

  public void sync(String payload) {
    var doc = document(payload);
    if (doc != null) index.upsert(alias, doc, vector(doc));
  }

  private String contentHash(StoreDocument doc) {
    // 슬롯 시각·가격만 바뀌면 설명 벡터를 재사용한다.
    return WaitlistService.hash(Objects.toString(doc.description(), ""));
  }

  public void embed(String payload) {
    var doc = document(payload);
    if (doc == null || vector(doc) != null) return;
    String hash = contentHash(doc);
    float[] value =
        com.bintime.search.model.EmbeddingVector.validate(
            ai.embed(Objects.toString(doc.description(), "")));
    var latest = current(doc.id());
    if (latest == null || !contentHash(latest).equals(hash)) return;
    tx.executeWithoutResult(
        t -> {
          db.lockUser("store:" + doc.id());
          var versions =
              db.jdbc()
                  .queryForList(
                      "select source_version from search_store_vector where store_id=?",
                      Long.class,
                      doc.id());
          if (!versions.isEmpty() && versions.getFirst() > latest.version()) return;
          if (versions.isEmpty())
            db.jdbc()
                .update(
                    "insert into"
                        + " search_store_vector(store_id,source_version,input_hash,model,dimensions,vector_json)"
                        + " values(?,?,?,?,1536,?)",
                    doc.id(),
                    latest.version(),
                    hash,
                    model,
                    db.encode(value));
          else
            db.jdbc()
                .update(
                    "update search_store_vector set"
                        + " source_version=?,input_hash=?,model=?,dimensions=1536,vector_json=?"
                        + " where store_id=?",
                    latest.version(),
                    hash,
                    model,
                    db.encode(value),
                    doc.id());
          db.enqueue("INDEX", db.encode(latest), clock.millis());
        });
  }
}
