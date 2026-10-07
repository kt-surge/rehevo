# GitHub push validation - 2026-10-07

## Current working tree

- Backend: `gradlew.bat :app:test --no-daemon --rerun-tasks` with Java 21. BUILD SUCCESSFUL; 92 suites, 461 tests, 0 failures, 0 errors, 0 skipped.
- The regular Gradle test task excludes the `integration` tag. This run does not imply that infrastructure integration tests or formal model quality experiments were rerun.
- Frontend: `pnpm run build` passed TypeScript checking and Vite production build. Existing large-chunk and outdated Browserslist warnings remain.
- Existing credential values were scanned in memory against the proposed commit files; no matches. Local `.env`, `data/local/`, experiment `runs/`, logs and Python caches are ignored.
- Experimental features retain their configured gates. Publishing their implementation and experiment reports does not imply production enablement or formal end-to-end acceptance.

Raw local experiments are retained on the development machine. The published scripts and reports document their protocols, results and limits.
