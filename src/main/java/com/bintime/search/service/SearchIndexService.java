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

  /** store는 커밋된 최신 전체 문서를 전달하고 재전송할 수 있다. 삭제는 slots=[]인 tombstone 문서로 전달한다. */
  public void changed(StoreDocument doc) {
    tx.executeWithoutResult(
        t -> {
          db.lockUser("store:" + doc.id());
          var versions =
              db.jdbc()
                  .queryForList(
                      "select source_version from search_store_projection where store_id=?",
                      Long.class,
                      doc.id());
          if (!versions.isEmpty() && versions.getFirst() >= doc.version()) return;
          String hash = contentHash(doc);
          var old =
              db.jdbc()
                  .queryForList(
                      "select input_hash from search_store_projection where store_id=?",
                      String.class,
                      doc.id());
          if (versions.isEmpty())
            db.jdbc()
                .update(
                    "insert into"
                        + " search_store_projection(store_id,source_version,document_json,input_hash)"
                        + " values(?,?,?,?)",
                    doc.id(),
                    doc.version(),
                    db.encode(doc),
                    hash);
          else
            db.jdbc()
                .update(
                    "update search_store_projection set"
                        + " source_version=?,document_json=?,input_hash=? where store_id=?",
                    doc.version(),
                    db.encode(doc),
                    hash,
                    doc.id());
          db.enqueue("INDEX", doc.id(), clock.millis());
          if (old.isEmpty() || !old.getFirst().equals(hash))
            db.enqueue("STORE_VECTOR", doc.id(), clock.millis());
        });
  }

  private StoreDocument document(String id) {
    return db.decode(
        db.jdbc()
            .queryForObject(
                "select document_json from search_store_projection where store_id=?",
                String.class,
                id),
        StoreDocument.class);
  }

  private float[] vector(StoreDocument doc) {
    var data =
        db.jdbc()
            .queryForList(
                "select vector_json from search_store_projection where store_id=? and vector_hash=?"
                    + " and vector_model=? and vector_dimensions=1536 and vector_json is not null",
                String.class,
                doc.id(),
                contentHash(doc),
                model);
    return data.isEmpty() ? null : db.decode(data.getFirst(), float[].class);
  }

  public void sync(String id) {
    var doc = document(id);
    index.upsert(alias, doc, vector(doc));
  }

  private String contentHash(StoreDocument doc) {
    // 공급 슬롯이 바뀌어도 동일한 매장/시술 설명은 다시 임베딩하지 않는다.
    return WaitlistService.hash(Objects.toString(doc.description(), ""));
  }

  public void embed(String id) {
    var doc =
        tx.execute(
            t -> {
              db.lockUser("store:" + id);
              return document(id);
            });
    String hash = contentHash(doc);
    float[] value = ai.embed(Objects.toString(doc.description(), ""));
    tx.executeWithoutResult(
        t -> {
          db.lockUser("store:" + id);
          int n =
              db.jdbc()
                  .update(
                      "update search_store_projection set"
                          + " vector_json=?,vector_hash=?,vector_model=?,vector_dimensions=1536"
                          + " where store_id=? and input_hash=?",
                      db.encode(value),
                      hash,
                      model,
                      id,
                      hash);
          if (n > 0) db.enqueue("INDEX", id, clock.millis());
        });
  }
}
