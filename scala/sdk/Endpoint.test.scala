package agent

class EndpointSuite extends munit.FunSuite:
  test("a bare origin is completed to /v1") {
    // Both spellings of the same base URL must land on the same endpoint, or an agent
    // handed the other one talks to a 404.
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint.withV1("http://127.0.0.1:8080/v1"), "http://127.0.0.1:8080/v1")
    assertEquals(Endpoint("http://h:1", "m").url, "http://h:1/v1/chat/completions")
  }

  test("a base URL that already has a path is left alone") {
    // Vertex AI's OpenAI-compatible route is a path, not an origin. Completing it produced
    // `…/openapi/v1/chat/completions` — a 404 indistinguishable from a wrong project.
    val vertex = "https://europe-west4-aiplatform.googleapis.com/v1/projects/p/locations/europe-west4/endpoints/openapi"
    assertEquals(Endpoint.withV1(vertex), vertex)
    assertEquals(Endpoint(vertex, "google/gemini-3-flash").url, s"$vertex/chat/completions")
    // Bedrock's already ends in /v1, and must not gain a second one.
    assertEquals(
      Endpoint.withV1("https://bedrock-mantle.us-east-1.api.aws/v1"),
      "https://bedrock-mantle.us-east-1.api.aws/v1"
    )
  }

  test("no credential is the default, and a key never prints") {
    assertEquals(Endpoint("http://h:1", "m").auth, Auth.Anonymous)
    assertEquals(Auth.Anonymous.header, Right(None))
    assertEquals(Auth.Bearer("sk-secret").header, Right(Some("Bearer sk-secret")))
    // The redaction is the point: this value reaches log lines through Endpoint.toString.
    assert(!Auth.Bearer("sk-secret").toString.contains("sk-secret"))
    assert(!Endpoint("http://h:1", "m", Auth.Bearer("sk-secret")).toString.contains("sk-secret"))
  }

  test("a supplier that cannot produce a token fails the request, not the process") {
    assertEquals(Auth.Fresh(() => Left("metadata server unreachable")).header, Left("metadata server unreachable"))
    var calls = 0
    val fresh = Auth.Fresh { () => calls += 1; Right(s"t$calls") }
    // Re-asked every time: a token cached for the life of the client outlives its own expiry.
    assertEquals(fresh.header, Right(Some("Bearer t1")))
    assertEquals(fresh.header, Right(Some("Bearer t2")))
  }
