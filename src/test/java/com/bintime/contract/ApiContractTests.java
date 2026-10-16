package com.bintime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "api-docs"})
class ApiContractTests {
  private final JsonMapper mapper = JsonMapper.builder().build();
  @LocalServerPort private int port;

  @Test
  void allNotionHttpOperationsHaveMatchingSwaggerContracts() throws Exception {
    JsonNode allDoc = getDoc("all");
    JsonNode publicDoc = getDoc("public");
    JsonNode internalDoc = getDoc("internal");
    assertThat(operationCount(publicDoc)).isEqualTo(43);
    assertThat(operationCount(internalDoc)).isEqualTo(2);
    assertThat(operationCount(allDoc)).isEqualTo(45);
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
      assertThat(operation.path("responses").has("501")).isTrue();
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
    assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(501);
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
