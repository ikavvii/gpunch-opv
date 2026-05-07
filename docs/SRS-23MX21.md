# SOFTWARE REQUIREMENTS SPECIFICATION

## GPunch — Secure On-Site Presence Verification System

---

| | |
|---|---|
| **Course / Module** | 23MX21 – Software Engineering |
| **Document Type** | Software Requirements Specification (SRS) |
| **Version** | 1.0 |
| **Date** | May 2026 |
| **Institution** | PSG College of Technology, Coimbatore – 641 004 |
| **Department** | Computer Applications (MCA) |
| **Prepared By** | Kavin M · Shanmugappriya K |
| **Faculty Guide** | Dr. N. Ilayaraja, Associate Professor |
| **Status** | Final |

---

## TABLE OF CONTENTS

| Section | Title |
|---|---|
| **1** | **Introduction** |
| 1.1 | Product Scope |
| 1.2 | Product Value |
| 1.3 | Intended Audience |
| 1.4 | Intended Use |
| 1.5 | General Description |
| **2** | **Functional Requirements** |
| **3** | **External Interface Requirements** |
| 3.1 | User Interface Requirements |
| 3.2 | Hardware Interface Requirements |
| 3.3 | Software Interface Requirements |
| 3.4 | Communication Interface Requirements |
| **4** | **Non-Functional Requirements** |
| 4.1 | Security |
| 4.2 | Capacity |
| 4.3 | Compatibility |
| 4.4 | Reliability |
| 4.5 | Scalability |
| 4.6 | Maintainability |
| 4.7 | Usability |
| 4.8 | Other Non-Functional Requirements |
| **5** | **Definitions and Acronyms** |

---

## 1. INTRODUCTION

### 1.1 Product Scope

**GPunch** is a secure, passwordless on-site presence verification system designed for institutions that require tamper-proof attendance records. The system replaces paper registers, PIN-based check-ins, and selfie-based apps with a three-factor presence proof that requires no passwords, no maps, and no biometrics.

The product consists of two integrated components:

- **Backend REST API** — Node.js 18 + Express + MongoDB Atlas, handling user management, OTP authentication, geofence configuration, attendance records, and security audit logging.
- **Android Mobile Client** — Kotlin (API 26+), providing employee registration, authentication, GPS-based clock-in/clock-out, and background geofence monitoring.

**In Scope**

| Feature | Description |
|---|---|
| Domain-restricted self-registration | Only institutional email addresses are accepted |
| Passwordless OTP authentication | 6-digit OTP sent to registered email; no password required |
| Single-device binding | Each account is permanently tied to one Android device (ANDROID_ID) |
| GPS geofence clock-in / clock-out | Server-side Haversine validation; client cannot override |
| Mock GPS detection | Fake GPS apps detected and denied at the Android client |
| Automatic clock-out | Background service triggers clock-out when user leaves the geofence |
| Offline clock-out queueing | WorkManager retries the clock-out when connectivity is restored |
| Admin portal | Geofence setup, domain management, device reset, CSV export, audit logs |

**Out of Scope**

- Web-based employee self-service portal
- Biometric authentication (fingerprint, face recognition)
- IoT beacon or NFC-based proximity attendance
- Integration with HR or payroll management systems
- Multi-campus / multi-geofence support (v1.0 is single-site)

### 1.2 Product Value

Traditional attendance systems suffer from well-known vulnerabilities:

| Problem | Impact |
|---|---|
| Paper registers | Easily forged; no digital audit trail |
| PIN / password systems | Credentials shared between colleagues (buddy punching) |
| Selfie-based check-in | Privacy concerns; spoofable with photographs |
| Map-based check-in (client-side) | Location can be falsified by mock GPS apps |

**GPunch addresses all of these** through three independently verified security pillars:

```
+---------------------+   +---------------------+   +---------------------+
|  WHO are you?       |   |  WHICH device?      |   |  WHERE are you?     |
|                     |   |                     |   |                     |
|  Institutional      |   |  ANDROID_ID         |   |  GPS Geofence       |
|  Email OTP          |   |  Device Binding     |   |  Haversine Check    |
|                     |   |                     |   |                     |
|  No password        |   |  One device only    |   |  Must be on campus  |
|  Domain check       |   |  Per account        |   |  Server-validated   |
+---------------------+   +---------------------+   +---------------------+
```

**Measurable outcomes:**

- Zero buddy-punching: device binding makes it physically impossible to clock in for another person.
- Zero GPS spoofing impact: server re-validates coordinates independently of the client.
- Zero data loss on network failure: WorkManager ensures clock-out records are never lost.
- Full audit trail: every security event (domain rejection, OTP failure, device mismatch, mock GPS) is logged and visible to administrators.

### 1.3 Intended Audience

| Reader | Purpose of This Document |
|---|---|
| **Software Developers** | Implementation reference for backend routes, models, and Android components |
| **Faculty Guide / Evaluators** | Academic assessment of scope, completeness, and design decisions |
| **System Administrators** | Deployment, geofence configuration, and day-to-day administration |
| **Quality Assurance / Testers** | Basis for writing, executing, and tracing test cases |
| **Future Maintainers** | Onboarding and change-impact analysis |

### 1.4 Intended Use

**Primary use case — Employee clock-in/clock-out:**

1. Employee opens the GPunch Android app at the start of the workday.
2. The app verifies that high-accuracy GPS is enabled; if not, a system dialog prompts to enable it.
3. Employee taps **Clock In**. The app checks that the GPS location is not mocked, then sends coordinates to the server.
4. The server validates that the employee is within the configured geofence radius using the Haversine formula.
5. A successful clock-in starts a background foreground service that continuously monitors location.
6. When the employee leaves the geofence (or manually taps **Clock Out**), the attendance record is closed.
7. If the device is offline during auto clock-out, the payload is queued by WorkManager and submitted when connectivity is restored.

**Secondary use case — Administrator management:**

1. Admin calls `POST /api/admin/geofence` to set the campus centre coordinates, allowed radius, and institutional email domain.
2. Admin monitors `GET /api/admin/audit-logs` for security events (domain violations, OTP failures, device mismatches, mock GPS).
3. Admin calls `POST /api/admin/reset-device/:userId` to unlink a user's device (e.g., lost phone).
4. Admin downloads `GET /api/admin/export-csv` for payroll or HR reporting.

### 1.5 General Description

GPunch follows a **client-server architecture** with a cloud-hosted backend and a native Android client.

```
+----------------------+        HTTPS / REST        +----------------------+
|   Android Client     |  <---------------------->  |   Express REST API   |
|   (Kotlin, API 26+)  |                             |   (Node.js 18)       |
|                      |                             +----------+-----------+
|  Activities (MVVM)   |                                        |
|  GeofenceService     |                             +----------v-----------+
|  PendingClockOut     |                             |   MongoDB Atlas      |
|  WorkManager         |                             |   (Cloud Database)   |
|  SessionManager      |                             +----------+-----------+
+----------------------+                                        |
                                                        Gmail SMTP (OTP)
```

**Backend layers:**

```
+--------------------------------------------------+
|  Route Handlers (Controllers)                    |
|  /api/auth   /api/punch   /api/admin   /api/audit|
+--------------------------------------------------+
|  Middleware: protect()  adminOnly()  rateLimiter  |
+--------------------------------------------------+
|  Mongoose Models                                 |
|  User  AttendanceRecord  GeofenceConfig  AuditLog|
+--------------------------------------------------+
|  MongoDB Atlas                                   |
+--------------------------------------------------+
```

**Android layers (MVVM):**

```
+------------------------------------------+
|  UI Layer — Activities (Views)           |
|  Splash, Login, Register, OTP, Dashboard |
+------------------------------------------+
|  ViewModel Layer                         |
|  AuthViewModel, DashboardViewModel       |
|  (LiveData, Coroutines)                  |
+------------------------------------------+
|  Repository Layer                        |
|  GpunchApiService (Retrofit + OkHttp)    |
+------------------------------------------+
|  Service / Worker Layer                  |
|  GeofenceMonitorService (Foreground)     |
|  PendingClockOutWorker (WorkManager)     |
+------------------------------------------+
|  Utility Layer                           |
|  SessionManager  GeofenceUtils           |
+------------------------------------------+
```

**Operating environment:**

| Component | Specification |
|---|---|
| Backend Runtime | Node.js >= 18.0.0 |
| Backend Framework | Express.js ^4.18 |
| Database | MongoDB Atlas (M0 free tier or higher) |
| Deployment | Render, Railway, or any HTTPS Node.js host |
| Android Min SDK | API 26 (Android 8.0 Oreo) |
| Android Target SDK | API 34 (Android 14) |
| Android Language | Kotlin 1.9.23 |

---

## 2. FUNCTIONAL REQUIREMENTS

### FR-01: Domain-Restricted Self-Registration

**Description:** The system allows employees to self-register using only an institutional email address. Registrations from non-institutional domains are automatically rejected.

**Inputs:** Full name, institutional email, Android device ID (ANDROID_ID)

**Processing:**
1. Backend loads `GeofenceConfig.allowedDomain` from the database.
2. Email domain (`email.split('@')[1]`) is compared against `allowedDomain`.
3. On mismatch: HTTP 403 returned; `INVALID_DOMAIN` audit event created.
4. On match: 6-digit numeric OTP generated, stored with 10-minute expiry, sent to the user's email.

**Outputs:** OTP email delivered to the user, or domain rejection error

| ID | Requirement |
|---|---|
| FR-01.1 | The backend shall compare the email domain against `GeofenceConfig.allowedDomain` on every registration request. |
| FR-01.2 | If the domain does not match, HTTP 403 shall be returned with message "Email domain is not allowed." |
| FR-01.3 | Every rejected domain attempt shall create an `INVALID_DOMAIN` entry in `AuditLog`. |
| FR-01.4 | If the domain matches, a 6-digit OTP shall be generated with a 10-minute expiry and emailed to the user. |
| FR-01.5 | Duplicate email registrations shall return HTTP 409. |

**Priority:** High | **Test Cases:** TC-01, TC-02

---

### FR-02: OTP Verification and Device Binding

**Description:** Upon entering the received OTP, the system verifies it and permanently binds the user's ANDROID_ID to their account.

**Inputs:** Email, 6-digit OTP, ANDROID_ID

**Processing:**
1. Server finds user by email.
2. OTP compared as strict string equality (prevents type-coercion attacks).
3. Expiry checked against current timestamp.
4. On failure: `OTP_FAILED` logged; HTTP 400 returned.
5. On success: `isVerified = true`, `androidId` saved; JWT issued.

**Outputs:** Signed JWT token, or error with `OTP_FAILED` audit entry

| ID | Requirement |
|---|---|
| FR-02.1 | OTP comparison shall use strict string equality to prevent type-coercion injection. |
| FR-02.2 | Expired OTPs shall return HTTP 400 and log `OTP_FAILED`. |
| FR-02.3 | On success, `androidId` shall be permanently stored against the user record. |
| FR-02.4 | A signed JWT (HS256, 7-day expiry) shall be issued on success. |
| FR-02.5 | OTP values shall never appear in any API response or log output. |

**Priority:** High | **Test Case:** TC-03

---

### FR-03: Device Binding Enforcement

**Description:** A verified user may only log in from the device they originally registered with. Login from a different device is rejected and logged.

**Inputs:** Email, ANDROID_ID

**Processing:**
1. Login endpoint compares `req.body.androidId` against `user.androidId` using strict equality.
2. On mismatch: HTTP 403 returned; `UNAUTHORIZED_DEVICE` audit event created.
3. On match: OTP sent to registered email for session establishment.

**Outputs:** OTP sent (if device matches), or device rejection error with audit log entry

| ID | Requirement |
|---|---|
| FR-03.1 | The login endpoint shall compare the submitted `androidId` against the stored binding. |
| FR-03.2 | A mismatch shall return HTTP 403 with message "Device not recognised." |
| FR-03.3 | Every failed device check shall log an `UNAUTHORIZED_DEVICE` event. |
| FR-03.4 | An admin may clear a device binding to allow re-binding (see FR-10). |

**Priority:** High | **Test Case:** TC-04

---

### FR-04: GPS Settings Check and Location Fetching

**Description:** Before clock-in, the Android app verifies that high-accuracy GPS is enabled. If GPS is off, an in-app system dialog guides the user to enable it.

**Inputs:** User taps "Clock In" button

**Processing:**
1. `LocationSettingsRequest` built with `PRIORITY_HIGH_ACCURACY`.
2. `LocationServices.getSettingsClient().checkLocationSettings()` called.
3. If settings satisfied: `getCurrentLocation()` proceeds.
4. If not satisfied: `ResolvableApiException` caught; system settings dialog launched via `IntentSenderRequest`.

**Outputs:** GPS coordinates obtained, or system settings dialog displayed

| ID | Requirement |
|---|---|
| FR-04.1 | GPS settings shall be checked before every clock-in attempt. |
| FR-04.2 | If GPS is disabled, the Android system settings dialog shall open in-app (no manual navigation). |
| FR-04.3 | Clock-in shall only proceed after valid GPS coordinates are obtained. |

**Priority:** High | **Test Case:** TC-05

---

### FR-05: Geofence-Based Clock-In

**Description:** Clock-in is permitted only when the user is physically within the configured geofence radius. The server validates coordinates independently of the client.

**Inputs:** JWT, latitude, longitude, ANDROID_ID

**Processing:**
1. Server loads `GeofenceConfig` (centre lat/lng, radius).
2. `haversineDistance(config, request)` computed server-side.
3. If `distance > allowedRadius`: HTTP 403 returned; `OUT_OF_BOUNDS` logged.
4. If `distance <= allowedRadius`: `AttendanceRecord` created with `clockInTime`, `clockInLat`, `clockInLng`.

**Outputs:** Clock-in record created, or out-of-bounds rejection with distance

| ID | Requirement |
|---|---|
| FR-05.1 | The server shall compute Haversine distance independently; client assertions are not trusted. |
| FR-05.2 | If `distance > allowedRadius`, HTTP 403 shall be returned with the actual and allowed distances. |
| FR-05.3 | A successful clock-in shall create an `AttendanceRecord` with full location and timestamp data. |
| FR-05.4 | The app shall display the computed distance from the geofence centre on the Dashboard. |

**Priority:** High | **Test Cases:** TC-06, TC-07

---

### FR-06: Mock / Fake GPS Detection

**Description:** The Android client detects GPS spoofing and denies clock-in. The event is reported to the server as a security audit log entry.

**Inputs:** Location object from Fused Location Provider

**Processing:**
1. `location.isMock` checked (API 18+; reliable from API 31+).
2. `Settings.Secure.ALLOW_MOCK_LOCATION` checked for devices below API 31.
3. If either returns `true`: clock-in denied; toast displayed; `MOCK_LOCATION` reported to `POST /api/audit`.

**Outputs:** Clock-in denied with user-visible message; `MOCK_LOCATION` audit entry created

| ID | Requirement |
|---|---|
| FR-06.1 | `location.isMock` shall be evaluated on every location fix before clock-in. |
| FR-06.2 | The legacy `ALLOW_MOCK_LOCATION` setting shall additionally be checked for pre-API-31 devices. |
| FR-06.3 | If mock location is detected, clock-in shall be denied and a toast message displayed. |
| FR-06.4 | The app shall report a `MOCK_LOCATION` event to `POST /api/audit`. |

**Priority:** High | **Test Case:** TC-08

---

### FR-07: Background Geofence Monitoring with Persistent Notification

**Description:** After a successful clock-in, a foreground service monitors the user's GPS position continuously and displays a persistent notification.

**Inputs:** Successful clock-in event

**Processing:**
1. `GeofenceMonitorService` started as an Android Foreground Service.
2. Persistent notification shown in the Android notification tray.
3. GPS polled via `FusedLocationProviderClient.requestLocationUpdates()` with adaptive intervals: 15 s when moving (>=5 m displacement), 60 s when stationary.

**Outputs:** Persistent notification visible in tray; continuous location monitoring active

| ID | Requirement |
|---|---|
| FR-07.1 | `GeofenceMonitorService` shall be started as a Foreground Service immediately after successful clock-in. |
| FR-07.2 | A persistent notification shall appear in the Android tray during the active session. |
| FR-07.3 | Adaptive GPS polling (15 s / 60 s) shall be used to minimise battery drain. |
| FR-07.4 | The service shall survive app backgrounding, task kill, and screen-off events. |

**Priority:** High | **Test Case:** TC-09

---

### FR-08: Automatic Clock-Out on Geofence Breach

**Description:** When the monitoring service detects that the user has left the geofence, it automatically calls the clock-out API and notifies the user.

**Inputs:** GPS coordinates from `GeofenceMonitorService` indicating boundary exit

**Processing:**
1. `haversineDistance` computed on each location update.
2. If `distance > fenceRadius`: `POST /api/punch/out` called with `autoClockOut: true`.
3. `SessionManager.setIsClockedIn(false)` updated.
4. Push notification displayed: "You have left the work zone. Attendance has been recorded."
5. Foreground service stops.

**Outputs:** Clock-out record persisted; user notified; background service stopped

| ID | Requirement |
|---|---|
| FR-08.1 | A geofence exit shall trigger `POST /api/punch/out` with `autoClockOut: true` automatically. |
| FR-08.2 | The app session state shall be updated to "clocked out" immediately. |
| FR-08.3 | A push notification shall inform the user that auto clock-out has occurred. |
| FR-08.4 | The foreground service shall stop after auto clock-out completes. |

**Priority:** High | **Test Case:** TC-10

---

### FR-09: Admin — Geofence and Domain Configuration

**Description:** The system administrator sets the geofence centre, radius, and allowed email domain. Changes take effect immediately for all subsequent clock-in attempts.

**Inputs:** JWT (admin role), latitude, longitude, radius (metres), email domain

**Endpoint:** `POST /api/admin/geofence`

**Processing:** Upserts a single `GeofenceConfig` document. All subsequent `/api/punch/in` calls load this configuration.

**Outputs:** Updated `GeofenceConfig` confirmed

| ID | Requirement |
|---|---|
| FR-09.1 | The endpoint shall accept `{ latitude, longitude, allowedRadius, allowedDomain }` with admin JWT. |
| FR-09.2 | The operation shall upsert, ensuring only one `GeofenceConfig` document exists at any time. |
| FR-09.3 | Changes shall take effect for all clock-in requests that arrive after the upsert. |

**Priority:** High | **Test Case:** TC-11

---

### FR-10: Admin — Device Unlinking

**Description:** The administrator can clear a user's device binding, allowing them to re-bind from a new device on the next OTP verification.

**Inputs:** JWT (admin role), user ID

**Endpoint:** `POST /api/admin/reset-device/:userId`

**Processing:** Sets `user.androidId = null`. On the user's next OTP verification, the new device ANDROID_ID is bound.

| ID | Requirement |
|---|---|
| FR-10.1 | The endpoint shall set `user.androidId = null` for the specified user. |
| FR-10.2 | After the reset, the user shall be able to bind a new device on next successful OTP verification. |

**Priority:** High | **Test Case:** TC-12

---

### FR-11: Admin — Security Audit Log Viewing

**Description:** The administrator can view a paginated, filterable list of all security events to monitor for attendance fraud attempts.

**Inputs:** JWT (admin role), optional `eventType` filter, `page`, `limit`

**Endpoint:** `GET /api/admin/audit-logs`

**Logged Event Types**

| Event Type | Trigger |
|---|---|
| `INVALID_DOMAIN` | Registration with non-institutional email (TC-01) |
| `OTP_FAILED` | Incorrect or expired OTP submitted (TC-03) |
| `UNAUTHORIZED_DEVICE` | Login from an unbound device (TC-04) |
| `MOCK_LOCATION` | Fake GPS app detected on client (TC-08) |
| `OUT_OF_BOUNDS` | Clock-in attempted outside the geofence |
| `SUSPICIOUS_ACTIVITY` | Other anomalies reported by the client |

| ID | Requirement |
|---|---|
| FR-11.1 | The endpoint shall support `eventType`, `page`, and `limit` query parameters. |
| FR-11.2 | The `eventType` filter shall be validated against a whitelist to prevent injection. |
| FR-11.3 | Each log entry shall include: `userId`, `email`, `androidId`, `eventType`, `timestamp`, `ipAddress`, `metadata`. |

**Priority:** High | **Test Case:** TC-13

---

### FR-12: Offline Clock-Out Queueing

**Description:** If the auto clock-out API call fails due to no network connectivity, the payload is persisted locally by WorkManager and retried automatically when the network is restored.

**Inputs:** Network failure during auto clock-out

**Processing:**
1. `IOException` (or any exception) caught in `GeofenceMonitorService`.
2. `PendingClockOutWorker` enqueued via WorkManager with a `CONNECTED` constraint.
3. `SessionManager.setIsClockedIn(false)` set immediately (optimistic update).
4. When connectivity returns, WorkManager fires the worker which calls `POST /api/punch/out`.

**Outputs:** Clock-out record persisted once network is available; no data lost

| ID | Requirement |
|---|---|
| FR-12.1 | Any network failure during auto clock-out shall enqueue a `PendingClockOutWorker` via WorkManager. |
| FR-12.2 | The WorkManager job shall have a `CONNECTED` network constraint. |
| FR-12.3 | The clock-out payload shall persist across app restarts and device reboots. |
| FR-12.4 | The app session state shall be updated to "clocked out" optimistically before retry. |

**Priority:** Medium | **Test Case:** TC-14

---

### FR-13: Admin — CSV Attendance Export

**Description:** The administrator can download a complete attendance report for all users in CSV format.

**Endpoint:** `GET /api/admin/export-csv` (admin JWT required)

**Output columns:** Name, Email, Clock-In Time, Clock-Out Time, Duration (minutes), Auto Clock-Out flag, Android ID

| ID | Requirement |
|---|---|
| FR-13.1 | The endpoint shall return a downloadable CSV file with a `Content-Disposition: attachment` header. |
| FR-13.2 | Every `AttendanceRecord` document shall appear as one row in the export. |

**Priority:** Medium

---

### Test Case Traceability Matrix

| TC | Requirement | Input / Action | Expected Output | FR | Result |
|---|---|---|---|---|---|
| TC-01 | Domain Restriction - Reject | Register with `user@gmail.com` | HTTP 403; `INVALID_DOMAIN` logged | FR-01 | Pass |
| TC-02 | Domain Restriction - Accept | Register with `user@psgtech.ac.in` | HTTP 200; OTP sent | FR-01 | Pass |
| TC-03 | OTP Verification Failure | Submit wrong 6-digit OTP | HTTP 400; `OTP_FAILED` logged | FR-02 | Pass |
| TC-04 | Device Binding Enforcement | Login from Device 2 (bound to Device 1) | HTTP 403; `UNAUTHORIZED_DEVICE` logged | FR-03 | Pass |
| TC-05 | GPS Settings Check | Tap Clock-In with GPS disabled | System location settings dialog shown | FR-04 | Pass |
| TC-06 | Geofence Inside | Clock-in at 20 m from fence centre | HTTP 201; foreground service starts | FR-05 | Pass |
| TC-07 | Geofence Outside | Clock-in at 200 m from fence centre | HTTP 403; distance in response | FR-05 | Pass |
| TC-08 | Mock Location | Enable Fake GPS app; attempt clock-in | Denied; toast shown; `MOCK_LOCATION` logged | FR-06 | Pass |
| TC-09 | Background Tracking | Minimise app after Clock-In | Persistent notification visible in tray | FR-07 | Pass |
| TC-10 | Auto Clock-Out | Move 100+ m from campus boundary | Clock-out API called; user notified | FR-08 | Pass |
| TC-11 | Admin Geofence Config | Change radius 50 m -> 10 m | Users at 20 m rejected on next clock-in | FR-09 | Pass |
| TC-12 | Admin Device Reset | Admin resets binding for User A | User A can bind new device on next login | FR-10 | Pass |
| TC-13 | Audit Log Visibility | Admin views security dashboard | TC-01, TC-03, TC-04, TC-08 events visible | FR-11 | Pass |
| TC-14 | Offline Clock-Out Queue | Auto clock-out in Airplane Mode | Payload queued; sent on reconnect | FR-12 | Pass |

---

## 3. EXTERNAL INTERFACE REQUIREMENTS

### 3.1 User Interface Requirements

**Android Application Screens**

| Screen | Key Elements | Description |
|---|---|---|
| **Splash** | Logo, progress bar | Auto-redirects: Dashboard (if JWT valid) or Login |
| **Login** | Email input, "Send OTP" button | Passwordless login via institutional email |
| **Register** | Name + email inputs, "Send OTP" button | New account with domain validation |
| **OTP Verification** | 6-digit code input, countdown timer (60 s), Resend link | OTP entry with resend throttle |
| **Dashboard** | Clock-In/Out button, distance indicator, session timer, status badge, History, Logout | Primary user interface for all attendance actions |

**UI Behaviour Requirements**

| ID | Requirement |
|---|---|
| UI-01 | All screens shall display human-readable error messages without technical stack traces. |
| UI-02 | The Dashboard shall display the current clock-in status ("Clocked In" / "Clocked Out") as a prominent status badge. |
| UI-03 | The OTP screen shall show a 60-second countdown before the Resend button becomes active. |
| UI-04 | The persistent notification shall contain the session start time and one-tap navigation to the Dashboard. |
| UI-05 | The Clock-In button shall be disabled while a GPS fix or network request is in progress. |

**Authentication and Navigation Flow**

```
App Launch
  |
  +--[JWT valid]--> Dashboard
  |
  +--[No session]--> Login
                       |
                       +--[New user]--> Register --> OTP Screen --> Dashboard
                       |
                       +--[Returning user]--> OTP Screen --> Dashboard
                                                                |
                                                           [Clock In]
                                                                |
                                                    GeofenceMonitorService
                                                    (runs in background)
```

### 3.2 Hardware Interface Requirements

| Hardware | Requirement |
|---|---|
| **GPS Receiver** | Required for coordinate acquisition; high-accuracy (GNSS) mode preferred |
| **Network Interface** | Wi-Fi or mobile data (2G minimum) for REST API communication |
| **Notification System** | Android notification tray for persistent session notification and auto clock-out alert |
| **Internal Storage** | SharedPreferences for JWT token, session state, and WorkManager job persistence |
| **Clock / RTC** | Device system time used for OTP expiry checks and attendance timestamps |

### 3.3 Software Interface Requirements

| Interface | Technology | Version | Purpose |
|---|---|---|---|
| **MongoDB Atlas** | Mongoose ODM | ^8.3 | Persistent storage for all four collections |
| **Gmail SMTP** | Nodemailer | ^8.0 | Transactional OTP email delivery |
| **Fused Location Provider** | Google Play Services | 21.3.0 | High-accuracy GPS coordinate acquisition |
| **WorkManager** | AndroidX WorkManager | 2.9.0 | Persistent background job scheduling |
| **Retrofit + OkHttp** | Retrofit / OkHttp | 2.11 / 4.12 | Android HTTP client with JWT interceptor |
| **express-validator** | npm | ^7.0 | Server-side request body validation |
| **helmet.js** | npm | ^7.1 | HTTP security header hardening |
| **express-rate-limit** | npm | ^7.2 | IP-based request rate limiting |
| **jsonwebtoken** | npm | ^9.0 | JWT signing and verification (HS256) |

### 3.4 Communication Interface Requirements

| Protocol | Usage | Details |
|---|---|---|
| **HTTPS (TLS 1.2+)** | All client-server API communication | Enforced by OkHttp `CertificatePinner` on Android; mandatory on Render/Railway deployments |
| **JSON** | Request and response body format | `Content-Type: application/json` on all endpoints |
| **Bearer JWT** | API session authentication | `Authorization: Bearer <token>` header on all protected routes |
| **SMTP / STARTTLS** | OTP email delivery | Gmail port 587 with STARTTLS; App Password authentication |

**Rate Limiting**

| Scope | Limit |
|---|---|
| Auth endpoints (`/api/auth/*`) | 20 requests per 15 minutes per IP |
| Global (all routes) | 100 requests per 15 minutes per IP |

---

## 4. NON-FUNCTIONAL REQUIREMENTS

### 4.1 Security

| ID | Requirement |
|---|---|
| NFR-S-01 | All API endpoints except `/api/auth/*` and `/health` shall require a valid JWT (HS256). |
| NFR-S-02 | Admin routes shall additionally verify `role: "admin"` in the JWT payload. |
| NFR-S-03 | OTP values shall never appear in API responses, server logs, or database query results (stripped via Mongoose `toJSON` transform). |
| NFR-S-04 | All Mongoose queries shall use explicit type casting to prevent NoSQL operator injection attacks. |
| NFR-S-05 | The `eventType` filter parameter on audit-log queries shall be validated against a whitelist. |
| NFR-S-06 | All HTTP response headers shall be hardened using `helmet.js` (X-Frame-Options, CSP, HSTS, etc.). |
| NFR-S-07 | Request body size shall be capped at 10 KB to mitigate payload-flooding attacks. |
| NFR-S-08 | HTTPS shall be enforced on all production deployments; HTTP connections shall be rejected or redirected. |
| NFR-S-09 | JWT tokens shall expire after 7 days; OTPs shall expire after 10 minutes. |
| NFR-S-10 | GPS coordinates shall be validated server-side; client-provided coordinates shall never be trusted without re-validation. |

**Security Threat Model**

| Threat | Mitigation |
|---|---|
| Buddy punching | Device binding: each account locked to one ANDROID_ID |
| GPS spoofing | `location.isMock` check + legacy developer-options check + server-side Haversine |
| Proxy API calls | Server-side Haversine validation; client cannot override distance result |
| NoSQL injection | Explicit typed Mongoose queries; `eventType` whitelisting |
| JWT forgery | HS256 with long `JWT_SECRET`; 7-day expiry |
| OTP brute-force | Auth rate limit: 20 req/15 min; 10-minute OTP expiry |
| Replay attacks | OTP is single-use (nulled after verification); JWT expiry |

### 4.2 Capacity

| ID | Requirement |
|---|---|
| NFR-C-01 | The system shall support at least 500 registered users on a MongoDB Atlas M0 (free tier) single-campus deployment. |
| NFR-C-02 | The backend shall handle at least 50 concurrent clock-in requests without degradation. |
| NFR-C-03 | `AttendanceRecord` collection shall support at least 2 years of daily records for 500 users without requiring index changes. |
| NFR-C-04 | `AuditLog` collection shall be indexed on `{ eventType, createdAt }` and `{ userId, createdAt }` to support efficient admin queries. |

### 4.3 Compatibility

| ID | Requirement |
|---|---|
| NFR-CP-01 | The Android client shall run on Android 8.0 (API 26) and all higher versions up to Android 14 (API 34). |
| NFR-CP-02 | The Android client shall function on ARM, ARM64, and x86_64 processor architectures. |
| NFR-CP-03 | The backend shall run on any Node.js >= 18.0.0 compatible hosting platform. |
| NFR-CP-04 | The backend API shall be accessible from any HTTP client that supports HTTPS and JSON (Retrofit, Postman, curl). |
| NFR-CP-05 | Google Play Services shall be available on the target Android device (required for FLP and WorkManager). |

### 4.4 Reliability

| ID | Requirement |
|---|---|
| NFR-R-01 | Offline clock-out payloads shall not be lost; WorkManager persistence survives app restarts and device reboots. |
| NFR-R-02 | `BootReceiver` shall restore the `GeofenceMonitorService` foreground service after a device reboot if the user was clocked in at shutdown. |
| NFR-R-03 | The backend deployment on Render/Railway shall achieve >= 99% uptime during normal workday hours (08:00-18:00). |
| NFR-R-04 | Failed WorkManager jobs shall be retried with exponential back-off until the `CONNECTED` constraint is satisfied. |

### 4.5 Scalability

| ID | Requirement |
|---|---|
| NFR-SC-01 | The backend architecture shall allow horizontal scaling by adding additional Node.js instances behind a load balancer without schema changes. |
| NFR-SC-02 | MongoDB Atlas connection pooling (via Mongoose) shall allow the system to scale to Atlas M10 or higher tiers without code changes. |
| NFR-SC-03 | The system design shall allow the addition of a second geofence (multi-campus) in a future release with only additive schema changes. |
| NFR-SC-04 | Rate limiting configuration shall be externalisable to environment variables to support traffic growth without code redeployment. |

### 4.6 Maintainability

| ID | Requirement |
|---|---|
| NFR-M-01 | Backend code shall follow MVC separation: `models/`, `routes/` (controllers), `middleware/`, `utils/`. |
| NFR-M-02 | Android code shall follow MVVM: Activities (Views) observe LiveData from ViewModels; network calls are made through `GpunchApiService`. |
| NFR-M-03 | All secrets and configuration values shall be externalised to `.env` files; no hardcoded secrets in source code. |
| NFR-M-04 | Backend unit tests shall cover >= 80% of utility functions (Haversine, OTP) and critical auth flows. |
| NFR-M-05 | The `.env.example` file shall document all required environment variables with descriptions. |

### 4.7 Usability

| ID | Requirement |
|---|---|
| NFR-U-01 | Registration shall require no passwords — only name, institutional email, and a 6-digit OTP. |
| NFR-U-02 | The OTP resend timer (60 seconds) shall prevent accidental double-sends while allowing genuine resends. |
| NFR-U-03 | All user-facing error messages shall be human-readable; no technical stack traces shall be displayed to end users. |
| NFR-U-04 | The persistent foreground notification shall provide one-tap navigation back to the Dashboard activity. |
| NFR-U-05 | The Clock-In button shall be visually disabled (greyed out) when the user is already clocked in. |
| NFR-U-06 | The app shall display the distance from the geofence centre on the Dashboard so the user can understand rejection reasons. |

### 4.8 Other Non-Functional Requirements

**Legal and Compliance**

| ID | Requirement |
|---|---|
| NFR-O-01 | Location data (GPS coordinates) shall be stored only for legitimate attendance tracking purposes and shall not be shared with third parties. |
| NFR-O-02 | No biometric data (fingerprint, face, voice) shall be collected or stored by the system. |
| NFR-O-03 | `ANDROID_ID` is a device-level pseudonymous identifier; it does not directly identify a natural person. |
| NFR-O-04 | Institutional administrators are responsible for ensuring the system is deployed in compliance with applicable data protection regulations (e.g., DPDP Act 2023). |

**Performance**

| ID | Requirement |
|---|---|
| NFR-O-05 | Clock-in API response time shall be <= 2 seconds under normal network conditions. |
| NFR-O-06 | The app shall obtain a GPS fix within 10 seconds of the Dashboard opening. |
| NFR-O-07 | Background service battery impact shall be minimised through adaptive GPS polling (15 s active / 60 s stationary). |

---

## 5. DEFINITIONS AND ACRONYMS

### 5.1 Acronyms

| Acronym | Full Form |
|---|---|
| **API** | Application Programming Interface |
| **ANDROID_ID** | A 64-bit hexadecimal identifier unique to an Android device per app install and signing key |
| **CSV** | Comma-Separated Values |
| **FLP** | Fused Location Provider (Google Play Services location API) |
| **FR** | Functional Requirement |
| **GPS** | Global Positioning System |
| **GNSS** | Global Navigation Satellite System |
| **HTTP / HTTPS** | HyperText Transfer Protocol / Secure |
| **JWT** | JSON Web Token |
| **MCA** | Master of Computer Applications |
| **MVVM** | Model-View-ViewModel |
| **NFR** | Non-Functional Requirement |
| **ODM** | Object Document Mapper |
| **OTP** | One-Time Password |
| **REST** | Representational State Transfer |
| **SDK** | Software Development Kit |
| **SMTP** | Simple Mail Transfer Protocol |
| **SRS** | Software Requirements Specification |
| **TC** | Test Case |
| **TLS** | Transport Layer Security |

### 5.2 Definitions

| Term | Definition |
|---|---|
| **Attendance Record** | A database document storing a single clock-in / clock-out session for one user, including timestamps, GPS coordinates, duration, and device ID. |
| **Audit Log** | A tamper-evident security event record capturing the type, actor, device, location, and timestamp of a security-relevant action (e.g., domain rejection, OTP failure, device mismatch). |
| **Buddy Punching** | An attendance fraud where one employee clocks in or out on behalf of another absent employee. |
| **Device Binding** | The permanent association of a user account with a specific ANDROID_ID established during first OTP verification. |
| **Foreground Service** | An Android service that runs while displaying a persistent notification, making it resistant to system termination. |
| **Geofence** | A virtual geographic boundary defined by a centre latitude/longitude coordinate pair and a radius in metres. |
| **Haversine Formula** | A mathematical formula that computes the great-circle distance between two points on a sphere given their latitude and longitude. |
| **JWT (JSON Web Token)** | A compact, self-contained token that encodes user identity and role claims, signed with HMAC-SHA256, used for stateless API session management. |
| **Mock Location** | A falsified GPS location generated by a third-party application for the purpose of deceiving location-based systems. |
| **OTP (One-Time Password)** | A 6-digit numeric code valid for 10 minutes, generated server-side and delivered by email, used in place of a password for authentication. |
| **Proxy Attendance** | An attendance fraud where a person submits an API request on behalf of another person who is not physically present. |
| **WorkManager** | An AndroidX library that schedules deferrable, constraint-based background tasks that persist across app restarts and device reboots. |

### 5.3 Data Model Summary

| Collection | Primary Fields | Purpose |
|---|---|---|
| `users` | name, email, role, androidId, otp, otpExpiresAt, isVerified, isActive | User accounts and device binding |
| `attendancerecords` | userId, email, clockInTime, clockOutTime, durationMinutes, autoClockOut, clockInLat/Lng | Attendance history |
| `geofenceconfigs` | latitude, longitude, allowedRadius, allowedDomain, updatedBy | Active geofence parameters (singleton) |
| `auditlogs` | userId, email, androidId, eventType, ipAddress, latitude, longitude, metadata | Security event audit trail |

### 5.4 Revision History

| Version | Date | Author | Change Description |
|---|---|---|---|
| 0.1 | April 2026 | Kavin M | Initial draft — scope and FR outline |
| 0.9 | April 2026 | Shanmugappriya K | NFRs, data models, API specification, test matrix |
| 1.0 | May 2026 | Kavin M, Shanmugappriya K | Final — all 14 test cases verified; 23MX21 template applied |

---

*Document ends — GPunch SRS v1.0 (Template: 23MX21)*
