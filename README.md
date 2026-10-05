# notification-service

[![CI](https://github.com/smart-home-automation-system/notification-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/notification-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/notification-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/notification-service?style=plastic)


---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/notification-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)
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

Every message is posted on the Discord text channel `alerts`, over the Discord REST API —
the bot does not keep a gateway session. A server without that channel is a delivery
failure, not a silent success.

# API

Base path `/home/notification` (`spring.webflux.base-path`). The endpoint is not routed by
`api-gateway-service`, so it is reachable only from inside the cluster.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/notification/skippy?message=<text>` | Send `message` to the Discord `alerts` channel. Returns `200 OK`; the `message` query parameter is required (`400 Bad Request` when missing). |

# Messaging

Consumes from RabbitMQ (virtual host `/notification`). The headers exchange `notification`,
the queues and their bindings (`category` + `env`) are pre-declared by the RabbitMQ
infrastructure — this service only consumes.

| Queue | Property | Payload | Handler |
|---|---|---|---|
| `notification.prod.alert` / `notification.dev.alert` | `rabbit.alert.queue` | `String` (plain text) | `RabbitAlertMessageConsumer#consumeAlertMessage` |
| `notification.prod.info` / `notification.dev.info` | `rabbit.info.queue` | `String` (plain text) | `RabbitInfoMessageConsumer#consumeInfoMessage` |

The `dev` queues are used in the `local` profile. Publishers send `text/plain`; the only one
today is `heating-service`, which reports temperature sensors that stopped reporting.

Both listeners return `Mono<Void>` and delegate to
`NotificationMessageService#processMessage`, which posts the text on Discord and retries a
failed delivery four times with a backoff starting at 5 s. When the retries are used up the
listener logs the message text at ERROR and acknowledges it: handing it back to the broker
would redeliver it immediately, in a loop, for as long as Discord is unreachable. The queues
keep a message for one hour, so a notification published while the service is down for longer
is lost.
