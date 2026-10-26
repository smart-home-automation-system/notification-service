# notification-service

[![CI](https://github.com/smart-home-automation-system/notification-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/notification-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/notification-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/notification-service?style=plastic)


---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/notification-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.2-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/notification-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/notification-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/notification-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/notification-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/notification-service?style=plastic)

---

# Description

Notification hub for the smart-home-automation-system. It delivers **Discord**
notifications through a Discord bot (`discord4j`), triggered two ways: by consuming alert
and info messages from RabbitMQ, and through a direct HTTP endpoint. Reactive throughout
(Spring WebFlux / Reactor).

Every message is posted on the Discord text channel `alerts` as one **embed**: the level
(`ERROR`, `WARN`, `INFO`) is its title, the text its description, and the bar on the left is
red, yellow or green by the level. The bot needs the "Embed Links" permission on the
channel — without it Discord refuses a message that is an embed and nothing else. The level comes from the `level` message header
(`error` / `warn` / `info`, any case); without it, or with an unknown value, a message from the
alert queue is an `ERROR` and one from the info queue an `INFO`. A text longer than 4096
characters, the limit of an embed description, is cut and marked with `…`. Posting goes over the Discord REST API —
the bot does not keep a gateway session. The channel is named by its Discord id,
`discord.bot.skippy.alerts-channel-id` (environment variable `discord_alerts_channel_id`; in
Discord: developer mode, "Copy Channel ID"). It is mandatory and has no default — the service
does not start without it. The channel is deliberately not looked up by name: listing the
channels makes discord4j decode every channel of the server, and one it cannot decode fails
the delivery (0.3.0 delivered nothing for that reason).

**The household category** (since 0.5.0, HAS-274) is for the people who live in the house, in
Polish, on a Discord channel of its own (`discord.bot.skippy.household-channel-id`, environment
variable `discord_household_channel_id`, mandatory like the alerts channel). Its payload is the
JSON `HouseholdNotification` of `smart-home-sdk` 1.13.0 - what happened to which device, never a
text: the words are composed here (`HouseholdMessages`), one embed per message with the device as
the title, the sentence as the description, the time of the house (`dd.MM.yyyy HH:mm`) in the
footer and the bar colored by the event:

| Event | Text | Color |
|---|---|---|
| `appliance finished` | `Koniec pracy: <device> - wymaga opróżnienia.` | orange |
| `appliance reminder` | `Przypomnienie <n>: <device> nadal czeka na opróżnienie.` | dark orange |
| `appliance acknowledged` | `<device> - potwierdzone, dziękujemy.` | green |

A household notification is delivered by every **transport**, all at once
(`HouseholdDispatcher`): Discord, **Web Push** (since HAS-276) and **SMS** (since HAS-275). A
transport that fails never fails the others; it is logged at ERROR with its name.

**SMS** (HAS-275) puts the sentence of the Discord message, cut to 160 characters, on the phones
of the members at home - one SMS per member a message, through SMSAPI (`POST /sms.do`, the
registered sender `smart-home`, `format=json`, `encoding=utf-8`):

- **Who**: the members `presence-service` says are at home who are switched on in the registry
  and have a phone (`GET /home/household` of `database-service`, read into a record of its own so
  that a new room or device kind never fails it). The two reads are cached for 60 s - the SMS and
  the footer of the Discord message ask at the same moment. Nobody at home is no failure; a read
  that fails is, and then no SMS goes out.
- **The Discord message names who the SMS went to** in its footer (`26.10.2026 11:30 · SMS: Ola,
  Jan`); a read that fails or takes more than 5 s leaves the footer as it was.
- **A refusal** (an error object of SMSAPI, with a 4xx or a 200) is logged at ERROR with the
  member and the error code - never the number, the text or the token. Nothing is retried: an SMS
  sent twice costs twice. The calls to SMSAPI are kept out of Logbook.
- **Configuration**: `household.sms.enabled` (on in the shared document, off locally unless
  `SMS_ENABLED=true`, and in the tests), `household.sms.sender` (`smsapi-sender`),
  `household.sms.token` (`smsapi-token`, from the secret `notification`). The service refuses to
  start with SMS on and no sender or token.

**Web Push** (HAS-276) puts the same words on the phones of the members at home, as a
notification of the installed application, also while it is closed (iOS 16.4+, the application
on the home screen, permission granted - the subscription is made by the application, HAS-284):

- **Who**: the members `presence-service` says are at home (`GET /home/presence/residents/presence`),
  each of their browsers that subscribed (`GET /home/push-subscriptions?members=` of
  `database-service`, 20 names a read). Nobody at home is no failure - the Discord channel has
  the message; a presence or database read that fails is, and then no push goes out at all.
- **What**: `{"notification": {title, body, tag, renotify, data}}` - the shape the service worker
  of Angular shows by itself; a tap opens `data.onActionClick.default.url`, the page where the
  household acknowledges (`acknowledgePath` of the payload, `/` without one). `tag` is
  `appliance-<deviceId>`, so a reminder replaces the notification of the same appliance, and the
  acknowledgement replaces it without a sound (`renotify: false`).
- **How**: encrypted per browser by RFC 8291 (`aes128gcm`) and signed by VAPID (RFC 8292), with
  the JDK alone (`WebPushEncryptor`, `VapidSigner`); posted with `TTL` (an hour) and `Urgency: high` - no `Topic`, which
  Apple refuses (`BadWebPushTopic`); the reason a push service gives for a refusal is logged
  (0.6.1). Only hosts of `household.push.allowed-hosts` are posted to (Apple, Google,
  Mozilla, Microsoft); a subscription elsewhere is skipped and logged by its host.
- **A dropped subscription** (404 or 410 from the push service) is removed through
  `DELETE /home/push-subscriptions?endpoint=` of `database-service` (0.19.0).
- **Configuration**: `household.push.enabled` (on in the shared document, off locally and in
  the tests), `household.push.subject` (environment variable `push-subject`, a `mailto:` or
  `https:` address), the VAPID pair `vapid-public-key` / `vapid-private-key` (base64url, as
  `web-push generate-vapid-keys` prints it). The service refuses to start with push on and a
  key, the pair or the subject wrong.

# API

Base path `/home/notification` (`spring.webflux.base-path`). The endpoint is not routed by
`api-gateway-service`, so it is reachable only from inside the cluster.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/notification/skippy?message=<text>&level=<error\|warn\|info>` | Send `message` to the Discord `alerts` channel; `level` is optional and defaults to `info`. Returns `200 OK`; the `message` query parameter is required (`400 Bad Request` when missing); `502 Bad Gateway` when Discord refuses the request (a wrong channel id included). |
| `GET` | `/home/notification/skippy?category=household&event=<event>&subject=<device>&reminder=<n>` | The smoke test of the household channel (since 0.5.0): a household notification of a made-up device, worded as the queue words one and posted to the household channel - **Discord only** by default, never the push. With `sms=true` (since HAS-275) the same words also go by SMS to the **administrators** of the household, at home or not - one SMS to the owner, never to every member. `event` defaults to `appliance finished`, `subject` to `Zmywarka` (3-50 characters), `reminder` to `0` (0-10). `200 OK`; `400` for an unknown event or a bound broken; `409` for `sms=true` while the SMS is switched off; `502` when Discord refuses. |

# Messaging

Consumes from RabbitMQ (virtual host `/notification`). The headers exchange `notification`,
the queues and their bindings (`category` + `env`) are pre-declared by the RabbitMQ
infrastructure — this service only consumes.

| Queue | Property | Payload | Handler |
|---|---|---|---|
| `notification.prod.alert` / `notification.dev.alert` | `rabbit.alert.queue` | `String` (plain text), optional header `level` (default `error`) | `RabbitNotificationConsumer#consumeAlertMessage` |
| `notification.prod.info` / `notification.dev.info` | `rabbit.info.queue` | `String` (plain text), optional header `level` (default `info`) | `RabbitNotificationConsumer#consumeInfoMessage` |
| `notification.prod.household` / `notification.dev.household` | `rabbit.household.queue` | JSON `HouseholdNotification` (since 0.5.0); a payload that is not the model, or breaks its bounds, is logged at ERROR and acknowledged | `RabbitNotificationConsumer#consumeHouseholdMessage` |

The `dev` queues are used in the `local` profile. Publishers send `text/plain`; the only one
today is `heating-service`, which reports temperature sensors that stopped reporting.

Each queue has a listener of its own; alert and info differ only in the default they give a message
that names no `level` (or an unknown one), household reads JSON and dispatches by every transport. Both return `Mono<Void>` and delegate to
`NotificationMessageService#processMessage`, which posts the text on Discord and retries a
failed delivery four times with a backoff starting at 5 s. Only what another attempt can change is retried: a
5xx from Discord and network failures. A wrong channel id, a rejected token or a message
Discord refuses is not. When the retries are used up, or there are none, the
listener logs the message text at ERROR and acknowledges it: handing it back to the broker
would redeliver it immediately, in a loop, for as long as Discord is unreachable. The queues
keep a message for one hour, so a notification published while the service is down for longer
is lost.
