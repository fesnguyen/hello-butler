# Home Screen Widget

**Version:** 1.0  
**Status:** Source of Truth  
**Authority:** Derived from `CLIENT_ARCHITECTURE.md` and `CLIENT_WORKFLOW.md`

---

# Purpose

Hello Butler provides an optional Android home-screen widget so the Butler remains useful without requiring the user to open the full application.

The widget is a compact Butler interaction surface. Its primary content is the **latest Butler response**, not the Daily Plan, Upcoming Events, settings, or another dashboard.

The initial design targets an approximately **4-column × 4-row** launcher footprint. Android launchers may map grid cells and resize bounds differently, so implementation must remain responsive rather than depend on one exact pixel size.

---

# Product Shape

The widget contains:

```text
Hello Butler                                      [Home]

┌─────────────────────────────────────────────────────┐
│ <latest Butler response text>                       │
│                                                     │
│ [🔊 Listen Aloud]        [☎ As a Call]              │
└─────────────────────────────────────────────────────┘

[Open Butler]              [Talk]              [Take Note]
```

The response text is the visual focus. Playback actions belong inside the response area, directly below the message, matching the conversation UI relationship.

---

# Actions

## Open Butler

Open Butler launches the normal Hello Butler Main Screen with the Butler conversation overlay visible.

The action uses the Butler hand-and-bell identity rather than a generic notification bell. The visual concept is a suited hand delicately holding/ringing a handbell.

The Home control in the widget header also opens the application.

## Talk

Talk starts the existing Butler **Talk** interaction path. The widget does not introduce a new request type or separate conversation.

Where Android home-screen widget restrictions prevent direct microphone capture inside the widget surface, the action must hand off to the smallest existing app interaction that can safely start/continue Talk. Do not invent a parallel recording implementation merely to keep the user on the launcher.

The widget intentionally exposes Talk only; Order remains available in the full application.

## Take Note

Take Note provides a quick path to the existing Notes capability. Notes remain backed by the same authoritative saved-context/User Context flow described by the client architecture.

The widget must not create a separate widget-only note store.

## Listen Aloud

Listen Aloud plays the latest Butler response through the normal outward audio route.

It reuses the existing response audio, cache, readiness, download/reconciliation, and playback service. It must not generate speech locally or create a second TTS path.

## As a Call

As a Call plays the same latest response audio through the existing private/call-style route.

The action uses a traditional telephone icon. It is playback routing, not a realtime telephone or network call.

Listen Aloud and As a Call are mutually exclusive and follow the same Loading → Ready → Speaking/Stop behavior as the in-app response controls.

---

# Data and State

The widget is a projection of existing client state:

```text
Backend result
    ↓
normal repository reconciliation
    ↓
Room / response-audio cache
    ├── Main Screen conversation
    └── Home-screen widget
```

The widget must reuse locally persisted canonical Butler response text. It must not independently query the backend just to render its normal state.

When the canonical latest Butler response changes, the client refreshes the widget. Audio readiness/cache changes should also refresh relevant playback state.

If no Butler response exists yet, show a lightweight empty state that invites the user to open or talk to Butler rather than displaying invented content.

The widget must remain useful offline for locally available response text and cached audio. Network-dependent actions follow the same application behavior and retry/reconciliation rules as their existing counterparts.

---

# Ownership Boundaries

The widget owns only launcher presentation, widget-specific interaction wiring, and refresh scheduling/event handling required by Android.

It does **not** own:

- a second conversation history
- a second response cache
- Daily Plan or Upcoming Event persistence
- a widget-only notes database
- speech synthesis
- semantic intent/reasoning
- independent backend polling
- a new Talk request contract

Room/repositories and existing application services remain the source for client-visible state and actions.

---

# Visual Direction

The widget should feel like Butler is present on the home screen rather than like a generic productivity dashboard.

Design principles:

- latest Butler response gets the most space
- keep the widget readable at a glance
- playback controls sit horizontally under the response text
- Open Butler, Talk, and Take Note form the primary action row
- Home remains a compact header action
- use the established Butler hand-and-bell visual identity for Open Butler
- use a traditional telephone symbol for As a Call
- respect launcher/widget size constraints and Android accessibility touch targets
- adapt to supported light/dark presentation without sacrificing text contrast

---

# Non-Goals

The first widget version does not show:

- Daily Plan
- Upcoming Events
- weather
- credits/settings
- a full conversation history
- Order as a widget action
- a separate AI/chat session

Users who need the complete product experience use Open Butler to enter the application.

---

# Guiding Principle

The widget should answer one question immediately:

> What did my Butler just tell me, and what do I want to do next?

Everything in the widget should support that interaction without duplicating the full application.

---

# Android implementation

The widget uses platform `AppWidgetProvider`/`RemoteViews`, with no additional UI framework. `ButlerWidgetProvider` renders a weighted, resizable layout; provider metadata targets 4 × 4 cells and provides dp fallbacks and minimum resize bounds. Light/dark resource colors follow the device configuration. Long responses are ellipsized; tapping the response opens the canonical conversation.

`ButlerApplication` observes the latest completed Butler message through the existing Butler repository and a focused Room DAO Flow, combined with `ButlerPlayback.state`. Persistence, history reconciliation, audio-cache updates, playback transitions, and logout clearing therefore refresh installed widgets without extra backend polling, database tables, or periodic widget work. Widget add/update/resize callbacks reload the same local state.

Actions use immutable, explicit PendingIntents:

- Home opens Main Screen; Open Butler opens its conversation overlay, including when User Settings was previously visible.
- Talk opens the existing overlay in Talk mode with a “Hold Talk below to speak” hint. The user holds the existing Talk control; its permission check, recorder, release/upload, and request flow are unchanged. Launcher taps do not silently start microphone recording.
- Take Note opens the shared `SavedContextEditor` over Main Screen, defaulting to an ordinary Note. The same `UserSettingsViewModel` and saved-context repository handle saving and errors; the draft stays open on failure and retains its UUID for retry.
- Listen Aloud and As a Call use the existing `ButlerAudioPlaybackService` foreground-service PendingIntents and response cache. The service retains exclusive route ownership. Active playback/preparation exposes Stop; pending cache work shows Loading, and unavailable audio disables playback.

`drawable-nodpi/butler_identity.webp` is a single square, padded adaptation of the supplied official hand-and-bell artwork. Its proportions are retained. The widget, Main Screen/overlay avatars, and adaptive launcher foreground reuse that resource. Launcher padding protects the artwork within icon masks; there is no substitute bell or assistant artwork.

## Device verification

After installing a build, add Hello Butler from the launcher widget picker, then check:

1. Empty state, a completed response, a newer response, and offline cached content.
2. Resize, portrait/landscape, light/dark, large font sizes, multiple widget instances, and removal/re-addition.
3. Home and Open Butler from a cold start and while User Settings was previously open.
4. Talk handoff, microphone permission denial/grant, hold/release, and one canonical persisted interaction.
5. Take Note save/cancel, offline failure/retry, and the resulting ordinary Note in User Settings.
6. Both audio routes, Loading/cache readiness, Stop, route switching, and completion/error reset, including when playback starts in the application.
7. Logout clears the response; launcher icon masks and Open Butler show the supplied artwork.

Build and instrumentation commands (from `client/`):

```shell
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

The existing behavioral tests are Android instrumentation tests; `testDebugUnitTest` alone does not execute them. Launcher and physical speaker/earpiece behavior require a device or emulator.
