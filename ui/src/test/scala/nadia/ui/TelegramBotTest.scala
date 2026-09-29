package nadia.ui

import okay.*
import okay.given
import okay.codec.Json
import okay.http.{Http, Request, Response}
import okay.telegram.{Bot, Update}
import okay.ui.Event

/** the real bot's two rules — access and typing-is-writing — over a recording Bot API */
class TelegramBotTest extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(20, "s")
  def go[A](p: A ! Async): A = Async.run[A, Pure](p).runWith

  final class Api extends Http:
    val calls = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
    def send(r: Request): Response ! Async = async {
      val m = r.url.substring(r.url.lastIndexOf('/') + 1)
      calls.add(m -> new String(r.body.bytes, "UTF-8"))
      val body = m match
        case "sendMessage" => """{"ok":true,"result":{"message_id":1}}"""
        case _ => """{"ok":true,"result":true}"""
      Response(200, Nil, Http.one(body.getBytes("UTF-8")))
    }
    def texts: Vector[String] =
      import scala.jdk.CollectionConverters.*
      calls.asScala.toVector.collect { case (("sendMessage" | "editMessageText"), b) => b }
    def until(p: => Boolean): Unit =
      val end = System.currentTimeMillis + 5000
      while !p && System.currentTimeMillis < end do Thread.sleep(20)
      assert(p, s"waited: ${texts.map(_.take(80))}")

  val me = 42L; val stranger = 666L; val chat = 42L

  test("a stranger is dropped without a word, and logged with the id to allow") {
    val api = Api()
    val shared = Shared(InMemory(), Journal.memory())
    val chats = TelegramBot.chats(Bot(api, "1:T"), shared)
    val log = scala.collection.mutable.ListBuffer.empty[String]
    val h = TelegramBot.handler(Set(me), shared, chats, s => log += s)
    go(h(Update.Message(1, 7, stranger, 1, "/start")))
    Thread.sleep(200)
    assert(api.calls.isEmpty, api.calls.toString)
    assert(log.exists(_.contains("666")), log.toString)
    assert(!chats.opened(7))
  }

  test("the operator: /start draws the workspace; a plain message is the composer, sent to the focused room") {
    val api = Api()
    val world = InMemory()
    val shared = Shared(world, Journal.memory())
    val chats = TelegramBot.chats(Bot(api, "1:T"), shared)
    val h = TelegramBot.handler(Set(me), shared, chats, _ => ())
    go(h(Update.Message(1, chat, me, 1, "/start")))
    api.until(api.texts.exists(_.contains("nadia workspace")))
    // the room is opened (on this device or any other — it is one session)
    shared(Event.Pressed("room.rozum.rozum"))
    api.until(api.texts.exists(_.contains("how is the rerank going")))
    go(h(Update.Message(2, chat, me, 2, "typed straight into the chat")))
    api.until(world.posted(Room("rozum", "rozum")).exists(_.text == "typed straight into the chat"))
    // and the screen shows it, edited in place
    api.until(api.texts.exists(_.contains("typed straight into the chat")))
  }

  test("a plain message to a focused nadia agent is a tell, not a room post") {
    val api = Api()
    val world = InMemory()
    val shared = Shared(world, Journal.memory())
    val chats = TelegramBot.chats(Bot(api, "1:T"), shared)
    val h = TelegramBot.handler(Set(me), shared, chats, _ => ())
    go(h(Update.Message(1, chat, me, 1, "/start")))
    api.until(api.texts.nonEmpty)
    shared(Event.Pressed("agent.nadia-7"))
    go(h(Update.Message(2, chat, me, 2, "run the contract cases too")))
    api.until(world.told.get("nadia-7").contains(Vector("run the contract cases too")))
  }
