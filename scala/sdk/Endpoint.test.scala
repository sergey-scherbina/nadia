package agent

class EndpointSuite extends munit.FunSuite:
  test("the gateway URL is normalized to one spelling") {
    // Both spellings of the same base URL must land on the same endpoint, or an agent
    // handed the other one talks to a 404.
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/v1"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint("http://h:1", "m").url, "http://h:1/v1/chat/completions")
  }
