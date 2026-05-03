# GPunch
## Secure On-Site Presence Verification System
### Software Requirements Specification — Presentation Edition

---
**Institution:** PSG College of Technology, Coimbatore  
**Department:** Computer Applications (MCA)  
**Team:** Kavin M · Shanmugappriya K  
**Faculty Guide:** Dr. N. Ilayaraja  
**Version:** 1.0 · May 2026

---

---

## Slide 1 — Problem Statement

### Traditional Attendance Systems Are Broken

| Problem | Impact |
|---|---|
| 📋 Paper registers | Easy to forge; no audit trail |
| 🔢 PIN / password systems | Shared credentials enable buddy punching |
| 📸 Selfie-based apps | Privacy concerns; spoofable with photos |
| 🗺️ Map-based check-in | No enforcement of physical presence |

### ✅ GPunch solves all of these — without passwords, maps, or selfies

---

## Slide 2 — What is GPunch?

> **GPunch** is a passwordless, GPS-geofenced attendance system that proves physical presence by combining institutional email OTP, device fingerprinting, and real-time GPS validation.

### Three Pillars of Security

```
┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
│  📧 Who are you? │   │  📱 Your Device?  │   │  📍 Where are you?│
│                  │   │                  │   │                  │
│  Institutional   │   │  ANDROID_ID      │   │  GPS Geofence    │
│  Email OTP       │   │  Device Binding  │   │  Haversine Check │
│                  │   │                  │   │                  │
│  ✓ No password   │   │  ✓ One device    │   │  ✓ Must be on    │
│  ✓ Domain check  │   │  ✓ Per account   │   │    campus        │
└──────────────────┘   └──────────────────┘   └──────────────────┘
```

---

## Slide 3 — System Architecture

```
                    ┌─────────────────────────┐
                    │     Android App (Kotlin) │
                    │  ┌───────────────────┐   │
                    │  │  DashboardActivity│   │
                    │  │  LoginActivity    │   │
                    │  │  RegisterActivity │   │
                    │  │  OtpActivity      │   │
                    │  └────────┬──────────┘   │
                    │  ┌────────▼──────────┐   │
                    │  │  GeofenceService  │   │  ◄── Persistent
                    │  │  (Foreground Svc) │   │      Notification
                    │  └────────┬──────────┘   │
                    │  ┌────────▼──────────┐   │
                    │  │  PendingClockOut  │   │  ◄── Offline Queue
                    │  │  Worker (WM)      │   │
                    │  └───────────────────┘   │
                    └────────────┬────────────┘
                                 │ HTTPS / REST
                    ┌────────────▼────────────┐
                    │   Node.js / Express API  │
                    │  ┌──────┬──────┬──────┐  │
                    │  │ Auth │Punch │Admin │  │
                    │  └──────┴──────┴──────┘  │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │      MongoDB Atlas       │
                    │  Users · Attendance      │
                    │  GeofenceConfig · Audit  │
                    └─────────────────────────┘
```

---

## Slide 4 — User Roles

| Role | How They Interact | Capabilities |
|---|---|---|
| 👤 **Employee** | Android App | Register · Login · Clock In/Out · View History |
| 🔧 **Admin** | REST API / Postman | Configure Geofence · Reset Devices · Export CSV · View Audit Logs |
| ⚙️ **System** | Automatic (Background) | Auto Clock-Out · Offline Retry · Boot Recovery |

---

## Slide 5 — Functional Requirements (FR-01 to FR-06)

### Authentication & Identity

| ID | Requirement | Key Constraint |
|---|---|---|
| **FR-01** | Domain-restricted self-registration | Only `@<institution>` emails accepted |
| **FR-02** | OTP verification + device binding | ANDROID_ID locked on first verify |
| **FR-03** | Device binding enforcement | Login from unbound device → rejected + logged |
| **FR-04** | GPS settings check | High-accuracy GPS required before clock-in |
| **FR-05** | Geofence clock-in / clock-out | Server-side Haversine validation |
| **FR-06** | Mock / fake GPS detection | `Location.isMock` + developer options check |

---

## Slide 6 — Functional Requirements (FR-07 to FR-13)

### Monitoring, Admin & Reliability

| ID | Requirement | Key Detail |
|---|---|---|
| **FR-07** | Persistent background notification | Foreground service (Android requirement) |
| **FR-08** | Auto clock-out on geofence breach | Background GPS monitoring, API call |
| **FR-09** | Admin: geofence + domain config | Upsert single config; instant effect |
| **FR-10** | Admin: device unlinking | Clears `androidId`; user can re-bind |
| **FR-11** | Admin: security audit log | 6 event types logged |
| **FR-12** | Offline clock-out queueing | WorkManager + `CONNECTED` constraint |
| **FR-13** | Admin: CSV export | All attendance records as downloadable file |

---

## Slide 7 — Clock-In Flow

```
  Employee                  App                    Server
     │                       │                       │
     │  ① Tap "Clock In"     │                       │
     ├──────────────────────►│                       │
     │                       │  ② Check GPS on?      │
     │                       │  ③ Get coordinates    │
     │                       │  ④ isMock() = false?  │
     │                       ├───────────────────────►
     │                       │  ⑤ POST /punch/in      │
     │                       │   { lat, lng, androidId}│
     │                       │                       │  ⑥ Haversine ≤ radius?
     │                       │◄──────────────────────┤
     │                       │  ⑦ 201 Clocked In     │
     │◄──────────────────────│                       │
     │  ⑧ Start Foreground   │                       │
     │     Service           │                       │
```

---

## Slide 8 — Auto Clock-Out Flow (with Offline Support)

```
  Background Service                Server             WorkManager
       │                               │                    │
       │  GPS poll every 15–60s        │                    │
       │  distance > radius?           │                    │
       │                               │                    │
       │── POST /punch/out ───────────►│                    │
       │                               │                    │
       │  If network OK ──────────────►│ Record saved       │
       │                               │                    │
       │  If Airplane Mode ────────────────────────────────►│
       │    → enqueue PendingClockOut  │             Queued  │
       │      Worker (CONNECTED)       │                    │
       │                               │                    │
       │◄─── Network restored ─────────────────────────────┤
       │                               │◄── Retry sent ─────┤
       │  Notification: "Auto clocked out"                  │
```

---

## Slide 9 — Security Model

### Threat → Mitigation Matrix

| 🔴 Threat | ✅ Mitigation | Where |
|---|---|---|
| Buddy punching | Device binding (1 account = 1 device) | FR-03 |
| GPS spoofing (Fake GPS) | `Location.isMock` + dev-options check | FR-06 |
| Remote API clock-in | Server Haversine validation (can't bypass) | FR-05 |
| NoSQL injection | Explicit type casting on all queries | NFR-01.4 |
| JWT forgery | HS256 + `JWT_SECRET` + 7-day expiry | NFR-01.1 |
| OTP brute-force | 20 req/15min rate limit + 10min OTP expiry | NFR-01 |
| Replay attacks | OTP nulled after use; JWT expiry | FR-02 |

---

## Slide 10 — Data Model (ERD Summary)

```
┌─────────────────┐         ┌────────────────────────┐
│      User        │         │    AttendanceRecord     │
├─────────────────┤         ├────────────────────────┤
│ _id             │─────────│ userId (ref)            │
│ name            │    1:N  │ email                   │
│ email (unique)  │         │ clockInTime / clockOutTime│
│ role            │         │ clockInLat / clockInLng │
│ androidId       │         │ durationMinutes         │
│ isVerified      │         │ autoClockOut            │
│ isActive        │         │ androidId               │
└─────────────────┘         └────────────────────────┘

┌─────────────────┐         ┌────────────────────────┐
│  GeofenceConfig │         │       AuditLog          │
├─────────────────┤         ├────────────────────────┤
│ latitude        │         │ userId (ref, nullable)  │
│ longitude       │         │ email                   │
│ allowedRadius   │         │ androidId               │
│ allowedDomain   │         │ eventType (enum)        │
│ updatedBy (ref) │         │ latitude / longitude    │
│                 │         │ ipAddress               │
│ [Singleton]     │         │ metadata                │
└─────────────────┘         └────────────────────────┘

eventType ∈ { MOCK_LOCATION · UNAUTHORIZED_DEVICE · OUT_OF_BOUNDS
              INVALID_DOMAIN · OTP_FAILED · SUSPICIOUS_ACTIVITY }
```

---

## Slide 11 — Non-Functional Requirements

### Security NFRs
- ✅ JWT required for all protected routes (HS256, 7-day expiry)
- ✅ OTP never appears in any API response
- ✅ `helmet.js` hardens all HTTP headers
- ✅ Body size capped at 10KB (flood protection)
- ✅ HTTPS enforced in production

### Performance NFRs
- ✅ Clock-in API response ≤ 2 seconds
- ✅ GPS fix within 10 seconds of app open
- ✅ Adaptive polling: 15s moving / 60s stationary (battery optimisation)

### Reliability NFRs
- ✅ Offline payloads survive app restarts (WorkManager persistence)
- ✅ `BootReceiver` restores monitoring after device reboot

### Usability NFRs
- ✅ Zero passwords — only email + OTP
- ✅ OTP resend timer (60s) prevents abuse
- ✅ Human-readable error messages throughout

---

## Slide 12 — Test Cases Overview

| TC | Requirement | Pass Criteria |
|---|---|---|
| TC-01 | Domain restriction | `@gmail.com` → rejected + audit logged |
| TC-02 | Domain restriction | `@psgtech.ac.in` → OTP sent |
| TC-03 | OTP verification | Wrong OTP → "Invalid OTP" + audit logged |
| TC-04 | Device binding | Device 2 → rejected + audit logged |
| TC-05 | GPS check | GPS off → system settings dialog |
| TC-06 | Geofence inside | 20m from centre → Clocked In ✅ |
| TC-07 | Geofence outside | 200m from centre → Denied |
| TC-08 | Mock location | Fake GPS → denied + `MOCK_LOCATION` logged |
| TC-09 | Background tracking | Minimise app → persistent notification |
| TC-10 | Auto clock-out | Walk away → background API + notification |
| TC-11 | Admin config | Radius 50m→10m → users at 20m denied |
| TC-12 | Device reset | Admin unlinks → user can re-bind |
| TC-13 | Audit log | Dashboard shows TC-01, TC-04, TC-08 events |
| TC-14 | Offline queueing | Airplane Mode → queued; sent on reconnect |

**10 backend tests passing** (Jest + supertest)

---

## Slide 13 — API Summary

### Auth (No JWT needed)
| Endpoint | Purpose |
|---|---|
| `POST /api/auth/register` | Self-registration (domain check + OTP) |
| `POST /api/auth/verify-otp` | OTP verify + device bind → JWT |
| `POST /api/auth/login` | Passwordless login → OTP |
| `POST /api/auth/resend-otp` | Resend OTP |

### Punch (JWT required)
| Endpoint | Purpose |
|---|---|
| `POST /api/punch/in` | Clock in (geofence validated) |
| `POST /api/punch/out` | Clock out (manual or auto) |
| `GET /api/punch/status` | Current status |
| `GET /api/punch/history` | Attendance history |

### Admin (JWT + admin role)
| Endpoint | Purpose |
|---|---|
| `POST /api/admin/geofence` | Set geofence + domain |
| `POST /api/admin/reset-device/:id` | Unlink device |
| `GET /api/admin/audit-logs` | Security logs |
| `GET /api/admin/export-csv` | Download CSV |

---

## Slide 14 — Technology Stack

```
┌────────────────────────────────────────────────────────┐
│                    Android Client                       │
│  Kotlin 1.9 · MVVM · Retrofit 2.11 · OkHttp 4.12      │
│  Google Play Services (FLP) 21.3 · WorkManager 2.9     │
│  Min SDK 26 (Android 8.0) · Target SDK 34              │
├────────────────────────────────────────────────────────┤
│                    Backend API                          │
│  Node.js ≥18 · Express 4.18 · Mongoose 8.3             │
│  jsonwebtoken 9.0 · Nodemailer 8.0                     │
│  express-validator 7.0 · helmet 7.1                    │
├────────────────────────────────────────────────────────┤
│                    Database                             │
│  MongoDB Atlas (M0 free tier)                          │
│  4 collections: Users, AttendanceRecords,              │
│                 GeofenceConfig, AuditLogs              │
├────────────────────────────────────────────────────────┤
│                    Infrastructure                       │
│  Render.com (free HTTPS hosting) · Gmail SMTP          │
│  Jest + supertest (10 tests passing)                   │
└────────────────────────────────────────────────────────┘
```

---

## Slide 15 — Admin Configuration Workflow

```
  ① Deploy backend to Render
        │
  ② Seed admin account
     POST /api/admin/seed
     { name, email, secret: ADMIN_SEED_SECRET }
        │
  ③ Login as admin
     POST /api/auth/login { email, androidId }
     → verify OTP → get JWT
        │
  ④ Configure Geofence
     POST /api/admin/geofence
     {
       latitude: 11.0168,      ← PSG College of Technology
       longitude: 76.9558,
       allowedRadius: 300,     ← 300 metres
       allowedDomain: "psgtech.ac.in"
     }
        │
  ⑤ Employees self-register — zero admin intervention needed
  ⑥ Monitor via GET /api/admin/audit-logs
  ⑦ Export records via GET /api/admin/export-csv
```

---

## Slide 16 — Key Design Decisions

| Decision | Rationale |
|---|---|
| **No passwords** | Eliminates credential sharing and password reuse attacks |
| **ANDROID_ID binding** | Ties attendance to a specific physical device; resets on factory reset |
| **Server-side Haversine** | Client coordinates cannot be manipulated; server is the source of truth |
| **Foreground Service** | Android OS will not kill the monitoring service; required for reliable auto-clock-out |
| **WorkManager (offline queue)** | Guarantees delivery of clock-out even after Airplane Mode / network loss |
| **Single geofence (singleton)** | Simplifies admin UX; one config per deployment, easily updated |
| **Audit logs** | Immutable record of all security events; enables proxy/buddy punch investigations |
| **MongoDB Atlas M0** | Zero-cost cloud database; sufficient for ≥500 users |

---

## Slide 17 — Deployment Guide (Quick Reference)

```bash
# 1. Clone and configure backend
cd backend
cp .env.example .env
# Edit: MONGODB_URI, JWT_SECRET, SMTP_USER, SMTP_PASS, ADMIN_SEED_SECRET

# 2. Deploy to Render (push to GitHub, connect repo, set env vars)

# 3. Seed admin
curl -X POST https://<render-url>/api/admin/seed \
  -H "Content-Type: application/json" \
  -d '{"name":"Admin","email":"admin@psgtech.ac.in","secret":"<secret>"}'

# 4. Configure geofence (after login)
curl -X POST https://<render-url>/api/admin/geofence \
  -H "Authorization: Bearer <JWT>" \
  -d '{"latitude":11.0168,"longitude":76.9558,"allowedRadius":300,"allowedDomain":"psgtech.ac.in"}'

# 5. Build Android app
# Open android/ in Android Studio
# Set BASE_URL in local.properties
# Run on physical device
```

---

## Slide 18 — Summary

### GPunch delivers:

✅ **Zero passwords** — OTP-only authentication via institutional email  
✅ **Zero spoofing** — Mock GPS detection + server-side coordinate validation  
✅ **Zero buddy punching** — One device per account (ANDROID_ID binding)  
✅ **Zero missed records** — Offline queue via WorkManager  
✅ **Full auditability** — 6 security event types logged for proxy punch detection  
✅ **Admin control** — Configure geofence, unlink devices, export CSV, view logs  
✅ **14 test cases** — All functional requirements verified  
✅ **10 automated tests** — Jest backend test suite passing  

---

### 🎯 Goal Achieved: Tamper-proof, passwordless, GPS-verified attendance for institutions.

---

*GPunch v1.0 · PSG College of Technology, Coimbatore · May 2026*
