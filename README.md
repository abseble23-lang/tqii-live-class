# TQII Live Class

Live video classes for 1 teacher + up to 3 students, with chat, screen sharing and background replacement. Bangla + English.

- `android/` — Android app (Kotlin, Jetpack Compose, WebRTC peer-to-peer, ML Kit selfie segmentation)
- `admin/` — Admin panel (single HTML page, deployed on Vercel)
- Backend — Supabase project `tqii-live-class` (auth, Postgres, Realtime for chat + call signaling)

## Download the app
Latest APK: https://github.com/abseble23-lang/tqii-live-class/releases/latest/download/TQII-Live-Class.apk

Every push to `main` that touches `android/` builds a new signed APK with GitHub Actions and publishes it as a release.

## Network note
Video goes directly phone-to-phone (no media server). On some mobile networks a TURN relay is needed; add it in the
`app_config` table (`ice_servers` key) — no app update required.
