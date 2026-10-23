import { LitElement, html, css } from 'lit';
import { calendarText, shared, spinner } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { BUILTIN, setInstalledElves, setCatalog, updateFor } from './tabs.js';
import './diff-view.js';
import './elf-settings.js';
import './elf-schedule.js';
import './elf-manage.js';
import './elf-settings-dialog.js';

const short = sha => (sha ?? '').slice(0, 7);

/**
 * The + at the end of the tabs, and the setup wizard on a new desk: the elves you can add (House-elves' and your own,
 * found by the house-elf topic), with what each says it reads and writes before you add it. Adding one opens its new
 * tab, which sets it up. An installed elf's settings, update and removal live in its tab; an elf without a tab
 * (mvnpm, until the GitHub elf takes it) keeps them here.
 */
class DeskElves extends LitElement {

    static properties = {
        _installed: { state: true },
        _catalog: { state: true },
        _busy: { state: true },        // install:<repo> while adding it
        _error: { state: true },
        _catalogError: { state: true },
        _focus: { state: true },       // #elves/<owner>/<name>: the elf Elf Maker just published, looked up directly
        _focused: { state: true },
        _focusError: { state: true },
        _changes: { state: true },
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
        h3 { font-size: 16px; font-weight: 600; }
        .desc { margin: 0; max-width: 46rem; }
        .meta { font-size: 13px; color: var(--muted); display: flex; flex-wrap: wrap; gap: 4px 14px; }
        .meta a { color: inherit; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        dl { display: grid; grid-template-columns: minmax(0, 9rem) minmax(0, 1fr); gap: 6px 16px; margin: 0; font-size: 14px; }
        dt { color: var(--muted); }
        dd { margin: 0; min-width: 0; }
        dd ul { margin: 0; padding-left: 18px; }
        .warn-note { margin: 0; font-size: 13.5px; color: var(--warn); max-width: 46rem; }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; }
        code { font: 13px var(--font-code); background: var(--surface-2); padding: 2px 6px; border-radius: 5px; }
        .sk { height: 120px; }
        .when { padding: 16px 18px; }
        h3 code { font-weight: 400; color: var(--muted); margin-left: 6px; }
        a.chip { text-decoration: none; }
        .make { padding: 18px 20px; display: flex; flex-wrap: wrap; gap: 10px 18px; align-items: center; justify-content: space-between; }
        .make p { margin: 0; max-width: 40rem; }
        .focus { outline: 2px solid var(--accent); outline-offset: 2px; }
        .commits { margin: 0; padding-left: 18px; font: 13px var(--font-code); }
        @media (max-width: 600px) {
            h1 { font-size: 26px; }
            dl { grid-template-columns: minmax(0, 1fr); }
            dt { margin-top: 6px; }
        }
    `];

    constructor() {
        super();
        this._onHash = () => this._readFocus();
    }

    connectedCallback() {
        super.connectedCallback();
        window.addEventListener('hashchange', this._onHash);
        this._readFocus();
        this._load();
    }

    disconnectedCallback() {
        window.removeEventListener('hashchange', this._onHash);
        super.disconnectedCallback();
    }

    /** #elves/<owner>/<name>: Elf Maker sent you here to add (or update) the elf it just published. */
    async _readFocus() {
        const m = /^#elves\/([A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9._-]{1,100})$/.exec(location.hash);
        const repo = m?.[1] ?? null;
        if (repo === this._focus) return;
        this._focus = repo;
        this._focused = null;
        this._focusError = null;
        this._changes = null;
        if (!repo) return;
        try {
            this._focused = await (await api()).elfRepo({ repo });
        } catch (e) {
            this._focusError = errorMessage(e);
        }
    }

    async _focusChanges(f) {
        this._busy = 'changes';
        this._error = null;
        try {
            this._changes = await (await api()).elfChanges({ elf: f.installed.name, commit: f.commit });
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _focusUpdate(f) {
        this._busy = 'update';
        this._error = null;
        try {
            const r = await (await api()).updateElf({ elf: f.installed.name, commit: f.commit });
            setInstalledElves(r.elves ?? []);
            location.hash = `#${f.installed.name}`;
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    /** The elf Elf Maker published: Add it, or (added already) its update, with the changes to read first. */
    _focusSection() {
        const f = this._focused;
        if (this._focusError) return html`<p class="error" role="alert">${this._focusError}</p>`;
        if (!f) return html`<div class="skeleton sk"></div>`;
        if (!f.installed) return html`<div class="focus" style="border-radius:var(--r-panel)">${this._available(f)}</div>`;
        const m = f.manifest ?? {};
        const c = this._changes;
        const current = f.installed.commit === f.commit;
        return html`<article class="panel elf focus" aria-labelledby="focus-h">
            <div class="top"><h3 id="focus-h">${m.title || m.name || f.repo}${m.version ? html` <code>version ${m.version}</code>` : ''}</h3></div>
            ${current ? html`<p class="desc">You have this version. <a href="#${f.installed.name}">Open ${m.title || f.installed.name}</a></p>`
                : html`<p class="desc">A new version of ${m.title || f.installed.name} is published: ${short(f.commit)} of
                    <a href=${f.url} target="_blank" rel="noopener">${f.repo}</a>.</p>
                ${c ? html`<p class="hint">This is the code that will run on your machine.</p>
                    <ul class="commits">${(c.commits ?? []).map(x => html`<li>${x}</li>`)}</ul>
                    <diff-view .diff=${c.diff} ?cut=${c.truncated} url="https://github.com/${f.repo}/compare/${c.from}...${c.to}"></diff-view>` : ''}
                <div class="acts">
                    <button class="primary" ?disabled=${!!this._busy} @click=${() => this._focusUpdate(f)}>
                        ${this._busy === 'update' ? spinner : ''}Update ${m.title || f.installed.name}</button>
                    ${c ? '' : html`<button class="quiet" ?disabled=${!!this._busy} @click=${() => this._focusChanges(f)}>
                        ${this._busy === 'changes' ? spinner : ''}See the changes first</button>`}
                </div>`}
        </article>`;
    }

    async _load() {
        this._error = null;
        const desk = await api();
        try {
            this._installed = (await desk.elves()).elves ?? [];
            setInstalledElves(this._installed);
        } catch (e) {
            this._error = errorMessage(e);
            this._installed = this._installed ?? [];
        }
        try {
            this._catalog = (await desk.elfCatalog()).elves;
            setCatalog(this._catalog);
            this._catalogError = null;
        } catch (e) {
            this._catalogError = errorMessage(e);
            this._catalog = this._catalog ?? [];
        }
    }

    /** Adds it, and opens its tab: its setup until it's set up (an elf without a tab opens its settings here). */
    async _install(c) {
        this._busy = `install:${c.repo}`;
        this._error = null;
        try {
            const r = await (await api()).installElf({ repo: c.repo, commit: c.commit });
            setInstalledElves(r.elves ?? []);
            const elf = (r.elves ?? []).find(e => e.repo === c.repo);
            if (elf?.tab) {
                location.hash = `#${elf.name}`;
                return;
            }
            // No tab to set it up in: its settings open here.
            await this._load();
            await this.updateComplete;
            this.renderRoot.querySelector(`elf-settings-dialog[elf="${elf?.name}"]`)?.show();
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    _lists(label, items) {
        return items?.length ? html`<dt>${label}</dt><dd><ul>${items.map(x => html`<li>${x}</li>`)}</ul></dd>` : '';
    }

    /** An installed elf without a tab: its settings, schedule, update and removal, in a pop-up from here. */
    _tabless(elf) {
        return html`<article class="panel elf" aria-labelledby="elf-${elf.name}">
            <div class="top">
                <h3 id="elf-${elf.name}">${elf.title || elf.name} <code>${elf.name}</code></h3>
                <div class="chips">${elf.missing?.length ? html`<span class="chip warn">Needs setting up</span>` : ''}
                    ${updateFor(elf.name) ? html`<span class="chip warn">Update waiting</span>` : ''}</div>
            </div>
            <p class="desc">${elf.description}</p>
            <div class="acts">
                <button class="sm" @click=${e => e.target.closest('article').querySelector('elf-settings-dialog').show()}>Settings</button>
            </div>
            <elf-settings-dialog elf=${elf.name} heading="${elf.title || elf.name} settings"
                note="In ~/.config/${elf.name}/config, only readable by you. Secrets are never shown."
                @elf-settings-saved=${() => this._load()} @elf-removed=${() => this._load()}></elf-settings-dialog>
        </article>`;
    }

    _available(c) {
        const m = c.manifest;
        return html`<article class="panel elf" aria-labelledby="av-${c.repo}">
            <div class="top">
                <h3 id="av-${c.repo}">${m?.title || m?.name || c.repo}${m?.name ? html` <code>${m.name}</code>` : ''}</h3>
                <div class="chips">
                    ${c.official ? html`<span class="chip ok">House-elves</span>` : html`<span class="chip warn">Not from House-elves</span>`}
                    ${c.private ? html`<span class="chip">Private</span>` : ''}
                </div>
            </div>
            <p class="desc">${m?.description || c.description}</p>
            <div class="meta">
                ${m?.version ? html`<span>Version ${m.version}</span>` : ''}
                ${m?.author ? html`<span>By <a href="https://github.com/${m.author}" target="_blank" rel="noopener">@${m.author}</a></span>` : ''}
                <a href=${c.url} target="_blank" rel="noopener">${c.repo}</a><span>at ${short(c.commit)}</span>
            </div>
            ${c.problem ? html`<p class="warn-note">Can't add it: ${c.problem}.</p>` : html`
                <dl>
                    ${this._lists('Reads', m.reads)}
                    ${this._lists('Writes', m.writes)}
                    ${m.tab ? html`<dt>Adds</dt><dd>A ${m.title || m.name} tab${m.card ? ', and a card on the dashboard' : ''}</dd>` : ''}
                    ${m.quietActions?.length ? html`<dt>Does on a click</dt><dd>${m.quietActions.join('; ')}</dd>` : ''}
                    ${m.askingActions?.length ? html`<dt>Asks you first</dt><dd>${m.askingActions.join('; ')}</dd>` : ''}
                    ${m.agents ? html`<dt>Agents</dt><dd>Starts Claude Code on a PR or issue when you ask, in the runner's sandbox, with no
                        GitHub credentials</dd>` : ''}
                    ${m.tools?.length ? html`<dt>Needs</dt><dd>${m.tools.join(', ')}</dd>` : ''}
                    ${m.settings?.length ? html`<dt>Asks for</dt><dd>${m.settings.map((s, i) => html`${i ? ', ' : ''}${s.label}${s.secret ? ' (secret)' : ''}`)}</dd>` : ''}
                    <dt>Runs</dt><dd>${m.refreshes ? html`When you refresh it${m.schedule ? html`, and ${calendarText(m.schedule)}
                        (you can change when)` : ''}. Its first run starts once it's set up.` : 'When you start it'}</dd>
                </dl>
                ${c.official ? '' : html`<p class="warn-note">It runs on your machine as you, with the settings you give it.
                    Read its code on GitHub before you add it.</p>`}
                <div class="acts">
                    <button class="primary" ?disabled=${!!this._busy} @click=${() => this._install(c)}>
                        ${this._busy === `install:${c.repo}` ? spinner : ''}Add ${m.title || m.name}</button>
                    <span class="hint">${m.tab ? 'Then its new tab helps you set it up.' : 'Then set it up below.'}</span>
                </div>`}
        </article>`;
    }

    render() {
        const installed = this._installed;
        const available = (this._catalog ?? []).filter(c => !c.installed);
        const tabless = (installed ?? []).filter(e => !e.tab && !e.tabs?.length);
        const maker = (installed ?? []).some(e => e.name === BUILTIN);
        const fresh = installed && !installed.some(e => e.name !== BUILTIN);
        return html`<div class="page">
            <div>
                <h1>Add an elf</h1>
                <p class="intro">Each elf does one job, on a schedule or when you refresh it, here on this machine and as you.
                    Anything that sends or posts still waits for your click. Each elf you add gets its own tab.</p>
            </div>
            ${fresh ? html`<section aria-labelledby="setup-h">
                <h2 id="setup-h">Set up your desk</h2>
                <ol class="steps">
                    <li><strong>Pick an elf</strong> below. Each says what it reads and what it can write.</li>
                    <li><strong>Fill in its settings</strong> on its new tab. Secrets are only ever written, never shown again.</li>
                    <li><strong>It runs once by itself</strong> when it's set up, then when you refresh it or on its schedule.</li>
                </ol>
            </section>` : ''}
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            ${this._focus ? html`<section aria-labelledby="made-h">
                <h2 id="made-h">From Elf Maker</h2>
                ${this._focusSection()}
            </section>` : ''}
            ${maker ? html`<section aria-labelledby="make-h">
                <h2 id="make-h">Make your own</h2>
                <div class="panel make">
                    <p>Can't find the elf you want? Say what it should do, and Elf Maker works out how it would work, builds it,
                        and adds it here once you've read it.</p>
                    <button class="primary" @click=${() => { location.hash = `#${BUILTIN}`; }}>Create an elf</button>
                </div>
            </section>` : ''}
            <section aria-labelledby="available-h">
                <h2 id="available-h">Elves you can add</h2>
                <p class="hint">Repos with the <code>house-elf</code> topic: House-elves' own, and your private ones.</p>
                ${this._catalogError ? html`<p class="error" role="alert">${this._catalogError}</p>` : ''}
                ${!this._catalog ? html`<div class="skeleton sk"></div>`
                    : available.length ? available.map(c => this._available(c))
                    : html`<p class="panel empty">You have every elf there is.</p>`}
            </section>
            ${tabless.length ? html`<section aria-labelledby="tabless-h">
                <h2 id="tabless-h">Added, without a tab</h2>
                <p class="hint">These work behind the scenes. Their settings are here.</p>
                ${tabless.map(e => this._tabless(e))}
            </section>` : ''}
            <section aria-labelledby="runner-h">
                <h2 id="runner-h">How agents run</h2>
                <p class="hint">The runner's settings, for every elf that starts an agent (such as GitHub's).</p>
                <div class="acts">
                    <button class="sm" @click=${() => this.renderRoot.querySelector('#runner-settings').show()}>The runner's settings</button>
                </div>
                <elf-settings-dialog id="runner-settings" elf="elf-runner" heading="How agents run"
                    note="In ~/.config/house-elves/runner.config, only readable by you. Most apply when the runner restarts."></elf-settings-dialog>
            </section>
        </div>`;
    }
}

customElements.define('desk-elves', DeskElves);
