<img src="docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# elf-desk

A [House Elf](https://github.com/House-elves) desk: a morning board of every open GitHub PR and issue you're involved in, grouped by what each one needs from you. Built with Quarkus, the Web Bundler, Lit (from mvnpm) and JSON-RPC.

Design doc: <https://claude.ai/artifact/HBSU81qqwHHQVUZ7v1VD3E>

**Phases 1 and 2 are built:** the board, plus agent threads. **Review** (PRs), **Investigate** (issues) and **Ask** (either) start an agent on [elf-runner](../elf-runner), which streams its work into a thread on the item. You follow up in the thread, then edit, discard or post the draft it produces. Merging and opening PRs come in Phase 3.

## Tabs

- **Dashboard** (the start page): one sentence per tab on what needs you there.
- **GitHub**: the board below. **Review**, **Investigate** and the CI and conflict actions start the agent and leave you on the board: the row shows **Agent working**, then **Answer ready** or **Draft ready**. **Ask** opens the item, since you type the question there.
- **Fix it** (on an issue's page, once the agent has answered): the agent fixes the issue as one commit on the default branch and drafts the PR. **Open draft PR…** shows where it goes and warns about open PRs that already mention the issue; **Push and open draft PR** syncs your fork, pushes the commit to a new `fix-<issue>-<sha>` branch on it (never a force push) and opens it as a **draft** PR. Read the change on GitHub, then **Ready for review** (on the PR's row in My PRs, or its page) marks it ready. The runner only opens draft PRs, and only marks your own PRs ready.
- **Diffs**: a PR's page has **Show files changed** (GitHub's diff), and every draft that pushes code (commits to push, a fix to open as a PR) has **Show the change**, the runner's diff of exactly what would be pushed, so you can read it before confirming.
- **One tab per elf**: every other tab belongs to an elf, named by its `title`: mail-triage's **Mail**, zulip-digest's **Zulip**, weekly-status's **Weekly status** and quarterly-review's **Quarterly review**. The **+** after the last tab adds an elf, and its new tab helps you set it up; removing it (in its settings) takes the tab away. Tabs come in the order you added them. See [Elf tabs](#elf-tabs).

## Elves

The **+** after the last tab adds elves; each elf's own settings update and remove it. The + page is also the setup wizard: on a new desk with no elves, the first visit goes there.

- **Finding them:** repos with the `house-elf` topic, House-elves' public ones and your own (private ones too). Each has an `elf.yaml` at its root saying what it is: its settings, the commands the desk may run, its schedule, what it reads and what it writes. See [weekly-status's](https://github.com/House-elves/weekly-status/blob/main/elf.yaml) for an example.
- **Before you add one,** the + page shows what the elf reads and writes, the tools it needs and the settings it asks for. An elf from outside House-elves says it runs as you, so read its code first. Install only takes repos from that list.
- **Installing** clones the repo into `~/.local/share/house-elves/elves/<name>` at the commit shown, and gives its config file (`~/.config/<name>/config`) the settings' defaults. Settings it already has are kept, so an elf you set up by hand keeps its settings.
- **Updating** never happens by itself. In the elf's settings, when the repo has newer commits, **See the changes** shows the commits and the diff from the installed commit, read from the elf's own clone, and **Update** moves it there.
- **Setting up** happens on the elf's tab: adding an elf goes there, and until the settings it can't run without are filled in (marked **Needed**), the tab shows its settings form instead. Once they're saved, the runner starts its first refresh by itself (not for an elf that already has runs saved), so the tab fills.
- **Each elf's settings** (its tab's Settings button) hold its own settings (when its tab draws them), its setup, when it refreshes, its version with any update, and **Remove**. A tab with a dot has an update: on that tab, click it again for the update pop-up (the changes to read, and Update). Removing keeps its config file, for if you add it again. An elf without a tab has these on the + page.
- **Refreshing**: each elf names the command that brings its tab up to date (`refresh`). Its tab's Refresh button and the dashboard's **Refresh everything** run it, and so does its schedule: never, at set times, or every hour between two times, on the days you pick (in its settings). The elf's `elf.yaml` suggests one until you change it. The runner runs schedules, not systemd timers, so they work on macOS too, and a run missed while the laptop slept runs once when it's back.

## Elf tabs

An elf brings its own tab, and a card on the dashboard. Its `elf.yaml` names it (`title`, shown on the tab and the card), says which page draws it (`tab: {page: ui/mail.js, card: true}`, and `settings: true` when that page also draws the elf's own settings, at the top of its Settings pop-up) and how it refreshes (`refresh: {command: triage, schedule: "Mon..Fri *-*-* 07:00"}`), with the folders its tab may read (`data`, under `~/.config/<name>/`) and the actions it may ask for (`actions`). Settings it can run without say `optional: true`; the others without a default must be set before it runs. The tab is the elf's own code, so it never runs in the desk's page:

- **A sandboxed frame.** It's drawn in an iframe with `sandbox="allow-scripts"` and no `allow-same-origin`, so it has no origin of its own: no cookie, no page key, no storage, nothing of the desk's page. Its CSP lets it connect nowhere (`connect-src 'none'`). Its files come from `/elf-files/<key>/ui/...`, where the key is made for each elf and commit when the desk starts.
- **A bridge.** The frame can only post messages to the desk's page, which answers for that one elf: its runs (and when it refreshes on its own), its own folders, its commands and the actions its `elf.yaml` declares. Its settings, with its schedule, open in the desk's own dialog, so secrets never reach it.
- **Your click.** An action that asks (`ask` is the default) waits for you in a bar the desk draws ("The desk asks, for weekly-status"), with what it would send. Only actions marked `ask: false`, such as marking an email read, run straight from the tab. The + page lists both before you add an elf.
- **The kit.** Elves build their tabs with `/elf-kit/1/kit.js`: Lit, the desk's look, a run bar and `elf`, the bridge. See mail-triage's [ui/](https://github.com/House-elves/mail-triage/tree/main/ui).

## Threads and posting

- **One thread per issue or PR,** backed by one resumable Claude session. Follow-ups continue it with the context it has already built up.
- **Drafts.** When the agent has something postable (a review with inline comments, or a comment), it shows as a draft you can edit, discard or post. A newer draft replaces an older pending one.
- **Posting takes two steps.** **Post…** shows exactly what will be sent and issues a one-time confirmation. **Post to GitHub** then sends it. If the draft changes in between, or ten minutes pass, you have to preview again. **Close** works the same way, with an inline confirmation. Every write is recorded in the `github_action` table.
- **Writes go through the runner,** which sends them with the machine's `gh` login for its `GITHUB_USER`. That's the "GitHub CLI" app orgs already approve, so it works wherever Claude Code's `gh` does, and orgs with OAuth App access restrictions don't block it. The desk reads with the same `gh` account and never writes with it: only `Poster` asks the runner to write, and the runner accepts only an allowlist of requests (see [elf-runner](../elf-runner)): reviews, comments, new issues and PRs, close and reopen, assign yourself, merges, CI re-runs and approvals. The agent, in its sandbox, has no credentials at all. Posting needs the runner online.

### mvnpm-dependabot

The **mvnpm updates** lane has **Run checks now** (a full round over every repo the elf watches, what its 06:00 timer used to run) and, on each PR, **Check now** (that PR only, re-checked even if already checked). The runner starts them as systemd user units on the host: the elf's own `mvnpm-dependabot.service`, or a one-off `mvnpm-dependabot-pr-…` unit. The lane shows what's running, and the board refreshes with the elf's verdicts when the checks finish. The elf does its own commenting, approving and summary email, as before. Its timer (`mvnpm-dependabot.timer`) is disabled: checks run when you ask. `systemctl --user enable --now mvnpm-dependabot.timer` brings the morning schedule back.

### Merge

**Merge** only shows on PRs that are **approved, have green CI, and that GitHub reports as CLEAN** (`mergeStateStatus`: every *required* check passed and nothing else blocks the merge), in every lane including mvnpm updates. A green status with only a few early checks isn't enough. Such PRs show **Waiting for required checks** instead, and **Behind base** when the repo requires an up-to-date branch. **Merge anyway** (More ▾, for green but unapproved PRs) follows the same rule. Approved means GitHub's review decision is APPROVED, or, where the repo doesn't require reviews, a reviewer with write access approved and none of them asks for changes. Clicking it shows the merge methods the repo allows, with your usual one preselected. Confirming merges straight away through the runner, pinned to the head commit you saw (`sha`), so nothing pushed since can be merged unseen. The desk refuses a merge that isn't approved or green, whatever the page showed. If GitHub is still working out whether the PR is mergeable, the request waits and is checked every minute (shown as "Merging"), under the same rules: new commits, red CI, conflicts or a lost approval stop it. Merged PRs show a purple **Merged** tag and no further actions until they leave the board.

### The runner connection

The runner connects to `ws://<desk>/runner` and sends the runner token (`elfdesk.runner.token`, which `house-elves install` puts in both config files) in its first message. With no token configured, every runner is refused. Jobs wait as `queued` until a runner is connected. If the runner reconnects without a job it had been running, that job is marked failed.

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

There's no sign-in page. The desk works as one of the accounts `gh` is logged in to (`elfdesk.github-user`, chosen by `house-elves install`) and reads GitHub with that account's `gh` token. Writes still go through the runner, only when you click.

Who may use it:

- **This machine only.** It listens on 127.0.0.1, and refuses any request whose host isn't `localhost`, `127.0.0.1` or `[::1]`, so a web page can't reach it by pointing its own domain at 127.0.0.1 (DNS rebinding).
- **At its own name.** Pages are only served at `elfdesk.host`, <http://elf-desk.localhost:7777> (Chrome, Firefox and Edge send `*.localhost` to 127.0.0.1 themselves); other local names are redirected there. Browsers keep cookies per host name, not per port, so a cookie on `localhost` would reach every other server there, such as an agent's dev mode. Health checks and the runner may use any local name.
- **Whoever holds a session.** `house-elves open` opens a one-time link (`/open?code=…`) that signs the browser in for 30 days with two secrets: a cookie (HttpOnly, SameSite=Strict, named per port) and a page key in the page's own storage. Cookies are shared by every port of a host name, so a server on another port could be sent the cookie; browser storage is per origin, port included. The page itself needs only the cookie, but everything carrying data (the JSON-RPC socket, the agent's files) also needs the page key (`k`). The data directory keeps only hashes, in `sessions`; `house-elves sign-out` deletes it, signing every browser out. Without a session, pages go to `/welcome`, which says how to open the desk.
- **Only from its own pages.** Anything but a page load (the JSON-RPC socket included) must come from the desk's own origin, and no other site may frame it.

From another machine, forward the port (`ssh -L 7777:localhost:7777 <this machine>`), run `house-elves open --print` here and open the link it prints there.

## Run it

### Installed (what you use)

[house-elves](https://github.com/House-elves/house-elves) installs the desk and the runner as user services (systemd on Linux, launchd on macOS): `house-elves install`, then `house-elves open`, `status`, `logs`, `restart`, `doctor`.

| Where | What |
|---|---|
| `~/.config/house-elves/desk.properties` | The desk's settings: `elfdesk.github-user`, `elfdesk.runner.token` (mode 600) |
| `~/.local/share/house-elves/` | The database (`desk.mv.db`), the sessions, and the installed programs in `app/` |

To run your own changes in place of the release:

```shell
./deploy.sh              # tests, build, swap into ~/.local/share/house-elves/app/desk, restart
./deploy.sh --no-tests
house-elves logs desk
```

### Developing

```shell
./mvnw quarkus:dev       # port 7481, data in ~/.local/share/house-elves-dev: never touches the installed desk
./mvnw test
```

The database is an H2 file, and Flyway manages its schema. Tests use an in-memory H2 database, so nothing needs Podman. `tools/PostgresToH2.java` copies a desk's data from the Postgres it used before.

## Configuration

Set these in `desk.properties` (or as environment variables).

| Property | Default | |
|---|---|---|
| `elfdesk.github-user` | the active `gh` account | The account the desk works as |
| `elfdesk.runner.token` | none (refuse all) | Shared secret for elf-runner |
| `elfdesk.mvnpm.config` | `~/.config/mvnpm-dependabot/config` | The mvnpm elf's config (for `REPOS`) |
| `elfdesk.mvnpm.state-dir` | `~/.local/state/mvnpm-dependabot` | The mvnpm elf's `state.json` |
| `quarkus.http.port` | `7777` | |
| `elfdesk.host` | `elf-desk.localhost` | The desk's own name. `localhost` for a browser that doesn't resolve `*.localhost` (its cookie then reaches every port there) |
| `HOUSE_ELVES_CONFIG` / `HOUSE_ELVES_DATA` | `~/.config/house-elves` / `~/.local/share/house-elves` | Where the config file and the data live |

## Tests

```shell
./mvnw test
```

Unit tests cover the lane rules, scope matching, the mvnpm reader, GitHub response parsing, the prompts, untrusted-text detection and the runner token check. `@QuarkusTest`s cover scope storage, the JSON-RPC API, a full thread (start, runner answer, edit, preview, refused and accepted posts, audit, follow-up), failures, cancels and runner restarts, plus the sessions, the one-time link, the desk's own name, the origin check and the localhost check. GitHub is mocked throughout.

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
  security/                   sessions, the desk's own name and localhost only, the gh account and its token
  settings/                   key/value settings
src/main/resources/web/       index.html + Lit components (bundled by the Web Bundler)
src/main/resources/db/migration/   Flyway
```

## Guides

- [Security: custom authentication mechanisms](https://quarkus.io/guides/security-customization)
- [Web Bundler](https://docs.quarkiverse.io/quarkus-web-bundler/dev/)
- [JSON-RPC](https://docs.quarkiverse.io/quarkus-json-rpc/dev/)
- [Hibernate ORM with Panache](https://quarkus.io/guides/hibernate-orm-panache)
- [Flyway](https://quarkus.io/guides/flyway)
