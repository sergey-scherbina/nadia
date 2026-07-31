package agent

class EndpointSuite extends munit.FunSuite:
  test("the gateway URL is normalized to one spelling") {
    // rozum launch exports ROZUM_GATEWAY_URL without /v1 and OPENAI_BASE_URL with it;
    // both must land on the same endpoint or the agent talks to a 404.
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/v1"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint("http://h:1", "m").url, "http://h:1/v1/chat/completions")
  }
