# notification-service

The way out of the system to a human: it takes notifications from RabbitMQ and posts them on
Discord through a bot (`discord4j`). Reactive (WebFlux), Java 21, Spring Boot 4.1.1, Maven.
Docker image `magikabdul/notification-service`; the pom keeps `0.0.1-SNAPSHOT`, the released
version comes from the git tag.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Consumes: `notification.prod.alert` and `notification.prod.info` on the `/notification`
  virtual host (`notification.dev.*` in the `local` profile). Both are bound to the headers
  exchange `notification` by `category` and `env`; the exchange, the queues and the bindings
  are declared by the broker definitions in `deployment-tools`, not by this service.
- Publishers: `heating-service` (silent temperature sensors, HAS-94). The payload is plain
  text — the listeners take a `String` and there is no JSON converter.
- Calls: the Discord REST API. Every message goes to one text channel (`alerts`), alerts and
  infos alike; the channel is configured by id.
- `GET /home/notification/skippy?message=` sends a text by hand. It is deliberately **not**
  routed by `api-gateway-service`.
- Uses libraries: `cholewa-commons`. No database, no `smart-home-sdk`.

## Build & run

- Build + tests: `mvn verify`
- Local run: `home,local` Spring profiles, port `6003` (Actuator `8003`); in-cluster port
  `6200`, Actuator `8200`. Needs a RabbitMQ broker, the bot token
  (`discord_bot_skippy_token`) and the channel id (`discord_alerts_channel_id`, mandatory).

## Specifics

- **A notification is posted as an embed colored by its level** (`NotificationLevel`: `ERROR`
  red, `WARN` yellow, `INFO` green; the level is the title, the text the description). The
  level is read from the `level` message header; a missing or unknown one falls back to the
  queue's own — alert → `ERROR`, info → `INFO` — because a notification with the wrong color
  is worth more than one not delivered. The description is cut at 4096 characters: Discord
  answers a longer one with 400, and a 400 is not retried.
- **The bot never opens a gateway session.** `DiscordBotService` uses the REST side of
  `DiscordClient` only. Until HAS-94 it called `skippy.login()` for every message and never
  logged out, so every message left one more websocket behind.
- **The channel is configured by id, never looked up by name** (`discord.bot.skippy.alerts-channel-id`).
  0.3.0 listed the server's channels to find `alerts`, and delivered nothing: discord4j 3.3.2
  could not decode one of the channels (`Optional cannot be cast to Id` in `ChannelData`),
  which failed the whole listing — in production, on the first alert, because no test talks
  to Discord. Posting to a known id decodes only the reply to the post. Do not bring the
  lookup back, and treat any new discord4j call that returns server data the same way.
- **A discord4j `ClientException` is answered with 502 by `GET /skippy`**, with the status only
  — its message carries the request and Discord's whole reply.
- **Delivery is retried in the service, not by the broker** (`NotificationMessageService`:
  four retries, backoff from 5 s; only a 5xx from Discord and network failures are
  retried, everything else would fail the same way again — the check walks the causes,
  because discord4j reports a 5xx wrapped in the "retries exhausted" of its own attempts). The listeners then swallow the error on purpose: a
  listener returning `Mono` that signals an error makes the container hand the message back,
  and the broker redelivers it at once — a tight loop for as long as Discord is down. When
  the retries are used up the message text is logged at ERROR; from then on the log is the
  only copy.
- **The queues have a one-hour TTL**, so a notification published while this service is down
  for longer is gone. Publishers that care repeat it themselves (`heating-service` reminds
  every 24 h).
- **`.contextCapture()` is the last operator of both listener chains** and has to stay there
  — Spring AMQP does not put the listener observation into the reactor context, so without it
  everything a message triggers is logged without a `traceId`.
- **Surefire activates the `test` profile for every class**; that document excludes
  `RabbitAutoConfiguration`, so no test needs a broker, and switches the console logs back to
  plain text.
