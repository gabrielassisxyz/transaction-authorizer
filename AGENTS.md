# AGENTS.md

Transaction-authorization API: consumes account-creation events from SQS and authorizes
credit/debit transactions against a Postgres-backed balance.

**Current scope:** SQS consumer that creates accounts (balance zero, idempotent) +
`POST /transactions/{transactionId}` authorizing CREDIT/DEBIT with the never-negative
balance invariant. Don't expand beyond it without a present need; if a change drifts
past it, STOP and flag it.

## Commands

- `bin/ci`: format check, lint, tests, coverage gate. The exact thing CI runs; green
  locally means green in CI. Run before every push.
- `bin/install-hooks`: once after clone; installs the gitleaks pre-commit and
  commit-msg gates.
- `bin/worktree new <task>`: per-session worktree; never work in the main tree.
- `docker compose up -d`: localstack SQS (queue seeded with 100k accounts by the
  `message-generator` service) + Postgres.
- `./gradlew bootRun`: run the app against the compose services.

## Stack (decided, do not re-litigate)

- Kotlin on Java 21, Spring Boot with **Spring MVC + virtual threads**
  (`spring.threads.virtual.enabled=true`). NOT WebFlux, NOT coroutines: one
  concurrency model only. Rationale in `docs/adr/`.
- Persistence: JPA + PostgreSQL. Money is **integer cents** (BRL), never binary float.
- JPA entities are regular `class`es, **never `data class`** (Hibernate identity vs
  `equals`/`hashCode`/`copy` corruption). Transport DTOs stay separate from entities.
  The `kotlin-jpa`/no-arg compiler plugins are required.
- Kotlin idiom elsewhere: sealed classes for authorization results, null-safety,
  collection operators.

## Architecture

- Hexagonal, single Gradle module, enforced by package structure + an ArchUnit test:
  `domain` (pure, no Spring imports) ← `application` ← `adapter/inbound/web`,
  `adapter/inbound/sqs`, `adapter/outbound/persistence` (`in`/`out` are Kotlin
  keywords, so they would need backticks in every package clause and import).
- The core invariant, that two concurrent debits must never drive a balance negative,
  is guarded by an atomic conditional update in the persistence adapter. Any change to
  balance logic, idempotency, or the SQS consumer is a High/Critical-tier diff (see
  Review).

## Testing

- Pyramid: many unit tests (pure domain), integration via Testcontainers (Postgres +
  localstack SQS) for adapters, few E2E over docker-compose, one ArchUnit suite.
- Stack: JUnit 5 + Spring Boot Test + Testcontainers + MockK + AssertJ. No Kotest.
- Corner cases are mandatory, not optional: debit that would go negative (refused,
  balance unchanged), non-existent account, duplicate `transactionId` (returns the
  stored first result), **two concurrent debits on the same account** (exactly one
  succeeds), duplicate SQS message (account created once), malformed payload.
- Coverage gate (Kover): 80% line coverage on `domain` + `application`; adapters and
  wiring exempt. Mutation testing (PIT) runs on `domain` as a non-blocking CI job.
- New behavior starts with a failing test; bug fixes start with a failing repro.

## Language policy (project override)

- Published prose (README, `docs/`, ADRs, commit and PR text) is written in
  **pt-BR**. This intentionally overrides any global English-only rule.
- Everything inside a source file is **English**: identifiers, comments, log and
  exception messages, test names. Same for build scripts, SQL migrations and config.

## Comments

- Density is LOW: only genuinely non-obvious logic. WHY, not WHAT. Never process
  narration (task ids, review findings, phase names), never ALL-CAPS labels
  ("WHY:", "NOTE:"), never didactic explanations of language basics.
- No file-header or class-level essays. If a type needs a paragraph to be understood,
  the paragraph is not a comment: architecture rationale goes to `docs/adr/` (public,
  numbered, written in the same PR as the change), and the longer "why this bit looks
  like this" goes to `design-notes.md` (private, gitignored), anchored by
  file + symbol (`AuthorizeTransactionService.authorize`), never by line.

## Prose

Applies to everything a reader outside this repo can see: Markdown, source comments,
config, commit messages, PR titles and bodies.

- No em-dash. A comma, a colon, a semicolon or a full stop says the same thing. This one
  is checked by `bin/ci` and by the `commit-msg` hook, so it fails before it ships; the
  rest of this list is judgment.
- Bold marks structure (a bullet lead-in, a table header), never emphasis in the middle
  of a sentence. Same for italics: introducing a term, not stressing a word.
- No process narration. No task ids, no phase or slice names, no review rounds, no
  mention of who or what reviewed a diff, no reference to a session or a conversation. A
  commit says what was wrong and what changed, never how the work was organised.
- No audience in the text. The README says what the software does, not who is going to
  read it.
- No redundant parenthetical. If it matters, it is a sentence; if it is a reference, it
  is a link; otherwise it is deleted.

## Review model (by risk tier of the diff)

- **Low** (DTOs, wiring, config, docs, diagrams): CI + self-read only.
- **High** (balance, credit/debit rule, idempotency, concurrency, SQS consumer,
  persistence writes): one independent review, never by whoever wrote the diff, plus
  the corner-case tests.
- **Critical** (atomic balance update, exactly-once account creation, concurrency
  control): two independent models, findings synthesized once; no review loops.
- Review is a documented judgment call per slice, not a blocking hook; the blocking
  gates are gitleaks and `bin/ci` only.

## Git & publication

- Branches: Conventional Branch. Commits: Conventional Commits, pt-BR descriptions,
  atomic. PRs: what + why, about the software and the problem, never the audience,
  never a session or conversation.
- The repo is public. No AI attribution anywhere (the commit-msg hook enforces this).
  No personal names in content. No secrets, `.example` files only. The challenge
  statement PDF is never committed.
- Polish ships with each slice: tests, ADR, clean comments, and the README/OpenAPI
  section land in the same PR as the feature. There is no end-of-project cleanup phase.

## Docs deliverables

- `README.md` (pt-BR): local run instructions, request collection, architecture at the
  top. `ROADMAP.md`: milestones + what would come next. `docs/adr/`: decisions.
  `docs/failure-modes.md`: per component, what happens when it fails. Diagrams as
  mermaid, updated in the PR that moves the architecture.

## Common hurdles

- The `message-generator` compose service seeds 100k messages and exits; wait for
  `message-generator exited with code 0` before consuming.
- Localstack SQS credentials are dummy (`test`/`test`, region `sa-east-1`,
  endpoint `http://localhost:4566`).
- Append a line here on every gotcha discovered.

<!-- BEGIN universal-principles v3 -->
## Working principles

- **The human defines the WHAT; the agent decides the HOW.** Don't wait for line-by-line
  dictation. Plan first for non-trivial tasks: show the plan + to-do list, wait for approval.
- **Think before coding — don't assume, don't hide confusion.** State assumptions explicitly;
  if multiple interpretations exist, present them — don't pick silently. If a simpler approach
  exists, say so and push back. If a task is impossible under the stated constraints, or info
  is missing, say so — don't guess. (For trivial tasks, use judgment; this is bias, not ritual.)
- **Surgical changes — touch only what you must.** Every changed line traces to the task.
  Don't "improve" adjacent code, reformat, or refactor what isn't broken; match existing style
  even if you'd do it differently. Flag unrelated dead code — don't delete it. Remove only the
  imports / variables / functions your own change orphaned.
- **Chesterton's Fence — find the problem before undoing the decision.** A config, a flag, a
  workaround that looks arbitrary is a **fence**: someone put it there, probably to fix
  something that is invisible to you *because the fence is working*. You arrive with no
  history, so absence of a visible reason is evidence of your ignorance, not of its
  uselessness. When your fresh measurement contradicts what the human vaguely remembers
  ("I changed this once, because of some problem"), **your measurement is the suspect first**
  — it may be measuring the case that *isn't* failing. Go find the original problem, then
  decide. *(A CIFS share was benchmarked with a big sequential `dd`, looked fast, and the
  local-disk download dir was "fixed" away — while the actual failure was random writes:
  par2, unrar, torrent piece-writes. Two wrong commits.)*
- **Goal-driven execution — define the success check, then loop to it.** Turn the task into
  something verifiable before coding: "add validation" → write tests for invalid inputs, then
  pass them; "fix the bug" → write a failing repro test, then pass it; "refactor X" → tests
  green before and after. For multi-step work, state a brief plan with a verify step each.
- **"Flaky" is not a diagnosis — test in the environment the thing actually runs in.** A
  component that fails *consistently* under automation is being **mis-invoked**, not being
  unreliable; "it works when I run it by hand" is not evidence that it works. The shell you
  test in has a TTY, a `$HOME`, an `ssh-agent`, an interactive stdin — the systemd unit, the
  CI job and the scripted harness have none of those, so a passing manual run can be testing
  a different program. Reproduce it *there* (start the unit, `env -u SSH_AUTH_SOCK`,
  `</dev/null`, `--dry-run` to print the real command line) before accepting "unstable" as a
  cause. **When a fix doesn't change the symptom, stop fixing and go look at what is actually
  being executed.** *(An interactive-mode flag with no TTY made one harness fail every review
  panel for weeks, written off as "flaky"; it was the wrong flag.)*
- **KISS — don't solve a problem you don't have yet.** Simplicity isn't "write less code";
  it's not building for a need that doesn't exist. Let structure emerge from the code.
- **YAGNI & flat.** No preventive abstractions, no single-use interfaces. Interfaces for
  real boundaries only. Architecture is *extracted* once a pattern proves itself in real
  use — never designed up front for a user who doesn't exist yet. Need pulls architecture.
- **Order: make it work → make it right → make it fast** (Kent Beck), in that order. Most
  over-engineering is doing "right"/"fast" before a working thing exists to justify it.
- **Flag scope creep — a standing duty, not a suggestion.** When a solo tool starts being
  framed as a public / multi-user / multi-tenant / plugin-system / configurable-N-backends
  platform before a real, present need exists, STOP and ask: "Is this needed now?" Justify
  future-proofing against a need that exists *today*.
- **No silent decisions (comprehension debt).** Never make a silent architectural or
  design call — state it and record the rationale, so the reasoning is recoverable later.
- **Real decisions are presented in the chat, in isolation — never via popup.** When a
  design/architecture/scope/trade-off decision arises, surface it on its own: the options,
  what each means, pros/cons/trade-offs, and a recommendation — then decide together.
  Don't bury it mid-text or bundle it with other topics, and don't compress it into a
  quick-pick widget (e.g. AskUserQuestion) — the widget skips the reasoning and overlays
  the explanation. Widgets are for trivial short-answer picks only.
- **Long answers are written to be scanned, not read twice.** For recaps, status reports,
  batch reviews, plans, and any comparison of options: lead with the outcome in one line,
  then break the body into bullets and **bold** the load-bearing terms. Options are always
  a list — one bullet per option, the recommended one marked — never a paragraph the reader
  has to parse to find the choices. Reserve unbroken prose for short arguments; a wall of
  paragraphs costs more in re-reading than the structure would have cost in words.

## Git: branches, commits, PRs, comments

- **Ask the repo for its default branch; never assume one.** Repos differ — `master` and `main`
  are both common, often in the same person's account — and a wrong guess sends a PR to a branch
  that does not exist, or, worse, has you "fixing" a URL that was right all along.
  `git symbolic-ref --short refs/remotes/origin/HEAD | sed 's|^origin/||'`, or
  `gh repo view --json defaultBranchRef -q .defaultBranchRef.name`.
  Never commit directly to it: branch, then PR.
- **Branches** — Conventional Branch (conventionalbranch.org): `<type>/<kebab-description>`,
  types `feature/`, `bugfix/`, `hotfix/`, `chore/`, `release/`, `docs/`.
- **Commits** — Conventional Commits (conventionalcommits.org): `<type>(scope): <description>`,
  types `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`, `build`, `perf`, `style`.
  Breaking change → `!` after the type or a `BREAKING CHANGE:` footer.
- **Atomic commits** — one logical change per commit, each independently green and
  revertible. Never `git add .` blind; split unrelated changes.
- **Always work in your own worktree — mandatory, not conditional.** Parallel sessions
  are opened freely and nothing signals their existence to you, so a "check whether another
  session is here first" step can never be reliable — the honest answer is always "maybe".
  The only collision-proof arrangement is structural: keep the main working tree on the
  default branch as a clean reference and **never work in it** — before your first write
  (commit, branch, rebase, stash; read-only exploration is exempt), create your own worktree
  and do everything there:
  `git worktree add ../<repo>-<task> -b <your-branch> <origin>/<default-branch>`. Do this
  **whether or not** you believe another agent is running — that belief is exactly what you
  cannot verify. Report which worktree/branch you used; remove it once merged. Only the human
  can see all the open sessions.
- **Pull requests** — describe **what + why**. *What*: a 1–3 line summary. *Why* (the bulk):
  decisions, trade-offs, rejected alternatives. The diff shows the what; the PR explains why.
- **Comments** — always **WHY, not WHAT**: explain intent, never restate the obvious
  mechanics. Keep existing comments; they carry intent.

## Code style (baseline)

- Functions: 4–40 lines, one thing each (SRP). Files: under ~500 lines, split by responsibility.
- Names specific and unique — avoid `data`, `handler`, `Manager`, `util`.
- Explicit types. Early returns over nested ifs; max ~2 levels of indentation.
- Inject dependencies; wrap third-party libs behind a thin interface this project owns.
- No duplication — but don't extract *too early*. Tolerate duplication while the pattern is
  still forming; extract the abstraction *from* proven, repeated code, never ahead of it.
- **Refactoring is not automatic.** After a large feature, list refactoring candidates
  (files > ~500 lines, duplicated logic, long functions, hardcoded config) and ask before
  pruning — the human decides, the tests are the safety net. Consolidate when the thing
  works and the seams are obvious, not before.
<!-- END universal-principles v3 -->
