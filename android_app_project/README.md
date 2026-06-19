<div align="center">
  <img src="assets/banner.png" alt="NexusFi Banner" width="100%">
</div>

# NexusFi (Micro-ISP & Local Cloud Ecosystem)
### Production-Grade Hotspot Infrastructure for Rooted Android / Termux

<div align="center">
  <a href="https://github.com/your-username/nexusfi/blob/main/legal.txt"><img src="https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge" alt="License: MIT"></a>
  <a href="#"><img src="https://img.shields.io/badge/Build-Passing-brightgreen.svg?style=for-the-badge" alt="Build: Passing"></a>
  <a href="#"><img src="https://img.shields.io/badge/Version-3.0.0-orange.svg?style=for-the-badge" alt="Version: 3.0.0"></a>
  <a href="#"><img src="https://img.shields.io/badge/Platform-Android%20%7C%20Termux-lightgrey.svg?style=for-the-badge" alt="Platform: Android/Termux"></a>
  <a href="#"><img src="https://img.shields.io/github/stars/your-username/nexusfi?style=for-the-badge&color=yellow" alt="Stars"></a>
  <a href="#"><img src="https://img.shields.io/github/forks/your-username/nexusfi?style=for-the-badge&color=lightblue" alt="Forks"></a>
</div>

> A self-contained, 3-tier telecommunications and intranet platform engineered to run natively on a rooted Android device under Termux. The system provisions a fully managed Wi-Fi hotspot, a captive portal with a gamification layer, an offline intranet (VOD, social network, file library, HTML5 games), and a native Android companion application — with zero dependency on cloud infrastructure.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture & Tech Stack](#2-architecture--tech-stack)
3. [Core Innovations & Infrastructure](#3-core-innovations--infrastructure)
   - [PENTA-SHIELD Device Fingerprinting](#31-penta-shield-device-fingerprinting)
   - [Ghost Radar & Session Management](#32-ghost-radar--session-management)
   - [Smart Storage & Hardware Telemetry](#33-smart-storage--hardware-telemetry)
4. [The Gamification & Loyalty Engine](#4-the-gamification--loyalty-engine)
5. [The Local Cloud (Offline Intranet)](#5-the-local-cloud-offline-intranet)
6. [Data Layer & Security Architecture](#6-data-layer--security-architecture)
7. [Deployment & Startup Sequence](#7-deployment--startup-sequence)
8. [Screenshots Gallery](#8-screenshots-gallery)

---

## 1. System Overview

This platform constitutes a complete **Micro Internet Service Provider (Micro-ISP)** stack operating entirely on rooted Android hardware. It is designed for dense, controlled-access Wi-Fi environments where the operator requires granular session control, reliable revenue tracking, and a rich offline content ecosystem — all without dependence on a remote server, VPS, or cloud infrastructure.

The system manages the full lifecycle of a client connection:

1. A device connects to the Wi-Fi access point and is immediately intercepted by a captive portal firewall.
2. The user authenticates via voucher code or a free trial, triggering real-time `iptables` rule injection to authorize that specific device.
3. An active session is continuously monitored via a heartbeat loop. Upon expiry or timeout, the firewall rules are atomically revoked and the session is closed in the database.
4. Throughout the session, the device is fingerprinted using a multi-signal approach that survives MAC address randomization. Loyalty points accrue, and the user can redeem earned gifts without operator intervention.
5. At any point, the user has access to a fully offline intranet — media server, social network, application library — served from the same Android device over the local network with zero internet bandwidth consumption.

The system is architected in three discrete but tightly coupled tiers, each with a defined responsibility boundary.

---

## 2. Architecture & Tech Stack

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        ANDROID DEVICE (Rooted)                          │
│                                                                          │
│  ┌──────────────────┐   ┌─────────────────────────────────────────────┐ │
│  │   TIER 3         │   │   TIER 1 — PHP/SQLite Hotspot Backend       │ │
│  │   Native Android │   │                                             │ │
│  │   App (Java)     │   │  ┌──────────────────┐  ┌────────────────┐  │ │
│  │                  │   │  │  Captive Portal   │  │  Admin Panel   │  │ │
│  │ • WifiService    │──▶│  │  index.php        │  │  admin.php     │  │ │
│  │ • Heartbeat Loop │   │  │  status.php       │  │                │  │ │
│  │ • PENTA-SHIELD   │   │  └──────────────────┘  └────────────────┘  │ │
│  │   DNA injection  │   │             │                  │            │ │
│  └──────────────────┘   │  ┌──────────▼──────────────────▼─────────┐ │ │
│                          │  │           INCLUDES LAYER              │ │ │
│  ┌──────────────────┐   │  │  SessionManager  │  firewall.php      │ │ │
│  │   TIER 2         │   │  │  auth.php        │  mac_detection.php │ │ │
│  │   FastAPI Local  │   │  │  gift_system.php │  accounting.php    │ │ │
│  │   Cloud (Python) │   │  │  device_telemetry│  captive_trace.php │ │ │
│  │                  │   │  └───────────────────────────────────────┘ │ │
│  │ • Media Hub (VOD)│   │             │                               │ │
│  │ • Social Network │   │  ┌──────────▼───────────────────────────┐  │ │
│  │ • File Library   │   │  │         API LAYER  (/api/)           │  │ │
│  │ • HTML5 Games    │   │  │  heartbeat.php │ payments.php        │  │ │
│  │ • Admin UI       │   │  │  users.php     │ analytics.php       │  │ │
│  │ • WebSocket      │   │  │  vouchers.php  │ system.php          │  │ │
│  │   Telemetry      │   │  └──────────────────────────────────────┘  │ │
│  └──────────────────┘   │             │                               │ │
│         :8090           │  ┌──────────▼───────────────────────────┐  │ │
│                          │  │    SQLite (WAL Mode) — hotspot.db    │  │ │
│  ┌──────────────────┐   │  └──────────────────────────────────────┘  │ │
│  │   DAEMONS        │   └─────────────────────────────────────────────┘ │
│  │ ghost_radar.sh   │                                                    │
│  │ fw-worker.php    │   ┌─────────────────────────────────────────────┐ │
│  │ captive_dns      │   │  KERNEL LAYER                               │ │
│  │   _server.php    │   │  iptables  │  tc (HTB)  │  /proc/net/arp   │ │
│  └──────────────────┘   └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                              Wi-Fi AP Interface
                                    │
              ┌─────────────────────┴─────────────────────┐
              │                Wi-Fi Clients              │
              │  Browser (Captive Portal) → :8080         │
              │  Offline Platform       → :8090           │
              └────────────────────────────────────────────┘
```

### Technology Stack by Tier

| Layer | Technology | Version Rationale |
|---|---|---|
| **Hotspot Backend** | PHP (CLI Server, 4 workers) | Native Termux support; no separate HTTP server required |
| **Database (Hotspot)** | SQLite 3 (WAL + `mmap`) | Atomic writes, crash-safe, no daemon, SD-card compatible |
| **Local Cloud** | FastAPI + `aiosqlite` (Python) | Async I/O handles concurrent media streams on single-core without blocking |
| **WSGI/ASGI Server** | Uvicorn (`--workers 1`) | Android kernel lacks `multiprocessing.fork`; asyncio is the correct model |
| **Firewall** | `iptables-legacy` / `/system/bin/iptables` | Runtime detection; falls back to native Android iptables when legacy unavailable |
| **Bandwidth Shaping** | Linux `tc` HTB (Hierarchical Token Bucket) | Per-user dedicated class; `fq_codel` leaf where kernel supports it |
| **DNS** | Custom PHP UDP DNS Server | Dual-mode: captive (spoof) and managed (blocklist + logging) |
| **Android App** | Native Java (Android SDK) | Direct kernel-level hardware access via `NetworkInterface`, `ActivityManager` |
| **Session Storage** | SQLite (`local_platform.db`) | Isolated from hotspot DB to prevent cross-tier locking |

---

## 3. Core Innovations & Infrastructure

### 3.1 PENTA-SHIELD Device Fingerprinting

**The Problem:** Modern Android devices (Android 10+) randomize their MAC address per Wi-Fi network by default. A user's hardware address changes on every reconnection, making all MAC-based session tracking and billing fundamentally unreliable with standard approaches.

**The Solution:** PENTA-SHIELD is a five-signal device identity system that constructs a persistent, immutable device fingerprint that survives MAC randomization. It operates across both the native Android application and the browser-based captive portal JavaScript.

#### Signal Layer 1: Native Android App Injection (Highest Trust)
The `WifiService` foreground service reads the device's `ANDROID_ID` — a hardware-derived, per-factory-reset unique identifier — via `Settings.Secure` and transmits it as the `native_id` field in every heartbeat POST request. The `ConnectivityChecker` further reads `Build.BRAND`, `Build.MODEL`, and total physical RAM via `ActivityManager.MemoryInfo`, injecting these as query parameters (`n_brand`, `n_model`, `n_ram`, `native_id`) directly into the captive portal URL before the page renders. This data arrives server-side *before the user has even interacted with the portal*.

When the backend receives a `native_id`, it overrides all other token sources:

```
// STRICT ANCHOR: If native_id is present, it is the ultimate, immutable device token.
$native_id = $payload['native_id'] ?? $profile['native_id'] ?? '';
if (!empty($native_id)) {
    $token = device_telemetry_text($native_id, 128);
}
```

Sessions carrying `native_id` are permanently tagged with `device_token = 'native_app'`. This token is write-protected in the database — no subsequent telemetry update can overwrite it.

#### Signal Layer 2: Browser-Side DNA Collection
The embedded JavaScript telemetry script (`device_telemetry.php`) collects a 13-dimension hardware fingerprint on every page load and heartbeat:

| Signal | Method |
|---|---|
| **OS & Browser** | User-Agent parsing + `navigator.userAgentData.getHighEntropyValues()` |
| **Brand & Model** | UA Client Hints (API), UA string fallback, platform inspection |
| **RAM** | `navigator.deviceMemory` |
| **CPU Cores** | `navigator.hardwareConcurrency` |
| **Screen Geometry** | `screen.width × screen.height` (portrait/landscape normalized) |
| **GPU Quirk Hash** | WebGL `UNMASKED_RENDERER_WEBGL`, viewport dims, texture limits |
| **Canvas Hash** | GPU-rendered 2D canvas pixel hash (sub-pixel rendering varies per GPU) |
| **Audio Hash** | WebAudio `DynamicsCompressor` parameter fingerprint |
| **Font Hash** | Character metric measurements across 13 font families |
| **Language & Timezone** | `navigator.language`, `Intl.DateTimeFormat().resolvedOptions().timeZone` |

These signals are hashed on-device and POSTed to `api/heartbeat.php` as `raw_dna`. The server-side `calculate_fuzzy_match()` function computes a weighted cosine-style similarity score across these 13 dimensions. A match above a configurable threshold confirms device continuity even after a MAC address change.

#### Signal Layer 3: Evercookie Persistence
The browser-side telemetry script writes a `device_token` to three independent storage locations simultaneously:
- `localStorage`
- `sessionStorage`
- HTTP Cookie (`max-age=31536000`, `SameSite=Lax`)

This triple-write approach ensures the token survives individual storage clears. Recovery reads from all three stores, and all three are re-synchronized on every access.

#### Signal Layer 4: Shadow Profile & MAC Change Migration
Every telemetry submission triggers an UPSERT into the `shadow_profiles` table keyed by `mac_address`. When a MAC change is detected (same `device_token`, different MAC), `updateMacAddress()` atomically migrates all linked records across `vouchers`, `users`, `free_trials`, `payment_requests`, and `gifts` tables in a single transaction — preserving the complete purchase and session history under the new hardware address.

#### Signal Layer 5: ARP Table Sovereignty
The backend never trusts client-submitted MAC addresses as the primary source. The canonical MAC resolution order is:

1. `/proc/net/arp` kernel table (direct file read, no shell exec) — unforgeably set by the kernel
2. `ip neigh show` command (Linux neighbor cache — L2 hardware table)
3. GET parameter (only if steps 1 and 2 produce no result — race condition fallback)

---

### 3.2 Ghost Radar & Session Management

The Ghost Radar is the session expiry and enforcement subsystem. It consists of two cooperating components: the `ghost_radar.sh` supervisor daemon and the `SessionManager` PHP class.

#### ghost_radar.sh — The Watchdog Loop
`ghost_radar.sh` runs as a persistent background daemon under a `mkdir`-based lock (POSIX-safe on Android, where `flock` is unreliable). It executes `admin.php ghost_radar` on a configurable interval (default: 30 seconds) and automatically restarts on failure with a 5-second backoff. This ensures the expiry enforcement process is always running and self-healing.

#### SessionManager — The Firewall State Machine
`SessionManager` is the single authority over the relationship between the database state and the live `iptables` rule set. It implements a file-lock (`flock(LOCK_EX)`) to prevent concurrent sessions from issuing conflicting firewall mutations.

**Core Operations:**

- **`reconcileClientAccess($mac)`** — The primary single-device reconciliation path. Reads the desired authorization state from the database, syncs any pending accounting bytes from `iptables` counters (monotonic accounting fix — prevents data loss when chains are flushed), then calls `firewall_apply_client_state()`.

- **`reconcileAll($pdo)`** — Full sweep reconciliation run on startup and periodically. Executes a `deepCleanupFirewall()` pass to purge any `ipset` entries for MACs not in the authorized list, then re-applies rules for all currently active vouchers.

- **`invalidateClientAccess($mac)` — The Guillotine** — Called when a heartbeat timeout is detected. Executes three atomic actions:
  1. `firewall_apply_client_state($mac, ..., authorized=false)` — removes all ACCEPT/RETURN rules for the MAC.
  2. `UPDATE vouchers SET ended_at = ..., end_reason = 'heartbeat_timeout'` — closes the session in the database.
  3. Writes a `guillotine_fired` event to the captive trace log.

- **`suspendClientAccess($mac)` — Soft Drop** — Applied when a native Android app session has been silent for more than 600 seconds but the voucher is still valid. Rather than terminating the session, this applies an `is_soft_dropped` flag and reconfigures the firewall to issue `REJECT --reject-with icmp-port-unreachable` responses, which forces the OS captive portal detection engine to fire and prompt the user to re-open the portal.

- **`guillotineSweep($pdo)`** — Proactively identifies active, non-soft-dropped `native_android` sessions whose last heartbeat timestamp is older than 600 seconds, or whose heartbeat token is no longer `native_app`. These are batch-flagged as `is_soft_dropped = 1` and pushed to the asynchronous firewall worker queue.

#### L2 Physical Deauthentication — `hotspot_l2_kick_client()`
When a session is revoked (`authorized=false`), the firewall module issues a Layer 2 Wi-Fi deauthentication frame directly to the client via the kernel driver:

```
iw dev $iface station del $mac
# or fallback:
hostapd_cli -i $iface deauthenticate $mac
```

This is a physical-layer forced disconnection. The client's Wi-Fi stack drops the link, the OS detects the reconnection, and the captive portal detection engine immediately re-triggers — without requiring any user action. The deauth is executed non-blocking in the background to prevent the PHP process from stalling.

#### Firewall Architecture — Custom iptables Chains

All firewall rules operate in isolated, app-owned chains to prevent interference with existing Android tethering rules:

| Chain | Table | Purpose |
|---|---|---|
| `CP_NAT` | `nat PREROUTING` | Default-deny portal redirect (HTTP→:8080, DNS→blind resolver) |
| `CP_FILTER` | `filter FORWARD` | Per-MAC/IP REJECT for unauthorized clients |
| `CP_NODOT` | `filter FORWARD` | Global block of DNS-over-TLS (port 853) and well-known DoH endpoints |
| `NOC_ACCT` | `filter FORWARD` | Parent accounting chain, jumps to per-voucher `ACCT_V_{id}` sub-chains |
| `ACCT_V_{id}` | `filter FORWARD` | Per-session byte counter chain for bandwidth accounting |
| `CP_SPEED` | `filter FORWARD` | Speed limiting chain hook |
| `CP_MANGLE` | `mangle POSTROUTING` | TTL normalization (set to 64) for anti-tethering detection |
| `CP_FILTER_V6` | `ip6tables filter` | Total IPv6 block via `icmp6-port-unreachable` |

**Routing Override:** A high-priority `ip rule` (`priority 15000`) forces all traffic from the hotspot interface into the `main` routing table, preempting OEM tables 1028/97/98 that Android uses for its own tethering subsystem.

**DNS Architecture — Two Modes:**
The custom PHP DNS server (`tools/captive_dns_server.php`) operates in two modes on separate ports:
- **Captive mode** (blind resolver port): Responds to all DNS queries with the gateway IP, trapping all HTTP traffic into the captive portal.
- **Managed mode** (standard port): Acts as a transparent forwarding resolver for authorized clients, applying downloaded domain blocklists (porn, gambling, admin categories) and logging all DNS queries to the traffic log.

**DoH/DoT Suppression:** The `CP_NODOT` chain rejects DNS-over-TLS (TCP/UDP port 853) with `tcp-reset`, and blocks all HTTPS access to 14 known public DoH providers (Google, Cloudflare, Quad9, etc.). The `tcp-reset` response is deliberate — it causes an instant TLS failure that triggers HTTP fallback on Android 11+ and iOS 14+ captive portal detection, rather than signaling the network as "administratively blocked" which would suppress the captive portal notification entirely.

---

### 3.3 Smart Storage & Hardware Telemetry

#### USB Hybrid Storage Wizard
At startup, `start.sh` executes a multi-pass storage candidate discovery scan across four storage namespaces:

1. **Internal storage** — application root filesystem
2. **Raw SD card** — `/mnt/media_rw/<UUID>` (root-escalated `df` read via `su -c`)
3. **FUSE SD card** — `/storage/<UUID>` (FUSE overlay of the same physical card)
4. **Adoptable storage** — `/mnt/expand/<UUID>` (SD card formatted as internal)
5. **USB OTG drives** — hardcoded paths + wildcard scan of `/mnt/*usb*`

Each candidate is qualified by minimum free space (>50 MB), filesystem type detection via `/proc/mounts`, and deduplication. If multiple candidates exist, an interactive menu with a 30-second timeout is presented. The selected path is exported as `OFFLINE_STORAGE_PATH` and injected into both the FastAPI server and the PHP portal at launch.

The FastAPI media server uses a runtime setting (`storage_mode`: `internal` | `usb`) backed by the local SQLite settings table to dynamically switch the active upload root, enabling hot-switching between storage backends without restart.

#### Live Hardware Telemetry (FastAPI)
The FastAPI local cloud admin dashboard maintains a real-time telemetry loop running every 2 seconds via an `asyncio` background task:

| Metric | Source |
|---|---|
| **CPU %** | `psutil.cpu_percent()` (3-sample rolling average) |
| **RAM %** | `psutil.virtual_memory().percent` (3-sample rolling average) |
| **CPU Temperature** | `psutil.sensors_temperatures()` → label heuristic (`cpu`, `soc`, `core`, `cluster`) |
| **Surface Temperature** | Board/battery sensors, or 60% of CPU temp as a calibrated estimate |
| **DB Latency (ms)** | `DBTimerMiddleware` intercepts every request cycle, aggregated per interval |
| **Battery %** | `psutil.sensors_battery()` |
| **Disk I/O** | `psutil.disk_io_counters()` delta per second |
| **Network I/O** | `psutil.net_io_counters()` delta per second |
| **Active WebSocket connections** | Live count from `ConnectionManager` |

All telemetry is broadcast over WebSocket to all connected admin dashboard clients simultaneously. A `DBTimerMiddleware` ASGI layer intercepts every HTTP request cycle to record response latency, which is aggregated and pushed as the `db_latency` metric.

The PHP hotspot admin panel independently reads hardware state by querying `/sys/class/thermal/` kernel nodes and `/proc/meminfo` for CPU temperature and RAM statistics.

---

## 4. The Gamification & Loyalty Engine

The loyalty system is a consumable points mechanism designed to reward repeat customers with free internet time while providing safeguards against double-redemption and concurrent session abuse.

### Points Accumulation
Each internet package carries a configurable point value (e.g., 24h = 1 point, 72h = 3 points, 168h = 7 points). Points are stored as `total_purchases` on the `users` table and increment on every paid voucher activation. The loyalty threshold (default: 10 purchases) is a runtime-configurable system setting.

### Gift Calculation — `calculate_and_award_gifts()`
The award calculation is strictly deterministic, not additive:

```
earned_gifts  = floor(total_purchases / loyalty_threshold)
pending_gifts = COUNT(*) FROM gifts WHERE mac = ? AND status IN ('pending', 'redeeming')
new_gifts     = MAX(0, earned_gifts - pending_gifts)
```

This formula is idempotent. It can be called at any time without danger of double-awarding — gifts are computed from the total purchases ledger, not from a delta. The `redeeming` status acts as a pessimistic lock: a gift that has entered the redemption transaction but not yet been fully committed counts against the pending total to prevent race conditions.

### Gift Redemption — `redeem_gift()`
The redemption flow enforces the following invariants atomically within a database transaction:

1. **Single-use enforcement:** The gift row is selected `FOR UPDATE` (via `UPDATE ... WHERE status = 'pending'` with `rowCount() === 1` check). If two concurrent requests attempt to redeem the same gift, exactly one will succeed.
2. **No concurrent active session:** A check against active (non-expired, non-voided) vouchers prevents gift activation while another package is running. The user must wait until their current session expires.
3. **Pessimistic lock state:** The gift is immediately transitioned to `status = 'redeeming'` before the voucher is created. Any failure after this point leaves the gift in the lock state, which a scheduled cleanup can resolve.
4. **Points deduction:** On successful redemption, `total_purchases` is decremented by exactly `loyalty_threshold`, consuming the earned gift. This ensures the next gift cycle begins cleanly from the remainder.
5. **Firewall reconciliation:** `SessionManager::reconcileClientAccess()` is called immediately after commit to push the new authorized state to the kernel.

---

## 5. The Local Cloud (Offline Intranet)

The offline intranet runs as a separate process (`local_server/app.py`) on port 8090. It is reachable by all connected clients — authorized or not — via a walled-garden `iptables` ACCEPT rule (`-p tcp --dport 8090 -j ACCEPT`) inserted at the top of the NAT `PREROUTING` chain, bypassing all captive portal redirection logic. This means users can browse the entire offline library without purchasing any internet time.

### Media Hub (VOD)
A structured file library organized across 5 top-level categories and 22 subcategories:
- **Videos:** Movies, series, anime, tutorials, YouTube archives, short clips
- **Audio:** Quran, music, podcasts, audiobooks
- **Apps & Games:** Android APKs, PC software, games
- **Images:** Memes, wallpapers, designs, general photos
- **Documents:** Books, study materials, research papers

Files are served as chunked streaming responses via FastAPI's `StreamingResponse` with byte-range support, enabling native HTML5 video and audio seeking without full-file downloads. A global async token-bucket throttle (`_throttle()`) caps all simultaneous file streams at 1 MB/s aggregate, protecting the Wi-Fi half-duplex chip from I/O saturation while keeping HTML page loads instantaneous.

Upload throttling is also enforced at the ASGI middleware layer (`UploadThrottleMiddleware`), which wraps the `receive` callable to pulse incoming data chunks rather than accepting burst uploads that would stall the event loop.

### Local Social Network
A full-featured social platform with the following capabilities:
- User registration, authentication, and profile management (avatar, bio, display name)
- Post feed with text and image content; real-time like and comment system
- Stories with 24-hour visibility, view tracking, and like reactions
- Real-time group chat via WebSocket (`ConnectionManager` with broadcast)
- User notification system (likes, comments, follows, post interactions)
- Admin moderation panel: user bans, upload permission grants, content management
- Owner role system with granular permission sets serialized as JSON

All social content is stored in `local_platform.db` (aiosqlite, WAL mode), completely isolated from the hotspot billing database.

### Content Administration
The FastAPI admin panel provides:
- Live hardware telemetry dashboard (WebSocket-pushed, 2-second refresh)
- File manager: multi-category upload, inline media preview, deletion
- USB/SD storage mode toggle with live path validation
- User management: ban, promote, grant large-upload permission
- Activity log viewer with pagination
- Maintenance mode toggle (blocks all non-admin access)

---

## 6. Data Layer & Security Architecture

### SQLite Configuration — Tuned for Android eMMC
Both database instances (hotspot and local platform) are configured identically for stability on Android storage:

```sql
PRAGMA journal_mode = WAL;          -- Concurrent readers + single writer
PRAGMA busy_timeout = 15000;        -- 15-second lock wait before error
PRAGMA synchronous = NORMAL;        -- Crash-safe without full fsync on every write
PRAGMA temp_store = MEMORY;         -- Avoid temp file creation on flash storage
PRAGMA cache_size = -10000;         -- 10 MB page cache
PRAGMA mmap_size = 268435456;       -- 256 MB memory-mapped I/O
PRAGMA wal_autocheckpoint = 100;    -- Checkpoint every ~400 KB to prevent WAL bloat
```

A storage asphyxiation guard (`disk_free_space() < 10MB`) halts the database layer with a `503 Service Unavailable` response before any write operation can cause partial commits or WAL file corruption.

### Admin Authentication
The admin panel implements a hardened login system:
- Passwords are stored as `bcrypt` hashes (`PASSWORD_DEFAULT`, cost factor ~11)
- Session tokens regenerated on login (`session_regenerate_id(true)`)
- Sessions expire after 30 minutes of inactivity (`SESSION_LIFETIME`)
- Brute-force protection: 5 failed attempts triggers a 30-minute IP lockout backed by the `login_attempts` table
- Lockout is enforced on both GET and POST — the login form is hidden entirely during lockout, replaced by a live countdown timer
- Passive cleanup of expired lockout records runs on every login attempt, adding zero background overhead

### Firewall Security Invariants
- **Fail-closed by design:** All iptables chains are initialized with a catch-all `REJECT --reject-with icmp-port-unreachable` rule. Unauthorized traffic is rejected, not dropped — this matters because DROP causes the OS captive portal engine to time out and mark the network as broken, suppressing the captive portal notification.
- **HTTPS forced to `tcp-reset`:** Port 443 is rejected with TCP reset (not ICMP) for unauthorized clients, causing an immediate TLS failure that triggers OS captive portal HTTP fallback on all modern Android and iOS versions.
- **Anti-tethering (TTL/HL mangling):** The `MANGLE` chain normalizes TTL to 64 on egress, defeating TTL-based hotspot sharing detection by cellular networks.
- **IPv6 fully disabled on the AP interface:** IPv6 is suppressed via `/proc/sys/net/ipv6/conf/{iface}/disable_ipv6` to prevent clients from bypassing the captive portal via IPv6 connectivity. The original kernel state is preserved and restored on shutdown.

---

## 7. Deployment & Startup Sequence

The system bootstraps via a single entry point: `start.sh`. The startup sequence is strictly ordered:

**Phase 1 — Storage Wizard:**
Interactive or automatic selection of storage backend for the offline platform. Multi-pass scan of internal, SD card (raw + FUSE), adoptable, and USB OTG mount points.

**Phase 1.5 — Bandwidth Configuration:**
Interactive prompt for default download speed and burst reservoir capacity. Values are written to `config_override.php` so they persist across PHP worker restarts without re-running the wizard.

**Phase 2 — FastAPI Server Launch:**
`uvicorn` is started via `setsid` (process session leader) and `nohup`, making it immune to shell exit. The process is granted OOM exemption (`oom_score_adj = -1000`) to prevent Android's Low Memory Killer from evicting it during memory pressure.

**Phase 2.5 — APK Auto-Updater:**
A background shell loop polls GitHub Releases every 24 hours for a new `app-release.apk` and atomically replaces the local copy on success.

**Phase 3 — Hotspot Supervisor Handoff:**
`exec bin/hotspot start` replaces the shell process entirely, keeping the FastAPI process alive as a sibling. The supervisor then:
1. Validates and configures the network interface
2. Attaches the fail-closed firewall (`setup_fail_closed_firewall()`)
3. Starts the PHP built-in server with 4 workers
4. Launches the custom DNS server in captive and managed modes
5. Starts `ghost_radar.sh` and the async firewall worker (`fw-worker.php`)
6. Enters the main monitoring loop

**Shutdown:**
`SIGTERM`/`SIGINT` triggers `cleanup_app_firewall_rules()`, which atomically removes all custom chains and hooks, restores the network interface IPv6 state, and cleans the TC bandwidth shaper — leaving the kernel in a clean state.

---

## 8. Screenshots Gallery

> _Replace placeholder paths with actual screenshot files placed in `assets/screenshots/`._

### Captive Portal — User Login
![Captive Portal](assets/screenshots/captive_portal.png)

### User Status & Gamification Dashboard
![User Status & Gamification](assets/screenshots/user_status_gamification.png)

### Admin Dashboard — Hardware Telemetry
![Admin Dashboard Hardware Telemetry](assets/screenshots/admin_hardware_telemetry.png)

### Local Social Network
![Local Social Network](assets/screenshots/local_social_network.png)

### Financial Reports & Revenue Analytics
![Financial Reports](assets/screenshots/financial_reports.png)

### Local Cloud Media Hub
![Local Cloud Media Hub](assets/screenshots/local_cloud_media_hub.png)

---

## License

See [`legal.txt`](legal.txt) for full terms and conditions.

---

*System version: `APP_DB_VERSION 2.1` — Architecture documented from source audit of `/root/wifi-4.5`.*
