package com.bintime.search.adapter;

import com.bintime.search.port.StoreIndexPort;
import com.bintime.search.service.*;
import com.bintime.search.service.StoreSearchService.Query;
import com.bintime.store.contract.SearchStoreCatalog.StoreDocument;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ElasticsearchStoreAdapter implements StoreIndexPort {
  private final String base, alias, authorization;
  private final JsonMapper json;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public ElasticsearchStoreAdapter(
      JsonMapper json,
      @Value("${search.elasticsearch.url:http://localhost:9200}") String base,
      @Value("${search.elasticsearch.alias:bintime-stores}") String alias,
      @Value("${search.elasticsearch.authorization:}") String authorization) {
    this.json = json;
    this.base = base;
    this.alias = alias;
    this.authorization = authorization;
    if (!alias.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid index alias");
  }

  public String open() {
    return request("POST", "/" + alias + "/_pit?keep_alive=5m", null).path("id").asText();
  }

  public void close(String pit) {
    try {
      request("DELETE", "/_pit", Map.of("id", pit));
    } catch (Exception ignored) {
    }
  }

  public Page search(Query q, String pit, List<Object> after, float[] vector, int size) {
    var filter = new ArrayList<Object>();
    filter.add(
        Map.of(
            "range",
            Map.of(
                "slots.startAt",
                Map.of("gte", q.from().toString(), "gt", q.snapshotAt().toString()))));
    if (q.to() != null)
      filter.add(Map.of("range", Map.of("slots.endAt", Map.of("lte", q.to().toString()))));
    if (q.vacancyOnly()) filter.add(Map.of("term", Map.of("slots.source", "VACANCY")));
    var nestedFilter = Map.of("bool", Map.of("filter", filter));
    var root = new ArrayList<Object>();
    root.add(
        Map.of(
            "geo_distance",
            Map.of(
                "distance",
                q.radiusMeters() + "m",
                "location",
                Map.of("lat", q.latitude(), "lon", q.longitude()))));
    root.add(Map.of("nested", Map.of("path", "slots", "query", nestedFilter)));
    if (q.category() != null) root.add(Map.of("term", Map.of("category", q.category())));
    if (q.keyword() != null && !q.keyword().isBlank())
      root.add(
          Map.of(
              "multi_match",
              Map.of(
                  "query",
                  q.keyword(),
                  "fields",
                  List.of("name", "description"),
                  "operator",
                  "and")));
    Object query = Map.of("bool", Map.of("filter", root));
    if (vector != null)
      query =
          Map.of(
              "script_score",
              Map.of(
                  "query",
                  query,
                  "script",
                  Map.of(
                      "source",
                      "doc['embedding'].size() == 0 ? 0 : cosineSimilarity(params.vector,"
                          + " 'embedding') + 1.0",
                      "params",
                      Map.of("vector", vector))));
    var sort = new ArrayList<Object>();
    if (vector != null) sort.add(Map.of("_score", "desc"));
    if (q.sort().equals("PRICE"))
      sort.add(
          Map.of(
              "slots.payablePrice",
              Map.of(
                  "order",
                  "asc",
                  "mode",
                  "min",
                  "nested",
                  Map.of("path", "slots", "filter", nestedFilter))));
    sort.add(
        Map.of(
            "_geo_distance",
            Map.of(
                "location",
                Map.of("lat", q.latitude(), "lon", q.longitude()),
                "order",
                "asc",
                "unit",
                "m",
                "distance_type",
                "arc")));
    sort.add(
        Map.of(
            "slots.startAt",
            Map.of(
                "order",
                "asc",
                "mode",
                "min",
                "nested",
                Map.of("path", "slots", "filter", nestedFilter))));
    sort.add(Map.of("id", "asc"));
    var body = new LinkedHashMap<String, Object>();
    body.put("query", query);
    body.put("sort", sort);
    body.put("pit", Map.of("id", pit, "keep_alive", "5m"));
    body.put("size", size);
    body.put("track_total_hits", false);
    if (after != null && !after.isEmpty()) body.put("search_after", after);
    JsonNode result = request("POST", "/_search", body);
    if (result.path("timed_out").asBoolean() || result.path("_shards").path("failed").asInt() > 0)
      throw SearchFailure.unavailable();
    var hits = new ArrayList<Hit>();
    for (JsonNode hit : result.path("hits").path("hits")) {
      var doc = json.treeToValue(hit.path("_source").path("document"), StoreDocument.class);
      List<Object> values = new ArrayList<>();
      for (JsonNode v : hit.path("sort")) values.add(json.treeToValue(v, Object.class));
      hits.add(new Hit(doc, values));
    }
    return new Page(result.path("pit_id").asText(pit), hits);
  }

  public void create(String index) {
    safe(index);
    try {
      String mapping =
          new String(
              getClass().getResourceAsStream("/search-index.json").readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      request("PUT", "/" + index, json.readTree(mapping));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public void upsert(String index, StoreDocument store, float[] vector) {
    safe(index);
    var body = new LinkedHashMap<String, Object>();
    body.put("document", store);
    body.put("id", store.id());
    body.put("name", store.name());
    body.put("category", store.category());
    body.put("description", store.description());
    body.put("location", Map.of("lat", store.latitude(), "lon", store.longitude()));
    body.put("slots", store.slots());
    if (vector != null) body.put("embedding", vector);
    request(
        "PUT",
        "/"
            + index
            + "/_doc/"
            + URLEncoder.encode(store.id(), java.nio.charset.StandardCharsets.UTF_8)
            + "?version="
            + store.version()
            + "&version_type=external_gte&refresh=wait_for",
        body);
  }

  public void activate(String index) {
    safe(index);
    request(
        "POST",
        "/_aliases",
        Map.of(
            "actions",
            List.of(
                Map.of("remove", Map.of("index", "*", "alias", alias, "must_exist", false)),
                Map.of("add", Map.of("index", index, "alias", alias)))));
  }

  private void safe(String name) {
    if (!name.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid index");
  }

  private JsonNode request(String method, String path, Object body) {
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json");
      if (!authorization.isBlank()) builder.header("Authorization", authorization);
      var r =
          client.send(
              builder
                  .method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() < 200 || r.statusCode() >= 300) throw SearchFailure.unavailable();
      return json.readTree(r.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw SearchFailure.unavailable();
    } catch (Exception e) {
      throw SearchFailure.unavailable();
    }
  }
}
