# OalendarBridge — Project Handoff

> Purpose: hand this Android project to a new GPT / coding agent without losing the engineering decisions already validated on a real OPPO / ColorOS device.
>
> Status: **v1 core behavior is stable and has passed real-device testing**.
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

The current clean/stable Android Studio project is named:

`OalendarBridgeClean`

Typical local development path used during development:

`~/AndroidStudioProjects/OalendarBridgeClean`

Do not assume that exact path on another machine.

The project is Kotlin + Jetpack Compose.

The current stable version has been compiled and tested successfully on a real OPPO / ColorOS device.

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

## Why this works on OPPO

Real-device testing confirmed that this JobScheduler design survives swiping the app away, but only when appropriate ColorOS permissions are granted.

---

# 11. OPPO / ColorOS Permissions

These ColorOS permissions/settings were important in real-device testing:

- allow auto-start
- allow background activity / background running
- allow background popup / “允许后台弹出界面”

The last one was especially important.

Without the proper ColorOS background permissions, the Job may exist but not behave as expected.

The problem was not fundamentally JobScheduler itself.

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

The stable project contains or has contained these core files:

- `MainActivity.kt`
- `SyncEngine.kt`
- `CalendarSyncScheduler.kt`
- `CalendarSyncJobService.kt`
- `BootReceiver.kt`
- `CalendarSelectionStore.kt`

A future agent should read these files first.

`SyncEngine.kt` is the most sensitive file.

Do not rewrite it wholesale unless explicitly asked.

---

# 15. Android Manifest Expectations

The app requires:

- READ_CALENDAR
- WRITE_CALENDAR
- RECEIVE_BOOT_COMPLETED

JobService must use:

`android.permission.BIND_JOB_SERVICE`

The app no longer needs a foreground-service architecture for its core stable behavior.

The stable design does not depend on a permanent notification.

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

# 22. Recommended Git Baseline

If Git is not already initialized / committed for the stable version, the next agent should strongly consider creating a stable baseline commit.

Suggested tag / commit concept:

```text
v1.0-stable-coloros
```

Commit message example:

```text
Stable v1: calendar selection, background sync, OPPO dirty repair
```

Do not alter the stable branch casually.

Future work should ideally happen on a separate branch.

---

# 23. Current UI Status

The current UI is functional.

UI polish was intentionally postponed.

Current priorities were correctness and stability.

Future safe UI improvements may include:

- cleaner source → target summary
- collapsible diagnostics
- version number
- status display
- more user-friendly permission guidance

These should be possible without modifying sync logic.

---

# 24. Suggested Next Development Areas

Possible future work, in approximate safety order:

1. UI polish only
2. better diagnostics / logs
3. visible app version / build information
4. exportable troubleshooting report
5. improved handling of reminders / attendees modifications
6. broader device compatibility beyond OPPO / ColorOS
7. release signing and GitHub release packaging

The project originally prioritized OPPO / ColorOS first.

Do not prematurely generalize the architecture for every Android vendor.

---

# 25. Recommended First Files to Read

A new agent should inspect in this order:

1. `PROJECT_HANDOFF.md`
2. `SyncEngine.kt`
3. `CalendarSelectionStore.kt`
4. `CalendarSyncScheduler.kt`
5. `CalendarSyncJobService.kt`
6. `BootReceiver.kt`
7. `MainActivity.kt`
8. `AndroidManifest.xml`
9. `app/build.gradle.kts`

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
