# Calm Premium UI — v1

This folder records the first polished Android client redesign, created from
`main` at `cb84cfa91947d19649b57170e678fc35642e916b`.

## Included states

- Authentication
- Main Screen with events
- Main Screen empty state
- Butler listening/capturing
- Butler response/conversation
- Text transcript editing
- Event Detail

## Direction

The visual language is calm and personal rather than dashboard-like: warm
neutral surfaces, deep forest actions, restrained amber emphasis, generous
rounded shapes, low elevation, serif display type, and compact state cues.

## Differences from the original reference

The redesign keeps the reference's single-day focus and Butler-over-the-day
mental model, but uses denser event cards for normal phone screens, a fixed
three-action bottom dock, explicit event-state accents, and a more structured
authentication surface. Butler voice input follows the current native
tap-to-speak implementation on `main`; it does not restore the older
press-and-hold interaction described in parts of `CLIENT_WORKFLOW.md`.

The PNGs are implementation-aligned visual reference renders at a 390 × 844
Android phone viewport. They document deterministic UI states that require
live authentication, Room data, speech recognition, or backend responses.
