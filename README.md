# OalendarBridge

> Automatically move events created in OPPO / ColorOS Calendar into a DAVx⁵-managed CalDAV / iCloud calendar.

**Current stable: v1.1.1 (`versionCode 6`)** — verified on real OPPO hardware (PMX110 / ColorOS 17 / Android 17)

---

## The problem it solves

Events created in OPPO Calendar land in the phone's **LOCAL** calendar, while what you actually want is for them to live in a **DAVx⁵-managed CalDAV / iCloud** calendar so they sync to your other devices.

There is a second, much less obvious problem: **when you edit an event that has already been synced to DAVx⁵, ColorOS changes the content but does not produce a `DIRTY=1` dirty flag.** DAVx⁵ therefore has no idea anything changed and never uploads the edit — even triggering a manual DAVx⁵ sync does not help.

OalendarBridge exists to fix both.

## How it works

### 1. New events — clone-then-delete

```
OPPO creates an event in the Local calendar
        ↓
Read the source event → INSERT a brand-new event into the target DAVx⁵ calendar
        ↓
Copy fields / reminders / attendees
        ↓
Confirm the new event was created successfully → delete the Local source event
```

It deliberately does **not** use the common shortcut of rewriting `Events.CALENDAR_ID` to move an event between calendars. That approach looks correct on screen, but later edits do not sync reliably through DAVx⁵, and the Android documentation warns that it can cause sync-adapter problems. The event must be a **genuine clone with the source deleted afterwards**, so that DAVx⁵ owns its own sync identity. Server-owned metadata such as `_SYNC_ID` is never copied.

### 2. Edited events — fingerprint comparison + DIRTY repair

```
Store a content fingerprint for every managed event
        ↓
Re-read the event on each pass and compare it with the stored fingerprint
        ↓
Content changed, DIRTY == 0, and the event was not deleted
        ↓
Perform a "same-value ContentResolver update"
(usually TITLE, falling back to another safe field such as DTSTART)
        ↓
CalendarProvider runs its own dirty-state bookkeeping → DAVx⁵ notices and uploads
```

The important detail: the app **never writes `DIRTY = 1` directly**. It triggers CalendarProvider's normal behaviour through an ordinary update.

### 3. Deleted events — left to DAVx⁵

Real-device testing confirmed that `OPPO delete → CalendarProvider → DAVx⁵ → iCloud` already works correctly on its own, so OalendarBridge **intentionally implements no custom deletion sync**.

## Master sync switch semantics

This is not a "pause and catch up later" switch.

| State | Behaviour |
|---|---|
| **OFF** | New events and edits made while OFF are **ignored permanently** and never backfilled |
| **ON** | Only changes that happen while ON are processed |
| **Switching to OFF** | One final check runs first, so a change that genuinely happened while ON — but that JobScheduler had not fired for yet — is not lost |
| **Back to ON** | A new baseline is established; nothing from the OFF period is replayed |
| **After a reboot** | **Automatic sync returns to OFF** |

> Sync becoming OFF after a reboot is a **product rule, not a bug**. ColorOS freezing or force-stopping the app does not change the state; only a reboot or you turning the switch off does.

## Requirements

- An **OPPO / ColorOS** phone (all development and verification happened on ColorOS; other vendors are untested)
- Android 8.0 or later (`minSdk 26`)
- **DAVx⁵** installed and configured, with at least one **writable** CalDAV / iCloud calendar
- Calendar read/write permission granted

## Install and use

**Install:** download the APK from [Releases](../../releases) and install it (see the signing note under *Known limitations*).

**Use:**

1. Open the app and grant the calendar permission
2. **Leave automatic sync OFF** and select the source calendar (`accountType = LOCAL`) and the target calendar (`accountType = bitfire.at.davdroid`, writable)
3. Turn the master sync switch ON
4. Allow the app in ColorOS: **auto-start**, **background running / background activity**, and **background pop-up** ("允许后台弹出界面")
5. Use OPPO Calendar normally from then on — OalendarBridge does not need to stay in the background

> While automatic sync is ON, the source and target calendar selectors are **locked**. This is by design: turn the switch off first if you need to change them.

## Known limitations

- **Verified on OPPO / ColorOS only.** The architecture is deliberately shaped around observed ColorOS behaviour; it has not been generalised to other vendors.
- **Automatic sync returns to OFF after a reboot** and must be turned back on manually.
- **APKs are currently debug-signed.** They install fine for testing, but if a properly signed release is published later, existing users must uninstall and reinstall (which wipes ColorOS permissions and app data).
- The application ID is still the placeholder `com.example.oalendarbridge`.
- The change-detection fingerprint **does not yet cover reminders or attendees**.
- Creating a single event typically triggers **2–4 job runs** (the migration itself writes a new row and re-triggers the content observer). This is inherent to the design and does not affect correctness.
- The watchdog job is a **safety net**, not a second sync path. If ColorOS freezes the app's background jobs as a whole, the watchdog will not run either, and only opening the app recovers the situation.

## Troubleshooting

The app has a **collapsed-by-default "Diagnostics" card** (tap the title to expand). It shows the build label, an environment summary, job state and recent log lines, and offers **one-tap copy of the whole payload** to the clipboard.

When something goes wrong: expand the card → copy → share the payload; that is enough to diagnose most problems. Read it in this order:

1. **The sync switch** — if it is OFF, stop here; nothing else matters
2. **System uptime** — a very short uptime means the phone rebooted (so OFF is expected)
3. **Job / pending reason** — `已注册，等待触发` (registered, waiting for trigger) with `CONSTRAINT_CONTENT_TRIGGER` is the healthy resting state
4. **Read the log bottom-up** — look for whether `作业被系统叫醒` (woken by the system) appears. That single line decides whether the content trigger was delivered at all

Some things that look like failures but are normal: `stopReason=CANCELLED_BY_APP` at the end of almost every run is the expected consequence of the "finish, then register a replacement job" design; `QUOTA` / `DEVICE_STATE` appearing in the same second a job just finished with a cumulative duration of `0m` is JobScheduler bookkeeping noise, not quota throttling.

## Project layout

```
app/src/main/java/com/example/oalendarbridge/
├── SyncEngine.kt              (2478 lines)  Core: migration + fingerprint / DIRTY repair
├── MainActivity.kt            (542 lines)   Compose UI + diagnostics panel
├── Diagnostics.kt             (536 lines)   Ring-buffer log (400 lines) + system-side job state
├── CalendarSyncScheduler.kt   (203 lines)   JobScheduler registration / cancellation / watchdog
├── CalendarSelectionStore.kt  (171 lines)   Calendar identity persistence and re-resolution
├── CalendarSyncJobService.kt  (161 lines)   Primary job (content-triggered)
├── WatchdogJobService.kt      (92 lines)    Hourly safety net: re-arms a lost primary job
└── BootReceiver.kt            (51 lines)    Reboot → sync OFF; app upgrade → may restore
```

**Background mechanism:** no foreground service, no persistent notification, no polling. A single `JobScheduler` job plus a `CalendarProvider` content trigger (`Events.CONTENT_URI`, `FLAG_NOTIFY_FOR_DESCENDANTS`, content-update delay of about 1 s). Since v1.1 there is also a low-frequency watchdog job (job ID `26090103`) that runs hourly and only does something if the **primary job has genuinely disappeared from the queue** — in that case it re-registers it and runs exactly one catch-up check.

## Building

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew clean :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Installing over an existing build (**do not uninstall casually** — it clears ColorOS permissions and app data):

```bash
"$HOME/Library/Android/sdk/platform-tools/adb" install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

## Read this before changing anything

**`PROJECT_HANDOFF_OalendarBridge.md` is the authoritative engineering record for this project.** It documents the findings of every round of real-device diagnosis.

Some implementations here look "less standard" than a textbook Android calendar sync. They are not technical debt — they exist to work around confirmed ColorOS behaviour:

- the Local calendar's default creation behaviour
- aggressive background execution restrictions
- the missing dirty state after editing DAVx⁵ events
- sync-adapter identity handling

**Unless you are explicitly asked to, do not refactor, simplify or "standardise" the stable core above. Real-device correctness takes priority over architectural elegance.** See `WORKBUDDY_RULES.md` and sections 21 / 28 of the handoff document.

The regression checklist is in section 19 of the handoff document, with a Chinese, item-by-item version in `README_稳定基线.txt`.

### Versions and rollback points

| Version | versionCode | Tag | Notes |
|---|---|---|---|
| v1.0 baseline | 1 | `v1.0-stable-coloros` | Permanent rollback point |
| **v1.1.1 (current)** | **6** | **`v1.1.1-stable-coloros`** | Watchdog + diagnostics panel; sync core identical to v1 |

`SyncEngine.kt` has had **zero diff** across every iteration since v1, and `CalendarSelectionStore.kt` has likewise never been touched — please keep that the default expectation.

## License

Licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE) for the full text.

Copyright © 2026 Anosoyo69

---

## Notes on the docs

This README and `PROJECT_HANDOFF_OalendarBridge.md` are in English; `README_稳定基线.txt` and `WORKBUDDY_RULES.md` are in Chinese. In-app strings and log lines are in Chinese.
