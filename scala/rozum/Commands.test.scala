package nadia.rozum

/** The help is rendered from the table, and these are the properties that make that worth
  * doing: the formats carry the arguments, the spellings a user actually types resolve, and
  * a typo gets the names rather than the page.
  */
class CommandsSuite extends munit.FunSuite:

  test("every command carries its arguments in the format, and both descriptions"):
    Commands.all.foreach { c =>
      assert(c.format.contains(c.name.stripPrefix("/")), s"${c.name}: format must show the command")
      assert(c.short.nonEmpty && c.long.nonEmpty, s"${c.name} needs both descriptions")
      assert(c.short.length <= 60, s"${c.name} short line is too long for a column: ${c.short}")
    }

  test("help resolves the spellings a user actually types"):
    List("/help", "help", "?", "/?", "HELP", " help ").foreach { spelling =>
      assertEquals(Commands.find(spelling).map(_.name), Some("/help"), spelling)
    }
    assertEquals(Commands.find("tools").map(_.name), Some("/tools"))
    assertEquals(Commands.find("/TOOLS").map(_.name), Some("/tools"))
    assertEquals(Commands.find("nonesuch"), None)

  test("the bare page lists every command with its format and short line"):
    val page = Commands.helpAll
    Commands.all.foreach { c =>
      assert(page.contains(c.format), s"page is missing ${c.format}")
      assert(page.contains(c.short), s"page is missing the short line of ${c.name}")
    }

  test("detailed help is format, short and the wrapped paragraph"):
    val one = Commands.helpOne("mcp").getOrElse(fail("/mcp is a command"))
    assert(one.contains("/mcp"), one)
    assert(one.contains("separate process"), one)
    assert(one.linesIterator.size >= 4, s"the long text should wrap: $one")
    assert(one.linesIterator.forall(_.length <= 80), s"wrapped too wide: $one")
    assertEquals(Commands.helpOne("nonesuch"), None)

  test("an unknown name gets the names, not the page"):
    val msg = Commands.unknown("/tol")
    assert(msg.contains("/tol"), msg)
    assert(msg.contains("/tools"), msg)
    assert(!msg.contains("costs schema tokens"), s"must not dump the page: $msg")

  test("help is a command only when it is the whole line's first word"):
    assertEquals(Commands.helpRequest("help"), Some(None))
    assertEquals(Commands.helpRequest("?"), Some(None))
    assertEquals(Commands.helpRequest("/help"), Some(None))
    assertEquals(Commands.helpRequest("help tools"), Some(Some("tools")))
    assertEquals(Commands.helpRequest("? /tools"), Some(Some("/tools")))
    // A sentence that merely CONTAINS the word is a message for the model.
    assertEquals(Commands.helpRequest("please help"), None)
    assertEquals(Commands.helpRequest("/tools"), None)
