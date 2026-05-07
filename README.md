# GPunch – Secure On-Site Presence Verification System

**Institution:** PSG College of Technology, Coimbatore  
**Department:** Computer Applications (MCA)  
**Team:** Kavin M, Shanmugappriya K  
**Faculty Guide:** Dr. N. Ilayaraja

---

## Architecture

```
gpunch-opv/
├── backend/          # Node.js + Express REST API
│   ├── src/
│   │   ├── index.js             # Server entry point
│   │   ├── models/              # Mongoose schemas
│   │   ├── routes/              # Express route handlers
│   │   ├── middleware/          # JWT auth middleware
│   │   └── utils/               # Haversine, mailer
│   ├── tests/                   # Jest unit tests
│   ├── package.json
│   └── .env.example
└── android/          # Native Android (Kotlin) client
    └── app/src/main/
        ├── java/com/gpunch/
        │   ├── api/             # Retrofit service interface
        │   ├── models/          # Request/Response DTOs
        │   ├── services/        # Foreground Service (auto clock-out)
        │   ├── ui/              # Activities + ViewModels
        │   └── utils/           # GeofenceUtils, SessionManager
        └── res/                 # XML layouts, strings, colors
```

---

## Backend Setup

### Prerequisites
- Node.js ≥ 18
- MongoDB Atlas account (free tier M0 works)
- Gmail account with [App Password](https://support.google.com/accounts/answer/185833) for OTP emails

### Local Development

```bash
cd backend
npm install
cp .env.example .env
# Edit .env with your MongoDB URI, JWT secret, and SMTP credentials
npm run dev
```

### Environment Variables

| Variable | Description |
|---|---|
| `MONGODB_URI` | MongoDB Atlas connection string |
| `JWT_SECRET` | Long random string for signing JWTs |
| `JWT_EXPIRES_IN` | Token validity (e.g. `7d`) |
| `SMTP_HOST` | SMTP host (`smtp.gmail.com`) |
| `SMTP_PORT` | SMTP port (`587`) |
| `SMTP_USER` | Gmail address |
| `SMTP_PASS` | Gmail App Password |
| `OTP_EXPIRY_MINUTES` | OTP validity window (default `10`) |
| `ADMIN_SEED_SECRET` | One-time secret to seed the first admin |

### API Endpoints

#### Auth
| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Domain-restricted registration – sends OTP |
| POST | `/api/auth/verify-otp` | Verify OTP + bind device |
| POST | `/api/auth/login` | Passwordless login – sends OTP |
| POST | `/api/auth/resend-otp` | Resend OTP |

#### Punch (requires JWT)
| Method | Path | Description |
|---|---|---|
| POST | `/api/punch/in` | Clock in with geofence validation |
| POST | `/api/punch/out` | Clock out (manual or auto) |
| GET | `/api/punch/status` | Current clock-in status |
| GET | `/api/punch/history` | Paginated attendance history |

#### Admin (requires JWT + admin role)
| Method | Path | Description |
|---|---|---|
| POST | `/api/admin/seed` | Bootstrap first admin account |
| POST | `/api/admin/geofence` | Set geofence lat/lng/radius/domain |
| GET | `/api/admin/geofence` | Get current geofence config |
| POST | `/api/admin/reset-device/:userId` | Clear device binding (F07) |
| GET | `/api/admin/users` | List all users |
| GET | `/api/admin/audit-logs` | Security audit log |
| GET | `/api/admin/export-csv` | Download attendance CSV (F08) |

#### Audit (requires JWT)
| Method | Path | Description |
|---|---|---|
| POST | `/api/audit` | Report security events from Android |

### Deploy to Render (free tier)

1. Push repository to GitHub.
2. Go to [render.com](https://render.com) → **New** → **Web Service**.
3. Connect your GitHub repository, set **Root Directory** to `backend`.
4. Set **Build Command**: `npm install`; **Start Command**: `node src/index.js`.
5. Add all environment variables from `.env.example`.
6. Deploy – Render provides a free HTTPS URL.

### Seed Admin Account

After deploying, call the seed endpoint once:

```bash
curl -X POST https://<your-render-url>/api/admin/seed \
  -H "Content-Type: application/json" \
  -d '{"name":"Admin","email":"admin@psgtech.ac.in","secret":"<ADMIN_SEED_SECRET>"}'
```

Then login via `/api/auth/login` to get a JWT and configure the geofence:

```bash
# Configure geofence (PSG College of Technology example)
curl -X POST https://<your-render-url>/api/admin/geofence \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"latitude":11.0168,"longitude":76.9558,"allowedRadius":300,"allowedDomain":"psgtech.ac.in"}'
```

---

## Android Client Setup

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17

### Configuration

Open `android/app/build.gradle` and update the `BASE_URL` in `buildConfigField` to point to your deployed backend URL.

Alternatively create `android/local.properties` and add:
```
BASE_URL=https://your-render-url.onrender.com
```

### Build & Run

1. Open the `android/` folder in Android Studio.
2. Let Gradle sync.
3. Run on a physical device (GPS required for accurate location).

### Key Features

| Feature | Implementation |
|---|---|
| Domain-restricted OTP registration | `RegisterActivity` → `/api/auth/register` |
| Device binding (ANDROID_ID) | `GeofenceUtils.getAndroidId()` bound on first OTP verify |
| Geofence clock-in | `DashboardActivity` → Haversine pre-check + server validation |
| Auto clock-out | `GeofenceMonitorService` (Foreground Service) |
| Mock location detection | `GeofenceUtils.isMockLocation()` using `Location.isMock` |
| Security audit logging | `/api/audit` for all security events |
| Battery optimisation | Adaptive GPS poll interval (15s active / 60s stationary) |
| Admin device reset | `POST /api/admin/reset-device/:userId` |
| CSV export | `GET /api/admin/export-csv` |

---

## Running Tests

```bash
cd backend
npm test
```

---

## Security Notes

- All API routes (except `/api/auth/*` and `/health`) require a valid JWT.
- Admin routes additionally require `role: "admin"`.
- OTPs are never returned in API responses (stripped by Mongoose `toJSON`).
- GPS mock-location detection runs on both client (Android `Location.isMock`) and is audited server-side.
- Rate limiting: 100 req/15 min globally; 20 req/15 min on auth endpoints.
- HTTPS enforced on deployment (Render provides TLS).

---

## License
MIT