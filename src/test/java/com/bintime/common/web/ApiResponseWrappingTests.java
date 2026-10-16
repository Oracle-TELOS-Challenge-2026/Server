package com.bintime.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bintime.common.dto.ApiError;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.common.dto.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ApiResponseWrappingTests {
  private final JsonMapper mapper = JsonMapper.builder().build();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiResponseAdvice(mapper), new ApiExceptionHandler())
            .build();
  }

  @Test
  void rawDtoIsWrappedAndMetadataContainsOnlyTimestampAndPath() throws Exception {
    JsonNode body = json("/api/v1/probe/raw?unused=hidden");
    assertThat(body.size()).isEqualTo(4);
    assertThat(body.path("resultType").asText()).isEqualTo("SUCCESS");
    assertThat(body.path("success").size()).isEqualTo(1);
    assertThat(body.at("/success/data/label").asText()).isEqualTo("sample");
    assertThat(body.has("error")).isTrue();
    assertThat(body.path("error").isNull()).isTrue();
    assertThat(body.path("meta").size()).isEqualTo(2);
    assertThat(body.at("/meta/path").asText()).isEqualTo("/api/v1/probe/raw");
    assertThat(body.at("/meta/timestamp").asText())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
  }

  @Test
  void existingSuccessEnvelopeIsNotWrappedTwice() throws Exception {
    JsonNode body = json("/api/v1/probe/wrapped");
    assertThat(body.at("/success/data/label").asText()).isEqualTo("sample");
    assertThat(body.at("/success/data/resultType").isMissingNode()).isTrue();
  }

  @Test
  void stringsAndEmptySuccessBodiesUseTheSameEnvelope() throws Exception {
    assertThat(json("/api/v1/probe/string").at("/success/data").asText()).isEqualTo("hello");
    JsonNode empty = json("/api/v1/probe/empty");
    assertThat(empty.path("resultType").asText()).isEqualTo("SUCCESS");
    assertThat(empty.at("/success/data").isNull()).isTrue();
  }

  @Test
  void exceptionsAndMalformedRequestsUseFailureEnvelopeAndPreserveStatus() throws Exception {
    JsonNode failure =
        mapper.readTree(
            mvc.perform(get("/api/v1/probe/unimplemented"))
                .andExpect(status().isNotImplemented())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertFailure(failure, "NOT_IMPLEMENTED");

    JsonNode missing =
        mapper.readTree(
            mvc.perform(get("/api/v1/probe/required"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertFailure(missing, "BAD_REQUEST");

    JsonNode malformed =
        mapper.readTree(
            mvc.perform(post("/api/v1/probe/body").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertFailure(malformed, "BAD_REQUEST");
  }

  @Test
  void explicitFailureEnvelopeKeepsItsDomainError() throws Exception {
    JsonNode body =
        mapper.readTree(
            mvc.perform(get("/api/v1/probe/conflict"))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertFailure(body, "SLOT_UNAVAILABLE");
    assertThat(body.at("/meta/path").asText()).isEqualTo("/api/v1/probe/conflict");
  }

  @Test
  void streamingEventsRemainSseFrames() throws Exception {
    var started =
        mvc.perform(get("/api/v1/probe/stream").accept("text/event-stream"))
            .andExpect(status().isOk())
            .andReturn();
    var result = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
    assertThat(result.getResponse().getContentAsString())
        .contains("event:probe", "data:{\"label\":\"event\"}")
        .doesNotContain("resultType", "success");
  }

  @Test
  void noContentRedirectAndNonApiDocumentsKeepTheirProtocol() throws Exception {
    mvc.perform(get("/api/v1/probe/no-content"))
        .andExpect(status().isNoContent())
        .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    mvc.perform(get("/api/v1/probe/redirect"))
        .andExpect(status().isFound())
        .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    JsonNode doc = json("/v3/probe");
    assertThat(doc.path("label").asText()).isEqualTo("document");
    assertThat(doc.has("resultType")).isFalse();
  }

  private JsonNode json(String path) throws Exception {
    return mapper.readTree(
        mvc.perform(get(path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void assertFailure(JsonNode body, String code) {
    assertThat(body.size()).isEqualTo(4);
    assertThat(body.path("resultType").asText()).isEqualTo("FAILURE");
    assertThat(body.has("success")).isTrue();
    assertThat(body.path("success").isNull()).isTrue();
    assertThat(body.at("/error/code").asText()).isEqualTo(code);
    assertThat(body.path("meta").size()).isEqualTo(2);
  }

  @RestController
  @org.springframework.boot.test.context.TestComponent
  static class ProbeController {
    @GetMapping("/api/v1/probe/raw")
    Sample raw() {
      return new Sample("sample");
    }

    @GetMapping("/api/v1/probe/wrapped")
    ApiResponse<Sample> wrapped() {
      return ApiResponse.success(new Sample("sample"));
    }

    @GetMapping("/api/v1/probe/string")
    String text() {
      return "hello";
    }

    @GetMapping("/api/v1/probe/empty")
    ResponseEntity<ApiResponse<Sample>> empty() {
      return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/probe/unimplemented")
    Sample unimplemented() {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @GetMapping("/api/v1/probe/required")
    Sample required(@RequestParam("id") String id) {
      return new Sample(id);
    }

    @PostMapping("/api/v1/probe/body")
    Sample body(@RequestBody Sample sample) {
      return sample;
    }

    @GetMapping("/api/v1/probe/conflict")
    ResponseEntity<ApiErrorResponse> conflict() {
      return ResponseEntity.status(409)
          .body(
              new ApiErrorResponse(
                  new ApiError("SLOT_UNAVAILABLE", "이미 예약된 시간이에요.", List.of(), false), null));
    }

    @GetMapping("/api/v1/probe/no-content")
    ResponseEntity<Void> noContent() {
      return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/api/v1/probe/stream", produces = "text/event-stream")
    SseEmitter stream() throws java.io.IOException {
      var emitter = new SseEmitter();
      emitter.send(SseEmitter.event().name("probe").data(new Sample("event")));
      emitter.complete();
      return emitter;
    }

    @GetMapping("/api/v1/probe/redirect")
    ResponseEntity<Void> redirect() {
      return ResponseEntity.status(302).header("Location", "/login").build();
    }

    @GetMapping("/v3/probe")
    Sample document() {
      return new Sample("document");
    }
  }

  record Sample(String label) {}
}
