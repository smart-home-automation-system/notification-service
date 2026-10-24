# notification-service

The way out of the system to a human: it takes notifications from RabbitMQ and posts them on
Discord through a bot (`discord4j`). Reactive (WebFlux), Java 21, Spring Boot 4.1.2, Maven.
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
- Publishers: `heating-service`, `boiler-service`, `weather-service`, `light-service`,
  `blinds-service`, `amx-service`. The payload of alert and info is plain text — those
  listeners take a `String` and there is no JSON converter.
- **Since HAS-274 also `notification.prod.household`** (`notification.dev.household` locally):
  the JSON `HouseholdNotification` of `smart-home-sdk` 1.13.0, read with the `JsonMapper` of
  Spring Boot into the model and validated (`HouseholdNotificationReader`), worded in Polish
  here (`HouseholdMessages`) and delivered by every `HouseholdTransport` (`HouseholdDispatcher`).
  Publisher to come: `appliance-service`.
- Calls: the Discord REST API. Alerts and infos go to the text channel `alerts`, the household
  category to a channel of its own; both are configured by id.
- `GET /home/notification/skippy?message=` sends a text by hand, and `?category=household` a
  household test message to the household channel (Discord only). It is deliberately **not**
  routed by `api-gateway-service`.
- Uses libraries: `smart-home-sdk` 1.13.0 (the household models, since HAS-274) and
  `cholewa-commons` (1.11.0 since HAS-274; 1.8.0 since HAS-206 before - this service has no
  `WebClient`, no own `LogbookClientHandler` and no `@Scheduled` task, so 1.10.0 / 1.11.0 needed
  no code here). Of what the library
  auto-configures, the error handling and the name of the RabbitMQ connection are used (see
  Specifics); its `NotificationPublisher` is opt-in and stays off — this service is the
  consumer of the notifications. There is no database. The English Bean Validation messages
  word the constraints of a household payload that breaks the model (they go to the log) and
  of `DiscordBotConfig`, validated at startup, where the JVM locale still decides (the accepted
  exception of the org rule).

## Build & run

- Build + tests: `mvn verify`
- Local run: `home,local` Spring profiles, port `6003` (Actuator `8003`); in-cluster port
  `6200`, Actuator `8200`. Needs a RabbitMQ broker, the bot token
  (`discord_bot_skippy_token`) and both channel ids (`discord_alerts_channel_id`,
  `discord_household_channel_id`, mandatory).

## Specifics

- **A notification is one embed colored by its level** (`NotificationLevel`: `ERROR` red,
  `WARN` yellow, `INFO` green): the level is the title, the text the description, and there
  is no plain content. That is the owner's choice after seeing both: 0.4.0 posted the text as
  content with a bare level box under it, which read poorly. The price is known — the bot
  needs "Embed Links" on the channel (it has it; without it Discord refuses the message as
  empty), and a push notification may preview less than it would of plain content. The
  level is read from the `level` message header; a missing or unknown one falls back to the
  queue's own — alert → `ERROR`, info → `INFO` — because a notification with the wrong color
  is worth more than one not delivered. Both listeners live in `RabbitNotificationConsumer` since HAS-179,
  as two thin methods over one chain — **two listeners on purpose, do not merge them into one
  on both queues**. That was tried in the same task (the queue read from
  `AmqpHeaders.CONSUMER_QUEUE`) and dropped in review: one container only warns when one of
  its queues is missing and keeps consuming the other, a publisher can overwrite that header
  (the header mapper copies the message's own headers over it, so `amqp_consumerQueue` on the
  wire would repaint an alert green), and a restart of the shared channel redelivers the
  unacknowledged messages of both queues. A blank text is replaced by a placeholder, and the
  description is cut at 4096 characters, by code points:
  Discord answers a longer one with 400, and a 400 is not retried.
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
  — its message carries the request and Discord's whole reply. It is the only exception with a
  processor of its own: `NotificationException` ("channel not found") went in HAS-179, nothing
  had thrown it since the channel is configured by id.
- **Delivery is retried in the service, not by the broker** (`NotificationMessageService`:
  four retries, backoff from 5 s; only a 5xx from Discord and network failures are
  retried, everything else would fail the same way again — the check walks the causes,
  because discord4j reports a 5xx wrapped in the "retries exhausted" of its own attempts). The listeners then swallow the error on purpose: a
  listener returning `Mono` that signals an error makes the container hand the message back,
  and the broker redelivers it at once — a tight loop for as long as Discord is down. When
  the retries are used up the message text is logged at ERROR; from then on the log is the
  only copy. An exception *thrown* on the way is the same trap: it would leave the listener
  method and start the same loop. `DiscordBotService.sendMessage` therefore defers the
  building of its request, and the listener chain defers its call into the service as well;
  the ERROR line names the class of the exception, because a fault of the service itself
  often has no message.
- **The queues have a one-hour TTL**, so a notification published while this service is down
  for longer is gone. Publishers that care repeat it themselves (`heating-service` reminds
  every 24 h).
- **`.contextCapture()` is the last operator of the listener chain**, shared by both listeners, and has to stay there
  — Spring AMQP does not put the listener observation into the reactor context, so without it
  everything a message triggers is logged without a `traceId`.
- **The RabbitMQ connection is named after the pod** (HAS-106): the broker shows
  `notification-service-<pod id>`, which tells the old pod from the new one during a rollout.
  `HOSTNAME` is taken only when it starts with `spring.application.name` and a dash — outside
  the cluster it is missing, empty, a workstation or a container id, and the name is then
  `notification-service-local`. **Since HAS-206 the service has no code for it**: the
  `ConnectionNameStrategy` comes from `cholewa-commons` (`RabbitConnectionNameAutoConfiguration`,
  bean `cholewaPodConnectionNameStrategy`, on by default) and Spring Boot hands it to the
  connection factory it configures; `RabbitConfig` is gone. A `ConnectionNameStrategy` bean
  declared here would silently replace the library's. `RabbitConnectionNameTest` pins what the
  library cannot know — that in the context of this service its strategy is the one the
  auto-configured `CachingConnectionFactory` holds, what the name is, and that no
  `NotificationPublisher` exists.
- **The household category** (HAS-274) - what is easy to break:
  - **A third listener with a container of its own** (`consumeHouseholdMessage`), for the reasons
    of the two above. It acknowledges every message: a payload that is not the model, or breaks
    its bounds, is logged at ERROR (`InvalidHouseholdNotificationException`) - another delivery
    would fail the same way; the dispatcher swallows what a transport failed.
  - **The words are composed here, never carried in the payload**, and they are the owner's call
    made on a real message (the lesson of HAS-94): `HouseholdMessages`, written out in
    `HouseholdMessagesTest`. The switch names every `HouseholdEvent` - a value added to the SDK
    stops the compiler there. The reminder says its number only: the payload does not carry how
    many there will be. The subject is the device name of the registry, used as it comes (the
    three appliances of the house are feminine in Polish - `zakończyła`).
  - **Every transport is tried, one after the other, and a failed one fails nothing else**
    (`HouseholdDispatcher`): logged at ERROR with the transport named, then the next. The message
    is acknowledged once all were tried - handing it back would deliver it again by the
    transports that did succeed. A transport retries itself what another attempt can change: the
    Discord one with `DiscordDeliveryRetry`, the policy the alerts use (extracted for it).
  - **The SMS and the push (HAS-275, HAS-276) are transports to add** - one `@Component`
    implementing `HouseholdTransport` each; the secret `notification` already gives them
    `vapid-private-key` and `smsapi-token`, the manifest `vapid-public-key` and `smsapi-sender`.
    The sender `smart-home` was refused by SMSAPI on 2026-10-24 (error 14): it is the name of the
    API token, not a sender name of the account - check the sender names of the account before
    HAS-275.
  - **`GET /skippy?category=household` posts to Discord only**, through `DiscordBotService`
    directly - a smoke test must not send an SMS or wake a phone.
- **Surefire activates the `test` profile for every class**; that document excludes
  `RabbitAutoConfiguration`, so no test needs a broker, and switches the console logs back to
  plain text. `RabbitConnectionNameTest` puts the auto-configuration back for its own contexts
  (`spring.autoconfigure.exclude=`), because the connection factory is its subject, and keeps
  the listener containers from starting (`spring.rabbitmq.listener.simple.auto-startup=false`,
  a broker address nothing listens on) — a context test that needs the factory copies both.
