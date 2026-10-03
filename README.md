# LiteWeb Extractor

A **minimal Android WebView app** with an embedded **NanoHTTPD local HTTP server** for HLS stream detection on low-end devices.

---

## Features

- 🪶 **Ultra-lightweight** — NanoHTTPD only, no OkHttp, no Retrofit, no Room
- 📡 **Local HTTP API** on `http://127.0.0.1:8080`
- 🎯 **HLS detection** via URL pattern (`.m3u8`) and `Content-Type` header
- 🚫 **Ad / tracker blocking** — 25+ known ad networks blocked by default
- 🖼️ **Lite Mode** — block unnecessary images to save RAM, CPU, battery
- ⚡ **Fast startup** — no background services, no polling, event-driven
- 📱 **Targets API 21+** (Android 5.0) for broad device compatibility

---

## API Endpoints

All endpoints are accessible on `http://127.0.0.1:8080` from the same device.

### Load a URL in the WebView

```
GET /?url=https://example.com/video
GET /url=https://example.com/video      # legacy path style
```

**Response:**
```json
{ "success": true, "url": "https://example.com/video", "message": "URL loaded in WebView" }
```

### Extract an HLS URL (recommended, handles many requests at once)

```
GET /extract?url=https://example.com/video[&timeout=25]
```

Opens the page in its own headless WebView, waits for the `.m3u8` request (up to `timeout` seconds,
default 25, max 90) and returns it. Up to **3 pages are processed in parallel**; extra requests wait in a
queue. Two requests for the same page share one job. The `url` may be encoded or raw (with `&` in it).

**Response (found):**
```json
{
  "success": true,
  "type": "hls",
  "url": "https://cdn.example.com/master.m3u8",
  "contentType": "application/vnd.apple.mpegurl",
  "source": "webview_detection"
}
```

**Response (not found):**
```json
{ "success": false, "url": null, "error": "HLS stream not detected" }
```

### Direct .m3u8 URL

If the `url` parameter itself ends with `.m3u8`, it's returned immediately:
```
GET /extract?url=https://example.com/master.m3u8
```

### Server status

```
GET /status
```
```json
{ "success": true, "server": "LiteWebExtractor", "version": "1.0.0", "port": 8080, "hlsDetected": false }
```

---

## Typical Usage Flow

```
1. Open LiteWeb Extractor on your Android device
2. POST a URL via the API:
   http://127.0.0.1:8080/?url=https://yourvideosite.com/watch/12345
3. The WebView navigates to the page and intercepts all network requests
4. When an HLS stream is detected:
   http://127.0.0.1:8080/extract?url=https://yourvideosite.com/watch/12345
   → returns the .m3u8 URL
5. Use the .m3u8 URL in your player (VLC, ffmpeg, etc.)
```

---

## Architecture

```
Android App
   │
   ├── MainActivity
   │      └── LiteWebView (custom WebView)
   │
   ├── LocalServer (NanoHTTPD on :8080)
   │      ├── GET /?url=         → load in WebView
   │      ├── GET /extract?url=  → return HLS JSON
   │      └── GET /status        → health check
   │
   ├── RequestInterceptor
   │      ├── Ad/tracker host blocking
   │      ├── Image blocking (Lite Mode)
   │      └── HLS URL detection
   │
   └── HlsUrlStore (thread-safe AtomicReference)
```

---

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1+) or newer
- JDK 17
- Android SDK with API 34

### Build via Android Studio

1. Clone the repo
2. Open in Android Studio
3. Click **Run ▶** or **Build > Build APK**

### Build via command line

```bash
git clone https://github.com/YOUR_USERNAME/LiteWebExtractor.git
cd LiteWebExtractor
chmod +x gradlew
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/LiteWebExtractor-1.0.0-debug.apk`

### Release build (unsigned)

```bash
./gradlew assembleRelease
```

---

## GitHub Actions — Automatic APK Build

Every push to `main` automatically:

1. **Runs unit tests**
2. **Builds debug APK** → uploaded as artifact
3. **Builds release APK (unsigned)** → uploaded as artifact
4. *(Optional)* **Builds signed release APK** when secrets are configured

### Download the APK from GitHub Actions

1. Go to your repository on GitHub
2. Click **Actions** tab
3. Click the latest **Build APK** workflow run
4. Scroll down to **Artifacts**
5. Download `LiteWebExtractor-debug` or `LiteWebExtractor-release-unsigned`

### Signing Setup (optional)

To produce a signed release APK automatically:

1. Generate a keystore:
   ```bash
   keytool -genkey -v -keystore release.keystore -alias liteweb \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Base64-encode it:
   ```bash
   base64 -i release.keystore | pbcopy   # macOS
   base64 release.keystore | xclip        # Linux
   ```

3. Add these **GitHub Secrets** (Settings → Secrets → Actions):
   | Secret | Value |
   |--------|-------|
   | `KEYSTORE_BASE64` | The base64 string from step 2 |
   | `KEY_ALIAS` | Your key alias (e.g. `liteweb`) |
   | `KEY_PASSWORD` | Key password |
   | `STORE_PASSWORD` | Keystore password |

4. Add a **GitHub Variable** (Settings → Variables → Actions):
   | Variable | Value |
   |----------|-------|
   | `SIGN_BUILD` | `true` |

---

## Running Tests

```bash
./gradlew test
```

Tests cover:
- NanoHTTPD server endpoints (load, extract, status)
- URL validation (valid http/https, invalid ftp/javascript)
- HLS detection by URL pattern and Content-Type
- HLS store thread safety
- Ad/tracker response detection

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| NanoHTTPD | 2.3.1 | Embedded HTTP server |
| AndroidX AppCompat | 1.6.1 | Base Activity |
| Material | 1.11.0 | Switch widget |
| ConstraintLayout | 2.1.4 | Layout |

**No** OkHttp, Retrofit, Dagger, Hilt, Room, or Kotlin Coroutines — by design.

---

## Security Notes

- The server binds to **127.0.0.1 only** — not exposed to the local network
- Only `http://` and `https://` destination URLs are accepted
- Cookies and session data are not logged
- No analytics, no crash reporting


---

## Using it through a Cloudflare tunnel

1. Open the app. The notification "Server running on port 8080" must be visible (the server now lives in a
   foreground service, so it keeps working while you are in Termux).
2. In Termux:
   ```bash
   ./run-tunnel.sh            # or: cloudflared tunnel --url http://127.0.0.1:8080
   ```
3. Use the printed `https://xxxx.trycloudflare.com` URL:
   ```
   https://xxxx.trycloudflare.com/extract?url=https://site.com/watch/1
   ```

Anyone who knows the tunnel URL can use your phone to load pages. Stop the tunnel (Ctrl+C) when done.
