# Butler Experience Enhancements

**Status:** Implementation guidance  
**Scope:** Android startup, proactive briefings, planning/interaction intelligence

## Goal

Hello Butler should feel immediately available and exercise practical judgment like a professional personal Butler, rather than behaving like a calendar reader or rigid scheduler.

## 1. Instant interaction on app launch

### Product requirement

A returning authenticated user must be able to open the app and start holding **Order** or **Talk** in about **500 ms** on a normal warm launch. The Main Screen controls are the critical path; plan/history/network refresh is not.

```text
launch
├── critical: render Main Screen + enable Order/Talk/Text
└── background: Room observation, plan sync, conversation recovery, pending results, reconciliation
```

### Current implementation finding

- `HelloButlerApp` already bypasses the session-check spinner when `AuthRepository.hasSession()` is true.
- `MainViewModel` starts `refreshPreparedDays()` and `butler.recover()` asynchronously, so these should not intentionally block composition.
- `AppContainer` is created before `setContent` and eagerly constructs database, Retrofit, repositories, and scheduler dependencies. Startup work around container/database/session initialization therefore needs profiling; expensive initialization should be lazy or moved off the UI critical path where safe.

### Rules

- Never wait for backend availability, Daily Plan sync, Upcoming Events, conversation recovery, TTS, or pending-request reconciliation before enabling Butler controls.
- Render cached/local content progressively; an empty/loading event area must not disable interaction.
- Recording must begin from local client state. Network work starts after/independently from capture.
- Preserve authentication safety: only an already-restored local authenticated session may enter the fast returning-user path.
- Add startup timing instrumentation and test cold/warm launches. Optimize measured blockers rather than adding an artificial splash delay.

### Acceptance

- Returning user can initiate Order/Talk while background refresh is still running.
- Slow/offline backend does not delay control readiness.
- Target: controls interactive in <=500 ms on normal warm launch; cold-start timing is measured and improved without blocking on network.

---

## 2. Professional Morning Brief and Good Night Summary

### Product requirement

Proactive speech is a **curated briefing**, not a chronological reading of Daily Events. Butler decides what is useful to hear, groups it naturally, and omits routine/noisy details.

### Current implementation finding

Morning Brief already has a dedicated AI composition call and its prompt says not to enumerate everything. However, `DayPlanningService` supplies `final_events` sorted by start time, which strongly encourages chronological narration. The briefing contract needs stronger selection/grouping semantics, not a TTS change. Good Night already has its own composition path and should remain a different briefing objective.

### Morning Brief behavior

Prioritize:

1. unusual or important commitments and constraints;
2. time-sensitive items and meaningful changes from routine;
3. useful grouping by parts of the day rather than event-by-event chronology;
4. contextual reminders that affect later actions.

Usually omit ordinary meals, routine blocks, low-value filler, and details the user does not need spoken aloud. Mention exact time only when it helps action; otherwise use natural ranges such as *this morning*, *after work*, or *this afternoon*.

Example shape:

> You have your usual workday this morning. Don't forget the client meeting this afternoon. After work, your evening is mostly open. One more thing: you're out of shampoo, so pick some up when you go for groceries.

Do not invent facts. Filtering means choosing from authoritative context, not deleting the underlying events.

### Good Night behavior

Good Night is not Morning Brief in past tense. Summarize meaningful outcomes/changes, surface important unfinished carry-over only when useful, and mention tomorrow only when relevant. Do not recite the full day, score productivity, or report routine events merely because they exist.

### Architecture

```text
structured authoritative day/context
        ↓
briefing composition: select → prioritize → group → narrate
        ↓
canonical briefing text
        ↓
shared TTS service
```

TTS remains presentation-only and must not perform filtering or rewriting.

### Acceptance

- Briefings can omit low-value events without altering Daily Plan data.
- Important later commitments are placed in a useful narrative context instead of blindly sorted chronology.
- Morning and Good Night use distinct objectives.
- Tests cover noisy routine-heavy days, one important later event, reminders/context, and mostly-empty days.

---

## 3. Intelligent event adaptation

### Product requirement

Butler should resolve ordinary schedule friction itself when user intent is clear: deduplicate equivalent events, move flexible events, and shorten/remove expendable generated blocks when necessary. It should ask only when a consequential choice genuinely belongs to the user.

### Current implementation finding

Current day planning treats every `known_event` as protected. Proposed events that overlap a known event are simply discarded by `_normalize`; the planner cannot distinguish a fixed client meeting from movable exercise or expendable planner-created personal time. Interaction instructions preserve existing event times unless explicitly changed/conflicting, but there is no shared flexibility/protection model.

### Required scheduling semantics

Planning/interaction must distinguish behavior, whether represented explicitly in contracts or deterministically inferred from authoritative origin/context:

- **Protected/fixed:** explicit user commitments, appointments, meetings, deadlines, user-protected time. Never silently delete or move.
- **Flexible:** routines or activities that may move while preserving their intent when practical.
- **Optional/expendable:** Butler-generated filler or generic personal/free-time blocks that may be shortened/removed to satisfy a higher-priority commitment.

Explicit user intent always outranks Butler optimization. "Keep 7–8 PM free for me" is protected even if its title resembles personal time.

### Conflict policy

For a clear requested/new commitment:

1. detect semantic duplicates and avoid creating a second equivalent event;
2. preserve protected commitments;
3. move flexible events to a sensible free slot when useful;
4. shorten/remove optional generated blocks when needed;
5. avoid moving/removing unrelated events merely to make a denser schedule;
6. clarify only when two protected/important commitments conflict and no safe interpretation is available.

The backend must still validate all AI-proposed mutations. The model does not directly mutate persistence.

### Planning implications

Do not keep the blanket rule that all `known_events` are equally immovable. Preserve the distinction between user-created/explicit commitments and planner-generated events across re-planning. Re-planning should reconcile by semantic identity where possible instead of relying only on positional planner keys, so duplicates and unnecessary churn are avoided.

### Acceptance

- Duplicate request does not produce duplicate Daily Events.
- New fixed commitment can displace a flexible/generated event without unnecessary clarification.
- Explicitly protected personal time is not silently removed.
- Flexible activity is moved when a sensible slot exists rather than simply dropped.
- Genuine protected-vs-protected conflict produces a natural clarification.

---

## Implementation order

Implement as separate changes, sequentially from latest `main`:

1. **Instant startup** — profile first, then remove measured blockers.
2. **Intelligent briefings** — strengthen composition inputs/prompts/contracts; TTS unchanged.
3. **Intelligent planning** — introduce protection/flexibility semantics and deterministic validation before broadening AI autonomy.

Do not combine these into one large code change. Preserve the existing asynchronous Butler request lifecycle, canonical-text-first TTS architecture, User Context authority, and local-first event UI behavior.