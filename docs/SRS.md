# Software Requirements Specification (SRS)

## GPunch – Secure On-Site Presence Verification System

| | |
|---|---|
| **Document Version** | 1.0 |
| **Date** | May 2026 |
| **Institution** | PSG College of Technology, Coimbatore |
| **Department** | Computer Applications (MCA) |
| **Team** | Kavin M, Shanmugappriya K |
| **Faculty Guide** | Dr. N. Ilayaraja |
| **Status** | Final |

---

## Table of Contents

1. [Introduction](#1-introduction)
   - 1.1 Purpose
   - 1.2 Project Scope
   - 1.3 Definitions, Acronyms, and Abbreviations
   - 1.4 References
   - 1.5 Document Overview
2. [Overall Description](#2-overall-description)
   - 2.1 Product Perspective
   - 2.2 Product Functions Summary
   - 2.3 User Classes and Characteristics
   - 2.4 Operating Environment
   - 2.5 Design and Implementation Constraints
   - 2.6 Assumptions and Dependencies
3. [System Features and Functional Requirements](#3-system-features-and-functional-requirements)
4. [Non-Functional Requirements](#4-non-functional-requirements)
5. [External Interface Requirements](#5-external-interface-requirements)
6. [System Architecture](#6-system-architecture)
7. [Data Models](#7-data-models)
8. [API Specification](#8-api-specification)
9. [Security Requirements](#9-security-requirements)
10. [Test Case Traceability Matrix](#10-test-case-traceability-matrix)
11. [Appendix](#11-appendix)

---

## 1. Introduction

### 1.1 Purpose

This Software Requirements Specification (SRS) describes the functional and non-functional requirements for **GPunch**, a secure, passwordless on-site presence verification system designed to replace traditional paper-based or PIN-based attendance methods. The primary objective is to ensure that attendance records are tamper-proof by combining GPS geofencing, OTP-based authentication, and device binding — without requiring passwords, maps, selfies, or biometrics.

This document is intended for:
- Software developers implementing the system
- Faculty guides and academic evaluators
- System administrators and deployment engineers
- Quality assurance personnel designing test plans

### 1.2 Project Scope

GPunch consists of two components:

1. **Backend REST API** (Node.js/Express + MongoDB Atlas) — manages user accounts, authentication, geofence configuration, attendance records, and security audit logs.
2. **Android Mobile Client** (Kotlin, API 26+) — provides the user-facing interface for registration, OTP verification, GPS-based clock-in/clock-out, and background geofence monitoring.

**In scope:**
- Email-domain-restricted self-registration with OTP
- One-time device binding (ANDROID_ID)
- GPS geofence clock-in/clock-out with server-side Haversine validation
- Automatic clock-out triggered by background location monitoring
- Mock/fake GPS detection
- Admin portal functions: geofence configuration, domain management, device unlinking, CSV export, audit log viewing
- Offline clock-out queueing with automatic retry on network restoration
- Security audit logging for proxy/buddy punching detection

**Out of scope:**
- Web-based employee dashboard
- Biometric authentication
- IoT beacon-based attendance
- Integration with HR or payroll systems (future roadmap)

### 1.3 Definitions, Acronyms, and Abbreviations

| Term | Definition |
|---|---|
| **OTP** | One-Time Password – a 6-digit numeric code sent to the user's registered email |
| **JWT** | JSON Web Token – stateless token used for API authentication |
| **GPS** | Global Positioning System |
| **Geofence** | A virtual geographic boundary defined by a centre coordinate and a radius |
| **Haversine** | Mathematical formula for computing great-circle distances on a sphere |
| **ANDROID_ID** | A 64-bit hexadecimal string that uniquely identifies a device per app+signing key |
| **API** | Application Programming Interface |
| **REST** | Representational State Transfer |
| **SRS** | Software Requirements Specification |
| **MVVM** | Model-View-ViewModel – Android architectural pattern |
| **FR** | Functional Requirement |
| **NFR** | Non-Functional Requirement |
| **TC** | Test Case |

### 1.4 References

- IEEE Std 830-1998: IEEE Recommended Practice for Software Requirements Specifications
- Android Developer Documentation: [Foreground Services](https://developer.android.com/guide/components/foreground-services)
- Android Developer Documentation: [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- Express.js Documentation: [https://expressjs.com](https://expressjs.com)
- Mongoose Documentation: [https://mongoosejs.com](https://mongoosejs.com)
- RFC 7519: JSON Web Token (JWT)

### 1.5 Document Overview

Section 2 provides an overall description of the product. Section 3 details all functional requirements. Section 4 covers non-functional requirements. Section 5 describes external interfaces. Section 6 describes the system architecture. Section 7 covers the data model. Section 8 provides the API specification. Section 9 describes security requirements. Section 10 maps test cases to requirements.

---

## 2. Overall Description

### 2.1 Product Perspective

GPunch is a standalone, self-contained system. It does not replace an existing institutional system but operates as an independent attendance verification layer. The system is designed with a cloud-first architecture — the backend can be deployed on any Node.js-compatible platform (e.g., Render, Railway, Heroku) and uses MongoDB Atlas as a managed database.

```
┌──────────────────────────────────────────────────────────────────────┐
│                          GPunch System                               │
│                                                                      │
│  ┌──────────────────┐         HTTPS/REST         ┌───────────────┐  │
│  │  Android Client  │  ◄────────────────────────►│  Express API  │  │
│  │  (Kotlin)        │                             │  (Node.js)    │  │
│  │                  │                             │               │  │
│  │  • LoginActivity │                             │  • Auth Routes│  │
│  │  • Dashboard     │                             │  • Punch Route│  │
│  │  • Foreground Svc│                             │  • Admin Route│  │
│  │  • WorkManager   │                             │  • Audit Route│  │
│  └──────────────────┘                             └───────┬───────┘  │
│                                                           │          │
│                                                   ┌───────▼───────┐  │
│                                                   │ MongoDB Atlas │  │
│                                                   │               │  │
│                                                   │  • Users      │  │
│                                                   │  • Attendance │  │
│                                                   │  • Geofence   │  │
│                                                   │  • AuditLogs  │  │
│                                                   └───────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
         │                                                    │
    Gmail SMTP                                          Admin Browser
   (OTP delivery)                                    (API calls / CSV)
```

### 2.2 Product Functions Summary

| # | Function | Actor |
|---|---|---|
| F-01 | Domain-restricted self-registration via OTP | Employee |
| F-02 | OTP verification with device binding | Employee |
| F-03 | Passwordless login via OTP | Employee |
| F-04 | GPS-based geofence clock-in | Employee |
| F-05 | Manual clock-out | Employee |
| F-06 | Automatic clock-out on geofence breach | System |
| F-07 | Mock/fake GPS detection | System |
| F-08 | Persistent background location monitoring notification | System |
| F-09 | Offline clock-out queueing with WorkManager retry | System |
| F-10 | Admin: configure geofence (lat/lng/radius/domain) | Admin |
| F-11 | Admin: reset/unlink user device binding | Admin |
| F-12 | Admin: view security audit logs | Admin |
| F-13 | Admin: export attendance CSV | Admin |
| F-14 | Admin: list all users | Admin |
| F-15 | Admin: seed first admin account | Admin |

### 2.3 User Classes and Characteristics

**Employee (Regular User)**
- MCA/staff member of the institution
- Has a valid institutional email (e.g., `@psgtech.ac.in`)
- Owns an Android smartphone (Android 8.0 / API 26 or higher)
- No technical expertise required
- Interacts exclusively via the Android mobile app

**System Administrator**
- Technical staff responsible for system configuration
- Accesses the system via REST API calls (Postman, curl, or a future web portal)
- Responsible for: geofence setup, domain whitelisting, device resets, audit log review, data export
- Has elevated JWT role (`admin`)

**System (Automated)**
- Background processes: `GeofenceMonitorService` (auto clock-out), `PendingClockOutWorker` (offline retry)
- Acts on behalf of the logged-in employee

### 2.4 Operating Environment

**Backend**
- Runtime: Node.js ≥ 18.0.0
- Database: MongoDB Atlas (M0 free tier or higher)
- Deployment: Any HTTPS-capable Node.js host (Render, Railway, Heroku, VPS)
- SMTP: Gmail with App Password (or any SMTP provider)

**Android Client**
- Minimum SDK: API 26 (Android 8.0 Oreo)
- Target SDK: API 34 (Android 14)
- Architecture: ARM / ARM64 / x86_64
- Required permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE`

### 2.5 Design and Implementation Constraints

- **No passwords**: Authentication relies solely on email OTP; no password field exists in the User schema.
- **No maps**: The system uses coordinate-based geofencing only; no mapping SDK is required.
- **No selfies/biometrics**: Identity is proven by device ownership (ANDROID_ID) + email OTP.
- **Single geofence**: The system supports one active geofence at a time (suitable for a single campus or office).
- **Physical device recommended**: Accurate GPS requires a physical Android device; emulators produce unreliable coordinates.
- **HTTPS required**: All API communication must be over TLS; the Android client enforces this via OkHttp.
- **Rate limiting**: Auth endpoints are limited to 20 requests per 15 minutes per IP; global limit is 100 requests per 15 minutes.

### 2.6 Assumptions and Dependencies

- Users have access to their institutional email to receive OTPs.
- The Android device has GPS hardware and the user grants location permissions.
- The admin has configured the geofence before employees attempt to clock in.
- Network connectivity is available for clock-in; clock-out is queued offline if unavailable.
- The institution's email domain is unique and admin-controlled (e.g., `psgtech.ac.in`).

---

## 3. System Features and Functional Requirements

### FR-01: Domain-Restricted Self-Registration

**Description:** The system allows employees to self-register using only an institutional email address. Registrations from non-institutional domains are automatically rejected without admin intervention.

**Inputs:** Full name, institutional email, Android device ID  
**Processing:**  
1. Backend loads the active `GeofenceConfig.allowedDomain`.
2. The email domain (`email.split('@')[1]`) is compared to `allowedDomain`.
3. If the domain does not match, the request is rejected with HTTP 403 and an `INVALID_DOMAIN` audit event is created.
4. If the domain matches, a 6-digit OTP is generated, stored (hashed-equivalent via expiry), and emailed to the user.

**Outputs:** OTP email sent, or domain rejection error  
**Priority:** High  
**Test Cases:** TC-01, TC-02

---

### FR-02: OTP Verification and Device Binding

**Description:** Upon entering the received OTP, the system verifies it and permanently binds the user's Android device ID to their account.

**Inputs:** Email, 6-digit OTP, ANDROID_ID  
**Processing:**  
1. Server finds the user by email.
2. OTP is compared as a string (preventing type coercion attacks).
3. OTP expiry is checked against the current timestamp.
4. If invalid or expired, the attempt is logged as `OTP_FAILED` and HTTP 400 is returned.
5. If valid, `isVerified = true` and `androidId` is saved. A JWT is returned.

**Outputs:** JWT token, or error message  
**Priority:** High  
**Test Cases:** TC-03

---

### FR-03: Device Binding Enforcement

**Description:** A verified user may only log in from the device they originally registered with. Attempting login from a different device is rejected and logged.

**Inputs:** Email, ANDROID_ID  
**Processing:**  
1. On login, server fetches the user and compares `req.body.androidId` against `user.androidId`.
2. If they differ, HTTP 403 is returned and an `UNAUTHORIZED_DEVICE` audit event is created.

**Outputs:** OTP sent (if device matches), or device rejection error  
**Priority:** High  
**Test Cases:** TC-04

---

### FR-04: GPS Location Fetching with Settings Check

**Description:** Before attempting clock-in, the Android app verifies that high-accuracy GPS is enabled. If it is not, a system dialog is presented to guide the user to the settings without leaving the app.

**Inputs:** User taps "Clock In" button  
**Processing (Android):**  
1. `LocationSettingsRequest` is built with `PRIORITY_HIGH_ACCURACY`.
2. `LocationServices.getSettingsClient().checkLocationSettings()` is called.
3. If settings are satisfied, `getCurrentLocation()` is called.
4. If not satisfied (`ResolvableApiException`), the system location settings dialog is launched via `IntentSenderRequest`.

**Outputs:** Current GPS coordinates, or settings dialog prompt  
**Priority:** High  
**Test Cases:** TC-05

---

### FR-05: Geofence-Based Clock-In and Clock-Out

**Description:** Clock-in is only permitted when the user is physically within the configured geofence radius. The server validates coordinates independently of the client.

**Inputs (Clock-In):** JWT, latitude, longitude, ANDROID_ID  
**Processing:**  
1. `haversineDistance(config.lat, config.lng, user.lat, user.lng)` is computed.
2. If `distance > config.allowedRadius`, HTTP 403 is returned with an `OUT_OF_BOUNDS` audit entry.
3. If `distance ≤ config.allowedRadius`, an `AttendanceRecord` is created with `clockInTime`.

**Inputs (Clock-Out):** JWT, latitude, longitude, ANDROID_ID, `autoClockOut` flag  
**Processing:** Open `AttendanceRecord` is found, `clockOutTime` and `durationMinutes` are set.

**Outputs:** Clock-in success with distance, or out-of-bounds error  
**Priority:** High  
**Test Cases:** TC-06, TC-07

---

### FR-06: Mock / Fake GPS Detection

**Description:** The system detects GPS spoofing on the Android client and denies clock-in, logging the attempt as a security event.

**Inputs:** Location object from Fused Location Provider  
**Processing (Android):**  
1. `location.isMock` is checked (API 18+, reliable from API 31+).
2. `Settings.Secure.ALLOW_MOCK_LOCATION` is checked (deprecated legacy check for older devices).
3. If either check returns `true`, clock-in is denied, a toast is shown, and `MOCK_LOCATION` is reported to `/api/audit`.

**Outputs:** Clock-in denied, security event logged  
**Priority:** High  
**Test Cases:** TC-08

---

### FR-07: Background Location Monitoring with Persistent Notification

**Description:** After a successful clock-in, a foreground service starts and continuously monitors the user's GPS position, displaying a persistent notification in the Android notification tray.

**Inputs:** Successful clock-in event  
**Processing:**  
1. `GeofenceMonitorService` is started as a foreground service with a persistent notification.
2. GPS polling uses `FusedLocationProviderClient.requestLocationUpdates()`.
3. Adaptive interval: 15s when moving (≥5m displacement), 60s when stationary.

**Outputs:** Persistent notification visible in the system notification tray  
**Priority:** High  
**Test Cases:** TC-09

---

### FR-08: Automatic Clock-Out on Geofence Breach

**Description:** When the background monitoring service detects that the user has left the geofence, it automatically calls the clock-out API and notifies the user.

**Inputs:** GPS coordinates from `GeofenceMonitorService`  
**Processing:**  
1. `haversineDistance` is computed between current position and fence centre.
2. If `distance > fenceRadius`, `POST /api/punch/out` is called with `autoClockOut: true`.
3. `SessionManager.setIsClockedIn(false)` is updated.
4. A push notification is shown: "You have left the work zone. Attendance has been recorded."
5. The foreground service stops.

**Outputs:** Automatic clock-out recorded, user notified  
**Priority:** High  
**Test Cases:** TC-10

---

### FR-09: Admin – Geofence and Domain Configuration

**Description:** The system administrator configures the geofence centre, radius, and allowed email domain. Changes take effect immediately for subsequent clock-in attempts.

**Inputs (Admin):** JWT (admin role), latitude, longitude, radius (metres), email domain  
**Endpoint:** `POST /api/admin/geofence`  
**Processing:** Upserts a single `GeofenceConfig` document. All subsequent `/api/punch/in` calls fetch this document.

**Outputs:** Updated geofence config  
**Priority:** High  
**Test Cases:** TC-11

---

### FR-10: Admin – Device Unlinking (Reset)

**Description:** The administrator can clear a user's device binding, allowing them to re-bind with a new device on their next login.

**Inputs (Admin):** JWT (admin role), user ID  
**Endpoint:** `POST /api/admin/reset-device/:userId`  
**Processing:** Sets `user.androidId = null`. The user can then successfully complete OTP verification from any device, which will bind that new device.

**Outputs:** Device binding cleared, confirmation response  
**Priority:** High  
**Test Cases:** TC-12

---

### FR-11: Admin – Security Audit Log Viewing

**Description:** The administrator can view a paginated list of all security events recorded in the system, enabling monitoring of proxy/buddy punching attempts.

**Inputs (Admin):** JWT (admin role), optional `eventType` filter, `page`, `limit`  
**Endpoint:** `GET /api/admin/audit-logs`  
**Events Logged:**

| Event Type | Trigger |
|---|---|
| `INVALID_DOMAIN` | Registration with non-institutional email (TC-01) |
| `OTP_FAILED` | Incorrect or expired OTP entered (TC-03) |
| `UNAUTHORIZED_DEVICE` | Login attempted from unbound device (TC-04) |
| `MOCK_LOCATION` | Fake GPS app detected on client (TC-08) |
| `OUT_OF_BOUNDS` | Clock-in attempted from outside geofence |
| `SUSPICIOUS_ACTIVITY` | Other anomalies reported by client |

**Outputs:** Paginated audit log with `userId`, `email`, `eventType`, `timestamp`, `ipAddress`, `metadata`  
**Priority:** High  
**Test Cases:** TC-13

---

### FR-12: Offline Clock-Out Queueing

**Description:** If the auto clock-out API call fails due to network unavailability (e.g., Airplane Mode), the payload is persisted and retried automatically when connectivity is restored.

**Inputs:** Network failure during `triggerAutoClockOut()`  
**Processing:**  
1. `IOException` (or any exception) is caught in `GeofenceMonitorService`.
2. A `PendingClockOutWorker` is enqueued via WorkManager with a `CONNECTED` network constraint.
3. `SessionManager.setIsClockedIn(false)` is set immediately (optimistic update).
4. When connectivity returns, WorkManager fires the worker, which calls `POST /api/punch/out`.

**Outputs:** Clock-out record created on server once network is available  
**Priority:** Medium  
**Test Cases:** TC-14

---

### FR-13: Admin – CSV Attendance Export

**Description:** The administrator can download a complete attendance CSV report for all users.

**Inputs (Admin):** JWT (admin role)  
**Endpoint:** `GET /api/admin/export-csv`  
**Output columns:** Name, Email, Clock-In Time, Clock-Out Time, Duration (minutes), Auto Clock-Out flag, Android ID  
**Priority:** Medium

---

## 4. Non-Functional Requirements

### NFR-01: Security

| ID | Requirement |
|---|---|
| NFR-01.1 | All API endpoints (except `/api/auth/*` and `/health`) require a valid JWT (HS256, 7-day expiry). |
| NFR-01.2 | Admin routes additionally require `role: "admin"` in the JWT payload. |
| NFR-01.3 | OTPs are never returned in any API response (stripped by Mongoose `toJSON` transform). |
| NFR-01.4 | All Mongoose queries use explicit type casting to prevent NoSQL operator injection. |
| NFR-01.5 | `eventType` query parameters are validated against a whitelist to prevent injection. |
| NFR-01.6 | All HTTP headers are hardened with `helmet.js`. |
| NFR-01.7 | Request body size is capped at 10KB to prevent payload flooding. |
| NFR-01.8 | HTTPS is enforced on production (Render TLS). |

### NFR-02: Performance

| ID | Requirement |
|---|---|
| NFR-02.1 | Clock-in API response time ≤ 2 seconds under normal network conditions. |
| NFR-02.2 | GPS location fix must be obtained within 10 seconds of the app opening. |
| NFR-02.3 | Background service battery impact is minimised via adaptive polling (15s active / 60s stationary). |

### NFR-03: Reliability

| ID | Requirement |
|---|---|
| NFR-03.1 | Offline clock-out payloads must not be lost; WorkManager persistence survives app restarts. |
| NFR-03.2 | The `BootReceiver` restores foreground service state after device reboot if the user was clocked in. |

### NFR-04: Usability

| ID | Requirement |
|---|---|
| NFR-04.1 | Registration requires no passwords — only name, institutional email, and OTP. |
| NFR-04.2 | The OTP resend timer (60s) prevents abuse while allowing genuine resends. |
| NFR-04.3 | All error messages are human-readable (no technical stack traces). |
| NFR-04.4 | The persistent notification provides one-tap access back to the dashboard. |

### NFR-05: Scalability

| ID | Requirement |
|---|---|
| NFR-05.1 | MongoDB Atlas free tier (M0) supports ≥500 concurrent users for a single-campus deployment. |
| NFR-05.2 | Rate limiting (100 req/15 min global) protects the server from abuse. |

### NFR-06: Maintainability

| ID | Requirement |
|---|---|
| NFR-06.1 | Backend follows MVC separation: models, routes, middleware, utils. |
| NFR-06.2 | Android follows MVVM: Activities observe LiveData from ViewModels via the repository pattern. |
| NFR-06.3 | All secrets are externalised to environment variables (`.env`); no secrets in source code. |

---

## 5. External Interface Requirements

### 5.1 User Interfaces

**Android Application**

| Screen | Purpose |
|---|---|
| Splash | Auto-redirect to Dashboard (logged in) or Login |
| Login | Enter institutional email, receive OTP |
| Register | Enter name + email, receive OTP |
| OTP Verification | Enter 6-digit OTP with 60s resend countdown |
| Dashboard | Clock-in/out, location display, session duration, logout |

### 5.2 Hardware Interfaces

- **GPS receiver**: Required on the Android device for coordinate acquisition
- **Network interface**: Wi-Fi or mobile data for API communication

### 5.3 Software Interfaces

| Interface | Purpose | Technology |
|---|---|---|
| MongoDB Atlas | Persistent data storage | Mongoose ODM |
| Gmail SMTP | OTP email delivery | Nodemailer |
| Fused Location Provider | GPS coordinates | Google Play Services |
| WorkManager | Offline job persistence | AndroidX WorkManager |

### 5.4 Communication Interfaces

- All client–server communication uses **HTTPS** (TLS 1.2+)
- Data format: **JSON** (Content-Type: application/json)
- Authentication: **Bearer JWT** in the `Authorization` header
- OTP email: HTML + plain-text multipart email via SMTP

---

## 6. System Architecture

### 6.1 Backend Layers

```
┌───────────────────────────────────────────────┐
│              Route Handlers                   │  ← business logic
│  /api/auth  /api/punch  /api/admin  /api/audit│
├───────────────────────────────────────────────┤
│              Middleware                        │
│     protect()  adminOnly()  rateLimiter        │
├───────────────────────────────────────────────┤
│              Mongoose Models                   │
│   User  AttendanceRecord  GeofenceConfig  AuditLog │
├───────────────────────────────────────────────┤
│              MongoDB Atlas                     │
└───────────────────────────────────────────────┘
```

### 6.2 Android Layers (MVVM)

```
┌──────────────────────────────────────┐
│              UI Layer                │
│   Activities (Views)                 │
│   DashboardActivity, LoginActivity…  │
├──────────────────────────────────────┤
│           ViewModel Layer            │
│   AuthViewModel, DashboardViewModel  │
│   (LiveData, Coroutines)             │
├──────────────────────────────────────┤
│           Repository Layer           │
│   GpunchApiService (Retrofit)        │
│   RetrofitClient (OkHttp + JWT)      │
├──────────────────────────────────────┤
│           Service Layer              │
│   GeofenceMonitorService (Foreground)│
│   PendingClockOutWorker (WorkManager)│
├──────────────────────────────────────┤
│           Utility Layer              │
│   GeofenceUtils  SessionManager      │
└──────────────────────────────────────┘
```

### 6.3 Authentication Flow

```
User                   Android App              Backend API           Email
 │                         │                       │                    │
 │── Enter email ─────────►│                       │                    │
 │                         │── POST /auth/register ►│                    │
 │                         │                       │── Validate domain  │
 │                         │                       │── Generate OTP ───►│
 │                         │◄── 200 OTP sent ──────│                    │
 │◄── "Check email" ───────│                       │                    │
 │                         │                       │           OTP email│
 │── Enter OTP ───────────►│                       │◄───────────────────│
 │                         │── POST /auth/verify-otp►│                   │
 │                         │                       │── Verify OTP       │
 │                         │                       │── Bind androidId   │
 │                         │◄── JWT token ─────────│                    │
 │◄── Dashboard opens ─────│                       │                    │
```

### 6.4 Clock-In Flow

```
Employee               Android App              Backend API
   │                       │                       │
   │── Tap Clock In ───────►│                       │
   │                       │── Check GPS enabled   │
   │                       │── Get current location│
   │                       │── Check isMock()      │
   │                       │── POST /punch/in ─────►│
   │                       │                       │── Load GeofenceConfig
   │                       │                       │── Haversine distance
   │                       │                       │── If inside: create record
   │                       │◄─── 201 Clocked In ───│
   │◄── Success toast ─────│                       │
   │                       │── Start Foreground Svc│
   │                       │   (persistent notify) │
```

---

## 7. Data Models

### 7.1 User

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | String | Yes | Trimmed |
| `email` | String | Yes | Unique, lowercase |
| `role` | String (enum) | Yes | `user` \| `admin` |
| `androidId` | String | No | Set on first OTP verify; device binding |
| `otp` | String | No | 6-digit, never returned in responses |
| `otpExpiresAt` | Date | No | OTP validity window |
| `isVerified` | Boolean | No | `true` after first OTP verify |
| `isActive` | Boolean | No | Admin can deactivate |
| `createdAt` | Date | Auto | Mongoose timestamp |
| `updatedAt` | Date | Auto | Mongoose timestamp |

### 7.2 AttendanceRecord

| Field | Type | Required | Notes |
|---|---|---|---|
| `userId` | ObjectId | Yes | Ref: User |
| `email` | String | Yes | Denormalised for CSV export |
| `clockInTime` | Date | No | Set on clock-in |
| `clockInLat` | Number | No | Latitude at clock-in |
| `clockInLng` | Number | No | Longitude at clock-in |
| `clockOutTime` | Date | No | Set on clock-out |
| `clockOutLat` | Number | No | Latitude at clock-out |
| `clockOutLng` | Number | No | Longitude at clock-out |
| `durationMinutes` | Number | No | Computed on clock-out |
| `autoClockOut` | Boolean | No | `true` if triggered by service |
| `androidId` | String | Yes | Device that made the record |

**Index:** `{ userId: 1, clockInTime: -1 }`

### 7.3 GeofenceConfig

| Field | Type | Required | Notes |
|---|---|---|---|
| `latitude` | Number | Yes | Centre coordinate |
| `longitude` | Number | Yes | Centre coordinate |
| `allowedRadius` | Number | Yes | Metres (10–100,000) |
| `allowedDomain` | String | Yes | e.g., `psgtech.ac.in` |
| `updatedBy` | ObjectId | No | Ref: User (admin) |

*Singleton document — always upserted, never duplicated.*

### 7.4 AuditLog

| Field | Type | Required | Notes |
|---|---|---|---|
| `userId` | ObjectId | No | Null for pre-registration events |
| `email` | String | No | For traceability |
| `androidId` | String | No | Device involved |
| `eventType` | String (enum) | Yes | See table in FR-11 |
| `latitude` | Number | No | Location at event time |
| `longitude` | Number | No | Location at event time |
| `distanceFromZone` | Number | No | Metres, for OUT_OF_BOUNDS |
| `ipAddress` | String | No | Client IP |
| `metadata` | Mixed | No | Additional context |

**Indexes:** `{ userId: 1, createdAt: -1 }`, `{ eventType: 1, createdAt: -1 }`

---

## 8. API Specification

**Base URL:** `https://<deployment-host>`  
**Authentication:** `Authorization: Bearer <JWT>`  
**Content-Type:** `application/json`

### 8.1 Authentication Routes

#### POST /api/auth/register
Register a new user (domain check + OTP).

| | |
|---|---|
| **Auth** | None |
| **Rate Limit** | 20 req / 15 min |
| **Body** | `{ name, email, androidId }` |
| **Success** | `200 { success: true, message: "OTP sent…" }` |
| **Error 403** | Domain not allowed |
| **Error 409** | Email already registered |

#### POST /api/auth/verify-otp
Verify OTP and bind device; returns JWT.

| | |
|---|---|
| **Auth** | None |
| **Body** | `{ email, otp, androidId }` |
| **Success** | `200 { success, token, user: { id, name, email, role, androidId } }` |
| **Error 400** | Invalid/expired OTP |
| **Error 403** | Device mismatch |

#### POST /api/auth/login
Passwordless login – sends OTP to registered email.

| | |
|---|---|
| **Auth** | None |
| **Body** | `{ email, androidId }` |
| **Success** | `200 { success: true, message: "OTP sent…" }` |
| **Error 403** | Device not recognised |
| **Error 404** | Account not found |

#### POST /api/auth/resend-otp

| | |
|---|---|
| **Body** | `{ email }` |
| **Success** | `200 { success: true }` |

### 8.2 Punch Routes (JWT Required)

#### POST /api/punch/in

| | |
|---|---|
| **Body** | `{ latitude, longitude, androidId }` |
| **Success 201** | `{ success, message, record: { id, clockInTime, distance } }` |
| **Error 403** | Out of bounds (includes `distance`, `allowedRadius`) |
| **Error 409** | Already clocked in |

#### POST /api/punch/out

| | |
|---|---|
| **Body** | `{ latitude, longitude, androidId, autoClockOut? }` |
| **Success 200** | `{ success, record: { clockInTime, clockOutTime, durationMinutes } }` |

#### GET /api/punch/status

**Response:** `{ success, isClockedIn, record }`

#### GET /api/punch/history

**Query:** `page`, `limit`  
**Response:** `{ success, page, totalPages, total, records[] }`

### 8.3 Admin Routes (JWT + Admin Role)

| Method | Path | Description |
|---|---|---|
| POST | `/api/admin/seed` | Bootstrap first admin |
| POST | `/api/admin/geofence` | Set geofence config |
| GET | `/api/admin/geofence` | Get geofence config |
| POST | `/api/admin/reset-device/:userId` | Clear device binding |
| GET | `/api/admin/users` | List users (paginated) |
| GET | `/api/admin/audit-logs` | View audit logs (filterable) |
| GET | `/api/admin/export-csv` | Download attendance CSV |

### 8.4 Audit Route (JWT Required)

#### POST /api/audit

| | |
|---|---|
| **Body** | `{ eventType, latitude?, longitude?, androidId, metadata? }` |
| **Valid eventTypes** | `MOCK_LOCATION`, `UNAUTHORIZED_DEVICE`, `OUT_OF_BOUNDS`, `INVALID_DOMAIN`, `OTP_FAILED`, `SUSPICIOUS_ACTIVITY` |
| **Success 201** | `{ success: true, logId }` |

---

## 9. Security Requirements

### 9.1 Threat Model

| Threat | Mitigation |
|---|---|
| **Buddy punching** (A clocks in for B) | Device binding: each account is locked to one ANDROID_ID |
| **GPS spoofing** (fake GPS app) | `Location.isMock` check + developer-options check; client denial + server audit log |
| **Proxy punching** (API call outside campus) | Server-side Haversine validation against stored geofence; client cannot override |
| **NoSQL injection** | All Mongoose queries use explicit typed values; `eventType` whitelisted |
| **JWT forgery** | HS256 with `JWT_SECRET` env var; tokens expire in 7 days |
| **Brute-force OTP** | Auth rate limit: 20 requests / 15 min; OTP expires in 10 min |
| **Replay attacks** | OTP is single-use (nulled after verification); JWT expiry |

### 9.2 Data Privacy

- OTP values are never logged to disk or returned in API responses.
- Location coordinates are stored only in `AttendanceRecord` and `AuditLog` for legitimate attendance tracking.
- No biometric data is collected.
- The `androidId` is a device-level identifier reset on factory reset; it is not personally identifiable on its own.

---

## 10. Test Case Traceability Matrix

| Test ID | Functional Requirement | Input / Action | Expected Output | FR Reference | Status |
|---|---|---|---|---|---|
| TC-01 | Domain Restriction | Register with `user@gmail.com` | HTTP 403, "Invalid Domain" error; `INVALID_DOMAIN` logged | FR-01 | ✅ |
| TC-02 | Domain Restriction | Register with `user@psgtech.ac.in` | HTTP 200, OTP sent | FR-01 | ✅ |
| TC-03 | OTP Verification | Enter incorrect 6-digit OTP | HTTP 400, "Invalid OTP"; `OTP_FAILED` logged | FR-02 | ✅ |
| TC-04 | Device Binding | Login from Device 2 (bound to Device 1) | HTTP 403, "Unrecognised device"; `UNAUTHORIZED_DEVICE` logged | FR-03 | ✅ |
| TC-05 | Location Fetching | Tap Clock-In with GPS off | System dialog: "Enable High-Accuracy Location" | FR-04 | ✅ |
| TC-06 | Geofence (Inside) | Clock-in at 20m from centre | HTTP 201, "Success: Clocked In"; foreground service starts | FR-05 | ✅ |
| TC-07 | Geofence (Outside) | Clock-in at 200m from centre | HTTP 403, "You are outside the work zone" | FR-05 | ✅ |
| TC-08 | Mock Location | Use Fake GPS app | Clock-in denied; toast shown; `MOCK_LOCATION` logged | FR-06 | ✅ |
| TC-09 | Background Tracking | Minimise app after Clock-In | Persistent notification in Android tray | FR-07 | ✅ |
| TC-10 | Auto Clock-Out | Walk 100m+ from campus | Background API call; clock-out recorded; user notified | FR-08 | ✅ |
| TC-11 | Admin Config | Change radius 50m → 10m | Users at 20m are rejected on next Clock-In | FR-09 | ✅ |
| TC-12 | Device Unlinking | Admin clicks "Reset Device" for User A | User A can bind a new device on next login | FR-10 | ✅ |
| TC-13 | Audit Logging | View Admin Security Dashboard | TC-01, TC-04, TC-08 events appear in logs | FR-11 | ✅ |
| TC-14 | Offline Queueing | Auto clock-out in Airplane Mode | Payload queued; sent automatically on reconnect | FR-12 | ✅ |

---

## 11. Appendix

### A. Haversine Formula

Distance in metres between two GPS coordinates:

```
a = sin²(Δlat/2) + cos(lat1) × cos(lat2) × sin²(Δlon/2)
c = 2 × atan2(√a, √(1−a))
d = R × c          where R = 6,371,000 m (Earth's radius)
```

### B. Environment Variables Reference

| Variable | Required | Description |
|---|---|---|
| `PORT` | No | Server port (default: 3000) |
| `NODE_ENV` | No | `development` \| `production` |
| `MONGODB_URI` | Yes | MongoDB Atlas connection string |
| `JWT_SECRET` | Yes | Long random string (≥ 32 chars) |
| `JWT_EXPIRES_IN` | No | Token validity (default: `7d`) |
| `SMTP_HOST` | Yes | SMTP server host |
| `SMTP_PORT` | No | SMTP port (default: 587) |
| `SMTP_USER` | Yes | Sender email address |
| `SMTP_PASS` | Yes | Gmail App Password |
| `OTP_FROM` | No | Sender display name |
| `OTP_EXPIRY_MINUTES` | No | OTP validity (default: 10) |
| `ADMIN_SEED_SECRET` | Yes | One-time admin bootstrap secret |

### C. Technology Stack Summary

| Layer | Technology | Version |
|---|---|---|
| Backend Runtime | Node.js | ≥ 18.0.0 |
| Backend Framework | Express.js | ^4.18 |
| ODM | Mongoose | ^8.3 |
| Auth | jsonwebtoken | ^9.0 |
| Email | Nodemailer | ^8.0 |
| Validation | express-validator | ^7.0 |
| Security | helmet | ^7.1 |
| Rate Limiting | express-rate-limit | ^7.2 |
| Database | MongoDB Atlas | M0+ |
| Android Language | Kotlin | 1.9.23 |
| Android Min SDK | API 26 (Android 8.0) | — |
| HTTP Client | Retrofit + OkHttp | 2.11 / 4.12 |
| Location | Google Play Services (FLP) | 21.3.0 |
| Background Jobs | AndroidX WorkManager | 2.9.0 |
| Architecture | MVVM + LiveData | — |
| Testing (Backend) | Jest + supertest | ^29.7 / ^7.0 |
