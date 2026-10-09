# ENGINEERING.md

# Butler Engineering Guide

**Version:** 1.4  
**Status:** Engineering Rules

---

# 1. Purpose

This document defines the engineering standards and commitments for the entire Butler codebase.

It applies to:

- Backend
- Client
- Shared tooling
- Automation
- Code written by humans
- Code written by AI coding agents

`PROJECT.md` defines **what Butler is and how the product should behave**.

`ENGINEERING.md` defines **how software in this repository should be written**.

Implementation-specific architecture, technology choices, project structure, workflows, and data design belong in the relevant technical documents:

```text
PROJECT.md                  # Highest product authority
ENGINEERING.md              # Shared engineering rules
backend/PROJECT_BACKEND.md  # Backend subsystem authority
backend/TTS.md              # Backend speech implementation
client/PROJECT_CLIENT.md    # Android subsystem authority
client/AUDIO_WORKFLOWS.md   # Android audio implementation
client/HOME_SCREEN_WIDGET.md
client/CLIENT_SYNC_FLOW.md
web/PROJECT_WEB.md          # Web subsystem authority
web/showcase/SHOWCASE.md    # Showcase feature implementation
```

This document should contain rules that apply across the project.

Backend-specific or client-specific implementation details should not be duplicated here.

---

# 2. Engineering Philosophy

Good software should be easy to understand, change, and remove.

The Butler codebase should optimize for:

- readability
- maintainability
- simplicity
- predictability
- correctness
- safe change
- fast reasoning over the code path

Prefer explicit and understandable code over clever abstractions.

A future engineer should be able to understand a component without relying on hidden knowledge or undocumented conventions.

The system should remain as simple as the product allows.

---

# 3. Simplicity First

Prefer the simplest solution that correctly satisfies the current requirement.

Follow:

- KISS
- YAGNI

Do not build abstractions, extension points, services, modules, or infrastructure for hypothetical future requirements.

Do not introduce complexity merely because it may become useful later.

Complexity must be justified by an actual problem.

---

# 4. Preserve Existing Architecture

Before modifying existing code:

1. Understand the relevant product requirement.
2. Read the relevant subsystem PROJECT_* document and affected feature documents.
3. Inspect the existing implementation.
4. Preserve established architectural boundaries and conventions unless there is a clear reason to change them.

Do not silently introduce a new architectural style into an existing area.

Architectural changes should be intentional and reflected in the relevant subsystem document and affected feature documents.

---

# 5. Maximum Code Density and Locality of Reasoning

Code should maximize the amount of meaningful logic that can be understood in a single view.

The goal is to reduce unnecessary scrolling, jumping between files, and mental context switching while following a code path.

Prefer:

- concise functions
- short, meaningful names
- nearby related logic
- compact control flow
- direct data transformations
- minimal ceremony
- minimal wrapper layers
- minimal indirection
- comments placed inline when practical

A developer should be able to inspect a meaningful portion of a workflow without constantly navigating across many files or scrolling through large amounts of boilerplate.

Locality does **not** mean placing an entire feature or workflow in one file.

Keep the high-level execution path visible in one place while extracting substantial implementation details into focused modules when they represent distinct responsibilities.

Prefer:

- one compact module that exposes workflow or orchestration
- focused modules for substantial persistence, actions, contracts, provider logic, or domain behavior
- grouping closely related operations together

Avoid:

- files that become catch-all containers for an entire feature
- mixing contracts, orchestration, persistence queries, mutations, formatting, and provider logic in one module
- preserving locality by allowing a file to grow until the primary workflow becomes difficult to identify

The goal is **locality by responsibility**, not locality by forcing everything into one file.

This principle does **not** mean compressing code until it becomes cryptic.

Density is valuable only when readability remains high.

Good:

```python
if event.is_cancelled:
    return

event.status = EventStatus.COMPLETED  # Local user action; sync is queued separately.
sync_queue.enqueue(event.id)
```

Avoid:

```python
if event.is_cancelled:
    return

# This changes the status of the event to completed.
event.status = EventStatus.COMPLETED

# This adds the event to the synchronization queue so that it can later be
# synchronized with the backend when network connectivity is available.
sync_queue.enqueue(event.id)
```

Comments should normally be concise, close to the code they explain, inline when practical, and focused on **why**, not obvious **what**.

Use multi-line comments only when the reasoning genuinely requires more context.

Prefer code that exposes the whole reasoning path with the least navigation possible.

---

# 6. Separation of Responsibilities

Every component should have one clear reason to change.

Avoid components that combine unrelated responsibilities.

For example, a component responsible for persistence should not also:

- perform AI reasoning
- manage UI state
- send notifications
- contain unrelated business rules

Responsibilities should remain explicit and easy to locate.

A component should be extracted when it has a distinct reason to change and its implementation obscures the primary responsibility of the containing module.

For example, a workflow module may show:

```text
load → understand → route → execute → persist
```

while detailed context loading, state mutation, and persistence logic live in focused modules.

Do not use line count as a mechanical rule, but treat sustained file growth as a signal to review whether multiple responsibilities have accumulated.

Do not split responsibilities so aggressively that understanding one operation requires navigating through unnecessary layers.

Separation of concerns and locality of reasoning must remain balanced.

---

# 7. Composition Over Inheritance

Prefer composition of small focused components over deep inheritance hierarchies.

Inheritance should only be used where the relationship is naturally hierarchical and materially simplifies the design.

Behavior should normally emerge from collaboration between focused components.

---

# 8. Dependency Injection

External dependencies should be provided to components rather than constructed inside business logic.

Examples include:

- repositories
- databases
- network clients
- AI providers
- clocks
- schedulers
- notification services
- platform services

Dependency injection improves replaceability, clarity, and separation of concerns.

Avoid hidden dependency creation inside domain or application logic.

Do not introduce dependency-injection frameworks or excessive indirection when simple constructor or parameter injection is sufficient.

---

# 9. Dependency Inversion

High-level business logic should not depend directly on low-level infrastructure.

Business behavior depends on abstractions.

Infrastructure implements those abstractions.

Conceptually:

```text
Business / Application Logic
            │
            ▼
        Abstraction
            ▲
            │
     Infrastructure
```

Infrastructure details should remain replaceable without forcing business logic to change unnecessarily.

Use abstractions only where the boundary is meaningful.

---

# 10. Strong Contracts

Boundaries between components should be explicit.

Prefer:

- typed parameters
- typed return values
- explicit models
- explicit interfaces
- well-defined state
- clear error contracts

Avoid loosely structured data when a meaningful model exists.

Avoid generic maps or dictionaries as substitutes for domain contracts.

Data crossing an architectural boundary should have a clearly understood shape and meaning.

---

# 11. Domain Language

Names should reflect the product and domain.

Prefer names such as:

```text
DailyEvent
DailyPlan
UserContext
MorningBrief
GoodNightSummary
ButlerInteraction
SyncOperation
```

Avoid vague names such as:

```text
Helper
Thing
Stuff
Util
Processor
DataManager
Common
Misc
```

Use nouns for concepts.

Use verbs for actions.

Names should communicate intent without requiring comments to explain basic behavior.

Use terminology consistently with `PROJECT.md`.

Do not introduce alternate names for established product concepts without a clear reason.

---

# 12. Organize by Meaning

Code should be organized around meaningful responsibilities and product capabilities rather than becoming a collection of generic technical folders.

Avoid dumping unrelated behavior into directories such as:

```text
utils/
helpers/
misc/
common/
```

Small shared utilities are acceptable when they represent genuinely shared, well-defined behavior.

Do not create a shared abstraction merely because two pieces of code look similar.

Project structure for each application is defined by its corresponding `ARCHITECTURE.md`.

When a product capability becomes substantial, prefer a capability package containing focused modules over a single catch-all module named after the capability.

Keep related code close enough that a normal workflow can be followed with minimal file navigation.

---

# 13. Configuration Over Hardcoding

Environment-specific or operational values should not be embedded directly in business logic.

Examples include:

- URLs
- credentials
- timeouts
- retry policies
- provider selection
- feature flags
- environment behavior

Configuration should have clear defaults where appropriate.

Secrets must never be committed to source control.

Product rules that are genuinely part of the domain should remain explicit code rather than being turned into configuration unnecessarily.

---

# 14. Error Handling

Errors should be explicit.

Validate assumptions at appropriate boundaries.

Fail early when the system enters an invalid state.

Prefer meaningful errors over ambiguous results.

Avoid using values such as:

```text
null
false
empty object
empty string
```

to represent unrelated failure conditions unless that value is genuinely part of the contract.

Errors should contain enough context to diagnose the failure without exposing sensitive information.

Expected failures and unexpected failures should be distinguishable.

Do not silently ignore exceptions.

---

# 15. State and Mutation

State changes should be intentional and visible.

Prefer immutable values where practical.

When mutation is required:

- make ownership clear
- keep mutation localized
- avoid hidden side effects
- make lifecycle transitions explicit

Operations that change important state should have predictable outcomes.

The same command should not accidentally produce duplicate side effects when retried.

Where retryable or synchronized operations exist, design for idempotency where appropriate.

---

# 16. Side Effects

Business decisions and external side effects should remain distinguishable.

Examples of side effects include:

- database writes
- network requests
- AI calls
- notifications
- file access
- device scheduling
- external service calls

Keep side effects at clear boundaries whenever practical.

Do not scatter the same side effect across unrelated layers.

A developer following a code path should be able to identify where important external actions occur.

---

# 17. Reuse and Abstraction

Avoid unnecessary duplication, but do not abstract prematurely.

A useful rule of thumb:

> Duplicate twice. Abstract the third time.

This is guidance, not a mechanical requirement.

Create an abstraction when:

- the shared concept is actually the same
- the abstraction has a meaningful name
- it reduces complexity
- it improves maintainability

Do not create abstractions merely to reduce line count.

A small amount of duplication is often preferable to the wrong abstraction.

Avoid abstraction that forces a reader to jump through multiple layers just to discover simple behavior.

---

# 18. Design Patterns

Use design patterns when they simplify a concrete problem.

Examples may include:

- Strategy
- Adapter
- Factory
- Repository
- Observer
- State

Patterns are tools, not goals.

Do not introduce a pattern solely because it is considered a common best practice.

Every additional abstraction has a maintenance and reasoning cost.

Prefer the pattern that keeps the code path easiest to follow.

---

# 19. External Services

External systems are implementation details.

Examples include:

- AI providers
- databases
- notification platforms
- cloud services
- analytics
- speech services
- third-party APIs

Core application behavior should not unnecessarily depend on vendor-specific SDKs.

External integrations should be isolated behind clear boundaries when doing so provides meaningful replaceability or clarity.

Changing an external provider should affect as little unrelated code as practical.

---

# 20. AI Engineering

AI models are not the source of truth for product behavior.

AI components must operate within the rules defined by:

```text
PROJECT.md
+
ENGINEERING.md
+
Relevant ARCHITECTURE.md
+
Relevant WORKFLOW.md
```

Do not rely on prompts alone to enforce critical deterministic behavior when the behavior can be enforced by normal software.

AI output should be validated before being used for important state changes.

Structured output should be preferred when downstream software depends on the result.

Provider-specific SDK usage should remain isolated from higher-level business logic.

Prompts should be treated as maintained application assets rather than informal strings scattered throughout the codebase.

---

# 21. Data Integrity

Persisted and synchronized data must have explicit ownership and lifecycle rules.

Changes should not silently overwrite valid state.

Important state transitions should be deliberate.

When multiple sources can modify the same data, such as backend and offline client state, synchronization behavior must be explicitly defined rather than left to accidental last-write behavior.

Specific database and synchronization designs belong in the relevant architecture and workflow documents.

---

# 22. Concurrency and Asynchronous Work

Concurrency should only be introduced where it provides meaningful benefit.

Code must remain correct when work is:

- delayed
- retried
- interrupted
- executed more than once
- completed in a different order than expected

Do not assume network requests, background jobs, synchronization, or AI calls complete exactly once.

Platform-specific concurrency and asynchronous programming conventions belong in the corresponding architecture documents.

---

# 23. Logging

Logs should help explain what the system did and why it failed.

Log meaningful events such as:

- important state transitions
- synchronization outcomes
- background job execution
- external integration failures
- unexpected exceptions

Avoid logging routine internal noise without operational value.

Never log:

- passwords
- authentication tokens
- API keys
- secrets
- sensitive private data unless explicitly required and safely handled

Logging should not become part of business behavior.

---

# 24. Observability

Important workflows should be diagnosable.

Where appropriate, record enough information to answer questions such as:

- What operation failed?
- Which component failed?
- Was the operation retried?
- Was synchronization successful?
- Which external dependency caused the failure?

Observability should be added in proportion to actual operational needs.

Do not introduce heavyweight monitoring infrastructure before it is justified.

---

# 25. Documentation and Code Comments

Documentation should explain decisions and contracts that cannot be understood reliably from code alone.

Comments are expected when code contains important reasoning that is not obvious from naming and structure alone.

Use comments to explain things such as:

- why a non-obvious branch or workaround exists
- important invariants or assumptions
- transaction, concurrency, retry, or ordering constraints
- security-sensitive reasoning
- provider or platform behavior that materially shapes the implementation
- surprising product rules or edge cases

Do not avoid a useful comment merely to make the file look cleaner.

Comments should primarily explain **why**, not restate **what** the code does.

Prefer:

```text
Clear code
+
Good naming
+
Concise, useful comments where reasoning is non-obvious
+
Concise documentation
```

Comments should be inline and concise when possible so the code path remains visible in one view. Multi-line comments are appropriate when the reasoning cannot be explained clearly inline.

Do not add comments mechanically to obvious code, and do not retrofit comments into stable code solely to satisfy a comment count.

Public or shared interfaces should be documented when their behavior is not obvious.

Architecture and workflow documentation must be updated when the implemented design materially changes.

Do not duplicate higher-level documentation into lower-level documents.

Reference the authoritative document instead.

---

# 26. Documentation Authority

Documentation follows this hierarchy:

```text
PROJECT.md
ENGINEERING.md
│
├── backend/
│   ├── ARCHITECTURE.md
│   └── WORKFLOW.md
│
└── client/
    ├── ARCHITECTURE.md
    ├── WORKFLOW.md
    └── ui/
```

`PROJECT.md` is authoritative for:

- product purpose
- product concepts
- expected behavior
- product boundaries
- user experience

`ENGINEERING.md` is authoritative for:

- engineering principles
- coding standards
- maintainability rules
- cross-project implementation discipline

Application `ARCHITECTURE.md` files are authoritative for:

- technology stack
- project structure
- technical boundaries
- design patterns
- major components

Application `WORKFLOW.md` files are authoritative for:

- execution flows
- data flows
- state transitions
- persistence relationships
- synchronization behavior

Client UI documentation is authoritative for:

- screen structure
- interaction behavior
- UI-specific presentation rules

Lower-level documents should not redefine higher-level decisions.

If documentation conflicts, the higher-level source takes precedence until the conflict is explicitly resolved.

---

# 27. Security

Treat external input as untrusted.

Validate data at system boundaries.

Never commit or expose:

- passwords
- API keys
- access tokens
- signing secrets
- private credentials

Use the minimum required privileges for external services and infrastructure.

Security-sensitive behavior should be explicit and reviewable.

Do not invent custom cryptography or authentication mechanisms when established solutions exist.

---

# 28. Privacy

Butler handles personal information.

Collect and persist only information required for the product.

Do not log personal information unnecessarily.

Do not send user information to external services unless required by the feature and architecture.

Data handling decisions should remain explicit and auditable.

---

# 29. Performance

Correctness and clarity come before premature optimization.

Do not optimize based on assumption.

Measure before introducing complexity for performance.

However, avoid obviously wasteful designs in frequently executed paths.

Performance-sensitive behavior should remain understandable.

---

# 30. Dependencies

Every dependency adds maintenance and security cost.

Before adding a dependency, ask:

1. Does it solve a real requirement?
2. Is the functionality substantial enough to justify a dependency?
3. Is the project actively maintained?
4. Can the requirement be solved clearly with existing tools?
5. Does the dependency introduce unnecessary coupling?

Prefer established, focused dependencies over large frameworks added for one small feature.

Remove dependencies that are no longer used.

---

# 31. Backward Compatibility

Do not maintain backward compatibility automatically when there is no real consumer requiring it.

When a contract is already depended upon by clients, stored data, or external systems, changes should be deliberate.

Breaking changes should be identified explicitly.

Compatibility layers should have a clear reason and, where appropriate, a plan for eventual removal.

---

# 32. Cleanup

When replacing an implementation, remove obsolete code once it is no longer needed.

Do not leave:

- abandoned implementations
- commented-out code
- unused abstractions
- obsolete compatibility paths
- dead configuration

Source control already preserves history.

The active codebase should represent the current design.

---

# 33. Scope Discipline

When implementing a task, change only what is necessary to complete the task correctly.

Do not perform unrelated refactoring unless it is required to safely implement the requested behavior.

If unrelated problems are discovered, identify them separately.

Small, coherent changes are easier to understand and review.

---

# 34. Development-Stage Testing Policy

At the current early stage of development, automated tests are **not required by default**.

The project is still establishing product behavior, architecture, data models, and workflows.

Adding broad automated test coverage too early can increase the cost of changing unfinished designs and slow iteration.

For now:

- prioritize correct implementation and fast iteration
- verify important behavior manually during development
- keep code structured so tests can be added later without architectural rewrites
- do not add tests merely to satisfy a coverage target
- do not create testing infrastructure unless a concrete need justifies it

Tests may be introduced selectively when a behavior becomes stable, critical, or difficult to verify manually.

The project can adopt a stronger automated testing policy later when the core architecture and product workflows stabilize.

---

# 35. Engineering Decision Rule

When multiple implementations are valid, prefer the one that:

1. satisfies the product requirement
2. preserves established architecture
3. has fewer moving parts
4. has clearer ownership
5. keeps related reasoning close together
6. requires less scrolling and navigation to understand
7. is easier to change
8. introduces less accidental complexity

Do not optimize for cleverness or theoretical flexibility.

---

# 36. Definition of Done

A change is complete when:

- the requested behavior works
- the implementation follows `PROJECT.md`
- the implementation follows `ENGINEERING.md`
- relevant architecture boundaries are preserved
- errors are handled explicitly
- important behavior has been manually verified when practical
- obsolete code introduced by the change is removed
- documentation is updated when contracts or architecture changed
- secrets or sensitive data are not exposed
- the code path remains understandable without hidden context
- the implementation avoids unnecessary scrolling, indirection, and boilerplate

Automated tests are not currently required unless explicitly requested or already needed for the specific feature.

---

# 37. Code Review Checklist

Before considering work complete, verify:

- [ ] The implementation solves the actual requirement.
- [ ] The solution is as simple as reasonably possible.
- [ ] Responsibilities are clear.
- [ ] Large capability files were reviewed for distinct responsibilities that should be extracted.
- [ ] Related logic is kept close enough for efficient reasoning.
- [ ] The code path can be followed with minimal unnecessary scrolling or file navigation.
- [ ] Non-obvious reasoning has a concise comment where it materially helps future readers.
- [ ] Comments explain why rather than obvious what.
- [ ] Dependencies follow established boundaries.
- [ ] Public contracts are explicit and typed.
- [ ] State changes and side effects are understandable.
- [ ] Errors are handled explicitly.
- [ ] Environment-specific values are configurable.
- [ ] External integrations remain isolated where appropriate.
- [ ] AI output is validated where required.
- [ ] Offline/retry behavior is considered when relevant.
- [ ] Important behavior was manually verified when practical.
- [ ] Naming matches established domain terminology.
- [ ] No unrelated complexity was introduced.
- [ ] Obsolete code was removed.
- [ ] Relevant documentation remains accurate.
- [ ] No secrets or sensitive information are exposed.

---

# 38. Guiding Principle

Optimize for the next engineer and for the next change.

The best implementation is not the most sophisticated one.

It is the implementation that correctly solves the problem while remaining easy to understand, reason about, and safely change.

> **Build only the complexity the product has earned. Keep the important code path visible.**
