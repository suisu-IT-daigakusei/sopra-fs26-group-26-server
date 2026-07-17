# Cabo production deployment

This stack serves `https://cabo.studenski.me` through Caddy. Only Caddy publishes
host ports. Next.js, Spring, and PostgreSQL remain private Docker services.
Every response includes an `X-Robots-Tag` header that prevents search indexing.

## Expected directory layout

```text
/opt/cabo/
  sopra-fs26-group-26-client/
  sopra-fs26-group-26-server/
```

Run all commands below from `sopra-fs26-group-26-server`.

## First deployment

1. Copy `deploy/env.example` to `deploy/.env`.
2. Generate the database password with `openssl rand -hex 32` and place it in
   `POSTGRES_PASSWORD`. Set the ACME email address. Never commit `deploy/.env`.
3. Protect the file with `chmod 600 deploy/.env`.
4. Validate, build one application at a time, and start:

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml config --quiet
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml pull database caddy
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml build --pull server
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml build --pull client
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml up -d
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml ps
```

Building sequentially avoids exhausting the 4 GB host. Caddy automatically obtains
and renews the TLS certificate after DNS and ports 80/443 are reachable.

## Operations

Inspect recent logs:

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml logs --tail=100
```

Stop containers without deleting data:

```bash
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml down
```

Never add `-v` to `down`: that would delete the persistent PostgreSQL and Caddy volumes.

## Performance report

PostgreSQL records normalized query statistics with its built-in
`pg_stat_statements` module. Caddy already emits bounded JSON access logs. Produce
one report containing host resources, container resources, database counters, top
SQL statements, and endpoint latency for the last six hours:

```bash
sudo python3 deploy/scripts/performance-report.py 6
```

The SQL totals accumulate from the last PostgreSQL statistics reset. The HTTP
section uses only the selected Caddy log interval. Neither report contains a paid
service or sends data off the server.

## Structurally validated daily backups

The backup job streams a custom-format PostgreSQL archive to the host, confirms
that its table of contents is readable with `pg_restore --list`, atomically
publishes it, and removes archives older than 14 days. This structural check is
not a full restore test. The job never operates on Docker volumes.

Install and test the timer once:

```bash
sudo install -m 0644 deploy/systemd/cabo-backup.service /etc/systemd/system/
sudo install -m 0644 deploy/systemd/cabo-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cabo-backup.timer
sudo systemctl start cabo-backup.service
sudo systemctl status cabo-backup.service --no-pager
sudo ls -lh /var/lib/cabo-backups
```

The default retention can be changed with
`Environment=CABO_BACKUP_RETENTION_DAYS=30` in a systemd override. Periodically
copy a structurally validated archive to your own PC over SSH: an on-server
backup alone does not protect against loss of the entire server. Also perform an
occasional manual restore drill into a disposable database; that is the reliable
way to verify the complete recovery procedure without imposing the cost on every
daily backup.

## Safe Docker disk maintenance

The maintenance job keeps one week of build cache and removes images only after
they have been unused for 30 days. It deliberately has no volume-prune command.

```bash
sudo install -m 0644 deploy/systemd/cabo-docker-maintenance.service /etc/systemd/system/
sudo install -m 0644 deploy/systemd/cabo-docker-maintenance.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cabo-docker-maintenance.timer
```

Compose already caps every service log at three 10 MiB files.

## On-demand Java Flight Recording

Use Flight Recorder only while diagnosing a JVM problem. The override restarts
the backend, records five minutes by default, and writes inside that temporary
container:

```bash
sudo JFR_DURATION=5m docker compose --env-file deploy/.env -f deploy/compose.production.yaml -f deploy/compose.jfr.yaml up -d --force-recreate server
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml -f deploy/compose.jfr.yaml ps -q server
sudo docker cp CONTAINER_ID:/tmp/cabo.jfr ./cabo.jfr
sudo docker compose --env-file deploy/.env -f deploy/compose.production.yaml up -d --force-recreate server
```

Wait for the selected duration before copying and replace `CONTAINER_ID` with the
ID printed by the preceding command. The last command returns the backend to its
normal non-recording configuration.
