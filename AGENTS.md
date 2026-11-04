# AGENTS.md — Photo Upload Service

## Java 21 is required but not in system PATH

The default `JAVA_HOME` points to JDK 17. Set it explicitly before any Gradle command:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
```

Gradle is not installed globally. Always use the wrapper:

```powershell
./gradlew.bat <task>
```

## Quick commands

```powershell
# Compile only (fast feedback)
./gradlew.bat compileJava

# Run all tests
./gradlew.bat test

# Run a single test class
./gradlew.bat test --tests "*FileNameGeneratorTest"

# Build JAR (skip tests)
./gradlew.bat build -x test

# Run locally in NOOP mode (no Google credentials needed)
./gradlew.bat bootRun -Dspring-boot.run.profiles=local

# Expose via Tailscale Funnel (replaces Pinggy/Cloudflare tunnel)
./start-tunnel.bat   # starts Docker + points funnel at SERVER_PORT (default 8080)

# Manual funnel control
tailscale funnel status
tailscale funnel --bg 8080   # (re)point funnel at the backend port
tailscale funnel reset

# Or build JAR and run directly
./gradlew.bat build -x test
java -jar build/libs/photo-upload-service-1.0.0.jar --spring.profiles.active=local

# Docker build
docker compose build
docker compose up -d

# Home PC as replica (archive outside OneDrive)
docker compose -f docker-compose.replica.yml up -d --build

# Cloud VM (Hetzner) — creates, bootstraps and exposes the primary
./nube-on.bat
# Copies the server's photos to Pictures\fotos-nube (nube-off runs it too)
./descargar-fotos.bat
# Destroys it, but only after proving the PC has every photo and downloading a copy
./nube-off.bat
```

## Architecture

Clean architecture under `com.wedding.upload`. No database — stateless service. Google Drive is the only storage backend.

| Package | Role |
|---------|------|
| `config/` | CORS, rate limiter, `@ConfigurationProperties` classes |
| `controller/` | `PhotoUploadController` (POST), `HealthController` (GET) |
| `dto/` | Immutable records: `UploadResponse`, `ErrorResponse`, `HealthResponse` |
| `exception/` | `GlobalExceptionHandler` + 3 custom exception types |
| `service/` | Interfaces: `UploadService` |
| `service.impl/` | `UploadServiceImpl` — note: sub-package, not same as interface |
| `storage/` | `StorageService` interface — swappable via DI |
| `storage.local/` | `LocalDiskStorageService` — disk storage, event-aware (`sanitizeEvent`, `resolveEventPath`) |
| `storage.drive/` | `GoogleDriveStorageService` — Service Account auth, stream uploads |
| `validation/` | `FileValidator` — MIME, extension, size, magic bytes |
| `util/` | `FileNameGenerator` — `yyyyMMdd_HHmmss_7charUUID_sanitized` |

Constructor injection only. No field injection. No Lombok. No MapStruct.

## Profiles

| Profile | File | Purpose |
|---------|------|---------|
| `local` | `application-local.yml` | Local dev: `storage.provider=noop` (no Google creds needed), DEBUG logging, relaxed rate limits |
| `prod` | `application-prod.yml` | Production: `storage.provider=drive`, compression, WARN logging, muted health details |
| (none/default) | `application.yml` | Every value resolves from env vars; `storage.provider` defaults to `drive`

## Multi-event albums

`POST /api/upload` and `GET /api/photos` accept an optional `event` param. Photos are
stored under `{storageRoot}/{event}/{date}/...` (event absent → storage root, default
album). `LocalDiskStorageService.sanitizeEvent()` normalizes event names to lowercase
`[a-z0-9_-]`, max 50. `GalleryController` returns event-scoped photo/thumbnail URLs and
serves them at `/api/photos/{event}/{date}/{filename}`. Existing default-album endpoints
stay for backward compat.

## Storage

`LocalDiskStorageService` is the only storage backend (`storage.local.path`, default
`./uploads`). Google Drive and the noop provider were removed — do not re-add them.

Photos live at `{storageRoot}/{event}/{yyyy-MM-dd}/{filename}` with an append-only
`manifest.jsonl` per date folder. `PhotoInfo` carries a `sha256` (nullable: entries
written before replication existed do not have one).

## Peer replication (cloud primary + home PC replica)

`replication.*` is **disabled by default**; a single-machine deployment behaves
exactly as before. When enabled:

| Bean | Role |
|------|------|
| `ReplicatingStorageService` | `@Primary` decorator: writes locally, then pushes to the peer. A peer failure never fails the guest upload — it goes to the outbox. |
| `Outbox` | Append-only `{state-path}/outbox.jsonl`, backoff 30 s → 5 min. |
| `ReconciliationJob` | Every 60 s compares both manifests by `(event, dateFolder, filename, sha256)` and repairs **both directions**. |
| `InternalReplicationController` | `/api/internal/{replicate,manifest,photo,status}`, guarded by `X-Replication-Token`. |

Invariants — do not break them:

1. The guest gets a success response only after the local write succeeds.
2. The internal endpoints write through the **concrete** `LocalDiskStorageService`,
   never through the `@Primary` decorator (otherwise pushes loop forever).
3. Idempotency is by filename: if the file already exists on the peer, the push is
   `skipped` and the manifest is not duplicated.
4. `replication.state-path` must be **outside** `storage.local.path`, or the outbox
   becomes downloadable via `/api/photos/...`.
5. Pushes carry an explicit `dateFolder`: a retry after midnight must still land in
   the folder of the original day.
6. Pushes carry the guest's `originalName` explicitly. The multipart part already
   has the renamed file, so `LocalDiskStorageService.store()` must never fall back
   to it on the replication path.

`RateLimitFilter` only matches `POST /api/upload`, so `/api/internal/**` is already
exempt — no change needed.

Tests use their own `src/test/resources/application.yml` — no profile needed.


## Container gotchas

- **Tailscale Funnel must match the backend port.** Funnel proxies `https://desktop-udmt3js.tailfe106b.ts.net` to `http://127.0.0.1:<port>` and returns HTTP 502 if the port is wrong. Keep it in sync with `SERVER_PORT` (default 8080) via `tailscale funnel --bg <port>`. Check with `tailscale funnel status`.
- **The root `/` of the funnel belongs to this service (photos).** `foodsnap-ai-backend` (port 8787) lives under the `/snap` path mount. Never run `tailscale funnel reset` (it wipes the `/snap` mount) — use `./start-tunnel.bat` instead, which idempotently re-points both `/` → `SERVER_PORT` and `/snap` → `8787`.
- **`GOOGLE_DRIVE_FOLDER_ID` is required** — no default. The app throws `IllegalStateException` at startup if missing.
- Rate limiter is in-memory (`SimpleRateLimiter`) — per-instance, not distributed. Uses sliding window (60s) with configurable `maxRequests`. `synchronized` on `tryConsume` to avoid race conditions.
- **Rate limit defaults:** prod = 200 req/min per IP, `local` profile = 1000. Both resolve `${UPLOAD_RATE_LIMIT_PER_MINUTE:...}` from env. Guests behind one public IP (venue WiFi / Render proxy) share the quota — keep it above peak, or everyone gets 429 after a burst.
- Docker uses `wget` for healthcheck — it's installed via `apk add --no-cache wget` in the Dockerfile.
- JVM flags in Docker: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` — critical for container memory limits on Render.

## Reliability hardening (stress-tested at 50-100 concurrent uploads)

- `PhotoManifest` is **append-only** (`manifest.jsonl`, one JSON `PhotoInfo` per line). `addEntry` is `synchronized` and O(1) per photo — no whole-file rewrite, so it scales to thousands of photos and never loses entries under concurrency. `readAll` also reads a legacy `manifest.json` (array) if present. Corrupt trailing lines are skipped.
- Thumbnails are **generated asynchronously** (`LocalDiskStorageService.thumbnailExecutor`, 4 daemon threads, bounded queue 256, `DiscardPolicy`). A full thumbnail queue never blocks the upload request thread — a dropped thumbnail is regenerated on-the-fly by `GalleryController` when the photo is viewed.
- `ImageThumbnail.readBounded()` caps decoded pixels to `MAX_DECODE_DIMENSION` (1280px) via `ImageReadParam.setSourceSubsampling`. Decoding a 3000-5000px phone photo at full resolution (~30-60MB heap each) used to OOM the 512MB heap under concurrency; subsampling bounds the intermediate bitmap.
- **Known Docker Desktop limitation:** concurrent JVM file writes to the bind mount / named volume stall intermittently for ~20s on Windows (threads sit in state S on the virtiofs/gRPC-FUSE layer). This is an environment artifact — dd throughput is >1GB/s and tiny files are unaffected. Validate real capacity on Render's native Linux filesystem.

## Test quirks

- `FileValidatorTest` and `UploadServiceImplTest` use `@MockitoSettings(strictness = Strictness.LENIENT)` — needed because `@BeforeEach` stubs methods not used by every test.
- `GoogleDriveStorageServiceTest` uses a package-private constructor `GoogleDriveStorageService(Drive, String)` to inject a mock Drive — the public constructor does real credential loading.
- Controller test uses `@WebMvcTest` with explicit exclusion of `RateLimitFilter` and `CorsConfig`, plus `@Import(GlobalExceptionHandler.class)`.
- `@MockitoBean` comes from `org.springframework.test.context.bean.override.mockito.MockitoBean` (Spring Boot 3.5.x path), NOT the old `org.springframework.boot.test.mock.mockito.MockBean`.

## User-facing text

All API error messages are in Argentine Spanish (es-AR). Code, classes, variables, comments, and docs remain in English. Do not mix languages.

## Danger zones

- Never commit credentials. `.gitignore` blocks `credentials/`, `*.json` (credential patterns), `*.pem`, `*.key`, `.env`. The `test-credentials.json` is fake — safe to commit.
- `UploadServiceImpl` lives in `com.wedding.upload.service.impl` but implements `com.wedding.upload.service.UploadService`. The package split is intentional per clean architecture. Don't flatten them.
- `FileNameGenerator` is a `@Component` — required for Spring DI into `UploadServiceImpl`. Don't remove the annotation.
- `CorsProperties` uses `@ConfigurationProperties(prefix = "cors")` to bind YAML lists — `@Value` can't resolve YAML arrays.
- `storage.noop.NoopStorageService` and `storage.drive.GoogleDriveStorageService` use mutual exclusion via `@ConditionalOnProperty(name = "storage.provider")`. Never load both simultaneously.
