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
  Publisher: `appliance-service` (since its 0.3.0).
- Calls: the Discord REST API. Alerts and infos go to the text channel `alerts`, the household
  category to a channel of its own; both are configured by id. **Since HAS-276 also**, for the
  Web Push of the household category: `presence-service` (`GET /home/presence/residents/presence`)
  and `database-service` (`GET` / `DELETE /home/push-subscriptions`) over k8s DNS
  (`internal.service.*`), and the push services of `household.push.allowed-hosts`. **Since HAS-275**
  for the SMS: `GET /home/household` of `database-service` (the phones) and SMSAPI
  (`https://api.smsapi.pl/sms.do`).
- `GET /home/notification/skippy?message=` sends a text by hand, and `?category=household` a
  household test message to the household channel (Discord only; with `sms=true` also an SMS to the
  administrators, HAS-275). It is deliberately **not**
  routed by `api-gateway-service`.
- Uses libraries: `smart-home-sdk` 1.13.0 (the household models, since HAS-274) and
  `cholewa-commons` (1.12.0 since HAS-276; 1.11.0 since HAS-274). The `WebClient`s of the push
  transport are built from the injected builder, so the Logbook filter of the library logs them
  with the `traceId` - there is no own `LogbookClientHandler`, and none may be added (every call
  would be logged twice); there is no `@Scheduled` task. Of what the library
  auto-configures, the error handling, `DownstreamErrors` and the name of the RabbitMQ connection
  are used (see Specifics); its `NotificationPublisher` is opt-in and stays off — this service is the
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
    would fail the same way; the dispatcher swallows what a transport failed. **The queue has to
    exist**: Spring AMQP treats a missing queue as fatal for its container, so a broker without
    `notification.<env>.household` (definitions before 1.4.0, HAS-273 - an old backup, a fresh
    kind cluster) stops the start of the service, the local run on `notification.dev.household`
    included.
  - **The words are composed here, never carried in the payload**, and they are the owner's call
    made on a real message (the lesson of HAS-94): `HouseholdMessages`, written out in
    `HouseholdMessagesTest`. The switch names every `HouseholdEvent` - a value added to the SDK
    stops the compiler there. The reminder says its number only: the payload does not carry how
    many there will be. **The three appliances are told by their Polish names** (HAS-285, owner
    2026-10-26: `POLISH_NAMES` maps `Washing machine` / `Dryer` / `Dishwasher` and the Polish names
    themselves, whatever the case, to `Pralka` / `Suszarka` / `Zmywarka`): all three feminine, so
    their sentences say what the appliance did (`Pralka skończyła pracę - czas ją opróżnić.`). The
    registry stays English (the owner's naming rule). **Any other subject is used as it comes** -
    Polish of any gender or English - so no sentence puts a gendered verb next to it (`Koniec pracy:
    <device> - ...`, the review of HAS-274: `Dishwasher zakończyła` read badly). A new appliance with
    a Polish name of another gender gets its own sentences, not a place in the map.
  - **Every transport is tried, all at once, and a failed one fails nothing else**
    (`HouseholdDispatcher`, `flatMap`): logged at ERROR with the transport named. At once and not
    in series: Discord retrying for over a minute must not hold back the SMS and the push. The
    message is acknowledged once all finished - handing it back would deliver it again by the
    transports that did succeed. A transport retries itself what another attempt can change: the
    Discord one with `DiscordDeliveryRetry`, the policy the alerts use (extracted for it). **Its
    warnings are logged under `DiscordDeliveryRetry` since HAS-274** (before:
    `NotificationMessageService`) - no dashboard of deployment-tools filtered by the old name.
  - **Who to reach is one package, `household.members`** (HAS-275): `HouseholdServicesConfig` (the
    WebClients of presence-service and database-service, `internal.service.*`, always there),
    `PresentMembersClient` (who is at home) and `HouseholdRegistryClient` (`GET /home/household`,
    read into a record of its own - name, phone, active, role as text - **never the
    `HouseholdMember` of the SDK**: a room or device enum this service does not know would fail the
    read of a phone). The push and the SMS take them from there.
  - **The SMS (HAS-275, package `household.sms`)** - what is easy to break:
    - **Only with `household.sms.enabled`** (`SmsConfig`): without it no SMS transport, no SMSAPI
      client, and the smoke test answers `sms=true` with 409. On in the shared document, off in
      `local` (`SMS_ENABLED=true`) and in `test` (SMSAPI at `http://localhost:1`). The start fails on
      a blank sender or token. The sender **`smart-home`** is the name registered at SMSAPI (active
      since 2026-10-24; the error 14 of that day came before it was confirmed).
    - **Recipients**: present by presence-service ∩ switched on with a phone in the registry,
      matched whatever the case; **cached 60 s** (`SmsRecipients`, `Mono.cache` with a TTL; a failed
      read is not kept). One SMS per member, one after the other; nobody at home completes, a failed
      read fails the transport - never an SMS to everybody instead; the transport fails only when no
      SMS was accepted.
    - **SMSAPI refuses with an error object, with a 4xx or with a 200** (`SmsApiClient`): accepted
      only with a 2xx, a `count` and no `error`. Logged at ERROR with the member and the **code
      alone** - the message may repeat the number; never the number, the text or the token. Nothing
      is retried (an SMS costs). The calls are excluded from Logbook (the form holds the phone).
    - **The text is the Discord sentence cut to 160 code points** (`SmsText`). Polish letters make
      it a Unicode SMS (70 characters a part): today's sentences fit one part; a longer one costs up
      to three per member (SMSAPI's `normalize=1` would be the way out, not taken).
    - **The Discord footer names who the SMS goes to** (`DiscordHouseholdTransport` asks the same
      cached `SmsRecipients`, within 5 s; a failure or a timeout leaves the footer as it was - the
      Discord message never waits for the SMS nor fails because of it). Without SMS no line.
    - **The smoke test `sms=true` texts the administrators** (`SmsSmoke`), at home or not - one SMS
      to the owner, never to every member.
    - The read of the registry is logged by Logbook at trace with its body (phones and MACs) - the
      accepted rule of the organization; database-service logs the same body.
  - **The Web Push (HAS-276, package `household.push`)** - what is easy to break:
    - **Only with `household.push.enabled`** (`WebPushConfig`, `@ConditionalOnBooleanProperty`):
      without it there is no push transport, no `WebClient` and no `internal.service` binding. On
      in the shared document, off in `local` (`PUSH_ENABLED=true` switches it on) and in `test`.
      With it on, the start fails on a missing or unreadable key, a public key of another pair
      (`VapidPair`: a signature of the private key checked with the public one - the JDK computes
      no public key) or a subject that is not `mailto:` / `https:` without quotes. The manifest
      needs `push-subject` besides the two VAPID variables.
    - **The JDK does all the cryptography** - no library: `WebPushEncryptor` (RFC 8291 /
      `aes128gcm`, one record, a new key pair and salt each message) is pinned by the test vector
      of RFC 8291 and by a decryption written apart in its test; `VapidSigner` signs ES256 in the
      P1363 form a JWT takes (`SHA256withECDSAinP1363Format`, not the DER of `SHA256withECDSA`),
      `aud` is the origin without user info or the default port, `k=` the public key re-encoded
      unpadded. The work runs on the parallel scheduler, off the event loop.
    - **Who gets it**: the members present by `presence-service`, 20 names a read of
      `database-service` (its bound), a name with a comma left out (the read splits on commas).
      Nobody at home completes; a failed read fails the transport and no push goes out - **never
      a push to everybody instead**. The transport fails only when there was a browser to reach
      and none took the message; the dispatcher then logs it at ERROR.
    - **Only the push services of `household.push.allowed-hosts`** (a host equal or below one, https
      only): the registry takes any https endpoint a browser gives, and this list is what keeps
      the service from posting anywhere else. A skipped subscription is logged by its host.
    - **404 / 410 removes the subscription** (`DELETE /home/push-subscriptions?endpoint=` of
      `database-service` >= 0.19.0); a removal that fails is a WARN, and the next message that finds
      it tries again. Any other answer - 429 and 5xx included - fails that browser for this
      message; nothing is retried (the next reminder is the next attempt, Discord has the message).
    - **The payload is the shape the service worker of Angular shows by itself**
      (`{"notification": {...}}`, `data.onActionClick.default`) - the application (HAS-284) needs
      no worker of its own; the Jira text named `{title, body, path, tag}`, which are all inside.
      `tag` is `appliance-<deviceId>` - **no `Topic` header**: Apple answered our `appliance-133` with
      400 `BadWebPushTopic` on the first real push (0.6.1 logs the reason of a refusal, which showed it);
      the tag still replaces the notification on the phone. `renotify` is off for the acknowledgement, which
      replaces the notification without a sound. `requireInteraction` (owner, 2026-10-26: a Chrome banner
      went after a few seconds) keeps it on the screen until clicked or closed - off for the acknowledgement,
      ignored by iOS; whether Chrome on Windows honours it is checked on the first push after the release.
    - **A subscription is never logged**: the endpoint is an address anybody could post to that
      browser with. The calls to the push services are kept out of Logbook by
      `logbook.client.predicate.exclude` (a glob on the whole URL) - **a new allowed host needs its
      line there too**. The read and the removal of `database-service` are logged at trace with the
      rest (the accepted rule of the organization).
    - **Logged as "delivered by [push]" also when nobody was at home** - the transport completed;
      the INFO line before it says that nobody was.
  - **`GET /skippy?category=household` posts to Discord only**, through `DiscordBotService`
    directly - a smoke test must not send an SMS or wake a phone. A mapping of its own
    (`params = "category=household"`, the value as written): the alert call neither reads nor
    checks the household parameters.
- **Surefire activates the `test` profile for every class**; that document excludes
  `RabbitAutoConfiguration`, so no test needs a broker, and switches the console logs back to
  plain text. `RabbitConnectionNameTest` puts the auto-configuration back for its own contexts
  (`spring.autoconfigure.exclude=`), because the connection factory is its subject, and keeps
  the listener containers from starting (`spring.rabbitmq.listener.simple.auto-startup=false`,
  a broker address nothing listens on) — a context test that needs the factory copies both.
