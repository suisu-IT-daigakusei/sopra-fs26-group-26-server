# Online-CABO — Backend

REST API and real-time server for **Online-CABO**, a web-based multiplayer card game inspired by Cabo. This repository is the Spring Boot backend for the SoPra FS26 project at the University of Zurich. The [frontend](https://github.com/liun777/sopra-fs26-group-26-client) runs at `http://localhost:3000` and talks to this service at **`http://localhost:8080`**.

## Introduction

**Goal:** Provide a secure, stateful multiplayer backend for Cabo-style rounds—lobbies, live game state, moves, scoring, rematch, friends, and session history—so the web client can stay thin and event-driven.

**Motivation:** Cabo relies on hidden information, timed phases, and synchronized updates between players. A dedicated server enforces rules, persists session data, and broadcasts changes over WebSockets to clients.

## Technologies used

- **Application stack** — Java 17, Spring Boot, Spring Data JPA
- **Real-time communication** — Spring WebSocket (STOMP)
- **Build & quality** — Gradle, JUnit, JaCoCo, SonarCloud
- **Database** — H2 (in-memory, local development)
- **Object mapping** — MapStruct (entity ↔ DTO)
- **Hosting** — Google App Engine (production deployment)

## High-level components

Five main parts work together: HTTP API → services → persistence, with WebSockets parallel to REST for push updates.

| Component | Role | Main entry points |
|-----------|------|-------------------|
| **Application shell** | Starts the server, CORS, scheduler thread pool, health check on `/` | [`Application.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/Application.java) |
| **REST controllers** | Expose HTTP endpoints (users, lobbies, games, moves, sessions, invites) | [`UserController.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/controller/UserController.java), [`LobbyController.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/controller/LobbyController.java), [`GameController.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/controller/GameController.java) |
| **Services** | Game rules, lobby lifecycle, authentication, scoring, timers | [`GameService.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/service/GameService.java), [`LobbyService.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/service/LobbyService.java), [`UserService.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/service/UserService.java) |
| **WebSockets** | Push lobby/game/rematch state to connected clients | [`WebSocketConfig.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/config/WebSocketConfig.java), [`LobbyWebSocketController.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/controller/LobbyWebSocketController.java) |
| **Persistence** | JPA entities and repositories (users, lobbies, games, sessions, moves) | [`Game.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/entity/Game.java), [`Lobby.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/entity/Lobby.java), [`GameRepository.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/repository/GameRepository.java) |

Controllers call services; services read/write entities via repositories and publish events to WebSocket topics. Shared settings (timers, CORS, player limits) live in [`application.properties`](src/main/resources/application.properties) (local) and [`application-gcp.properties`](src/main/resources/application-gcp.properties) (GCP).

## Launch & Deployment

### Prerequisites

- Java 17 (JDK)
- Git  
Optional: VS Code/IntelliJ, [Postman](https://www.postman.com/) for manual API calls.

### Getting started (new developer)

```bash
git clone https://github.com/liun777/sopra-fs26-group-26-server.git
cd sopra-fs26-group-26-server
```

### Build and run locally

```bash
./gradlew build
./gradlew bootRun
```

Open [http://localhost:8080](http://localhost:8080) — expected response: `The application is running.`

**Development mode** (auto-rebuild without running tests): in one terminal `./gradlew build --continuous -x test`, in another `./gradlew bootRun`.

### Run tests

```bash
./gradlew test
```

**Coverage report:**

```bash
./gradlew test jacocoTestReport
```

Open `build/reports/jacoco/test/html/index.html` in a browser.


### External dependencies and database

| Dependency | Purpose |
|------------|---------|
| **[Frontend](https://github.com/liun777/sopra-fs26-group-26-client)** | Required for end-to-end play; must be running separately for full-stack local dev |
| **H2** | In-memory database (local); no install needed. Console: [http://localhost:8080/h2-console](http://localhost:8080/h2-console) — JDBC `jdbc:h2:mem:testdb`, user `sa`, empty password |
| **Deck of Cards API** | External deck via [`DeckOfCardsAPIService.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/service/DeckOfCardsAPIService.java); if unavailable, [`GameService.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/service/GameService.java) builds a local fallback deck |

CORS allows `http://localhost:3000` and the deployed Vercel client URL (see [`application.properties`](src/main/resources/application.properties)).

### Releases / production deployment

- **Google App Engine** (flex, Java 17): [`app.yaml`](app.yaml) sets `SPRING_PROFILES_ACTIVE=gcp`.
- Pushes to **main** typically run CI (build, tests, deploy) via GitHub Actions.
- **Docker** (optional, CI only): In the repo’s **Settings → Secrets**, maintain `dockerhub_username`, `dockerhub_password`, and `dockerhub_repo_name`. On push/PR, [`.github/workflows/dockerize.yml`](.github/workflows/dockerize.yml) builds [`Dockerfile`](Dockerfile) and pushes the image `username/repo_name:latest` to Docker Hub.

- **Manual JAR:**

```bash
./gradlew bootJar
java -jar build/libs/soprafs26.jar
```

### API overview

REST examples: `/users`, `/lobbies`, `/games`, `/sessions`, `/auth/rules`. Auth uses a token header (see [`UserService.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/service/UserService.java)). WebSocket STOMP setup: [`WebSocketConfig.java`](src/main/java/ch/uzh/ifi/hase/soprafs26/config/WebSocketConfig.java).

## Roadmap

Top ideas for new contributors:

1. **Persistent production database** (e.g. Cloud SQL) with schema migrations instead of only in-memory H2 locally.
2. **OpenAPI / Swagger** documentation generated from controllers.

## Authors and acknowledgment

**Authors**

* **Alexandra Gort** — Frontend — [@aleexgort](https://github.com/aleexgort)
* **Liun Grichting** — Backend — [@liun777](https://github.com/liun777)
* **Jana Graf** — Backend — [@janagraf](https://github.com/janagraf)
* **Jan Alexander Studenski** — Frontend — [@suisu-IT-daigakusei](https://github.com/suisu-IT-daigakusei)
* **Uliana Solohub** — Backend — [@uIiana](https://github.com/uIiana)

See also: [contributors](https://github.com/liun777/sopra-fs26-group-26-server/graphs/contributors)

**Acknowledgment**

* Thomas Fritz, Prof. Dr., and the SoPra FS26 teaching assistants at the University of Zurich
* The original Cabo card game for design inspiration
* Contributors to the open-source libraries used in this project

## License

This project is licensed under the **Apache License 2.0** — see [LICENSE](LICENSE).