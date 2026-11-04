# Photo Upload Service

Backend REST API for uploading photos to Google Drive. Designed for events (weddings, birthdays, conferences, parties) where guests scan a QR code and upload photos from their mobile devices.

## Architecture

Clean Architecture with abstraction over storage providers.

```
com.wedding.upload
├── config/
├── controller/       REST endpoints
├── dto/              Immutable records
├── exception/        Global handler + custom exceptions
├── service/          Business logic interfaces
├── service.impl/     Business logic implementations
├── storage/          StorageService interface (abstraction)
├── storage.drive/    Google Drive implementation
├── validation/       File validation (MIME, magic bytes, size)
└── util/             File name generation
```

## Quick Start (Local)

### Prerequisites

- Java 21
- Gradle wrapper included (no global Gradle required)

### Setup

```bash
# 1. Create credentials directory
mkdir credentials

# 2. Place your Service Account JSON there
cp /downloads/service-account.json credentials/service-account.json

# 3. Copy env file
cp .env.example .env

# 4. Edit .env with your values
# Required: GOOGLE_DRIVE_FOLDER_ID

# 5. Run with local profile
./gradlew bootRun -Dspring-boot.run.profiles=local
```

### Verify

```bash
curl http://localhost:8080/api/health
# {"status":"UP"}
```

## API

### Upload Photos

```http
POST /api/upload
Content-Type: multipart/form-data
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `files` | Yes | Multiple image files |
| `guestName` | No | Name of the uploading guest |
| `event` | No | Album/event identifier (e.g. `marina-fernando`, `meli-diego`). When omitted, photos go to the default album. |

Success response (200):
```json
{
  "success": true,
  "uploaded": 5,
  "failed": 0,
  "message": "Las fotos se subieron correctamente."
}
```

Error response (400):
```json
{
  "status": 400,
  "message": "Archivo no permitido: documento.pdf. Formatos aceptados: jpg, jpeg, png, webp, heic.",
  "timestamp": "2026-07-21T22:15:00"
}
```

### Health Check

```http
GET /api/health
# {"status":"UP"}
```

### Gallery (albums)

```http
GET /api/photos?page=0&size=6&event=marina-fernando
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `page` | No | Zero-based page (default `0`) |
| `size` | No | Items per page (default `6`) |
| `event` | No | Album identifier. Omit for the default album. |

Photo and thumbnail URLs returned are relative and event-scoped when an event is used:

```json
{
  "url": "/api/photos/marina-fernando/2026-12-05/foto.jpg",
  "thumbnail": "/api/thumbnails/marina-fernando/2026-12-05/foto.jpg"
}
```

Serve endpoints:
- `GET /api/photos/{date}/{filename}` / `GET /api/thumbnails/{date}/{filename}` — default album.
- `GET /api/photos/{event}/{date}/{filename}` / `GET /api/thumbnails/{event}/{date}/{filename}` — event album.

Spring Boot Actuator health is also at `/actuator/health`.

### Upload Rules

| Rule | Default | Config Key |
|------|---------|------------|
| Max file size | 100 MB | `UPLOAD_MAX_FILE_SIZE_MB` |
| Max files per request | 20 | `UPLOAD_MAX_FILES_PER_REQUEST` |
| Allowed formats | JPG, PNG, WebP, HEIC | `upload.allowed-extensions` |
| Rate limit per IP | 200 req/min (prod default) | `UPLOAD_RATE_LIMIT_PER_MINUTE` |
| CORS origins | meliydiego.site, marinayfernando.site, ger1211.github.io, localhost | `cors.allowed-origins` |

## Multi-Event Support

The same backend can serve several weddings/events simultaneously. Pass an `event`
identifier to keep albums isolated on disk:

- `POST /api/upload?event=marina-fernando` → saves to `uploads/marina-fernando/{date}/`
- `GET /api/photos?event=marina-fernando` → lists only that event's photos

Event names are sanitized to lowercase `[a-z0-9_-]` (max 50 chars). Requests without
`event` use the default album at the storage root, so existing integrations keep working
without changes. No database or config change is needed to add a new event.

## File Naming

Uploaded files are renamed: `yyyyMMdd_HHmmss_7charUUID_originalFilename`

Example: `20260721_221501_a3f91c2_IMG_0001.jpg`

## Configuration Reference

All settings are resolved from environment variables.

### Required

| Variable | Description |
|----------|-------------|
| `GOOGLE_DRIVE_FOLDER_ID` | Google Drive folder ID (from folder URL) |
| `GOOGLE_SERVICE_ACCOUNT_PATH` | Path to credentials JSON |

### Optional

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | HTTP server port |
| `SPRING_PROFILES_ACTIVE` | (none) | `local` or `prod` |
| `LOG_LEVEL` | `INFO` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `WEB_LOG_LEVEL` | `WARN` | Spring Web logging level |
| `UPLOAD_MAX_FILE_SIZE_MB` | `100` | Max single file size (MB) |
| `UPLOAD_MAX_REQUEST_SIZE_MB` | `2100` | Max multipart request size (MB) |
| `UPLOAD_MAX_FILES_PER_REQUEST` | `20` | Max files per upload |
| `UPLOAD_RATE_LIMIT_PER_MINUTE` | `200` | Rate limit per IP (prod). The `local` profile defaults to `1000`. Guests sharing a public IP (venue WiFi / proxy) share this quota — keep it above your expected peak. |
| `HEALTH_SHOW_DETAILS` | `always` | Actuator health detail level |

## Google Cloud Setup

### 1. Create a Project

Go to [Google Cloud Console](https://console.cloud.google.com) → Create a new project.

### 2. Enable Google Drive API

APIs & Services → Library → Search "Google Drive API" → **Enable**.

### 3. Create a Service Account

APIs & Services → Credentials → **Create Credentials** → Service Account.

- Name: `photo-upload-service`
- Role: None needed (Drive File scope is sufficient)
- Click Done

### 4. Generate a JSON Key

1. Click the Service Account you just created
2. Keys tab → Add Key → Create New Key → JSON
3. Download the file → rename to `service-account.json`

### 5. Share the Drive Folder

1. Open [Google Drive](https://drive.google.com)
2. Create a folder (or select an existing one)
3. Copy the Folder ID from the URL:
   ```
   https://drive.google.com/drive/folders/1A2B3C4D5E6F7G8H9I0J
                                     ^--- THIS IS YOUR FOLDER ID ---^
   ```
4. Click **Share** on the folder
5. Enter the Service Account email (found in `service-account.json` under `client_email`)
6. Set permission to **Editor**
7. Click Send

### 6. Credential Auto-Detection

The application tries to find credentials in this order:

1. `RENDER_SECRETS_DIR` (`/etc/secrets/service-account.json`) — Render Secret Files
2. `GOOGLE_SERVICE_ACCOUNT_PATH` env var (any path: file, absolute, classpath)
3. Default classpath locations (`classpath:service-account.json`, `classpath:credentials/service-account.json`)
4. Default filesystem locations (`credentials/service-account.json`, `service-account.json`)

## Cloud Deployment (current)

The service runs on a disposable Hetzner VM (cloud primary) and keeps an identical
copy on the home PC (replica) over Tailscale. Turn it on with `nube-on.bat` and off
with `nube-off.bat`. Full runbook: [deploy/README.md](deploy/README.md).

## Render Deployment (legacy)

> The project no longer uses Render or Google Drive. This section is kept for
> historical reference only.


### Step 1: Push to GitHub

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/photo-upload-service.git
git push -u origin main
```

### Step 2: Create Render Web Service

1. Log in to [Render Dashboard](https://dashboard.render.com)
2. Click **New +** → **Web Service**
3. Connect your GitHub repository
4. Select the `photo-upload-service` repo

### Step 3: Configure the Service

| Field | Value |
|-------|-------|
| Name | `photo-upload-service` |
| Runtime | Docker |
| Region | Oregon (or nearest) |
| Branch | `main` |
| Root Directory | (leave blank) |
| Health Check Path | `/api/health` |
| Plan | Free |

### Step 4: Add a Secret File

1. In the service dashboard, go to **Environment** → **Secret Files**
2. Click **Add Secret File**
3. **Filename**: `service-account.json`
4. **Contents**: Paste the entire contents of your `service-account.json` file
5. The file will be available at `/etc/secrets/service-account.json` inside the container

### Step 5: Configure Environment Variables

In the **Environment** tab, add:

| Key | Value |
|-----|-------|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SERVER_PORT` | `8080` |
| `LOG_LEVEL` | `INFO` |
| `GOOGLE_DRIVE_FOLDER_ID` | `1A2B3C4D5E6F7G8H9I0J` (your folder ID) |
| `GOOGLE_SERVICE_ACCOUNT_PATH` | `/etc/secrets/service-account.json` |
| `JAVA_OPTS` | `-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` |

### Step 6: Deploy

Click **Create Web Service**. Render will:
1. Pull your repository
2. Build the Docker image
3. Start the container
4. Run health checks

### Step 7: Verify Deployment

Once the service is live, test the health endpoint:

```bash
curl https://photo-upload-service.onrender.com/api/health
# {"status":"UP"}
```

Test an upload (requires `curl` with multipart support):

```bash
curl -X POST https://photo-upload-service.onrender.com/api/upload \
  -F "files=@photo.jpg" \
  -F "guestName=Test"
```

### Step 8: Connect to Your Frontend

Update your frontend (e.g., the wedding invitation website) to point uploads to:

```
https://photo-upload-service.onrender.com/api/upload
```

No authentication needed from guests — Google auth is handled server-side via the Service Account.

## Changing the Drive Folder Without Recompiling

1. Go to the Render Dashboard → Your Service → Environment
2. Change the `GOOGLE_DRIVE_FOLDER_ID` value to the new folder ID
3. Share the new folder with your Service Account email (Editor permission)
4. Render will **automatically redeploy** with the new value

No code changes. No recompile. No Docker rebuild.

## Rotating Google Credentials

If you need to regenerate the Service Account key:

1. Google Cloud Console → Service Accounts → Keys → Delete old key → Create new key
2. Download the new JSON
3. Render Dashboard → Environment → Secret Files → Edit `service-account.json` → Paste new content → Save
4. Render redeploys automatically

## Docker (Local)

```bash
# Build and run
docker compose up -d

# View logs
docker compose logs -f

# Stop
docker compose down
```

## Troubleshooting Render Deployments

### Service won't start

Check the deploy logs in the Render Dashboard. Common issues:

- **"GOOGLE_DRIVE_FOLDER_ID is required"**: The env var is not set. Go to Environment → add it.
- **"credentials not found"**: The Secret File is not mounted correctly. Verify the filename matches `service-account.json`.
- **"Forbidden" from Google Drive**: The Service Account does not have Editor access to the folder. Share the folder with the `client_email` from the JSON.

### Health check fails

Override the health check path if needed. The default is `/api/health`. Also verify your Service Account credentials are valid — the health check won't pass if the Drive initialization fails.

### Out of memory

The Free plan has 512 MB RAM. Adjust `JAVA_OPTS`:

```
-Xms128m -Xmx400m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
```

### How to read Render logs

1. Dashboard → Your Service → **Logs** tab
2. Filter by date/time
3. Search for specific terms
4. Download logs if needed (button at top right)

## Project Structure

```
photo-upload-service/
├── src/main/java/com/wedding/upload/
│   ├── PhotoUploadApplication.java
│   ├── config/
│   │   ├── CorsConfig.java
│   │   ├── GoogleDriveProperties.java
│   │   ├── UploadProperties.java
│   │   ├── RateLimitProperties.java
│   │   ├── RateLimiterConfig.java
│   │   ├── SimpleRateLimiter.java
│   │   └── RateLimitFilter.java
│   ├── controller/
│   │   ├── PhotoUploadController.java
│   │   └── HealthController.java
│   ├── dto/
│   │   ├── UploadResponse.java
│   │   ├── ErrorResponse.java
│   │   └── HealthResponse.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── FileValidationException.java
│   │   ├── StorageException.java
│   │   └── UploadLimitExceededException.java
│   ├── service/
│   │   ├── UploadService.java
│   │   └── impl/UploadServiceImpl.java
│   ├── storage/
│   │   ├── StorageService.java
│   │   └── drive/GoogleDriveStorageService.java
│   ├── validation/FileValidator.java
│   └── util/FileNameGenerator.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   └── application-prod.yml
├── src/test/java/com/wedding/upload/   (31 tests)
├── Dockerfile
├── docker-compose.yml
├── render.yaml
├── build.gradle
└── README.md
```

## Reusing for Other Events

This service is **multi-event**. To reuse for a different event:

1. Pick an event slug (e.g. `marina-fernando`).
2. Update the frontend `event` param on `POST /api/upload` and `GET /api/photos`.
3. Optionally add the frontend domain to `cors.allowed-origins`.

No code changes needed on the backend.

## Switching Storage Providers

To replace Google Drive with S3, R2, or Azure:

1. Implement `StorageService` interface
2. Annotate with `@Service` (or `@Primary`)
3. Remove or disable `GoogleDriveStorageService`

The controllers and upload logic remain unchanged.
