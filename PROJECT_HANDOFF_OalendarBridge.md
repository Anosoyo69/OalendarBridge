# OalendarBridge — Project Handoff

> Purpose: hand this Android project to a new GPT / coding agent without losing the engineering decisions already validated on a real OPPO / ColorOS device.
>
> Status: **v1.1.1 is the current stable baseline** (`main`, tag `v1.1.1-stable-coloros`, `versionCode 6`). The v1 sync core is unchanged and still passes its original OPPO / ColorOS real-device checks; v1.1.1 adds only a job watchdog and an in-app diagnostics panel. `v1.0-stable-coloros` (versionCode 1) remains the rollback point.
>
> Priority rule for any future agent: **do not “clean up”, “standardize”, or refactor the core sync behavior unless explicitly asked. Several non-obvious choices below exist because standard Android behavior did not work correctly on the target OPPO / ColorOS device.**

---

# 1. Project Goal

OalendarBridge is a small Android app designed to solve a specific OPPO / ColorOS calendar interoperability problem.

## Problem

On the target OPPO / ColorOS device:

- OPPO Calendar creates new events in a **LOCAL** calendar.
- The user actually wants those events to end up in a **DAVx⁵-managed CalDAV / iCloud calendar**.
- OPPO / ColorOS background behavior is aggressive and can interfere with normal app execution.
- OPPO Calendar can also modify DAVx⁵-backed events **without causing the expected CalendarProvider dirty state**, so DAVx⁵ may fail to upload edits.

## Desired behavior

When OalendarBridge automatic sync is ON:

- New OPPO Local events should be migrated into the selected DAVx⁵ calendar.
- Later edits to OalendarBridge-managed DAVx⁵ events should propagate to iCloud / CalDAV.
- Event deletion should continue to use native DAVx⁵ / CalendarProvider behavior.
- The app should continue working even after the user swipes it away from Recent Apps, as long as the required ColorOS permissions are granted.

When automatic sync is OFF:

- New events created while OFF must be ignored permanently.
- Event modifications made while OFF must also be ignored permanently.
- Turning sync back ON must **not backfill** anything that happened while OFF.

After a phone reboot:

- Automatic sync must default to OFF.

---

# 2. Current Stable Project

The current working repository path is:

`/Users/cic/Desktop/OalendarBridge`

(The project was originally developed as `OalendarBridgeClean` under
`~/AndroidStudioProjects/OalendarBridgeClean`. Do not assume either exact path
on another machine.)

The project is Kotlin + Jetpack Compose.

## Version map

| Version | versionCode | Branch | Tag | Notes |
|---|---|---|---|---|
| v1 (original baseline) | 1 | — | `v1.0-stable-coloros` | OPPO / ColorOS real-device validated sync core |
| diagnostic iterations | 2 / 3 / 4 | `diag/coloros17-job-trigger` | — | logging only, **not** release builds |
| v1.1 (watchdog, no diagnostics UI) | 5 | `release/v1.1-selfheal` | — | superseded |
| **v1.1.1 (current)** | **6** | `release/v1.1.1` + `main` | **`v1.1.1-stable-coloros`** | watchdog + collapsed diagnostics panel |
| test build (watchdog + diagnostics) | 4 | `test/v1.1-diagtest` | — | used for the clean-install reproduction, not for release |

The version numbers are deliberately ordered so that every build can be
installed over the previous one **without uninstalling**: diagnostics test (4)
< v1.1 (5) < v1.1.1 (6).

Uninstalling is destructive on ColorOS: it clears the app's auto-start /
background-run / background-popup permissions, the runtime calendar permission,
and all stored app data.

The stable version has been compiled and tested successfully on a real
OPPO / ColorOS device.

---

# 3. Critical Product Rules

These rules are intentional and must be preserved.

## 3.1 Master sync toggle

There is a master automatic-sync switch.

### OFF

- No new Local event created while OFF may later be migrated.
- No modification made while OFF may later be replayed.
- Re-enabling sync establishes a new baseline.

### ON

- Only changes that happen while ON are eligible for processing.

### Turning OFF

Before actually disabling sync, the app may perform one final sync check so that an event that truly happened while the switch was still ON is not lost merely because JobScheduler had not fired yet.

### Reboot

On `BOOT_COMPLETED`:

- sync must become OFF
- the JobScheduler job must be cancelled / not restored

This is intentional.

### App upgrade

`MY_PACKAGE_REPLACED` may restore scheduling if the sync preference remains ON.

This differs from a full reboot.

---

# 4. Calendar Selection — Do Not Hard-Code IDs

Calendar IDs are not stable across phones, accounts, reinstalls, or provider changes.

The app now allows explicit selection of:

- source Local calendar
- target DAVx⁵ calendar

The selection system must not rely on only `calendarId`.

Stable identity fields include:

- `calendarId`
- `accountType`
- `accountName`
- `displayName`

## Resolution logic

1. If stored ID still exists and identity matches, use it.
2. If ID changed, search for an exact identity match by account type / account name / display name.
3. If exactly one matching calendar is found, re-bind to the new ID.
4. If the calendar is missing or cannot be uniquely identified:
   - do not guess
   - turn sync OFF
   - require the user to re-select

## Candidate types

Source:
- `accountType == "LOCAL"`

Target:
- `accountType == "bitfire.at.davdroid"`
- writable access level

## UI rule

Source and target calendar selectors are locked while automatic sync is ON.

The user must turn sync OFF before changing calendars.

---

# 5. New Event Migration — Important Architecture

## DO NOT use the old migration approach

An early implementation modified an existing Local event by changing:

`Events.CALENDAR_ID`

from the Local calendar to the DAVx⁵ calendar.

This looked correct visually, but later edits did not sync reliably through DAVx⁵.

Android documentation also warns that moving an event between calendars this way can cause sync-adapter behavior problems.

## Current correct architecture

For a new Local event:

1. Read the Local event.
2. INSERT a new event into the selected DAVx⁵ target calendar.
3. Copy relevant event fields.
4. Copy reminders.
5. Copy attendees.
6. Only after the new target event is successfully created, delete the original Local event.
7. Let DAVx⁵ own all sync metadata.

This means the resulting event is a genuine DAVx⁵ event, not a Local event whose calendar ID was rewritten.

## Event fields currently copied

Core fields include:

- TITLE
- EVENT_LOCATION
- DESCRIPTION
- DTSTART
- DTEND
- EVENT_TIMEZONE
- EVENT_END_TIMEZONE
- DURATION
- ALL_DAY
- RRULE
- RDATE
- EXRULE
- EXDATE
- ACCESS_LEVEL
- AVAILABILITY

Reminders:

- MINUTES
- METHOD

Attendees:

- name
- email
- relationship
- type
- status

For all-day events, UTC handling is used.

Do not copy DAVx⁵ sync metadata such as:

- `_SYNC_ID`
- `DIRTY`
- sync adapter private fields
- other server-owned sync identity fields

DAVx⁵ must create and manage its own server identity.

---

# 6. OPPO Modification Bug and the DIRTY Repair

This is one of the most important discoveries in the project.

## Real-device behavior

After a Local event was correctly migrated into a true DAVx⁵ event:

- it uploaded to iCloud correctly
- it had a valid `_SYNC_ID`

However, when editing that event in OPPO Calendar:

- the event content changed
- `calendar_id` remained the DAVx⁵ calendar
- `_SYNC_ID` remained valid
- but `DIRTY` stayed `0`

Therefore DAVx⁵ did not detect the edit as a local modification.

Even manually triggering DAVx⁵ sync did not upload the change.

## Diagnostic proof

A normal Android ContentProvider update that rewrote an existing value to the same value caused CalendarProvider to mark the event dirty correctly.

After that, DAVx⁵ uploaded the OPPO edit.

## Current workaround

OalendarBridge maintains fingerprints / snapshots for managed DAVx⁵ events.

On each sync pass:

1. Read the current event content.
2. Compare it with the stored fingerprint.
3. If the content changed:
   - and `DIRTY == 0`
   - and event is not deleted
4. Perform a **normal same-value ContentResolver update**:
   - normally TITLE if available
   - otherwise another safe field such as DTSTART
5. CalendarProvider then performs its normal dirty bookkeeping.
6. DAVx⁵ detects the pending edit and uploads it.

## Very important

Do **not** directly write `DIRTY = 1`.

The app should trigger CalendarProvider’s normal behavior through a standard event update.

This workaround has been verified successfully on the real OPPO device.

---

# 7. Managed Event Tracking

OalendarBridge stores / tracks DAVx⁵ events that it created or manages.

The system uses:

- managed event IDs
- content fingerprint / snapshot per managed event

It may also discover prior OalendarBridge-created DAV events via:

`Events.MUTATORS`

containing the application package name.

## Re-baselining rule

When automatic sync is turned ON:

- existing current event content becomes the baseline
- changes that happened while sync was OFF must not be interpreted as new modifications

This is required to preserve the “OFF means ignore forever” product rule.

---

# 8. Modification Fingerprint

The current body-event fingerprint includes fields such as:

- TITLE
- EVENT_LOCATION
- DESCRIPTION
- DTSTART
- DTEND
- EVENT_TIMEZONE
- EVENT_END_TIMEZONE
- DURATION
- ALL_DAY
- RRULE
- RDATE
- EXRULE
- EXDATE
- ACCESS_LEVEL
- AVAILABILITY

Reminders and attendees may not yet be included in the modification fingerprint.

Do not silently expand the fingerprint without understanding the sync consequences.

---

# 9. Event Deletion

Do not implement custom OalendarBridge deletion synchronization unless a future real-device bug proves it is necessary.

Real-device testing confirmed:

OPPO Calendar deletion  
→ CalendarProvider / DAVx⁵  
→ iCloud deletion

already works correctly.

Therefore deletion is intentionally left to the native DAVx⁵ sync path.

---

# 10. Background Execution Architecture

The stable implementation does **not** use a permanent foreground service.

An earlier idea used persistent notifications / foreground service, inspired by DAVx⁵.

This was eventually unnecessary.

## Stable approach

Use a single JobScheduler job with a CalendarProvider content trigger.

Job ID used during development:

`26090101`

The Job uses:

`JobInfo.TriggerContentUri(CalendarContract.Events.CONTENT_URI, FLAG_NOTIFY_FOR_DESCENDANTS)`

Typical configuration:

- trigger content update delay: about 1000 ms
- max trigger delay: about 5000 ms

After a content-trigger Job runs, the Job must be scheduled again so it can continue monitoring future changes.

## v1.1 addition — the watchdog job (job ID `26090103`)

`getPendingJob()` proved that the content-trigger job can be **missing from the
queue entirely**. In v1 the only three things that ever re-registered it were:

- the app UI loading (`MainActivity.reload()` → `ensureScheduled()`)
- the master switch being flipped
- `MY_PACKAGE_REPLACED` (i.e. installing a new APK over the old one)

So once the job was dropped, nothing re-armed it until the user opened the app.
This was observed as a fresh-install symptom on ColorOS 17 / Android 17: new
calendar events were not migrated until the app had been opened once.

v1.1 adds a low-frequency safety net. It is **not** a second sync path and it
does not change any sync semantics:

- `WatchdogJobService` — a periodic job, `WATCHDOG_INTERVAL_MS = 60 * 60 * 1000`
  (1 hour), no extra constraints.
- Behaviour, in order:
  1. sync OFF → log and call `CalendarSyncScheduler.cancel()`, do nothing else
  2. primary job still in the queue → **do nothing at all** (so it can never
     race the primary job or double-migrate)
  3. primary job actually lost → `ensureScheduled()` it, then run **exactly one**
     catch-up `SyncEngine.checkAndMigrateNewEvents()` — the same entry point the
     app UI uses, so OFF-period changes are still not backfilled
- `CalendarSyncScheduler`: `WATCHDOG_JOB_ID = 26090103`,
  `ensureWatchdogScheduled()` (private, no-op if already queued),
  `isPrimaryJobPending()` (read-only helper for the watchdog).
  `ensureScheduled()` arms the watchdog; `cancel()` cancels it too, so the OFF
  switch and the reboot rule stay strictly symmetric — a periodic job is only
  removed by `JobScheduler.cancel()`, never by its own completion.
- `AndroidManifest.xml`: one additional `<service>` declaration
  (`BIND_JOB_SERVICE`, `exported="false"`). Additive only.

Caveat to keep in mind: the watchdog only helps when **a single job** was lost.
If ColorOS freezes the app's background jobs as a whole, the watchdog will not
run either — in that case only opening the app recovers the situation.

## Why this works on OPPO

Real-device testing confirmed that this JobScheduler design survives swiping the app away, but only when appropriate ColorOS permissions are granted.

Note (2026-09, ColorOS 17 / Android 17): the content trigger was observed firing
correctly with the app swiped away from Recent Apps. The three ColorOS switches
are therefore not required for the trigger mechanism itself, but they should
still be recommended because they affect how aggressively the app's jobs are
restricted. See section 29 for the full diagnostic reasoning.

---

# 11. OPPO / ColorOS Permissions

These ColorOS permissions/settings were important in real-device testing:

- allow auto-start
- allow background activity / background running
- allow background popup / “允许后台弹出界面”

The last one was especially important.

Without the proper ColorOS background permissions, the Job may exist but not behave as expected.

The problem was not fundamentally JobScheduler itself.

> Later note (2026-09): on ColorOS 17 the content trigger worked with the app
> swiped away, so these switches are not a prerequisite for the trigger itself.
> They still matter because they govern how aggressively ColorOS restricts the
> app's jobs. Keep recommending them to users; just do not treat them as the
> explanation for a specific failure without checking the diagnostics first
> (section 29).

---

# 12. JobScheduler Diagnostics from Real Device

During successful testing, `dumpsys jobscheduler` showed conditions such as:

- CONTENT_TRIGGER satisfied
- BACKGROUND_NOT_RESTRICTED
- DEVICE_NOT_DOZING
- WITHIN_QUOTA
- Doze whitelisted: true
- Standby bucket: EXEMPTED

The important conclusion:

ColorOS app-management permissions were the primary blocker.

> Later note (2026-09, ColorOS 17 / Android 17): this conclusion was about the
> original installation. On the newer device the content trigger fired correctly
> with the app removed from Recent Apps and with no permanent background
> presence, and the real failure turned out to be **the job missing from the
> queue** plus **the master switch being OFF after a reboot**. Treat this section
> as the historical record of the first diagnosis; see section 29.

---

# 13. Boot / Package Receiver Behavior

Manifest should include the necessary boot permission and receiver behavior.

Relevant permission:

`android.permission.RECEIVE_BOOT_COMPLETED`

Receiver behavior:

## BOOT_COMPLETED

- force sync OFF
- cancel JobScheduler

## MY_PACKAGE_REPLACED

- if persisted sync state is still ON, scheduler may be restored

Do not treat full reboot and app upgrade as the same case.

---

# 14. Current Core Kotlin Files

The stable project contains these core files:

- `MainActivity.kt`
- `SyncEngine.kt`
- `CalendarSyncScheduler.kt`
- `CalendarSyncJobService.kt`
- `BootReceiver.kt`
- `CalendarSelectionStore.kt`

Added in v1.1 / v1.1.1 (see sections 10 and 29):

- `WatchdogJobService.kt` — hourly safety net that re-arms a lost primary job
- `Diagnostics.kt` — in-app ring-buffer log + system-side job state readout

A future agent should read these files first.

`SyncEngine.kt` is the most sensitive file. It has had **zero** diff in every
change since v1, including v1.1 and v1.1.1, and that should stay the default
expectation. `CalendarSelectionStore.kt` is likewise untouched so far.

Do not rewrite `SyncEngine.kt` wholesale unless explicitly asked.

---

# 15. Android Manifest Expectations

The app requires:

- READ_CALENDAR
- WRITE_CALENDAR
- RECEIVE_BOOT_COMPLETED

Every JobService must use:

`android.permission.BIND_JOB_SERVICE`

and must be `android:exported="false"`.

Two JobServices are declared as of v1.1.1:

- `CalendarSyncJobService` (the real sync job, content-triggered)
- `WatchdogJobService` (hourly safety net, added in v1.1)

The app does **not** need a foreground-service architecture for its core stable
behavior, and it does not depend on a permanent notification. It also does not
request a battery-optimisation exemption — the exemption seen on the test device
was granted by the system / user, not by the app.

---

# 16. Build Environment Used During Development

Android Studio JDK:

`/Applications/Android Studio.app/Contents/jbr/Contents/Home`

Typical shell build:

```bash
cd ~/AndroidStudioProjects/OalendarBridgeClean

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew clean :app:assembleDebug
```

APK location:

```text
app/build/outputs/apk/debug/app-debug.apk
```

ADB used during development:

```text
$HOME/Library/Android/sdk/platform-tools/adb
```

Install without clearing app data:

```bash
"$HOME/Library/Android/sdk/platform-tools/adb" \
  install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

Do not uninstall/reinstall casually during testing because that may reset ColorOS app permissions and stored app preferences.

---

# 17. Important Real-Device Calendar Example

On the original test phone, the environment at one point contained:

Local calendar:
- `_id = 1`
- displayName = `local account`
- account_name = `local account`
- account_type = `LOCAL`

DAVx⁵ calendar:
- `_id = 6`
- displayName = `Calendar`
- account_type = `bitfire.at.davdroid`

These IDs are only historical examples.

**Never hard-code 1 or 6.**

The app now has calendar selection precisely because IDs differ across users and devices.

---

# 18. Confirmed Real-Device Behaviors

The stable v1 logic has passed practical testing for the core flows.

## Confirmed

- source calendar selection works
- target calendar selection works
- new Local event migrates into a true DAVx⁵ event
- migrated event uploads to iCloud
- later OPPO Calendar modifications upload after OalendarBridge dirty repair
- automatic modification repair works without manually opening DAVx⁵
- deleting a migrated DAVx⁵ event removes it from iCloud through native sync
- JobScheduler can continue functioning after swiping OalendarBridge away
- master sync toggle semantics work
- reboot resets automatic sync to OFF

---

# 19. Regression Test Checklist

Before calling any future build stable, test at minimum:

## A. Calendar selection

With sync OFF:

- source selector appears
- target selector appears
- Local source can be selected
- DAVx⁵ target can be selected

With sync ON:

- both selectors are locked

## B. New event while OFF

1. Sync OFF
2. Create event `OB_OFF_NEW`
3. Wait
4. Confirm it is not migrated
5. Turn sync ON
6. Confirm it still does not get backfilled

## C. New event while ON

1. Sync ON
2. Create `OB_ON_NEW`
3. Confirm:
   - event appears in selected DAVx⁵ / iCloud
   - no duplicate event remains

## D. Modify while ON

Rename:

`OB_ON_NEW`

to:

`OB_ON_NEW_EDIT`

Confirm iCloud updates automatically.

Then modify time and confirm iCloud time updates.

## E. Swipe app away

With sync ON:

1. swipe OalendarBridge from Recent Apps
2. create new event
3. confirm automatic migration still occurs
4. edit that event
5. confirm edit uploads automatically

## F. Delete

Delete a migrated event.

Confirm iCloud deletion occurs.

No OalendarBridge custom deletion logic should be required.

## G. Modification while OFF

1. Existing migrated event exists
2. Sync OFF
3. modify it in OPPO Calendar
4. turn sync ON later
5. OalendarBridge must not treat the OFF-period modification as a new pending bridge change

## H. Reboot

1. Sync ON
2. reboot phone
3. open app
4. confirm sync is OFF

---

# 20. Failed / Abandoned Approaches

These are important because future agents may independently suggest them again.

## 20.1 Direct CALENDAR_ID move

Rejected.

Reason:
- later DAVx⁵ edits did not sync reliably
- sync identity behavior was wrong

Use clone-to-DAVx⁵ + delete-source instead.

## 20.2 Directly setting DIRTY

Do not do this.

Use a normal ContentResolver update and allow CalendarProvider to set sync state correctly.

## 20.3 Foreground service / permanent notification as mandatory architecture

Not necessary for current stable target behavior.

JobScheduler + content trigger + proper ColorOS permissions works.

Do not reintroduce a foreground service merely because it looks more conventional.

## 20.4 Custom deletion sync

Not necessary.

Native DAVx⁵ deletion already works.

## 20.5 Hard-coded source / target calendar IDs

Rejected.

The stable version has explicit selection and identity re-resolution.

---

# 21. Development Safety Rules for a New Agent

A new GPT / coding agent should follow these rules.

## Rule 1 — Preserve stable sync core

Do not alter the following without explicit permission:

- Local → DAVx⁵ INSERT architecture
- source deletion-after-success behavior
- managed-event snapshot behavior
- same-value update dirty repair
- master ON/OFF semantics
- JobScheduler content-trigger architecture
- reboot OFF behavior
- native deletion behavior
- calendar identity resolution

## Rule 2 — Explain before modifying

Before touching core code, state:

1. which files will change
2. why they must change
3. whether the change affects any already-tested behavior

## Rule 3 — Always build after modification

At minimum run:

```bash
./gradlew :app:assembleDebug
```

Prefer:

```bash
./gradlew clean :app:assembleDebug
```

when validating a release candidate.

## Rule 4 — Do not rely only on IDE red lines

Gradle compiler output is the authoritative build result.

## Rule 5 — Keep backups

Before large edits, create a backup or Git commit.

The project previously suffered accidental code loss / overwrite during iterative editing.

## Rule 6 — Prefer full-file edits when working with a non-programmer user

The project owner is not a professional programmer.

When giving manual instructions:

- avoid “insert this around line 237”
- avoid piecemeal patches when possible
- prefer complete files, scripts, or agent-applied changes
- report exactly what changed

---

# 22. Git Baseline — Current State

Git is initialised. The repository state as of v1.1.1:

```text
main                           5d5f147  Promote v1.1.1 to the stable baseline
                               tag: v1.1.1-stable-coloros   (versionCode 6)  <- current
                               tag: v1.0-stable-coloros     (versionCode 1)  <- rollback point
release/v1.1.1                 b0f7b18  v1.1.1: ship the diagnostics panel as a collapsed section
release/v1.1-selfheal          65cc2b1  v1.1: watchdog job that re-arms a lost content-trigger job
test/v1.1-diagtest             35539aa  test build: v1.1 logic + diagnostics  (versionCode 4)
diag/coloros17-job-trigger     684852f  diag1 / diag2 / diag3  (versionCode 2 / 3 / 4)
```

Rules that have served this project well:

- `main` is only advanced after a change has been confirmed on the real device.
- Experimental and diagnostic work goes on its own branch. `v1.0-stable-coloros`
  is never touched, so there is always a known-good rollback.
- Advancing `main` was done with `--no-ff` so the promotion stays visible in the
  history, and the resulting commit was tagged.
- **Never `git reset --hard` / force-push over a stable tag.**

Version codes must always go up when a build is meant to be installed over an
existing one, because Android refuses to downgrade and the only workaround is
uninstalling — which destroys ColorOS permissions and app data (section 2).

---

# 23. Current UI Status

The current UI is functional.

UI polish was intentionally postponed; the priorities were correctness and
stability, and that is still the right default.

As of v1.1.1 the UI has:

- source / target calendar selection with a plain-language status line
- the master auto-sync switch
- a status card
- a **collapsed-by-default diagnostics card** (tap the title to expand; contains
  the build label, environment summary, job state, 150 log lines, and buttons to
  copy the whole payload to the clipboard or clear the log)
- a detected-calendars list showing `id` / `accountName` / `accountType`

The diagnostics card is the field-support channel: a user who is not a developer
can open it and send the whole picture in one tap. Its contents are read-only
with respect to sync state — `Diagnostics.kt` never writes anything the sync
engine reads.

Further safe UI improvements that would still not touch sync logic:

- a cleaner source → target summary
- better permission guidance (e.g. a direct deep link into ColorOS app management)
- localised / friendlier wording

---

# 24. Suggested Next Development Areas

Status as of v1.1.1:

1. ~~UI polish only~~ — partly done
2. ~~better diagnostics / logs~~ — done (`Diagnostics.kt` + the panel)
3. ~~visible app version / build information~~ — done (`BUILD_LABEL`, shown as
   the first line of the copied payload)
4. ~~exportable troubleshooting report~~ — done (复制诊断信息 copies the payload
   to the clipboard)
5. improved handling of reminders / attendees modifications — still open
6. broader device compatibility beyond OPPO / ColorOS — still open
7. release signing and GitHub release packaging — still open; note that builds
   are currently debug-signed, so distributing to other people's phones requires
   a proper signing setup, and switching signing keys later means those users
   must uninstall/reinstall

The project originally prioritized OPPO / ColorOS first.

Do not prematurely generalize the architecture for every Android vendor.

---

# 25. Recommended First Files to Read

A new agent should inspect in this order:

1. `PROJECT_HANDOFF_OalendarBridge.md` (this file)
2. `SyncEngine.kt`
3. `CalendarSelectionStore.kt`
4. `CalendarSyncScheduler.kt`
5. `CalendarSyncJobService.kt`
6. `WatchdogJobService.kt`
7. `Diagnostics.kt`
8. `BootReceiver.kt`
9. `MainActivity.kt`
10. `AndroidManifest.xml`
11. `app/build.gradle.kts`

Then run a build before proposing changes.

---

# 26. First-Session Prompt for a New GPT / Coding Agent

Copy the following into the first chat with the new agent:

```text
This is my Android project OalendarBridge.

First read PROJECT_HANDOFF.md completely, then inspect the current source code.

The current v1 core behavior has already passed real-device testing on OPPO / ColorOS.

Until I explicitly authorize it, do not refactor or change the existing sync core.

In particular, do not independently replace or redesign:

- Local → DAVx⁵ migration
- clone-then-delete behavior
- OPPO modification fingerprint detection
- same-value update / DIRTY repair
- JobScheduler content trigger
- automatic sync ON/OFF semantics
- reboot default OFF behavior
- native DAVx⁵ deletion behavior
- source / target calendar identity resolution

Before changing code, tell me:

1. your understanding of the current architecture
2. which files you intend to modify
3. whether the change can affect already-tested behavior

After every code change, run the Gradle build and fix compile errors before reporting completion.

Do not assume conventional Android behavior is always correct here. Some unusual logic exists specifically because OPPO / ColorOS real-device behavior was tested and found to differ from the standard expectation.
```

---

# 27. One-Sentence Architecture Summary

**OalendarBridge watches selected OPPO Local calendar changes while the master switch is ON, creates genuine events in a selected DAVx⁵ calendar, repairs OPPO’s missing dirty-state behavior for later edits, relies on DAVx⁵ for deletion, and never backfills changes made while sync was OFF.**

---

# 28. Final Warning to Future Agents

This project has already gone through multiple rounds of real-device diagnosis.

Some code may look more complicated than a “standard Android calendar sync” implementation.

That complexity is not automatically technical debt.

Before simplifying anything, verify whether the apparently unusual behavior exists to work around one of these confirmed OPPO / ColorOS issues:

- Local calendar default creation
- background execution restrictions
- missing dirty state after editing DAVx⁵ events
- sync adapter identity behavior

**Real-device correctness has priority over architectural elegance.**

---

# 29. ColorOS 17 / Android 17 Investigation (2026-09)

This section records the second round of real-device diagnosis, on an
OPPO PMX110 running **ColorOS 17 / Android 17 (API 37)**, `targetSdk = 37`.
It is the source of the v1.1 watchdog and the v1.1.1 diagnostics panel.

## 29.1 Symptom

After installing the app on the new phone and setting it up, a newly created
calendar entry did **not** move to the iCloud / DAVx⁵ calendar. Opening the app
once made it migrate immediately. This reproduced only on a fresh install.

## 29.2 What was actually proved

1. **The sync core is fine on Android 17.** Migration, calendar identity
   resolution, fingerprint / DIRTY repair all behaved as on the original device.
   No platform adaptation was needed.
2. **The trigger chain does not need the app to be alive.** With the app swiped
   out of Recent Apps, the log still shows the system waking the job within
   about a second of a calendar write. Background residency, auto-start and
   background-popup permissions are therefore *not* what the trigger depends on.
3. **The primary job can be missing from the queue** — the real v1 gap. In v1,
   nothing re-registered it until the UI loaded, the switch was flipped, or an
   APK was installed over it. This also explains why "installing the diagnostic
   APK made it work": `MY_PACKAGE_REPLACED` re-arms the job and the install
   clears the system's stopped-state flag for the package.
4. **The master switch being OFF accounts for the other observed misses.**
   Reboot sets sync to OFF by product rule (section 3.1). When the UI loads with
   sync already OFF, the log contains only `cancel：已取消作业调度` and no
   `界面触发一次检查` — that single line is a reliable fingerprint for "the
   switch was already off before the user opened the app".

## 29.3 What was NOT proved — do not over-claim these

- The watchdog has **never been observed actually firing** in the field. Logs
  showed the primary job always present. It is a safety net whose necessity is
  argued from mechanism, not from an observed rescue.
- `QUOTA` and `DEVICE_STATE` appear in the pending-job reason history every time
  a job finishes, with a cumulative duration of `0m`, and in the same minute the
  job was in fact woken up 3–4 times normally. Treat them as JobScheduler's
  bookkeeping noise, **not** as evidence of quota throttling. Real throttling
  would look like: the pending reason stays `QUOTA` for a long stretch **and**
  no "job woken" line appears for a long stretch.
- One new event triggers **2–4 job runs**, because the migration writes a new
  row into the calendar table and therefore re-triggers the content observer.
  This is inherent to v1's design, it does not affect correctness, and it was
  left alone.

## 29.4 False leads worth remembering

- **The three ColorOS permission switches.** Reasonable hypothesis, but the
  trigger works without them on ColorOS 17. Still worth recommending to users as
  a general background-restriction mitigation, not as the fix.
- **`stopReason=CANCELLED_BY_APP` at the end of almost every run.** This is the
  *expected* consequence of the "finish, then register a replacement job" design —
  re-registering the same job ID stops the running instance. It is not an error.
- **"The diagnostic build fixed it."** It did not; installing any APK would have
  had the same side effect (see 29.2 item 3). Every diagnostic commit was
  verified to be logging-only — `SyncEngine.kt` had zero diff in all of them.

## 29.5 Diagnostic log-line reference

| Log line | Meaning |
|---|---|
| `作业被系统叫醒：authorities=… uris=…` | The content trigger fired; the system is delivering a calendar change |
| `作业执行结果：检查完成：新增：成功 N 个` | A migration actually happened |
| `作业执行结果：检查完成：没有新的日历变化` | Empty pass; harmless |
| `作业被系统停止：stopReason=CANCELLED_BY_APP` | Expected; see 29.4 |
| `schedule：注册结果=1（1=成功/0=失败）` | `RESULT_SUCCESS = 1`, `RESULT_FAILURE = 0` (AOSP). Do not read this backwards |
| `ensureScheduled：作业不在队列中，重新注册` | **The job had been lost** — this is the v1 gap in the act |
| `ensureScheduled：作业已在队列中` | No action needed |
| `界面触发一次检查` | The UI loaded with sync ON (it runs a check immediately) |
| `界面加载：自动同步为 OFF，确认取消调度` | The UI loaded with sync already OFF |
| `用户操作：把自动同步打开 / 关闭` | Someone toggled the switch |
| `watchdog：主作业不在队列中，补注册并补一次检查` | The v1.1 safety net fired |
| `watchdog：主作业在队列中，不做任何事` | The v1.1 safety net correctly stayed out of the way |
| `重启后按产品规则：关闭自动同步并取消作业` | `BOOT_COMPLETED` path (product rule, not a bug) |

## 29.6 Device facts recorded during the investigation

```text
System              : Android 17 (API 37), OPPO PMX110 / ColorOS 17
Standby bucket      : EXEMPTED (5)
Battery whitelist   : exempted (granted by system/user, not requested by the app)
Doze                : not idle during the tests
Jobs in queue       : 1 (only the app's own)
```

Standby bucket values worth knowing: `5 = EXEMPTED`, `50 = NEVER`. The
diagnostics panel decodes these; an unknown value is printed as `其他(<n>)`.

---

# 30. How to Read a Diagnostics Payload

`Diagnostics.BUILD_LABEL` is always the first line of the copied payload, so the
build is identifiable from a screenshot or a pasted blob alone. Always read it
first — a stale label has already caused confusion once in this project.

Then, in order:

1. **`自动同步开关`** — if OFF, stop here. Almost nothing else matters; the job
   is deliberately cancelled and OFF-period changes will never be backfilled.
2. **`系统已开机时长`** — a short uptime means the phone rebooted, which sets
   sync to OFF by product rule. This distinguishes "reboot" from "someone
   toggled the switch".
3. **`作业` / `待执行原因`** — `已注册，等待触发` + `CONSTRAINT_CONTENT_TRIGGER`
   is the healthy resting state.
4. **The log, read bottom-up** — look for whether `作业被系统叫醒` appears
   *before* the moment the user opened the app. That single fact decides whether
   the trigger was delivered or not.
5. Only then look at the migration side.

The panel's ring buffer holds 400 lines and survives process death (it is stored
in app-private SharedPreferences, `oalendar_bridge_diagnostics`). It is written
only from sync-code call sites via `Diagnostics.log()`; nothing in the sync path
reads it back, so it can never influence behaviour.

