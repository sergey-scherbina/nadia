package nadia.cloud

import agent.Auth
import java.nio.file.{Files, Path}

class ProviderSuite extends munit.FunSuite:

  private val local = "http://127.0.0.1:8080/v1"

  private def keyFile(contents: String): Path =
    val p = Files.createTempFile("nadia-key", "")
    Files.writeString(p, contents)
    p

  /** The environment of the machine running the tests is not ours to control, and every
    * fallback here reads it. Skip rather than fail when a variable is already set —
    * a red test that only reproduces on one laptop teaches people to ignore red tests.
    */
  private def withoutEnv(names: String*)(body: => Unit): Unit =
    val set = names.filter(sys.env.contains)
    assume(set.isEmpty, s"set in this environment: ${set.mkString(", ")}")
    body

  private def resolve(
      provider: String,
      model: String = "m",
      region: Option[String] = None,
      project: Option[String] = None,
      key: Option[String] = None
  ) = Provider.resolve(provider, local, model, region, project, key)

  test("local is the default and needs no credential") {
    withoutEnv("NADIA_API_KEY", "OPENAI_API_KEY") {
      val ep = resolve("local").fold(fail(_), identity)
      assertEquals(ep.baseUrl, local)
      assertEquals(ep.auth, Auth.Anonymous)
      // `local` as a model id means "whatever you have resident" — fine here, and only here.
      assert(resolve("local", model = "local").isRight)
    }
  }

  test("a key file is read, trimmed, and becomes a bearer token") {
    val f = keyFile("  sk-from-a-mounted-secret\n")
    val ep = resolve("local", key = Some(f.toString)).fold(fail(_), identity)
    assertEquals(ep.auth, Auth.Bearer("sk-from-a-mounted-secret"))
  }

  test("an unreadable or empty key file is refused by name") {
    val missing = resolve("local", key = Some("/nonexistent/key"))
    assert(missing.left.exists(_.contains("not readable")), missing.toString)
    val empty = resolve("local", key = Some(keyFile("   \n").toString))
    assert(empty.left.exists(_.contains("is empty")), empty.toString)
  }

  test("huggingface routes through the documented OpenAI-compatible endpoint") {
    val ep = resolve("huggingface", model = "Qwen/Qwen3.5-4B-Instruct",
      key = Some(keyFile("hf_token").toString)).fold(fail(_), identity)
    assertEquals(ep.baseUrl, "https://router.huggingface.co/v1")
    assertEquals(ep.url, "https://router.huggingface.co/v1/chat/completions")
    assertEquals(ep.auth, Auth.Bearer("hf_token"))
    // The provider-selection suffix the router documents is part of the model id, not ours
    // to interpret — it must pass through untouched.
    val pinned = resolve("hf", model = "openai/gpt-oss-120b:cheapest",
      key = Some(keyFile("t").toString)).fold(fail(_), identity)
    assertEquals(pinned.model, "openai/gpt-oss-120b:cheapest")
  }

  test("a weights repository goes to the local runtime, not to the router") {
    // This is the repository the project's own local model comes from, so it is the first id
    // its operator will type. Nobody hosts MLX or GGUF builds behind an API — they are files
    // to download — so the request can only be answered by a local runtime, and that is where
    // it goes. No token is involved: nothing is asked of the Hub at inference time.
    withoutEnv("NADIA_API_KEY", "OPENAI_API_KEY", "HF_TOKEN", "HUGGING_FACE_HUB_TOKEN") {
      List("mlx-community/Qwen3.5-4B-MLX-4bit", "bartowski/Qwen3.5-4B-GGUF").foreach { id =>
        val ep = resolve("huggingface", model = id).fold(m => fail(s"$id: $m"), identity)
        assertEquals(ep.baseUrl, local, s"$id must be served locally")
        assertEquals(ep.auth, Auth.Anonymous, s"$id needs no Hub credential")
        assertEquals(ep.model, id, "the id passes through — the gateway resolves the repository")
        assert(Provider.servedLocally("huggingface", id))
      }
    }
    // A partner-hosted repository still goes to the router, and still needs a token.
    val hosted = resolve("huggingface", model = "Qwen/Qwen3.5-4B-Instruct",
      key = Some(keyFile("t").toString)).fold(fail(_), identity)
    assertEquals(hosted.baseUrl, "https://router.huggingface.co/v1")
    assert(!Provider.servedLocally("huggingface", "Qwen/Qwen3.5-4B-Instruct"))
  }

  test("bedrock builds the documented endpoint") {
    val ep = resolve("bedrock", model = "us.anthropic.claude-sonnet-4-6", region = Some("us-east-1"),
      key = Some(keyFile("bedrock-key").toString)).fold(fail(_), identity)
    assertEquals(ep.baseUrl, "https://bedrock-mantle.us-east-1.api.aws/v1")
    // The base already ends in /v1 and must not gain a second one.
    assertEquals(ep.url, "https://bedrock-mantle.us-east-1.api.aws/v1/chat/completions")
  }

  test("vertex builds the documented endpoint, regional and global") {
    val ep = resolve("vertex", model = "google/gemini-3-flash", region = Some("europe-west4"),
      project = Some("my-project")).fold(fail(_), identity)
    assertEquals(
      ep.baseUrl,
      "https://europe-west4-aiplatform.googleapis.com/v1/projects/my-project/locations/europe-west4/endpoints/openapi"
    )
    // The path must survive normalization intact — this is what /v1-appending broke.
    assertEquals(ep.url, s"${ep.baseUrl}/chat/completions")
    // No static key, so the token is fetched per request rather than once at startup.
    assert(ep.auth.isInstanceOf[Auth.Fresh], ep.auth.toString)

    val global = resolve("vertex", model = "google/gemini-3-flash", region = Some("global"),
      project = Some("p")).fold(fail(_), identity)
    assert(global.baseUrl.startsWith("https://aiplatform.googleapis.com/"), global.baseUrl)
  }

  test("a missing value is refused by the name of the flag that supplies it") {
    withoutEnv("AWS_REGION", "AWS_DEFAULT_REGION", "AWS_BEARER_TOKEN_BEDROCK", "NADIA_API_KEY", "OPENAI_API_KEY") {
      val noRegion = resolve("bedrock", model = "m")
      assert(noRegion.left.exists(_.contains("--region")), noRegion.toString)
      val noKey = resolve("bedrock", model = "m", region = Some("us-east-1"))
      assert(noKey.left.exists(_.contains("AWS_BEARER_TOKEN_BEDROCK")), noKey.toString)
    }
    withoutEnv("GOOGLE_CLOUD_PROJECT", "GCP_PROJECT") {
      val noProject = resolve("vertex", model = "m", region = Some("us-central1"))
      assert(noProject.left.exists(_.contains("--project")), noProject.toString)
    }
    withoutEnv("NADIA_API_KEY", "OPENAI_API_KEY") {
      val noKey = resolve("openai", model = "gpt-4o")
      assert(noKey.left.exists(_.contains("NADIA_API_KEY")), noKey.toString)
    }
    withoutEnv("NADIA_API_KEY", "OPENAI_API_KEY", "HF_TOKEN", "HUGGING_FACE_HUB_TOKEN") {
      val noToken = resolve("huggingface", model = "Qwen/Qwen3.5-4B-Instruct")
      assert(noToken.left.exists(_.contains("HF_TOKEN")), noToken.toString)
    }
  }

  test("the resident-model placeholder is refused for a hosted provider") {
    // `--model local` against Bedrock is a 404 on a model name, and that error does not
    // say "you forgot --model".
    val r = resolve("bedrock", model = "local", region = Some("us-east-1"),
      key = Some(keyFile("k").toString))
    assert(r.left.exists(m => m.contains("--model") && m.contains("us.anthropic")), r.toString)
  }

  test("an unknown provider lists the ones that exist") {
    val r = resolve("azure")
    assert(r.left.exists(m => m.contains("azure") && m.contains("bedrock")), r.toString)
  }
