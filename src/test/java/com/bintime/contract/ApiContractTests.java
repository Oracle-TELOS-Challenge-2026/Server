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
  private static final Set<String> IMPLEMENTED =
      Set.of("U-01", "U-02", "U-03", "U-04", "U-05", "U-06", "U-07", "U-08", "U-09");
  private final JsonMapper mapper = JsonMapper.builder().build();
  @LocalServerPort private int port;

  @Test
  void nullableObjectsUseSeparateReferenceAndNullAlternatives() throws Exception {
    for (String group : new String[] {"all", "public"}) {
      JsonNode schemas = getDoc(group).at("/components/schemas");
      for (String[] field :
          new String[][] {
            {"UserProfileResponse", "location", "UserLocationResponse"},
            {"ChatRoomResponse", "lastMessage", "ChatMessageResponse"},
            {"StoreSearchResponseItem", "bestSlot", "StoreSearchResponseItemBestSlot"},
            {"WaitlistResponse", "store", "WaitlistResponseStoreSummary"}
          }) {
        JsonNode parent = schemas.path(field[0]);
        JsonNode property = parent.path("properties").path(field[1]);
        assertThat(stringValues(parent.path("required"))).contains(field[1]);
        assertThat(property.has("$ref")).as("%s.%s", field[0], field[1]).isFalse();
        assertThat(property.has("type")).isFalse();
        assertThat(property.path("oneOf").size()).isEqualTo(2);
        assertThat(property.at("/oneOf/0/$ref").asText())
            .isEqualTo("#/components/schemas/" + field[2]);
        assertThat(property.at("/oneOf/1/type").asText()).isEqualTo("null");
      }
      assertNoNullReference(schemas);
      assertThat(schemas.at("/ChatRoomResponse/properties/store/$ref").asText())
          .isEqualTo("#/components/schemas/ChatRoomResponseStore");
    }
  }

  private void assertNoNullReference(JsonNode node) {
    if (node.isObject()) {
      if (node.has("$ref")) {
        JsonNode type = node.path("type");
        assertThat(type.asText()).isNotEqualTo("null");
        if (type.isArray()) assertThat(stringValues(type)).doesNotContain("null");
      }
      for (var property : node.properties()) assertNoNullReference(property.getValue());
    } else if (node.isArray()) {
      for (JsonNode child : node) assertNoNullReference(child);
    }
  }

  @Test
  void storeDocumentsPublicQueriesAndAlternativeAuthenticationMethods() throws Exception {
    JsonNode doc = getDoc("all");
    int protectedCount = 0;
    int publicCount = 0;
    for (var path : doc.path("paths").properties()) {
      for (var method : path.getValue().properties()) {
        JsonNode operation = method.getValue();
        if (!operation.path("tags").toString().contains("store")) continue;
        JsonNode security = operation.path("security");
        if (path.getKey().startsWith("/api/v1/stores/") && method.getKey().equals("get")) {
          assertThat(security.isMissingNode() || security.isEmpty()).isTrue();
          publicCount++;
        } else {
          var schemes = new HashSet<String>();
          for (JsonNode alternative : security) {
            assertThat(alternative.size()).isEqualTo(1);
            for (var scheme : alternative.properties()) schemes.add(scheme.getKey());
          }
          assertThat(schemes).containsExactlyInAnyOrder("sessionCookie", "bearerJwt");
          protectedCount++;
        }
      }
    }
    assertThat(publicCount).isEqualTo(5);
    assertThat(protectedCount).isEqualTo(15);
  }

  @Test
  void allNotionAndLocalExtensionHttpOperationsHaveMatchingSwaggerContracts() throws Exception {
    JsonNode allDoc = getDoc("all");
    JsonNode publicDoc = getDoc("public");
    JsonNode internalDoc = getDoc("internal");
    assertThat(operationCount(publicDoc)).isEqualTo(48);
    assertThat(operationCount(internalDoc)).isEqualTo(2);
    assertThat(operationCount(allDoc)).isEqualTo(50);
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
        assertThat(resolve(doc, envelope.at("/properties/success")).path("type").toString())
            .contains("object");
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
                case "U-04" -> expected.add("401");
                case "U-05", "U-06", "U-08" -> expected.addAll(Set.of("400", "401", "403", "409"));
                case "U-07" -> expected.addAll(Set.of("401", "403"));
                case "ST-06", "ST-16", "S-03", "N-04" -> expected.add("409");
                case "C-01" -> expected.add("409");
                case "C-04" -> expected.addAll(Set.of("409", "429"));
                case "C-05" -> expected.add("400");
                case "C-06" -> expected.addAll(Set.of("409", "429"));
                case "C-07" -> expected.add("409");
                case "N-01" -> expected.addAll(Set.of("400", "401"));
                case "N-02" -> expected.addAll(Set.of("400", "401", "403", "404"));
                case "N-03" -> expected.addAll(Set.of("400", "401"));
                case "N-05" -> expected.addAll(Set.of("401", "403", "404"));
                case "N-06" -> expected.addAll(Set.of("400", "403", "404", "503"));
              }
              if (contract.path("id").asText().startsWith("C-"))
                expected.addAll(Set.of("400", "401", "403", "404"));
              if (contract.path("id").asText().equals("N-04"))
                expected.addAll(Set.of("400", "401", "403"));
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
  void implementedUserWritesDocumentTheRequiredCsrfHeader() throws Exception {
    JsonNode doc = getDoc("public");
    for (String[] endpoint :
        new String[][] {
          {"post", "/api/v1/auth/logout"},
          {"patch", "/api/v1/users/me"},
          {"put", "/api/v1/users/me/location"},
          {"delete", "/api/v1/users/me/location"},
          {"put", "/api/v1/users/me/preferences"},
          {"post", "/api/v1/notifications/push-subscriptions"},
          {"delete", "/api/v1/notifications/push-subscriptions/{subscriptionId}"},
          {"patch", "/api/v1/notifications/{notificationId}/read"},
          {"post", "/api/v1/chatting/rooms"},
          {"post", "/api/v1/chatting/rooms/{roomId}/messages"},
          {"put", "/api/v1/chatting/rooms/{roomId}/read"},
          {"post", "/api/v1/chatting/rooms/{roomId}/booking-proposals"},
          {"post", "/api/v1/chatting/rooms/{roomId}/booking-proposals/{proposalId}/confirm"}
        }) {
      JsonNode operation = doc.path("paths").path(endpoint[1]).path(endpoint[0]);
      JsonNode csrf = parameter(operation, "X-CSRF-Token");
      assertThat(csrf.path("in").asText()).isEqualTo("header");
      assertThat(csrf.path("required").asBoolean())
          .as("CSRF header for %s %s", endpoint[0], endpoint[1])
          .isTrue();
    }
  }

  @Test
  void chatDocumentsOnlyCookieAuthenticationAndNoPrincipalInput() throws Exception {
    JsonNode doc = getDoc("public");
    int count = 0;
    for (var path : doc.path("paths").properties()) {
      if (!path.getKey().startsWith("/api/v1/chatting/")) continue;
      for (JsonNode operation : path.getValue()) {
        var security = operation.path("security");
        assertThat(security.size()).isEqualTo(1);
        assertThat(security.get(0).propertyNames()).containsExactly("sessionCookie");
        for (JsonNode parameter : operation.path("parameters")) {
          assertThat(parameter.path("name").asText()).isNotEqualTo("principal");
        }
        count++;
      }
    }
    assertThat(count).isEqualTo(7);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chatting/rooms"))
            .GET()
            .build();
    assertThat(
            HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void bookingProposalsDocumentStoredQuotesVersionOnlyConfirmationAndNullableCards()
      throws Exception {
    JsonNode doc = getDoc("public");
    JsonNode schemas = doc.path("components").path("schemas");
    JsonNode create = schemas.path("CreateBookingProposalRequest");
    assertThat(create.path("properties").propertyNames())
        .containsExactlyInAnyOrder(
            "sourceMessageId",
            "serviceId",
            "serviceDescription",
            "startAt",
            "durationMinutes",
            "price",
            "waitlistId");
    assertThat(stringValues(create.path("required")))
        .contains(
            "sourceMessageId",
            "serviceId",
            "serviceDescription",
            "startAt",
            "durationMinutes",
            "price")
        .doesNotContain("waitlistId");
    assertThat(create.at("/properties/serviceDescription/maxLength").asInt()).isEqualTo(1000);
    assertThat(create.at("/properties/startAt/format").asText()).isEqualTo("date-time");
    assertThat(create.at("/properties/durationMinutes/minimum").asInt()).isEqualTo(15);
    assertThat(create.at("/properties/durationMinutes/maximum").asInt()).isEqualTo(480);
    assertThat(create.at("/properties/durationMinutes/multipleOf").asInt()).isEqualTo(5);
    assertThat(create.at("/properties/price/minimum").asLong()).isZero();
    assertThat(create.at("/properties/price/maximum").asLong()).isEqualTo(10000000L);

    JsonNode confirm = schemas.path("ConfirmBookingProposalRequest");
    assertThat(confirm.path("properties").propertyNames()).containsExactly("expectedVersion");
    assertThat(stringValues(confirm.path("required"))).containsExactly("expectedVersion");
    assertThat(confirm.at("/properties/expectedVersion/minimum").asLong()).isEqualTo(1L);

    JsonNode message = schemas.path("ChatMessageResponse");
    assertThat(stringValues(message.at("/properties/type/enum")))
        .containsExactlyInAnyOrder("TEXT", "BOOKING_PROPOSAL");
    JsonNode proposal = message.at("/properties/bookingProposal");
    assertThat(stringValues(message.path("required"))).doesNotContain("bookingProposal");
    assertThat(proposal.at("/oneOf/0/$ref").asText())
        .as("nullable proposal schema: %s", proposal)
        .isEqualTo("#/components/schemas/ChatBookingProposalResponse");
    assertThat(proposal.at("/oneOf/1/type").asText()).isEqualTo("null");
    JsonNode card = resolve(doc, proposal);
    assertThat(card.path("properties").propertyNames())
        .containsExactlyInAnyOrder(
            "id",
            "sourceMessageId",
            "serviceId",
            "serviceDescription",
            "startAt",
            "endAt",
            "durationMinutes",
            "price",
            "status",
            "version",
            "reservationId",
            "waitlistId");
    assertThat(stringValues(card.at("/properties/status/enum")))
        .containsExactlyInAnyOrder("PENDING", "CONFIRMED", "SUPERSEDED", "REVIEW_REQUIRED");

    for (String path :
        new String[] {
          "/api/v1/chatting/rooms/{roomId}/booking-proposals",
          "/api/v1/chatting/rooms/{roomId}/booking-proposals/{proposalId}/confirm"
        }) {
      JsonNode operation = doc.path("paths").path(path).path("post");
      JsonNode key = parameter(operation, "Idempotency-Key");
      assertThat(key.path("in").asText()).isEqualTo("header");
      assertThat(key.path("required").asBoolean()).isTrue();
      assertThat(operation.path("description").asText()).contains("24시간", "X-CSRF-Token");
    }
  }

  @Test
  void kakaoCallbackDocumentsOptionalParametersAndConditionalSuccessFailureRules()
      throws Exception {
    JsonNode callback =
        getDoc("public").path("paths").path("/api/v1/auth/oauth/kakao/callback").path("get");
    for (String name : new String[] {"code", "state", "error", "error_description"}) {
      JsonNode parameter = parameter(callback, name);
      assertThat(parameter.path("in").asText()).isEqualTo("query");
      assertThat(parameter.path("required").asBoolean()).as("callback %s", name).isFalse();
    }
    assertThat(callback.path("description").asText())
        .contains("성공", "code", "state", "실패", "error", "error_description");
  }

  @Test
  void fcmSubscriptionSchemaUsesInstallationIdAndObjectSuccessData() throws Exception {
    JsonNode doc = getDoc("public");
    JsonNode request =
        doc.path("components").path("schemas").path("RegisterPushSubscriptionRequest");
    assertThat(request.path("properties").propertyNames()).containsExactly("installationId");
    assertThat(stringValues(request.path("required"))).contains("installationId");
    assertThat(request.at("/properties/installationId/maxLength").asInt()).isEqualTo(128);
    var response =
        doc.at(
            "/paths/~1api~1v1~1notifications~1push-subscriptions/post/responses/201/content/application~1json/schema");
    var envelope = resolve(doc, response);
    var success = resolve(doc, envelope.at("/properties/success"));
    var data = success.at("/properties/data");
    assertThat(data.path("type").toString()).isNotEqualTo("\"null\"").isNotEqualTo("[\"null\"]");
    assertThat(resolve(doc, data).path("properties").has("id")).isTrue();
    var failure = doc.at("/components/schemas/ApiErrorResponse");
    assertThat(failure.at("/properties/error/type").toString())
        .isNotEqualTo("\"null\"")
        .isNotEqualTo("[\"null\"]");
  }

  @Test
  void preferencesTextHasAnOptionalRequestAndRequiredResponseWithoutMovingVersion()
      throws Exception {
    JsonNode schemas = getDoc("public").path("components").path("schemas");
    JsonNode request = schemas.path("SavePreferencesRequest");
    JsonNode requestText = request.path("properties").path("text");
    assertNonNullableString(requestText);
    assertThat(requestText.path("maxLength").asInt()).isEqualTo(2000);
    assertThat(stringValues(request.path("required"))).doesNotContain("text");
    assertThat(stringValues(request.path("required")))
        .contains("categories", "keywords", "maxPrice", "expectedVersion");

    for (String name : new String[] {"UserProfileResponsePreferences", "UserPreferencesResponse"}) {
      JsonNode response = schemas.path(name);
      assertNonNullableString(response.path("properties").path("text"));
      assertThat(stringValues(response.path("required"))).as(name).contains("text");
    }

    JsonNode profile = schemas.path("UserProfileResponse");
    assertThat(stringValues(profile.path("required"))).contains("version");
    assertThat(profile.path("properties").has("version")).isTrue();
    assertThat(schemas.path("UserProfileResponsePreferences").path("properties").has("version"))
        .isFalse();
    assertThat(stringValues(schemas.path("UserPreferencesResponse").path("required")))
        .contains("version");
  }

  @Test
  void documentationProfileDisablesUserExecutionAndKeepsProtectedSearchClosed() throws Exception {
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
  void dtoTypesAcceptAllNotionAndLocalExtensionRequestAndResponseExamples() throws Exception {
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
      if (isImplemented(contract.path("id").asText())) continue;
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
    var combined = mapper.createArrayNode();
    // Local feature requests extend the contract without rewriting the original Notion snapshot.
    for (String resource :
        new String[] {"/contracts/notion-api.json", "/contracts/chat-booking-proposals.json"}) {
      try (var stream = getClass().getResourceAsStream(resource)) {
        assertThat(stream).as(resource).isNotNull();
        for (JsonNode contract : mapper.readTree(stream)) combined.add(contract);
      }
    }
    return combined;
  }

  private boolean isImplemented(String id) {
    return IMPLEMENTED.contains(id)
        || id.startsWith("S-")
        || id.startsWith("ST-")
        || id.startsWith("N-")
        || id.startsWith("C-");
  }

  private JsonNode parameter(JsonNode operation, String name) {
    for (JsonNode parameter : operation.path("parameters")) {
      if (parameter.path("name").asText().equals(name)) return parameter;
    }
    throw new AssertionError("Missing parameter " + name + " for " + operation.path("operationId"));
  }

  private Set<String> stringValues(JsonNode values) {
    var result = new HashSet<String>();
    for (JsonNode value : values) result.add(value.asText());
    return result;
  }

  private void assertNonNullableString(JsonNode schema) {
    JsonNode type = schema.path("type");
    Set<String> types = type.isArray() ? stringValues(type) : Set.of(type.asText());
    assertThat(types).containsExactly("string");
    assertThat(schema.path("nullable").asBoolean()).isFalse();
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
    if (schema.has("oneOf")) {
      for (JsonNode alternative : schema.path("oneOf")) {
        if (alternative.has("$ref")) return resolve(doc, alternative);
      }
    }
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
