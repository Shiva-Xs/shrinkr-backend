# Shrinkr - Backend

The backend for [Shrinkr.in](https://shrinkr.in), a no-login URL shortener. Built with Spring Boot, PostgreSQL, and Redis.

**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL · Redis · Flyway · Azure App Service

---

## Features

- 6-character Base62 slugs, ~56 billion possible combinations
- Redirects served from Redis cache, no DB hit after the first visit. Expiring links are cached too — the cache TTL is capped at the expiry instant, so Redis itself enforces the deadline
- Click counts written to a Redis buffer and flushed to Postgres every 60 seconds via a single atomic `UPDATE … SET click_count = click_count + n`. The redirect path never touches the DB
- Every new link is scanned against Google Safe Browsing in the background. Flagged links redirect to a warning page instead. A 10-minute sweep re-scans links stuck in PENDING (executor queue full, app restarted mid-scan)
- Two-tier rate limiting via Bucket4j, stored in Redis so it survives restarts: 50 requests/hour per IP on shorten + unlock, and a generous 300 redirects/minute per IP on the public redirect + QR endpoints — never throttles a human, stops slug-enumeration bots from streaming SELECTs at Postgres
- Graceful degradation: Redis is an optimisation layer, not a source of truth. If Redis is down, redirects fall through to Postgres (uncached, uncounted) instead of erroring, and the rate limiter fails open
- No accounts. Link ownership is proved by a delete token returned at creation time
- Optional: password protection, expiry date, click cap
- QR code generation on demand

---

## Project structure

```
src/main/java/com/shivaxdev/shrinkr/
├── ShrinkrApplication.java
├── config/
│   ├── AsyncConfig.java              # thread pool for background malware scan
│   ├── RedisConfig.java              # RedisTemplate setup
│   └── SecurityConfig.java           # CORS, stateless session, no auth
├── controller/
│   └── LinkController.java           # all 8 endpoints
├── dto/
│   ├── ShortenRequest.java
│   ├── ShortenResult.java
│   ├── EditRequest.java
│   └── LinkInfoResponse.java
├── model/
│   └── ShortLink.java
├── exception/
│   ├── GlobalExceptionHandler.java
│   └── PasswordProtectedException.java
├── repository/
│   └── LinkRepository.java
└── service/
    ├── LinkService.java
    ├── SlugService.java
    ├── RateLimitService.java
    ├── MalwareScanService.java
    └── QrService.java
```

---

## API

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/v1/shorten` | none | Create a short link |
| `GET` | `/{slug}` | none | Redirect (302) |
| `GET` | `/api/v1/info/{slug}` | none | Public link info |
| `GET` | `/api/v1/stats/{slug}` | `X-Delete-Token` | Owner stats |
| `PUT` | `/api/v1/links/{slug}` | `X-Delete-Token` | Edit expiry / click cap / password |
| `DELETE` | `/api/v1/links/{slug}` | `X-Delete-Token` | Delete link |
| `POST` | `/api/v1/unlock/{slug}` | none | Submit password, get redirect URL |
| `GET` | `/api/v1/qr/{slug}` | none | QR code PNG |
| `GET` | `/actuator/health` | none | Health probe |

The token goes in a header rather than a query param. Query params end up in server logs, browser history, and Referer headers.

### Edit request body

All fields are optional. Only send what you want to change.

```json
{ "expiresAt": "2026-12-31T23:59:59" }   // set expiry
{ "clearExpiry": true }                   // remove expiry
{ "maxClicks": 100 }                      // set click cap
{ "clearMaxClicks": true }                // remove click cap
{ "password": "newpassword" }             // set / change password
{ "clearPassword": true }                 // remove password
```

---

## Redis keys

| Pattern | What it stores | TTL |
|---------|----------------|-----|
| `slug:{slug}` | Destination URL for clean links (no cap/password) | 24h, capped at link expiry |
| `clicks:buffer:{slug}` | Pending click count, flushed to Postgres every 60s | None |
| `rate:{ip}` | Creation rate-limit bucket per IP (50/hour) | 1h |
| `rate:redirect:{ip}` | Redirect/QR rate-limit bucket per IP (300/min) | 1m |

---

## Running locally

You'll need Java 21, PostgreSQL, and Redis running.

```bash
git clone https://github.com/shivaxdev/shrinkr-backend.git
cd shrinkr-backend
```

Create the dev database:

```sql
CREATE DATABASE shrinkr_dev;
```

Start the app:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`application-dev.yml` is gitignored. Copy the example file and fill in your values:

```bash
cp application-dev.yml.example application-dev.yml
```

Flyway runs the migrations automatically on first startup.

Quick test:

```bash
curl -X POST http://localhost:8080/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com"}'
```

---

## Deploying to Azure

Set these in **App Service → Configuration → Application settings**:

| Variable | Value |
|----------|-------|
| `DATABASE_URL` | `jdbc:postgresql://<host>:5432/<db>?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Postgres username |
| `SPRING_DATASOURCE_PASSWORD` | Postgres password |
| `REDIS_URL` | `rediss://:<access-key>@<host>.redis.cache.windows.net:6380` |
| `FRONTEND_URL` | e.g. `https://shrinkr.azurestaticapps.net` |
| `SAFE_BROWSING_API_KEY` | Google Safe Browsing API v4 key |

Azure Cache for Redis uses TLS on port 6380. The access key goes where the password is in the URL (the part after the colon, before the `@`).

---

## If localStorage is cleared

Links keep working fine. The owner just loses the manage panel on that device. The `manageUrl` returned at creation time has the delete token in it, so if they bookmarked it they can still manage the link from any device.

---

*Frontend: [shrinkr-frontend](https://github.com/shivaxdev/shrinkr-frontend)*
