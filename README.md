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
(`HouseholdDispatcher`): Discord today, the SMS of SMSAPI and the Web Push to come (HAS-275,
HAS-276). A transport that fails never fails the others; it is logged at ERROR with its name.

# API

Base path `/home/notification` (`spring.webflux.base-path`). The endpoint is not routed by
`api-gateway-service`, so it is reachable only from inside the cluster.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/notification/skippy?message=<text>&level=<error\|warn\|info>` | Send `message` to the Discord `alerts` channel; `level` is optional and defaults to `info`. Returns `200 OK`; the `message` query parameter is required (`400 Bad Request` when missing); `502 Bad Gateway` when Discord refuses the request (a wrong channel id included). |
| `GET` | `/home/notification/skippy?category=household&event=<event>&subject=<device>&reminder=<n>` | The smoke test of the household channel (since 0.5.0): a household notification of a made-up device, worded as the queue words one and posted to the household channel - **Discord only**, never the SMS or the push. `event` defaults to `appliance finished`, `subject` to `Zmywarka` (3-50 characters), `reminder` to `0` (0-10). `200 OK`; `400` for an unknown event or a bound broken; `502` when Discord refuses. |

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
