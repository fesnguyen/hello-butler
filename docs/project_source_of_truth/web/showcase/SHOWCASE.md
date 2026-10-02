# Showcase Web

## Purpose

`web/showcase/` is a simple public single-page website for presenting applications built in this repository and future projects.

It should let a visitor quickly understand a project, see its demo/screenshots, inspect the public source code, and download the latest available release.

The Showcase is public and read-only. Management belongs to the future Admin web application.

## Location

```text
hello-butler/
├── backend/
├── client/
└── web/
    └── showcase/
```

Keep Showcase independent from the Android client while remaining in the same monorepo.

## Technology

Use a small modern frontend stack:

- React + TypeScript
- Vite
- pnpm
- Tailwind CSS v4
- shadcn/ui where reusable UI components help
- TanStack Query when server data fetching/caching is needed

Prefer standard React and browser capabilities over adding frameworks or libraries without a clear need.

Principles:

- strict TypeScript
- responsive/mobile-first UI
- accessible semantic HTML
- reusable components without premature abstraction
- environment-based API configuration
- fast production build with static assets
- keep dependencies small

## V1 UI

Showcase starts as one simple page.

The page contains:

1. a small header/identity
2. a short introduction
3. application cards/sections
4. application image or demo media
5. name, short description, platform/status
6. `View Source` link for public GitHub repositories
7. `Download` when a release is available

Hello Butler is the first showcased application.

Do not depend on Google Play availability. While Android is distributed directly, the page should expose the downloadable APK/release and the public GitHub repository so visitors can inspect the project and source.

Keep the visual design clean and lightweight. The application content and demo media are more important than decorative landing-page elements.

## Showcase Data

Application content should not be permanently hard-coded into React.

The intended flow is:

```text
Database -> Backend API -> Showcase
```

The backend is the source of truth for showcase metadata such as:

- application name and slug
- short description
- status/platform
- icon/hero/demo media references
- public repository URL
- releases and download information

Media references should be data-driven so images can later be changed through Admin or directly in persisted data without rebuilding the Showcase frontend.

Until the backend showcase API is implemented, temporary local mock data is acceptable for UI development, but keep it behind a small data boundary so it can be replaced by the API cleanly.

## Releases and GitHub CI/CD

GitHub is the source repository and CI/CD trigger.

Target release flow:

```text
merge/tag in GitHub
        |
        v
GitHub Actions
        |
        +-- validate/build
        +-- build Android release when applicable
        +-- publish release artifact
        |
        v
Showcase release data
        |
        v
visitor sees latest version/download
```

The Showcase must not contain release versions or APK URLs scattered through UI components. Release information should come from the release/data boundary so CI/CD can update publication data without requiring UI code changes.

GitHub Actions workflows should eventually be path-aware so backend, Android client, and web changes only run the jobs they require.

Secrets, signing keys, tokens, and production credentials must use GitHub Actions secrets/environment configuration and must never be committed.

## Boundaries

Showcase is responsible for:

- public presentation of applications
- demos/screenshots
- links to public source repositories
- published release/download information

Showcase does not directly manage database content or perform administrative operations.

The future Admin web application will provide authenticated management of the same underlying application/media/release data.

## Implementation Direction

Build incrementally:

```text
1. Bootstrap web/showcase with React + TypeScript + Vite + pnpm
2. Build the simple responsive single-page UI with mock data
3. Add backend showcase data models/API
4. Replace mock data with API data
5. Add deployment
6. Connect release publication to GitHub Actions
```

Keep each step usable and avoid building infrastructure before the Showcase needs it.
