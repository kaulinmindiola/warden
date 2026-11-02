# Running Warden locally

This guide starts the reference environment of Warden with one command, runs the request collection
against it and shows where to look while it runs (REQ-INST-001, REQ-MAINT-008). Every command is given
for PowerShell (Windows) and for a POSIX shell (Linux, macOS). Run them from the repository root.

## Requirements

- Docker with Compose v2: Docker Desktop on Windows or macOS, Docker Engine 25 or later on Linux.
  Nothing else: the application, the request collection and every tool run in containers.
- About 3 GB of free memory for the containers; Warden itself is limited to 1 GiB.
- These ports free on `127.0.0.1`: 8080 (API), 3000 (Grafana), 9090 (Prometheus), 5432 (PostgreSQL),
  6379 (Redis), 8091 and 8092 (simulated e-mail providers). Each one can be changed in `.env`
  (see [Configuration](#configuration)).

The verified platform is `linux/amd64`. On `linux/arm64` (for example Apple silicon) the environment is
expected to work, since every image is published for both platforms, but its result is not yet
documented (REQ-PORT-001).

## Start the environment

```powershell
docker compose up -d --wait
docker compose ps
```

```bash
docker compose up -d --wait
docker compose ps
```

The first start builds the Warden image from source and pulls the other images, which takes a few
minutes. Once the images exist, the environment is ready in less than 3 minutes: `--wait` returns when
every service is healthy, Warden included (its readiness depends on PostgreSQL only).

| Service | Address | What it is |
|---------|---------|------------|
| `warden` | http://localhost:8080/api/v1 | The API. Its management port (8081) is never published. |
| `grafana` | http://localhost:3000 | The dashboard "Warden — Access & Delivery", open without login. |
| `prometheus` | http://localhost:9090 | Metrics and alert rules (`/alerts`). |
| `provider-a`, `provider-b` | http://localhost:8091, http://localhost:8092 | Simulated e-mail providers (WireMock). Every message sent is in `/__admin/requests`. |
| `postgres`, `redis` | `localhost:5432`, `localhost:6379` | Published for development only. |

The signing and encryption keys are generated on the first start into the `warden-keys` volume and
reused afterwards; no key is kept in the repository.

## Run the request collection

`docs/api/bruno` holds a [Bruno](https://www.usebruno.com/) collection with every endpoint of the API in
one flow of 45 requests that creates its own data. It reads the single-use tokens of verification,
invitation and password reset e-mails from simulated provider A, so it needs nothing but the running
environment. The Bruno CLI runs in a container on the Compose network:

```powershell
docker run --rm --network warden_default -v "${PWD}/docs/api/bruno:/collection" -w /collection `
  node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914 `
  npx --yes @usebruno/cli@4.3.0 run --env local `
  --env-var baseUrl=http://warden:8080/api/v1 --env-var providerAUrl=http://provider-a:8080
```

```bash
docker run --rm --network warden_default -v "$PWD/docs/api/bruno:/collection" -w /collection \
  node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914 \
  npx --yes @usebruno/cli@4.3.0 run --env local \
  --env-var baseUrl=http://warden:8080/api/v1 --env-var providerAUrl=http://provider-a:8080
```

The run takes about 30 seconds and ends with `Requests: 45 (45 Passed)` and `Tests: 45/45`. Three
requests expect an error on purpose, and their documentation says why: a login with a wrong password
(401), a verification resend for a verified account (409) and a retry of a delivery that was sent (409).
The collection can be run again: every run creates new accounts.

With the [Bruno desktop app](https://www.usebruno.com/downloads), open the `docs/api/bruno` folder and
choose the `local` environment, which points at `localhost`.

The OpenAPI document of the API is [`docs/api/openapi.json`](../api/openapi.json), and the error types
are listed in [`docs/api/problem-types.md`](../api/problem-types.md).

## Watch it work

- **Dashboard.** http://localhost:3000 opens "Warden — Access & Delivery": logins, refreshes, revoked
  sessions, authorization denials and rate-limit rejections; deliveries by status and scope, the
  dead-letter queue, provider attempts, error rate, latency and circuit breakers; pending outbox events
  and outbox lag. It refreshes every 10 seconds. The admin user (`admin`, password `admin_local`
  unless changed in `.env`) is only needed to change Grafana itself.
- **Alerts.** http://localhost:9090/alerts lists the five alert rules: an open circuit breaker, a
  growing dead-letter queue, a high outbox lag, a spike of refresh token reuse and an instance that is
  not ready. There is no Alertmanager: alerts are watched here.
- **Logs.** Every line is a JSON object with `traceId`, and `userId`, `workspaceId` or `originTraceId`
  where they apply; tokens and e-mail addresses are masked.
  [Following a request through the logs](log-correlation.md) shows how to find everything one request
  caused.

  ```powershell
  docker compose logs -f warden
  ```

  ```bash
  docker compose logs -f warden
  ```

## Two instances

The `scale` profile adds `warden-2` on port 8082, sharing PostgreSQL, Redis and the keys with the first
instance (REQ-DIST-001). A token issued by one instance is accepted by the other:

```powershell
docker compose --profile scale up -d --wait
$body = @{ email = "scale-$([guid]::NewGuid())@mail.test"; password = 'correct horse battery staple' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/register -ContentType 'application/json' -Body $body | Out-Null
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body $body
(Invoke-WebRequest -Uri http://localhost:8082/api/v1/auth/sessions -Headers @{ Authorization = "Bearer $($login.access_token)" }).StatusCode
```

```bash
docker compose --profile scale up -d --wait
body="{\"email\":\"scale-$(date +%s%N)@mail.test\",\"password\":\"correct horse battery staple\"}"
curl -s -o /dev/null -X POST http://localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' -d "$body"
token=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d "$body" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/api/v1/auth/sessions -H "Authorization: Bearer $token"
```

The last command prints `200`. Prometheus scrapes both instances, and the dashboard adds them up.

## Simulate a provider outage

The simulated providers accept runtime stubs through the WireMock admin API. This one makes provider A
answer 503 to every message, so deliveries fail over to provider B and, after ten failed calls, the
circuit breaker of provider A opens (the "Circuit breakers open" panel turns red):

```powershell
$outage = '{"priority":1,"request":{"method":"POST","urlPath":"/v1/messages"},"response":{"status":503}}'
Invoke-RestMethod -Method Post -Uri http://localhost:8091/__admin/mappings -ContentType 'application/json' -Body $outage
```

```bash
curl -s -X POST http://localhost:8091/__admin/mappings -H 'Content-Type: application/json' \
  -d '{"priority":1,"request":{"method":"POST","urlPath":"/v1/messages"},"response":{"status":503}}'
```

Do the same on port 8092 to take both providers down: deliveries are then retried with backoff and,
after five attempts, move to the dead-letter queue. To restore the usual answers:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8091/__admin/mappings/reset
Invoke-RestMethod -Method Post -Uri http://localhost:8092/__admin/mappings/reset
```

```bash
curl -s -X POST http://localhost:8091/__admin/mappings/reset
curl -s -X POST http://localhost:8092/__admin/mappings/reset
```

## Configuration

Every setting has a local default. To change one, copy `.env.example` to `.env` and uncomment the
variables to override; Docker Compose reads `.env` automatically. `.env` is never committed. The
example file documents the ports, the passwords of the local databases and of Grafana, the keys, the
rate limits, the providers and the link URLs.

## Develop without the Warden container

To run the application from the source code, start only its dependencies and use the `local` profile:

```powershell
docker compose up -d --wait postgres redis provider-a provider-b
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

```bash
docker compose up -d --wait postgres redis provider-a provider-b
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The API is then on http://localhost:8080 and its management port on http://localhost:8081. Without
key variables, the `local` profile generates ephemeral keys, which do not survive a restart.

To build and run every test, unit and integration (Testcontainers starts its own PostgreSQL and Redis),
with JDK 21 and Docker:

```powershell
.\mvnw.cmd verify
```

```bash
./mvnw verify
```

## Stop and remove everything

```powershell
docker compose --profile scale down -v --remove-orphans
```

```bash
docker compose --profile scale down -v --remove-orphans
```

This removes every container, the network and the volumes of the project, so the next start begins
from an empty database with new keys (REQ-INST-004). The images stay; remove `warden:local` with
`docker image rm warden:local` if you no longer need it.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `Bind for 127.0.0.1:8080 failed: port is already allocated` | Another program uses the port. Set another one in `.env`, for example `WARDEN_API_PORT=18080`. |
| `docker compose up --wait` ends with `warden` unhealthy | Read `docker compose logs warden`. The first lines name a missing or invalid setting. |
| The collection fails at a "Read … e-mail" request | The e-mail had not been sent yet. Run the collection again; if it keeps failing, check `docker compose logs warden` for delivery errors. |
| `pull access denied for warden` | Run `docker compose up` from the repository root, so Compose builds the image. |
| Changes to the source code are not in the container | Rebuild the image: `docker compose up -d --wait --build`. |