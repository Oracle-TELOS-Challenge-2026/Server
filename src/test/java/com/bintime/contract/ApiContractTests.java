package com.bintime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "api-docs"})
class ApiContractTests {
  private static final Set<String> IMPLEMENTED = Set.of("U-01", "U-02", "U-03", "U-09");

  private static boolean isImplemented(String id) {
    return id.startsWith("S-") || id.startsWith("ST-") || IMPLEMENTED.contains(id);
  }

  private final JsonMapper mapper = JsonMapper.builder().build();
  @LocalServerPort private int port;

  @Test
  void allNotionHttpOperationsHaveMatchingSwaggerContracts() throws Exception {
    JsonNode allDoc = getDoc("all");
    JsonNode publicDoc = getDoc("public");
    JsonNode internalDoc = getDoc("internal");
    assertThat(operationCount(publicDoc)).isEqualTo(46);
    assertThat(operationCount(internalDoc)).isEqualTo(2);
    assertThat(operationCount(allDoc)).isEqualTo(48);
    var operationIds = new HashSet<String>();

    for (JsonNode contract : contracts()) {
      String path = contract.path("path").asText();
      JsonNode doc = path.contains("/internal/") ? internalDoc : publicDoc;
      JsonNode operation =
          doc.path("paths").path(path).path(contract.path("method").asText().toLowerCase());
      assertThat(
              allDoc.path("paths").path(path).has(contract.path("method").asText().toLowerCase()))
          .isTrue();
      assertThat(operation.isMissingNode()).as(contract.path("id").asText()).isFalse();
      assertThat(operationIds.add(operation.path("operationId").asText())).isTrue();
      assertThat(operation.path("responses").has(contract.path("status").asText())).isTrue();
      assertThat(operation.path("responses").has("501"))
          .isEqualTo(!isImplemented(contract.path("id").asText()));
      assertThat(publicDoc.path("paths").has("/api/v1/chatting/ws")).isFalse();

      for (JsonNode query : contract.path("query")) {
        boolean found = false;
        for (JsonNode parameter : operation.path("parameters")) {
          if (parameter.path("name").asText().equals(query.path("name").asText())) {
            assertThat(parameter.path("in").asText()).isEqualTo("query");
            assertThat(parameter.path("required").asBoolean())
                .isEqualTo(query.path("required").asBoolean());
            found = true;
          }
        }
        assertThat(found).as("query %s %s", path, query.path("name")).isTrue();
      }
      if (!contract.path("request").isNull()) {
        JsonNode schema =
            operation.path("requestBody").path("content").path("application/json").path("schema");
        assertExampleFields(doc, schema, contract.path("requestExample"));
      }
      if (!contract.path("response").isNull()) {
        JsonNode schema =
            operation
                .path("responses")
                .path(contract.path("status").asText())
                .path("content")
                .path("application/json")
                .path("schema");
        JsonNode envelope = resolve(doc, schema);
        assertThat(envelope.path("properties").has("meta")).isTrue();
        assertThat(envelope.at("/properties/success/type").toString()).contains("object");
        JsonNode example =
            operation
                .path("responses")
                .path(contract.path("status").asText())
                .at("/content/application~1json/examples/success/value");
        assertThat(example.path("resultType").asText()).isEqualTo("SUCCESS");
        assertThat(example.at("/success/data")).isEqualTo(contract.path("responseExample"));
        assertThat(example.path("error").isNull()).isTrue();
        assertThat(example.at("/meta/path").asText()).isEqualTo(path);

        assertExampleFields(
            doc,
            resolve(doc, envelope.path("properties").path("success"))
                .path("properties")
                .path("data"),
            contract.path("responseExample"));
      }
    }
    assertThat(publicDoc.path("components").path("schemas").has("ChatWebSocketFrame")).isTrue();
    assertThat(publicDoc.path("components").path("schemas").has("NotificationCreatedEvent"))
        .isTrue();
    assertThat(
            publicDoc
                .path("paths")
                .path("/api/v1/notifications/stream")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .has("text/event-stream"))
        .isTrue();
  }

  @Test
  void swaggerOnlyDocumentsDeclaredResponsesInEveryGroup() throws Exception {
    for (String group : new String[] {"all", "public", "internal"}) {
      JsonNode doc = getDoc(group);
      for (var path : doc.path("paths").properties()) {
        for (var method : path.getValue().properties()) {
          JsonNode operation = method.getValue();
          var codes = new HashSet<String>();
          for (var response : operation.path("responses").properties()) {
            codes.add(response.getKey());
            if (Integer.parseInt(response.getKey()) >= 400) {
              assertThat(response.getValue().at("/content/application~1json/schema/$ref").asText())
                  .isEqualTo("#/components/schemas/ApiErrorResponse");
              assertThat(response.getValue().path("description").asText())
                  .doesNotContain("공통 실패 응답");
            }
          }
          var expected = new HashSet<String>();
          if (path.getKey().contains("/search/")) {
            expected.addAll(java.util.Set.of("400", "401", "403", "404", "503"));
            if (java.util.Set.of(
                    "createWaitlist", "updateWaitlist", "cancelWaitlist", "createMatchJob")
                .contains(operation.path("operationId").asText())) expected.add("409");
          }
          for (JsonNode contract : contracts()) {
            if (contract.path("path").asText().equals(path.getKey())
                && contract.path("method").asText().equalsIgnoreCase(method.getKey())) {
              expected.add(contract.path("status").asText());
              if (!isImplemented(contract.path("id").asText())) expected.add("501");
              switch (contract.path("id").asText()) {
                case "U-01" -> expected.addAll(Set.of("400", "503"));
                case "U-03" -> expected.addAll(Set.of("401", "403"));
                case "U-09" -> expected.add("401");
                case "U-05", "U-06", "ST-06", "ST-16", "S-03", "N-04", "C-04" ->
                    expected.add("409");
                case "C-05" -> expected.add("400");
              }
            }
          }
          if (path.getKey().equals("/api/v1/notifications/stream")) expected.add("410");
          if (java.util.Set.of(
                  "dispatchNotification",
                  "registerPushSubscription",
                  "createChatRoom",
                  "sendMessage")
              .contains(operation.path("operationId").asText())) {
            expected.add("200");
          }
          if (operation.path("tags").toString().contains("store")) {
            expected.clear();
            String id = operation.path("operationId").asText();
            expected.add(
                java.util.Set.of("createStore", "createService", "createSlot", "createReservation")
                        .contains(id)
                    ? "201"
                    : "200");
            if (!java.util.Set.of(
                    "getCategories", "getStore", "getSlot", "getReservation", "getOwnerReservation")
                .contains(id)) expected.add("400");
            if (!java.util.Set.of(
                    "getCategories", "getStore", "getServices", "getAvailableSlots", "getSlot")
                .contains(id)) expected.addAll(java.util.Set.of("401", "403"));
            if (!java.util.Set.of("getCategories", "getMyReservations", "createStore").contains(id))
              expected.add("404");
            if (!java.util.Set.of(
                    "getCategories",
                    "getStore",
                    "getServices",
                    "getAvailableSlots",
                    "getSlot",
                    "getMyReservations",
                    "getReservation",
                    "getOwnerReservation",
                    "getVacancies")
                .contains(id)) expected.add("409");
          }
          assertThat(codes)
              .as("%s %s", group, path.getKey())
              .containsExactlyInAnyOrderElementsOf(expected);
          for (String code : expected) {
            int status = Integer.parseInt(code);
            JsonNode response = operation.path("responses").path(code);
            if (status == 204 || (status >= 300 && status < 400)) {
              assertThat(response.path("content").isMissingNode()).isTrue();
            } else if (status < 300 && !path.getKey().endsWith("/notifications/stream")) {
              JsonNode schema = resolve(doc, response.at("/content/application~1json/schema"));
              assertThat(schema.at("/properties/success").isMissingNode()).isFalse();
            }
          }
        }
      }
    }
  }

  @Test
  void swaggerUiSelectsTheCompleteApiGroupByDefault() throws Exception {
    JsonNode config = getDoc("swagger-config");
    assertThat(config.path("urls.primaryName").asText()).isEqualTo("all");
    var groups = new HashSet<String>();
    for (JsonNode group : config.path("urls")) {
      groups.add(group.path("name").asText());
    }
    assertThat(groups).contains("all", "public", "internal");
  }

  @Test
  void developmentEndpointsDoNotRequireAuthenticationOrCsrf() throws Exception {
    var client = HttpClient.newHttpClient();
    var get =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users/me"))
            .GET()
            .build();
    assertThat(client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(501);

    var post =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/search/intents/analyze"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"내일 네일 예약\"}"))
            .build();
    assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
  }

  @Test
  void unmappedApiPathsReturnTheCommonFailureEnvelope() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/not-a-real-route"))
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(404);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.path("resultType").asText()).isEqualTo("FAILURE");
    assertThat(body.path("success").isNull()).isTrue();
    assertThat(body.at("/meta/path").asText()).isEqualTo("/api/v1/not-a-real-route");
  }

  @Test
  void dtoTypesAcceptAllNotionRequestAndResponseExamples() throws Exception {
    for (JsonNode contract : contracts()) {
      for (String direction : new String[] {"request", "response"}) {
        if (!contract.path(direction).isNull()) {
          String name =
              "com.bintime."
                  + contract.path("domain").asText()
                  + ".dto."
                  + contract.path(direction).asText();
          Object dto =
              mapper.treeToValue(contract.path(direction + "Example"), Class.forName(name));
          assertThat(dto).as(name).isNotNull();
          assertThat(mapper.valueToTree(dto).isObject()).isTrue();
        }
      }
    }
  }

  @Test
  void controllerSkeletonsReturnOnlyNotImplementedWithoutDependencies() throws Exception {
    for (JsonNode contract : contracts()) {
      if (contract.path("id").asText().startsWith("S-")
          || contract.path("id").asText().startsWith("ST-")) continue;
      if (IMPLEMENTED.contains(contract.path("id").asText())) continue;
      String name =
          "com.bintime."
              + contract.path("domain").asText()
              + ".controller."
              + contract.path("controller").asText();
      Class<?> type = Class.forName(name);
      var method =
          java.util.Arrays.stream(type.getDeclaredMethods())
              .filter(m -> m.getName().equals(contract.path("methodName").asText()))
              .findFirst()
              .orElseThrow();
      Object controller = type.getConstructor().newInstance();
      try {
        method.invoke(controller, new Object[method.getParameterCount()]);
        org.junit.jupiter.api.Assertions.fail("Stub must throw a 501 status exception");
      } catch (java.lang.reflect.InvocationTargetException exception) {
        assertThat(exception.getCause())
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(
                ((org.springframework.web.server.ResponseStatusException) exception.getCause())
                    .getStatusCode()
                    .value())
            .isEqualTo(501);
      }
    }
  }

  private JsonNode contracts() throws Exception {
    try (var stream = getClass().getResourceAsStream("/contracts/notion-api.json")) {
      return mapper.readTree(stream);
    }
  }

  private JsonNode getDoc(String group) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs/" + group))
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  private int operationCount(JsonNode doc) {
    int count = 0;
    for (JsonNode path : doc.path("paths")) count += path.size();
    return count;
  }

  private JsonNode resolve(JsonNode doc, JsonNode schema) {
    while (schema.has("$ref")) {
      schema = doc.at(schema.path("$ref").asText().substring(1));
    }
    return schema;
  }

  private void assertExampleFields(JsonNode doc, JsonNode schema, JsonNode example) {
    schema = resolve(doc, schema);
    assertThat(schema.isMissingNode()).as("Missing schema for %s", example).isFalse();
    if (example.isObject()) {
      for (var property : example.properties()) {
        JsonNode field = schema.path("properties").path(property.getKey());
        assertThat(field.isMissingNode()).as("Missing field %s", property.getKey()).isFalse();
        assertExampleFields(doc, field, property.getValue());
      }
    } else if (example.isArray()) {
      for (JsonNode item : example) assertExampleFields(doc, schema.path("items"), item);
    }
  }
}
