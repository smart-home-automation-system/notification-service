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
- Calls: the Discord REST API. Every message goes to the text channel `alerts`, alerts and
  infos alike.
- `GET /home/notification/skippy?message=` sends a text by hand. It is deliberately **not**
  routed by `api-gateway-service`.
- Uses libraries: `cholewa-commons`. No database, no `smart-home-sdk`.

## Build & run

- Build + tests: `mvn verify`
- Local run: `home,local` Spring profiles, port `6003` (Actuator `8003`); in-cluster port
  `6200`, Actuator `8200`. Needs a RabbitMQ broker and the bot token
  (`discord_bot_skippy_token`).

## Specifics

- **The bot never opens a gateway session.** `DiscordBotService` uses the REST side of
  `DiscordClient` only. Until HAS-94 it called `skippy.login()` for every message and never
  logged out, so every message left one more websocket behind.
- **A missing `alerts` channel is an error**, not an empty success — otherwise a notification
  nobody will ever read is reported as delivered. `GET /skippy` answers it with 502: the
  caller did nothing wrong. A discord4j `ClientException` gets 502 as well, with the status
  only — its message carries the request and Discord's whole reply.
- **The channel id is looked up once and kept** in `DiscordBotService`, and forgotten when
  Discord answers 404 for it. Only the first matching channel gets the message: posting to
  every match could not be retried without repeating the posts that had already succeeded.
- **Delivery is retried in the service, not by the broker** (`NotificationMessageService`:
  four retries, backoff from 5 s; only a 5xx or 404 from Discord and network failures are
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
