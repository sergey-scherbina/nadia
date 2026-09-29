package nadia.ui

import okay.*
import okay.given
import okay.telegram.{Bot, Chats, Update}
import okay.ui.Event

/**
 * The workspace in a real Telegram chat (rozum:docs/specs/okay-workspace-ui.md, S3 —
 * stage 2 straight away: okay-telegram is the transport, no Rust bridge in between).
 *
 * `Chats` (okay-telegram) puts okay-ui's chat host behind the bot, one host per chat;
 * every chat attaches to the ONE shared session with the narrow layout, so the phone
 * and the terminal are the same workspace.
 *
 * Two rules on top, both here and nowhere else:
 *
 *  - ACCESS: only the Telegram user ids in `allowed` reach the workspace. Anyone else
 *    is dropped without a word (a bot that answers strangers tells them it exists),
 *    and the refusal is logged with the id, so the operator's first contact shows the
 *    number to put in `NADIA_TELEGRAM_USERS`.
 *  - TYPING IS WRITING: a plain message, when the screen is not waiting for a typed
 *    value, is the composer — it becomes the draft of the focused room or agent and is
 *    sent (a room post, or a `tell` to an agent with an inbox). The pencil button still
 *    works; it is just no longer needed for the common case. `/start` opens the screen.
 */
object TelegramBot:

  def from(u: Update): Option[(Long, Long)] = u match
    case Update.Message(_, chat, from, _, _) => Some((chat, from))
    case Update.Callback(_, chat, from, _, _, _) => Some((chat, from))
    case _ => None

  /** the handler `bot.serve` calls with every update */
  def handler(allowed: Set[Long], shared: Shared, chats: Chats, log: String => Unit): Update => Unit ! Async =
    u => from(u) match
      case None => pure(())
      case Some((chat, user)) if !allowed(user) =>
        async(log(s"refused: user $user in chat $chat — to let them in, add $user to NADIA_TELEGRAM_USERS"))
      case Some((chat, _)) => u match
        case Update.Message(_, _, _, _, text)
            if chats.opened(chat) && !chats.awaiting(chat) && !text.startsWith("/") =>
          async {
            shared(Event.Edited("compose", text))
            shared(Event.Pressed("send"))
            ()
          }
        case Update.Message(_, _, _, _, text) if text.startsWith("/") && chats.opened(chat) =>
          pure(())                                  // /start on an open screen: it is already there
        case _ => chats.hear(u)                     // a press, an answer to the pencil, the first contact

  /** the chats, each a narrow-layout device of the shared session */
  def chats(bot: Bot, shared: Shared, external: () => Source[Event] = () => pure(()))(using Scheduler, CanBlock): Chats =
    Chats(bot, (_, host) => shared.attach(host, Layout.Narrow(), external()).map(_ => ()))
