//> using scala 3.6.3
//> using dep com.lihaoyi::upickle:4.4.3
//> using jvm 21
//> using options -deprecation -feature -Wunused:all
//> using test.dep org.scalameta::munit:1.3.4

// nadia — the Scala 3 implementation.
//
// The third of three (see SPEC.md §0). Rust is the executable reference; the ScalaScript
// one dogfoods that language over its `std.agent` SDK; this one is plain Scala 3 on the
// JDK, with no SDK underneath it — so it carries all three contracts of
// `rozum:docs/specs/integration.md` itself: the model client, the agent loop, and the
// tools. That makes it the honest answer to "how much of this is the framework": about
// four hundred lines, and they are all here.
//
// One dependency (upickle, for JSON). HTTP is `java.net.http` from the JDK, processes are
// `ProcessBuilder`, files are `java.nio` — nothing else is needed, and every extra
// dependency would be one more thing to explain in a program whose point is to be legible.
