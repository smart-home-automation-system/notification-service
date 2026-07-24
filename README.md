# notification-service

[![CI](https://github.com/smart-home-automation-system/notification-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/notification-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_notification-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_notification-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/notification-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/notification-service?style=plastic)


---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/notification-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.0-blue?style=plastic)
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
messages from RabbitMQ, and through a direct HTTP endpoint. Reactive throughout
(Spring WebFlux / Reactor).

# API

Base path `/home/notification` (`spring.webflux.base-path`); external traffic reaches it
through `api-gateway-service`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/notification/skippy?message=<text>` | Send `message` to the Discord `alerts` channel. Returns `200 OK`; the `message` query parameter is required (`400 Bad Request` when missing). |

# Messaging

Consumes from RabbitMQ (virtual host `/notification`); the queue is pre-declared by the
RabbitMQ infrastructure — this service only consumes.

| Queue (`rabbit.alert.queue`) | Payload | Handler |
|---|---|---|
| `notification.prod.alert` / `notification.dev.alert` | `String` (raw alert text) | `RabbitAlertMessageConsumer#consumeAlertMessage` |

The listener returns `Mono<Void>` and delegates to `AlertMessageService#processMessage`.
Failures are handled with `onErrorResume` — logged and swallowed (`Mono.empty()`) so a
single bad message does not drop the consumer.