package nadia

/** the build compiles against the platform — nothing else yet; the
 * screens, the fleet, the access roster and the models seam each get
 * their own suite as docs/specs/app.md's behavior items are implemented */
class TestBuild extends munit.FunSuite:
  test("okay-ui, okay-agent, okay-telegram and okay-security are on the classpath"):
    val ui: okay.ui.Ui = okay.ui.Ui.Text("nadia")
    val turn: okay.agent.Turn = okay.agent.Turn.User("hello")
    val key: okay.ui.Telegram.Key = okay.ui.Telegram.Key.Press("Home", "home")
    val decision = okay.security.Policy.role("owner")
    assertEquals(ui, okay.ui.Ui.Text("nadia"))
    assert(turn != null && key != null && decision != null)
