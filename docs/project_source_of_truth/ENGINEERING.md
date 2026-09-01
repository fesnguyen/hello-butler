# ENGINEERING.md

# Butler Engineering Guide

Version: 2.0

---

# Purpose

This document defines the engineering standards for the Butler backend.

Its purpose is to ensure that all code—whether written by humans or AI coding
agents—is consistent, maintainable, testable, and easy to understand.

Architecture decisions are defined in **ARCHITECTURE.md**.

This document explains how those architectural decisions should be implemented.

---

# Engineering Philosophy

Good software is easier to change than to write.

The Butler codebase should optimize for:

- readability
- maintainability
- simplicity
- testability
- scalability

Prefer explicit, predictable code over clever abstractions.

Future maintainers should understand a module quickly without needing hidden
knowledge.

---

# Core Principles

## Simplicity First

Prefer the simplest solution that correctly solves the problem.

Avoid unnecessary abstractions.

Follow:

- KISS
- YAGNI

Do not build extensibility until it is justified.

---

## Composition Over Inheritance

Prefer composing small components rather than creating deep inheritance
hierarchies.

Behavior should emerge from collaboration between components.

---

## Dependency Injection

External dependencies should be injected rather than created internally.

Benefits include:

- easier testing
- loose coupling
- easier replacement
- clearer dependencies

---

## Dependency Inversion

Business logic depends on abstractions.

Infrastructure depends on business logic.

Never reverse this relationship.

---

## Single Responsibility

Every component should have one clear responsibility.

Examples

Good

PlannerRepository

↓

Persist Planner state.

Bad

PlannerRepository

↓

Persist state

Call OpenAI

Publish events

Generate reminders

---

## Separation of Concerns

Keep responsibilities separated.

Examples

Router

↓

HTTP

Use Case

↓

Application workflow

Planner

↓

Business reasoning

Repository

↓

Persistence

Provider

↓

External service

---

## Strong Contracts

Public interfaces should be explicit.

Prefer:

- typed models
- typed return values
- well-defined DTOs
- repository interfaces

Avoid returning loosely structured dictionaries.

---

## Configuration Over Hardcoding

Behavior should be configurable whenever practical.

Examples

- retry intervals
- AI providers
- feature flags
- timeout values

Never hardcode environment-specific values.

---

# Code Organization

Organize code by business capability rather than technical role.

Good

```text
planner/
schedule/
routine/
memory/
notification/
```

Avoid

```text
utils/
helpers/
misc/
common/
```

Each module should contain everything required for that capability.

---

# Naming

Choose names that describe the business domain.

Good

DailyPlan

MorningBrief

ReminderSchedule

Planner

ScheduleConflict

Avoid

Helper

Thing

Manager

Util

Processor

Prefer nouns for domain concepts.

Prefer verbs for actions.

---

# API Design

HTTP endpoints should remain thin.

Routers should:

- validate input
- authenticate users
- invoke use cases
- return responses

Routers should never contain business rules.

---

# Error Handling

Fail fast.

Validate assumptions early.

Raise explicit exceptions.

Avoid returning ambiguous values such as:

- None
- False
- empty dictionaries

Unexpected states should fail immediately with meaningful errors.

---

# Async Programming

Prefer asynchronous APIs whenever external I/O is involved.

Examples

- database access
- HTTP requests
- AI providers
- storage
- messaging

Avoid blocking operations inside asynchronous code.

---

# Data Models

Each layer should own its own models.

Examples

API

↓

Pydantic schemas

Application

↓

DTOs

Domain

↓

Entities

Infrastructure

↓

ORM models

Avoid sharing persistence models across layers.

---

# Immutability

Prefer immutable data where practical.

Objects representing completed operations or value objects should not change
after creation.

Mutability should be explicit.

---

# Reuse

Avoid duplication.

However, do not introduce abstractions too early.

Rule of thumb:

Duplicate twice.

Abstract the third time.

---

# Design Patterns

Use patterns only when they simplify the design.

Common examples include:

- Strategy
- Factory
- Builder
- Adapter

Avoid introducing patterns solely because they are well known.

---

# AI Integration

AI providers are implementation details.

Application code communicates only through abstractions.

Never call provider SDKs directly from business logic.

Changing providers should require minimal code changes.

---

# Logging

Log meaningful events.

Examples

- synchronization started
- Daily Plan generated
- Planner failed
- unexpected exceptions

Avoid excessive logging.

Never log secrets.

---

# Documentation

Every module should include:

- module docstring
- public API documentation
- concise comments explaining decisions

Comments should explain **why**, not **what**.

Good names reduce the need for comments.

---

# Testing

Every feature should be testable in isolation.

Prefer:

- dependency injection
- mocked infrastructure
- deterministic tests

Testing pyramid:

1. Domain
2. Application
3. Infrastructure
4. API
5. End-to-End

Business rules should be testable without:

- databases
- HTTP
- AI providers

---

# Code Review Checklist

Before considering work complete, verify:

□ Code is simple.

□ Responsibilities are clear.

□ Business rules remain in the Domain.

□ Dependencies point inward.

□ Public APIs are typed.

□ Error handling is explicit.

□ Configuration is externalized.

□ Tests cover the new behavior.

□ Naming reflects the business domain.

□ Documentation remains accurate.

---

# Guiding Principle

When writing code, optimize for the next engineer—not the current one.

The best code is not the most clever.

The best code is the easiest to understand, test, and safely change.