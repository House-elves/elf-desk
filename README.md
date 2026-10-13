# elf-desk

A [House Elf](https://github.com/House-elves) desk: a morning board of every open GitHub PR and issue you're involved in, grouped by what each one needs from you. Built with Quarkus, the Web Bundler, Lit (from mvnpm) and JSON-RPC.

Design doc: <https://claude.ai/artifact/HBSU81qqwHHQVUZ7v1VD3E>

**Phases 1 and 2 are built:** the board, plus agent threads. **Review** (PRs), **Investigate** (issues) and **Ask** (either) start an agent on [elf-runner](../elf-runner), which streams its work into a thread on the item. You follow up in the thread, then edit, discard or post the draft it produces. Merging and opening PRs come in Phase 3.

## Tabs

- **Dashboard** (the start page): one sentence per tab on what needs you there.
- **GitHub**: the board below. **Review**, **Investigate** and the CI and conflict actions start the agent and leave you on the board: the row shows **Agent working**, then **Answer ready** or **Draft ready**. **Ask** opens the item, since you type the question there.
- **Fix it** (on an issue's page, once the agent has answered): the agent fixes the issue as one commit on the default branch and drafts the PR. **Open PR…** shows where it goes and warns about open PRs that already mention the issue; **Push and open PR** syncs your fork, pushes the commit to a new `fix-<issue>-<sha>` branch on it (never a force push) and opens the PR.
- **Email**: **Triage email now** runs [daily-triage](../daily-triage) `--triage-only --no-email` through the runner. It marks noise read and labels it, as its 8 AM email run did, and lists what needs you per Gmail account. Its timer (`daily-triage.timer`) is disabled; `systemctl --user enable --now daily-triage.timer` brings the morning email back.
- **Zulip**: **Check Zulip now** runs `daily-triage --zulip-only`: the last 24 hours plus older unread mentions, with a digest per topic, marking nothing read.

The desk reads daily-triage's saved runs from `elfdesk.triage.runs-dir` (`~/.config/daily-triage/runs`) and never writes there.

## Threads and posting

- **One thread per issue or PR,** backed by one resumable Claude session. Follow-ups continue it with the context it has already built up.
- **Drafts.** When the agent has something postable (a review with inline comments, or a comment), it shows as a draft you can edit, discard or post. A newer draft replaces an older pending one.
- **Posting takes two steps.** **Post…** shows exactly what will be sent and issues a one-time confirmation. **Post to GitHub** then sends it. If the draft changes in between, or ten minutes pass, you have to preview again. **Close** works the same way, with an inline confirmation. Every write is recorded in the `github_action` table.
- **Writes go through the runner,** which sends them with the machine's `gh` login for its `GITHUB_USER`. That's the "GitHub CLI" app orgs already approve, so it works wherever Claude Code's `gh` does, and orgs with OAuth App access restrictions don't block it. The desk holds no write token, and its GitHub sign-in only reads (`read:org`). Only `Poster` asks the runner to write, and the runner accepts only an allowlist of requests (see [elf-runner](../elf-runner)): reviews, comments, new issues and PRs, close and reopen, assign yourself, merges, CI re-runs and approvals. The agent, in its sandbox, has no credentials at all. Posting needs the runner online.

### mvnpm-dependabot

The **mvnpm updates** lane has **Run checks now** (a full round over every repo the elf watches, what its 06:00 timer used to run) and, on each PR, **Check now** (that PR only, re-checked even if already checked). The runner starts them as systemd user units on the host: the elf's own `mvnpm-dependabot.service`, or a one-off `mvnpm-dependabot-pr-…` unit. The lane shows what's running, and the board refreshes with the elf's verdicts when the checks finish. The elf does its own commenting, approving and summary email, as before. Its timer (`mvnpm-dependabot.timer`) is disabled: checks run when you ask. `systemctl --user enable --now mvnpm-dependabot.timer` brings the morning schedule back.

### Merge

**Merge** only shows on PRs that are **approved, have green CI, and that GitHub reports as CLEAN** (`mergeStateStatus`: every *required* check passed and nothing else blocks the merge), in every lane including mvnpm updates. A green status with only a few early checks isn't enough. Such PRs show **Waiting for required checks** instead, and **Behind base** when the repo requires an up-to-date branch. **Merge anyway** (More ▾, for green but unapproved PRs) follows the same rule. Approved means GitHub's review decision is APPROVED, or, where the repo doesn't require reviews, a reviewer with write access approved and none of them asks for changes. Clicking it shows the merge methods the repo allows, with your usual one preselected. Confirming merges straight away through the runner, pinned to the head commit you saw (`sha`), so nothing pushed since can be merged unseen. The desk refuses a merge that isn't approved or green, whatever the page showed. If GitHub is still working out whether the PR is mergeable, the request waits and is checked every minute (shown as "Merging"), under the same rules: new commits, red CI, conflicts or a lost approval stop it. Merged PRs show a purple **Merged** tag and no further actions until they leave the board.

### The runner connection

The runner connects to `ws://<desk>/runner` and sends `ELFDESK_RUNNER_TOKEN` (from `.env`) in its first message. With no token configured, every runner is refused. Jobs wait as `queued` until a runner is connected. If the runner reconnects without a job it had been running, that job is marked failed.

## What the board shows

| Lane | What's in it |
|---|---|
| Needs my review | Open PRs requesting your review |
| My PRs | Open PRs you authored |
| Assigned issues | Open issues assigned to you |
| Mentioned / involved | Everything else `involves:@me` finds |
| mvnpm | Dependabot `org.mvnpm` bumps in the repos [mvnpm-dependabot](https://github.com/House-elves/mvnpm-dependabot) watches, with that elf's verdict (`SAFE` / `NOT_SAFE` / `NEEDS_HUMAN`), whether it approved, and whether it has checked the PR's current head commit. Read-only: that elf does its own posting. |
| N more outside your scope | Items from repos you don't watch, review requests included. Each has **Watch repo** and **Watch org** buttons. |

An item appears in one lane only: the first that matches, in the order above (mvnpm first).

**Settings** chooses what the board watches: whole orgs or users (with optional excluded repos) and single repos. On first sign-in it suggests the orgs and users that have open items involving you, with the orgs you belong to ticked. It also shows the mvnpm lane's repos, which come from `REPOS` in `~/.config/mvnpm-dependabot/config`, and the board's refresh interval.

### How it fetches

Every refresh runs `is:open involves:@me` (up to GitHub's cap of 1000 results) and `is:open is:pr review-requested:@me` through the GraphQL API with your token, plus one search for Dependabot PRs in the mvnpm elf's repos. The scope is applied in the desk, not in the query, so the hidden count is exact and long scope lists don't hit GitHub's 256-character query limit. Results are cached for 2 minutes; **Refresh** skips the cache.

## Sign-in

GitHub OAuth, via `quarkus-oidc`'s GitHub provider. Only logins on `elfdesk.allowed-logins` (default `phillip-kruger`) get the `owner` role; any other GitHub account is signed in but refused. The GitHub token stays in the encrypted session cookie and is used only for your own calls.

### Create the OAuth App (once)

Signed in to GitHub as **phillip-kruger**, open <https://github.com/settings/applications/new>:

- **Application name:** Elf Desk (local)
- **Homepage URL:** `http://houseelves.lan:7777`
- **Authorization callback URL:** `http://houseelves.lan:7777/auth/callback`

Register it, click **Generate a new client secret**, and put both values in `.env` in this directory (it's git-ignored):

```
ELFDESK_GITHUB_CLIENT_ID=Ov23li...
ELFDESK_GITHUB_CLIENT_SECRET=...
```

The app asks only for `read:org`: the board reads public data, and writes go through the runner.

## Run it

### As a service (what you use)

Elf Desk runs as a systemd user service, `elf-desk.service`, from a packaged build in `~/.local/share/elf-desk/app`. It listens on port 7777 (where the GitHub OAuth callback points), uses the `elfdesk` database in the `elf-desk-pg` container, and reads its secrets from `~/.config/elf-desk/env` (mode 600): `ELFDESK_GITHUB_CLIENT_ID`, `ELFDESK_GITHUB_CLIENT_SECRET`, `ELFDESK_RUNNER_TOKEN`, `ELFDESK_DB_PASSWORD`. It starts after its database and at boot (lingering is on).

```shell
./deploy.sh              # tests, build, install, restart; the only way changes reach the running desk
./deploy.sh --no-tests
journalctl --user -u elf-desk -f
```

### Developing

```shell
./mvnw quarkus:dev       # port 7481, database elfdesk_dev: never touches the running desk
./mvnw test
```

Dev mode reads `.env` for the same variables. Signing in to dev mode needs its own GitHub OAuth App with a `http://<host>:7481/auth/callback` callback; most work doesn't need it (the tests mock GitHub).

The database is the Podman Quadlet `~/.config/containers/systemd/elf-desk-pg.container` (data in the `elf-desk-pg` volume). Flyway manages the schema in both databases. Tests use a throwaway Dev Services database.

## Configuration

| Property | Default | |
|---|---|---|
| `ELFDESK_GITHUB_CLIENT_ID` / `ELFDESK_GITHUB_CLIENT_SECRET` | none | The OAuth App |
| `elfdesk.allowed-logins` | `phillip-kruger` | Comma-separated GitHub logins allowed in |
| `ELFDESK_RUNNER_TOKEN` | none (refuse all) | Shared secret for elf-runner |
| `elfdesk.mvnpm.config` | `~/.config/mvnpm-dependabot/config` | The mvnpm elf's config (for `REPOS`) |
| `elfdesk.mvnpm.state-dir` | `~/.local/state/mvnpm-dependabot` | The mvnpm elf's `state.json` |
| `quarkus.http.port` | `7777` | |
| `QUARKUS_DATASOURCE_JDBC_URL` etc. | Dev Services | Postgres outside dev/test |

## Tests

```shell
./mvnw test
```

Unit tests cover the lane rules, scope matching, the mvnpm reader, GitHub response parsing, the prompts, untrusted-text detection and the runner token check. `@QuarkusTest`s cover scope storage, the JSON-RPC API, a full thread (start, runner answer, edit, preview, refused and accepted posts, audit, follow-up), failures, cancels and runner restarts, plus the sign-in redirect. GitHub is mocked throughout.

## Layout

```
src/main/java/io/github/houseelves/desk/
  api/DeskApi.java            JSON-RPC API for the UI (scope "desk")
  agent/                      prompts and the agent's output schema
  runner/                     the runner WebSocket (RunnerHub) and live pushes to the page
  thread/                     threads, turns, jobs, drafts; Poster (the only GitHub writer)
  board/                      Board, lane rules (BoardClassifier), fetching and caching (BoardService)
  github/                     GraphQL client and Item
  mvnpm/MvnpmElf.java         reads mvnpm-dependabot's config and state.json
  scope/                      watched orgs, users and repos
  security/                   owner role by GitHub login; the current user's token
  settings/                   key/value settings
src/main/resources/web/       index.html + Lit components (bundled by the Web Bundler)
src/main/resources/db/migration/   Flyway
```

## Guides

- [OIDC well-known providers (GitHub)](https://quarkus.io/guides/security-openid-connect-providers)
- [Web Bundler](https://docs.quarkiverse.io/quarkus-web-bundler/dev/)
- [JSON-RPC](https://docs.quarkiverse.io/quarkus-json-rpc/dev/)
- [Hibernate ORM with Panache](https://quarkus.io/guides/hibernate-orm-panache)
- [Flyway](https://quarkus.io/guides/flyway)
