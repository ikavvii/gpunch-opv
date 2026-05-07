---
title: "GPunch"
subtitle: "Secure On-Site Presence Verification System"
author: "Kavin M · Shanmugappriya K"
date: "May 2026"
institute: "PSG College of Technology · MCA · Dr. N. Ilayaraja"
theme: "Madrid"
colortheme: "whale"
fontsize: 11pt
aspectratio: 169
---

---

## Agenda

| # | Section | Time |
|---|---|---|
| 1 | Introduction — Scope, Value, Audience, Use, Architecture | ~3 min |
| 2 | Functional Requirements — 13 FRs + Test Cases | ~4 min |
| 3 | External Interface Requirements | ~2 min |
| 4 | Non-Functional Requirements | ~3 min |
| 5 | Definitions & Acronyms | ~1 min |
|   | **Q & A** | ~2 min |

---

## 1 · Introduction — Problem & Scope

### Why existing systems fail

| System | Weakness |
|---|---|
| Paper registers | Easily forged; no audit trail |
| PIN / password | Credentials shared — buddy punching |
| Selfie check-in | Privacy concerns; photo-spoofable |
| Client-side GPS | Overridden by mock-location apps |

### GPunch scope

- **Backend** REST API — Node.js 18 + Express + MongoDB Atlas
- **Android Client** — Kotlin (API 26+), GPS + OTP + device binding
- Single campus, single geofence, fully passwordless

---

## 1.2 · Product Value — Three Security Pillars

```
+-------------------+  +-------------------+  +-------------------+
|  WHO are you?     |  |  WHICH device?    |  |  WHERE are you?   |
|                   |  |                   |  |                   |
|  Institutional    |  |  ANDROID_ID       |  |  GPS Geofence     |
|  Email OTP        |  |  Device Binding   |  |  Haversine (srv)  |
|                   |  |                   |  |                   |
|  No password      |  |  One device only  |  |  Campus boundary  |
+-------------------+  +-------------------+  +-------------------+
```

- **Zero buddy-punching** — each account locked to one physical device
- **Zero GPS spoofing impact** — server re-validates coordinates
- **Zero attendance data loss** — WorkManager offline queue

---

## 1.3 & 1.4 · Intended Audience & Use

### Audience

| Role | Purpose |
|---|---|
| **Employee** | Register → OTP → Clock In/Out via Android app |
| **Admin** | Configure geofence, manage devices, view audit logs, export CSV |
| **System** | Auto clock-out, offline retry (background automation) |

### Typical employee flow (30 seconds)

1. Open app → tap **Clock In**
2. App checks GPS is on → obtains location fix
3. Server validates Haversine distance against geofence
4. Success → foreground service monitors in background
5. Leave campus → **auto clock-out** triggered

---

## 1.5 · General Description — Architecture

```
+--------------------+   HTTPS/REST   +--------------------+
|  Android Client    | <----------->  |  Express REST API  |
|  (Kotlin, API 26+) |                |  (Node.js 18)      |
|                    |                +--------+-----------+
|  Activities (MVVM) |                         |
|  GeofenceService   |                +--------v-----------+
|  PendingClockOut   |                |   MongoDB Atlas    |
|  WorkManager       |                |  Users · Attend.   |
|  SessionManager    |                |  Geofence · Audit  |
+--------------------+                +--------------------+
                                              |
                                       Gmail SMTP (OTP)
```

**Backend:** Route Handlers → Middleware (JWT/rate-limit) → Mongoose Models → Atlas

**Android:** View (Activity) → ViewModel (LiveData) → Repository (Retrofit) → Service/Worker

---

## 2 · Functional Requirements — Authentication

| FR | Feature | Key Rule |
|---|---|---|
| **FR-01** | Domain-restricted registration | Only `@psgtech.ac.in` emails accepted; rejects log `INVALID_DOMAIN` |
| **FR-02** | OTP verification + device binding | Strict string equality; binds `ANDROID_ID` on success; issues JWT |
| **FR-03** | Device binding enforcement | Login from different device → HTTP 403 + `UNAUTHORIZED_DEVICE` log |

### Auth flow

```
Register (email)  -->  OTP email  -->  Enter OTP  -->  Device bound  -->  JWT issued
Login (email)     -->  Device check  -->  OTP email  -->  Enter OTP  -->  JWT issued
```

**Passwordless throughout** — no password field exists anywhere in the system

---

## 2 · Functional Requirements — Location & Attendance

| FR | Feature | Key Rule |
|---|---|---|
| **FR-04** | GPS settings check | `LocationSettingsRequest` → system dialog if GPS off |
| **FR-05** | Geofence clock-in | Server Haversine; inside → HTTP 201; outside → HTTP 403 + distance |
| **FR-06** | Mock GPS detection | `location.isMock` → deny + log `MOCK_LOCATION` |
| **FR-07** | Background monitoring | Foreground service; adaptive poll 15 s (moving) / 60 s (still) |
| **FR-08** | Auto clock-out | Service detects boundary exit → API call → push notification |
| **FR-12** | Offline queue | Network failure → WorkManager `CONNECTED` constraint retry |

---

## 2 · Functional Requirements — Admin & Test Cases

### Admin capabilities (FR-09 to FR-13)

- **FR-09** Set geofence lat/lng/radius/domain (changes are instant)
- **FR-10** Reset device binding — unlinks lost/replaced phone
- **FR-11** View paginated audit logs (filterable by event type)
- **FR-13** Export full attendance CSV

### Test case summary (14 / 14 passing)

| TC | Covers | Result |
|---|---|---|
| TC-01 & TC-02 | Domain reject / accept | Pass |
| TC-03 & TC-04 | OTP failure / device mismatch | Pass |
| TC-05 – TC-10 | GPS check, geofence, mock, background, auto-out | Pass |
| TC-11 – TC-14 | Admin config, device reset, audit, offline queue | Pass |

---

## 3 · External Interface Requirements

### 3.1 User Interface (Android)

| Screen | Purpose |
|---|---|
| Login / Register | Passwordless entry; domain validated on register |
| OTP Verification | 6-digit input; 60 s resend countdown |
| Dashboard | Clock In/Out, distance indicator, session timer, history |

### 3.2 Hardware · 3.3 Software · 3.4 Communication

| Layer | Technology |
|---|---|
| GPS | Fused Location Provider (Google Play Services 21.3.0) |
| Background jobs | AndroidX WorkManager 2.9.0 |
| HTTP client | Retrofit 2.11 + OkHttp 4.12 (JWT interceptor) |
| Database | MongoDB Atlas via Mongoose 8.3 |
| Email (OTP) | Nodemailer 8.0 → Gmail SMTP port 587 STARTTLS |
| Transport | HTTPS (TLS 1.2+) enforced on all endpoints |

---

## 4 · Non-Functional Requirements — Security & Capacity

### 4.1 Security highlights

| Threat | Mitigation |
|---|---|
| Buddy punching | ANDROID_ID device binding — one account, one device |
| GPS spoofing | `location.isMock` (client) + Haversine server re-validation |
| NoSQL injection | Explicit typed Mongoose queries + `eventType` whitelist |
| OTP brute-force | 20 req / 15 min rate limit; 10-minute OTP expiry |
| JWT forgery | HS256 + long secret; 7-day expiry |

### 4.2 Capacity · 4.3 Compatibility

- **500+ users** on MongoDB Atlas M0 free tier (single campus)
- Android **API 26 → 34** (Android 8 – 14); ARM / ARM64 / x86_64
- Backend on any **Node.js ≥ 18** host (Render, Railway, VPS)

---

## 4 · Non-Functional Requirements — Reliability to Usability

### 4.4 Reliability

- WorkManager payload survives **app restart + device reboot** (FR-12)
- `BootReceiver` restores foreground service after reboot if clocked in

### 4.5 Scalability

- Horizontal scaling: stateless Node.js + Atlas connection pooling
- Rate-limit config externalised to `.env` — no code change to scale

### 4.6 Maintainability

- Backend: **MVC** — `models/ routes/ middleware/ utils/`
- Android: **MVVM** — Activity → ViewModel → Repository → Service

### 4.7 Usability · 4.8 Other

- **No password** — name + email + 6-digit OTP only
- Human-readable errors; one-tap notification → Dashboard
- DPDP Act 2023 compliance — location data for attendance only; no biometrics

---

## 5 · Definitions & Acronyms

| Term | Meaning |
|---|---|
| **OTP** | One-Time Password — 6-digit code, 10 min expiry, email-delivered |
| **JWT** | JSON Web Token — HS256 signed, 7-day session token |
| **Geofence** | Virtual boundary: centre (lat/lng) + radius in metres |
| **Haversine** | Great-circle distance formula — server-side, client cannot override |
| **ANDROID_ID** | Device fingerprint bound to account on first OTP verify |
| **FR / NFR / TC** | Functional Req. / Non-Functional Req. / Test Case |
| **FLP** | Fused Location Provider — Google Play Services GPS API |
| **MVVM** | Model-View-ViewModel — Android architecture pattern |
| **WorkManager** | AndroidX library — persists background jobs across restarts |
| **Buddy punching** | Fraud: employee clocks in/out on behalf of another |

---

## Summary

### GPunch delivers three guarantees

> **Who** you are (OTP) · **Which** device (ANDROID_ID) · **Where** you are (Haversine)

| Component | Technology | Status |
|---|---|---|
| Backend API | Node.js · Express · MongoDB Atlas | 10 tests passing |
| Android App | Kotlin · MVVM · WorkManager | 14 TC verified |
| Security | JWT · helmet · rate-limit · audit logs | All threats mitigated |
| Documentation | SRS (IEEE + 23MX21) + Presentation | Complete |

### All 14 test cases pass

FR-01 through FR-13 · TC-01 through TC-14 · Zero known vulnerabilities

---

## Thank You

\begin{center}
\Large\textbf{GPunch — Secure On-Site Presence Verification}

\vspace{0.5cm}
\normalsize
Kavin M · Shanmugappriya K

MCA · PSG College of Technology

Faculty Guide: Dr. N. Ilayaraja

\vspace{0.8cm}
\textit{Questions?}
\end{center}
