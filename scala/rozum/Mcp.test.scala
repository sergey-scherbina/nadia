package nadia.rozum

import java.nio.file.Files

/** The parts of the MCP wiring that decide what happens BEFORE a server is reached: the
  * config shape, the refusals, and the naming. Connecting one is exercised live (`docs/`),
  * because a fake server would only test the fake.
  */
class McpSuite extends munit.FunSuite:

  private def parse(json: String): List[Mcp.ServerSpec] =
    val f = Files.createTempFile("mcp", ".json")
    Files.writeString(f, json)
    Mcp.load(f).fold(e => fail(e), identity)

  test("reads the ecosystem's mcpServers shape, so an existing config works unchanged"):
    val specs = parse("""{"mcpServers":{"rozum":{"command":"rozum","args":["mcp-proxy"],"env":{"A":"b"}}}}""")
    assertEquals(specs.length, 1)
    assertEquals(specs.head.name, "rozum")
    assertEquals(specs.head.command, Some("rozum"))
    assertEquals(specs.head.args, List("mcp-proxy"))
    assertEquals(specs.head.env, Map("A" -> "b"))
    assertEquals(Mcp.stdioRefusal(specs.head), None)

  test("an http entry is refused by name rather than skipped"):
    val specs = parse("""{"mcpServers":{"remote":{"url":"https://example.com/mcp"}}}""")
    val why = Mcp.stdioRefusal(specs.head).getOrElse(fail("must refuse a url entry"))
    assert(why.contains("remote"), why)
    assert(why.contains("stdio"), why)
    assert(why.contains("https://example.com/mcp"), why)

  test("a config the caller asked for is an error when missing, never an empty list"):
    val missing = Files.createTempDirectory("mcp").resolve("nope.json")
    assert(Mcp.load(missing).isLeft, "a missing --mcp-config must not read as 'no servers'")

  test("selecting an unknown server lists the real ones"):
    val specs = parse("""{"mcpServers":{"rozum":{"command":"r"},"fs":{"command":"f"}}}""")
    assertEquals(Mcp.select(specs, List("rozum"), false).map(_.map(_.name)), Right(List("rozum")))
    assertEquals(Mcp.select(specs, Nil, true).map(_.length), Right(2))
    val err = Mcp.select(specs, List("rozom"), false).left.getOrElse(fail("must refuse a typo"))
    assert(err.contains("rozom") && err.contains("rozum") && err.contains("fs"), err)

  test("names are prefixed, so the six can never be shadowed"):
    assertEquals(Mcp.prefixed("rozum", "meeting.submit"), "mcp__rozum__meeting.submit")
    List("read_file", "write_file", "edit_file", "list_dir", "grep", "bash").foreach { builtin =>
      assertNotEquals(Mcp.prefixed("evil", builtin), builtin)
      assert(!Mcp.isMcpTool(builtin), s"$builtin must not read as an MCP tool")
    }
    assert(Mcp.isMcpTool("mcp__rozum__meeting.submit"))
