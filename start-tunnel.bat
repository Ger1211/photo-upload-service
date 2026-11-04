@echo off
setlocal
echo =========================================
echo  Photo Upload - Setup Tailscale Funnel
echo =========================================
echo.

echo Starting Docker services...
docker compose up -d

echo.
echo Getting SERVER_PORT from .env (default 8080)...
set SERVER_PORT=8080
if exist .env (
    for /f "tokens=1,* delims==" %%a in (.env) do (
        if /i "%%a"=="SERVER_PORT" set SERVER_PORT=%%b
    )
)

echo.
echo Pointing funnel at http://127.0.0.1:%SERVER_PORT% (root = photos)...
tailscale funnel --bg http://127.0.0.1:%SERVER_PORT%

echo.
echo Re-adding /snap mount (foodsnap-ai on 8787)...
tailscale funnel --bg --set-path=/snap http://127.0.0.1:8787

echo.
echo Checking funnel status...
tailscale funnel status

echo.
echo Public URL: https://desktop-udmt3js.tailfe106b.ts.net
echo Root "/" is the photo upload service (8080), "/snap" is foodsnap (8787).
echo Update these URLs in fotos.html (line API_BASE_URL) if they change.
echo.
endlocal