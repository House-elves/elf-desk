import { LitElement, html, css } from 'lit';
import { calendarText, shared, spinner, ago, settingsToggle } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { setInstalledElves } from './tabs.js';
import './elf-settings.js';
import './diff-view.js';

const short = sha => (sha ?? '').slice(0, 7);

/** "Mon 26 Oct, 08:00". */
function when(iso) {
    if (!iso) return '';
    return new Date(iso).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

/**
 * The Elves page, and the setup wizard on a new desk: the elves you can install (House-elves' and your own, found by
 * the house-elf topic), what each says it reads and writes before you install it, then its settings and a dry run.
 * Each elf is installed at the commit shown, and only moves when you look at the changes and update it.
 */
class DeskElves extends LitElement {

    static properties = {
        _installed: { state: true },
        _catalog: { state: true },
        _open: { state: true },        // name of the installed elf whose settings are open
        _changes: { state: true },     // {elf, commit, commits, diff, truncated} being looked at
        _removing: { state: true },
        _busy: { state: true },        // what's in flight: install:<repo>, update:<elf>, run:<elf>, …
        _error: { state: true },
        _catalogError: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 30px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .intro { color: var(--muted); margin: 6px 0 0; max-width: 44rem; }
        section { display: grid; gap: 12px; }
        h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .hint { font-size: 14px; color: var(--muted); margin: 0; }
        ol.steps { margin: 0; padding-left: 20px; display: grid; gap: 4px; color: var(--muted); font-size: 14px; }
        ol.steps strong { color: var(--ink); font-weight: 600; }
        .elf { padding: 18px 20px; display: grid; gap: 12px; min-width: 0; }
        .top { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: baseline; justify-content: space-between; }
        h3 { font-size: 16px; font-weight: 600; font-family: var(--font-code); }
        .desc { margin: 0; max-width: 46rem; }
        .meta { font-size: 13px; color: var(--muted); display: flex; flex-wrap: wrap; gap: 4px 14px; }
        .meta a { color: inherit; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        dl { display: grid; grid-template-columns: minmax(0, 9rem) minmax(0, 1fr); gap: 6px 16px; margin: 0; font-size: 14px; }
        dt { color: var(--muted); }
        dd { margin: 0; min-width: 0; }
        dd ul { margin: 0; padding-left: 18px; }
        .warn-note { margin: 0; font-size: 13.5px; color: var(--warn); max-width: 46rem; }
        .confirm { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 14px;
            padding: 10px 12px; border-radius: var(--r-control); background: var(--surface-2); }
        pre.out { margin: 0; max-height: 18rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere;
            font: 12.5px/1.5 var(--font-code); background: var(--surface-2); border: 1px solid var(--line);
            border-radius: var(--r-control); padding: 10px 12px; }
        .run { display: grid; gap: 6px; }
        .run-head { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; font-size: 13.5px; color: var(--muted); }
        .commits { margin: 0; padding-left: 18px; font: 13px var(--font-code); }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; }
        code { font: 13px var(--font-code); background: var(--surface-2); padding: 2px 6px; border-radius: 5px; }
        .sk { height: 120px; }
        @media (max-width: 600px) {
            h1 { font-size: 26px; }
            dl { grid-template-columns: minmax(0, 1fr); }
            dt { margin-top: 6px; }
        }
    `];

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    disconnectedCallback() {
        clearTimeout(this._poll);
        super.disconnectedCallback();
    }

    async _load() {
        this._error = null;
        const desk = await api();
        try {
            this._showInstalled(await desk.elves());
        } catch (e) {
            this._error = errorMessage(e);
            this._installed = this._installed ?? [];
        }
        try {
            this._catalog = (await desk.elfCatalog()).elves;
            this._catalogError = null;
        } catch (e) {
            this._catalogError = errorMessage(e);
            this._catalog = this._catalog ?? [];
        }
    }

    _showInstalled(r) {
        this._installed = r.elves ?? [];
        setInstalledElves(this._installed);
        // While a run is going, look again every few seconds so its output shows when it ends.
        clearTimeout(this._poll);
        if (this._installed.some(e => (e.runs ?? []).some(x => x.running))) {
            this._poll = setTimeout(async () => {
                try {
                    this._showInstalled(await (await api()).elves());
                } catch (e) {
                    // the next load will say what's wrong
                }
            }, 4000);
        }
    }

    async _do(busy, call) {
        this._busy = busy;
        this._error = null;
        try {
            const r = await call(await api());
            if (r?.elves) this._showInstalled(r);
            return r;
        } catch (e) {
            this._error = errorMessage(e);
            return null;
        } finally {
            this._busy = null;
        }
    }

    async _install(c) {
        const r = await this._do(`install:${c.repo}`, desk => desk.installElf({ repo: c.repo, commit: c.commit }));
        if (r) {
            this._open = c.manifest?.name;
            this._load();
        }
    }

    async _showChanges(elf, commit) {
        const r = await this._do(`changes:${elf.name}`, desk => desk.elfChanges({ elf: elf.name, commit }));
        if (r) this._changes = r;
    }

    async _update(elf, commit) {
        const r = await this._do(`update:${elf}`, desk => desk.updateElf({ elf, commit }));
        if (r) {
            this._changes = null;
            this._load();
        }
    }

    async _remove(elf) {
        const r = await this._do(`remove:${elf}`, desk => desk.removeElf({ elf }));
        if (r) {
            this._removing = null;
            this._load();
        }
    }

    _run(elf, command) {
        return this._do(`run:${elf}`, desk => desk.runElf({ elf, command, params: null }));
    }

    _catalogFor(elf) {
        return (this._catalog ?? []).find(c => c.repo === elf.repo);
    }

    _lists(label, items) {
        return items?.length ? html`<dt>${label}</dt><dd><ul>${items.map(x => html`<li>${x}</li>`)}</ul></dd>` : '';
    }

    _installedElf(elf) {
        const latest = this._catalogFor(elf);
        const behind = latest && latest.commit !== elf.commit;
        const missing = (elf.tools ?? []).filter(t => !t.found).map(t => t.name);
        const dry = (elf.runs ?? []).find(r => r.command === elf.dryRun);
        const changes = this._changes?.elf === elf.name ? this._changes : null;
        return html`<article class="panel elf" aria-labelledby="elf-${elf.name}">
            <div class="top">
                <h3 id="elf-${elf.name}">${elf.name}</h3>
                <div class="chips">
                    <span class="chip" title=${elf.commit}>at ${short(elf.commit)}</span>
                    ${behind ? html`<span class="chip warn">Update: ${short(latest.commit)}</span>` : ''}
                    ${missing.length ? html`<span class="chip bad">Needs ${missing.join(', ')}</span>` : ''}
                </div>
            </div>
            <p class="desc">${elf.description}</p>
            <div class="meta">
                <a href="https://github.com/${elf.repo}" target="_blank" rel="noopener">${elf.repo}</a>
                ${elf.schedule ? html`<span>Runs ${calendarText(elf.schedule.calendar)}${elf.schedule.next ? html`, next ${when(elf.schedule.next)}` : ''}</span>`
                    : html`<span>Runs when you start it from its tab</span>`}
            </div>
            <div class="acts">
                ${settingsToggle(this._open === elf.name, () => { this._open = this._open === elf.name ? null : elf.name; })}
                ${elf.dryRun ? html`<button class="sm" ?disabled=${!!this._busy || dry?.running}
                        @click=${() => this._run(elf.name, elf.dryRun)}>
                    ${dry?.running ? html`${spinner} Dry run going` : 'Dry run'}</button>` : ''}
                ${behind ? html`<button class="sm" ?disabled=${!!this._busy} aria-expanded=${changes ? 'true' : 'false'}
                        @click=${() => changes ? (this._changes = null) : this._showChanges(elf, latest.commit)}>
                    ${this._busy === `changes:${elf.name}` ? spinner : ''}${changes ? 'Hide changes' : 'See the changes'}</button>` : ''}
                <button class="sm quiet danger" ?disabled=${!!this._busy} @click=${() => { this._removing = elf.name; }}>Remove</button>
            </div>
            ${this._removing === elf.name ? html`<div class="confirm" role="group" aria-label="Remove ${elf.name}">
                Remove ${elf.name}? Its settings stay, for if you install it again.
                <button class="sm danger" ?disabled=${!!this._busy} @click=${() => this._remove(elf.name)}>
                    ${this._busy === `remove:${elf.name}` ? spinner : ''}Remove</button>
                <button class="sm quiet" @click=${() => { this._removing = null; }}>Keep it</button>
            </div>` : ''}
            ${this._open === elf.name ? html`<elf-settings elf=${elf.name} heading="Settings"
                    note="In ~/.config/${elf.name}/config, only readable by you. Secrets are never shown."></elf-settings>` : ''}
            ${dry && (dry.running || dry.output) ? html`<div class="run" aria-live="polite">
                <div class="run-head">
                    ${dry.running ? html`<span class="chip run">Running</span>`
                        : dry.failed ? html`<span class="chip bad">Dry run failed</span>` : html`<span class="chip ok">Dry run done</span>`}
                    ${dry.at && !dry.running ? html`<span>${ago(dry.at)}</span>` : ''}
                </div>
                ${dry.output ? html`<pre class="out">${dry.output}</pre>` : ''}
            </div>` : ''}
            ${changes ? html`<div class="run">
                <p class="hint">${changes.commits.length} new commit${changes.commits.length === 1 ? '' : 's'} since ${short(changes.from)}.
                    This is the code that will run on your machine.</p>
                <ul class="commits">${changes.commits.map(c => html`<li>${c}</li>`)}</ul>
                <diff-view .diff=${changes.diff} ?cut=${changes.truncated}
                           url="https://github.com/${elf.repo}/compare/${changes.from}...${changes.to}"></diff-view>
                <div class="acts">
                    <button class="primary" ?disabled=${!!this._busy} @click=${() => this._update(elf.name, changes.to)}>
                        ${this._busy === `update:${elf.name}` ? spinner : ''}Update to ${short(changes.to)}</button>
                    <button class="quiet" @click=${() => { this._changes = null; }}>Not now</button>
                </div>
            </div>` : ''}
        </article>`;
    }

    _available(c) {
        const m = c.manifest;
        return html`<article class="panel elf" aria-labelledby="av-${c.repo}">
            <div class="top">
                <h3 id="av-${c.repo}">${m?.name ?? c.repo}</h3>
                <div class="chips">
                    ${c.official ? html`<span class="chip ok">House-elves</span>` : html`<span class="chip warn">Not from House-elves</span>`}
                    ${c.private ? html`<span class="chip">Private</span>` : ''}
                </div>
            </div>
            <p class="desc">${m?.description || c.description}</p>
            <div class="meta"><a href=${c.url} target="_blank" rel="noopener">${c.repo}</a><span>at ${short(c.commit)}</span></div>
            ${c.problem ? html`<p class="warn-note">Can't install it: ${c.problem}.</p>` : html`
                <dl>
                    ${this._lists('Reads', m.reads)}
                    ${this._lists('Writes', m.writes)}
                    ${m.tabs?.length ? html`<dt>Adds</dt><dd>${m.tabs.length === 1 ? 'A tab' : 'Tabs'}: ${m.tabs.join(', ')}</dd>` : ''}
                    ${m.quietActions?.length ? html`<dt>Does on a click</dt><dd>${m.quietActions.join('; ')}</dd>` : ''}
                    ${m.askingActions?.length ? html`<dt>Asks you first</dt><dd>${m.askingActions.join('; ')}</dd>` : ''}
                    ${m.tools?.length ? html`<dt>Needs</dt><dd>${m.tools.join(', ')}</dd>` : ''}
                    ${m.settings?.length ? html`<dt>Asks for</dt><dd>${m.settings.map((s, i) => html`${i ? ', ' : ''}${s.label}${s.secret ? ' (secret)' : ''}`)}</dd>` : ''}
                    <dt>Runs</dt><dd>${m.schedule ? `${calendarText(m.schedule)}, and when you start it` : 'When you start it'}</dd>
                </dl>
                ${c.official ? '' : html`<p class="warn-note">It runs on your machine as you, with the settings you give it.
                    Read its code on GitHub before you install it.</p>`}
                <div class="acts">
                    <button class="primary" ?disabled=${!!this._busy} @click=${() => this._install(c)}>
                        ${this._busy === `install:${c.repo}` ? spinner : ''}Install at ${short(c.commit)}</button>
                </div>`}
        </article>`;
    }

    render() {
        const installed = this._installed;
        const available = (this._catalog ?? []).filter(c => !c.installed);
        const fresh = installed && !installed.length;
        return html`<div class="page">
            <div>
                <h1>Elves</h1>
                <p class="intro">Each elf does one job, on a schedule or when you start it, here on this machine and as you.
                    Anything that sends or posts still waits for your click.</p>
            </div>
            ${fresh ? html`<section aria-labelledby="setup-h">
                <h2 id="setup-h">Set up your desk</h2>
                <ol class="steps">
                    <li><strong>Pick elves</strong> below. Each says what it reads and what it can write.</li>
                    <li><strong>Fill in their settings.</strong> Secrets are only ever written, never shown again.</li>
                    <li><strong>Do a dry run</strong> to see it work on your own data. It sends nothing.</li>
                </ol>
            </section>` : ''}
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            ${installed?.length ? html`<section aria-labelledby="installed-h">
                <h2 id="installed-h">Installed</h2>
                ${installed.map(e => this._installedElf(e))}
            </section>` : ''}
            <section aria-labelledby="available-h">
                <h2 id="available-h">${fresh ? 'Elves you can install' : 'More elves'}</h2>
                <p class="hint">Repos with the <code>house-elf</code> topic: House-elves' own, and your private ones.</p>
                ${this._catalogError ? html`<p class="error" role="alert">${this._catalogError}</p>` : ''}
                ${!this._catalog ? html`<div class="skeleton sk"></div>`
                    : available.length ? available.map(c => this._available(c))
                    : html`<p class="panel empty">You have every elf there is.</p>`}
            </section>
        </div>`;
    }
}

customElements.define('desk-elves', DeskElves);
