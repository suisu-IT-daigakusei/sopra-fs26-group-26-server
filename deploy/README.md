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
