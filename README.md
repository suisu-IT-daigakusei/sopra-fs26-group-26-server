# Online-CABO — Server

Version 1.9.0 of the REST and WebSocket server for Online-CABO.

The backend runs on Java 25 LTS and Spring Boot 4.1, stores its data in PostgreSQL 18, and manages its schema with Flyway. The matching [web client](https://github.com/suisu-IT-daigakusei/sopra-fs26-group-26-client) normally runs at `http://localhost:3000`.

## Current stack

- Java 25 LTS
- Spring Boot 4.1
- Gradle 9.6.1
- PostgreSQL 18.4
- Flyway database migrations
- Spring Data JPA and MapStruct
- STOMP over WebSocket and SockJS
- JUnit and Testcontainers

Passwords are stored as adaptive hashes. A successfully authenticated legacy plaintext password is replaced with a hash automatically.

## Run locally with Docker

Docker Desktop must be running. To build the server and start it together with PostgreSQL:

```powershell
docker compose --profile server up --build
```

The API is available at `http://localhost:8080`. The browser client can connect from `http://localhost:3000` or `http://127.0.0.1:3000` by default.

Stop the containers without deleting the database:

```powershell
docker compose --profile server down
```

PostgreSQL data lives in the named Docker volume `cabo-postgres-data` and survives container recreation. Running `docker compose down --volumes` deliberately deletes it.

For local overrides, copy `.env.example` to `.env`. Do not commit `.env`. Before exposing the service outside your computer, replace the sample database password and configure the exact browser origin through `CORS_ALLOWED_ORIGINS`.

## Run from a local JDK

Install Java 25 and start only PostgreSQL:

```powershell
docker compose up -d database
./gradlew.bat bootRun
```

On macOS or Linux, use `./gradlew bootRun` instead. The local profile connects to `jdbc:postgresql://localhost:5432/cabo` with the local Compose defaults unless `DB_URL`, `DB_USERNAME`, or `DB_PASSWORD` override them.

## Tests and build

Repository integration tests use PostgreSQL through Testcontainers, so Docker must be running.

```powershell
./gradlew.bat test
./gradlew.bat build
./gradlew.bat test jacocoTestReport
```

The coverage report is written to `build/reports/jacoco/test/html/index.html`.

To verify the container image separately:

```powershell
docker build --tag cabo-server:local .
```

## Persistence and migrations

Flyway migrations are stored in `src/main/resources/db/migration`. Hibernate validates the mapped entities against that schema at startup; it does not silently create or mutate production tables.

The old local H2 configuration was memory-only, so the PostgreSQL database starts empty. H2 remains available only for isolated tests and is never a runtime database.

Back up the local database before risky changes:

```powershell
docker compose exec database pg_dump -U cabo -d cabo --format=custom --file=/tmp/cabo.dump
docker compose cp database:/tmp/cabo.dump ./cabo.dump
```

Restoring replaces the current database. Keep the dump outside the container, stop the server, and restore only after verifying that the backup file exists:

```powershell
$dbUser = (docker compose exec -T database printenv POSTGRES_USER).Trim()
$dbName = (docker compose exec -T database printenv POSTGRES_DB).Trim()
docker compose cp ./cabo.dump database:/tmp/cabo.dump
docker compose stop server
docker compose exec -T database dropdb -U $dbUser --if-exists --force $dbName
docker compose exec -T database createdb -U $dbUser -O $dbUser $dbName
docker compose exec -T database pg_restore -U $dbUser -d $dbName --exit-on-error /tmp/cabo.dump
docker compose --profile server up -d server
```

## API structure

Controllers expose users, lobbies, games, moves, sessions, and invitations. Services enforce game rules and publish real-time state through STOMP topics. Authentication uses the token returned by the user endpoints.

The main areas are:

- `src/main/java/ch/uzh/ifi/hase/soprafs26/controller` — HTTP and WebSocket endpoints
- `src/main/java/ch/uzh/ifi/hase/soprafs26/service` — application and game rules
- `src/main/java/ch/uzh/ifi/hase/soprafs26/entity` — persisted domain state
- `src/main/java/ch/uzh/ifi/hase/soprafs26/repository` — database access
- `src/main/resources/application.properties` — shared runtime settings

Game creation and reshuffling use the complete local Cabo deck and Java's in-process
shuffle. The production game path performs no external deck HTTP requests. The legacy
Deck of Cards API adapter remains only for isolated compatibility tests.

## Repository automation

The remaining GitHub Actions workflow only builds and tests pull requests or an explicitly requested manual run. It contains no publishing or deployment job. Local Docker commands never publish an image unless you explicitly add and run a registry push command yourself.

## Authors and acknowledgment

**Authors**

* **Alexandra Gort** — Frontend — [@aleexgort](https://github.com/aleexgort)
* **Liun Grichting** — Backend — [@liun777](https://github.com/liun777)
* **Jana Graf** — Backend — [@janagraf](https://github.com/janagraf)
* **Jan Alexander Studenski** — Frontend — [@suisu-IT-daigakusei](https://github.com/suisu-IT-daigakusei)
* **Uliana Solohub** — Backend — [@uIiana](https://github.com/uIiana)

See also: [contributors](https://github.com/suisu-IT-daigakusei/sopra-fs26-group-26-server/graphs/contributors)

**Acknowledgment**

* Thomas Fritz, Prof. Dr., and the SoPra FS26 teaching assistants at the University of Zurich
* The original Cabo card game for design inspiration
* Contributors to the open-source libraries used in this project

## License

This project is licensed under the Apache License 2.0 — see [LICENSE](LICENSE).
