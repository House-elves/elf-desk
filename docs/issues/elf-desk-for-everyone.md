# Let other people use Elf Desk, and add their own elves

Right now Elf Desk only works for one person on one machine. This issue covers what it takes for colleagues to install it on their own workstation, how to make that easy, and a plugin system where people describe an elf, the desk builds and installs it, and they can share it.

## What stops someone else using it today

### Hardcoded to one person

- **Sign-in:** `elfdesk.allowed-logins=phillip-kruger` is in `application.properties`, and the GitHub OAuth App used for sign-in belongs to that account. Every new user would have to register their own OAuth App.
- **Quarkus-team features:** the mvnpm lane, the "CI Merge Gate" handling, and the `dependabot` deployment-environment approval are built in. They're harmless for Quarkus colleagues, but they aren't optional.
- **The elves:** the runner has `daily-triage`, `weekly-status`, `quarterly-review` and `mvnpm-dependabot` written into its code (`ELF_CONFIGS` in `ElfRunner.java`).

### Install steps, all by hand

1. **Database:** a Postgres container in Podman (`elf-desk-pg`), with its password in `~/.config/elf-desk/env`.
2. **Desk secrets:** the OAuth App's client ID and secret, and a runner token shared by the desk's env and `~/.config/elf-runner/config`.
3. **Runner tools:** bwrap, Podman (for Dev Services and Testcontainers in reviews), Java 25 and jbang.
4. **Logins:** a one-time Claude login in the runner's own config dir (`CLAUDE_CONFIG_DIR=~/.local/share/elf-runner/claude claude`, then `/login`), `gh` logged in as `GITHUB_USER`, and `GIT_NAME`/`GIT_EMAIL` for agent commits.
5. **Builds:** each part is built from source with its own `deploy.sh`, and each elf has its own `install.sh` and systemd timer.
6. **Linux only:** it uses systemd user services and a bwrap sandbox. Many Quarkus developers are on macOS, which needs launchd and a different sandbox (for example `sandbox-exec`).

The per-elf settings that are already editable from the desk (Gmail, Zulip, schedules, and secrets as write-only fields) are fine as they are.

## Making it easier

1. **Local mode, no OAuth App.** When the desk only listens on localhost, sign in through the user's `gh` login: no OAuth App and no allowed-logins list. Keep OAuth as an option for opening the desk from another machine, for example over the LAN.
2. **No separate Postgres.** Use an embedded database in a file (H2 or SQLite), which takes Podman out of the desk entirely. Keep Postgres as an option.
3. **One installer, plus `doctor`.** For example `jbang elf-desk@House-elves/elf-desk install`. It would:
   - check every prerequisite and say exactly what's missing and how to get it;
   - generate the tokens;
   - write the services (systemd on Linux, launchd on macOS);
   - walk through the `gh` and Claude logins;
   - offer the elves to install.

   `elf doctor` would later say what broke: a service down, a login expired, a missing tool.
4. **Releases instead of building from source.** Publish desk and runner artifacts (a jbang catalog or GitHub releases), with updates applied from the desk.
5. **Optional features.** Make mvnpm and the Quarkus CI handling plugins you turn on, the way the mvnpm switch already works.

## Plugins: describe an elf, the desk builds it, people share it

The Email and Zulip tabs already follow a common shape: a scheduled run gathers data, Claude reads it, the desk shows cards, and you click actions. Turn that shape into a manifest.

### An elf is a folder

- `elf.yaml` holds:
  - name and description;
  - schedule;
  - settings and secrets, using the setting kinds the runner already has (TEXT, INT, EMAIL, EMAILS, SECRET, HOST, LOGIN, WORDS, CALENDAR);
  - the sources it reads: GitHub, Gmail, Zulip, iCal, web pages;
  - what it produces: cards with actions (Done, Open, Draft a reply), a document, or an email draft.
- `prompt.md` is the instruction for Claude.
- An optional jbang script fetches data that no built-in source covers.

### One runtime for every elf

- The runner gathers the sources the manifest names and runs Claude in the sandbox with the prompt.
- It checks the answer against the elf's output schema.
- The desk shows it in a generic tab, so a new elf needs no new desk code.
- The existing elves move to manifests one at a time. weekly-status is the first, to prove the design.

### "New elf" in the desk

1. You describe what you want in plain words.
2. An agent drafts `elf.yaml` and `prompt.md`.
3. The desk does a dry run on your real data and shows the result.
4. You install it, or refine it and try again.

### Sharing

- An elf is published as a folder in a GitHub repo. A shared catalog repo would let people browse.
- Installing is from a URL and is pinned to a commit.
- Before installing, the desk shows what the elf reads, which secrets it asks for, and what it can write.
- Updates arrive as a diff you approve.
- Setting values and secrets never leave your machine; only the manifest and prompt are shared.

### Safety rules stay as they are

- A shared prompt is untrusted input.
- The agent stays sandboxed with no credentials.
- Every write to GitHub or email still needs the user's click.

## Suggested order

1. **Installable for one colleague:** local mode, the embedded database, the installer with `doctor`, and releases. Linux first.
2. **The elf manifest and generic runtime,** proved by rebuilding weekly-status as a manifest-based elf.
3. **"New elf" and sharing:** the generator, the catalog and install-from-URL.
4. **macOS support:** launchd and a macOS sandbox.

## Open questions

- **Who is it for:** Quarkus colleagues, or anyone?
- **macOS from the start?** If yes, macOS support moves into phase 1, because it changes how the sandbox and services are built.
- **One desk per person** on their own machine (recommended: nobody's credentials sit on someone else's computer), or a shared server?
- **Where it lives:** elf-desk and elf-runner aren't on GitHub yet; they need a public home, possibly under House-elves.
