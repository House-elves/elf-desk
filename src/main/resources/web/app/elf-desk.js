import { LitElement, html, css, svg } from 'lit';
import { keyed } from 'lit/directives/keyed.js';
import { shared, itemKeyFromHash, closeX } from './shared.js';
import { api, connectionState, onConnectionChange, onBusyChange, onPush, errorMessage } from './rpc.js';
import { currentTheme, setTheme } from './theme.js';
import { tabs, findTab, onTabsChange, setInstalledElves, installedElves, installedElf, githubElf, setCatalog, updateFor } from './tabs.js';
import './desk-settings.js';
import './elf-tab.js';
import './elf-manage.js';
import './desk-dashboard.js';
import './desk-elves.js';

/** How often to look for updates of Elf Desk and the elves. */
const LOOK_EVERY = 10 * 60 * 1000;

/** Set once the setup wizard has shown in this browser, so the dashboard isn't skipped again. */
const WELCOMED = 'elfdesk.elves-welcomed';

/** The tabs before each elf had one, named after the elf: an old link still lands. */
const MOVED = { email: 'mail-triage', zulip: 'zulip-digest', status: 'weekly-status', quarterly: 'quarterly-review' };

const THEMES = [
    { id: 'system', label: 'Match system', icon: svg`<rect x="2.5" y="3" width="11" height="8" rx="1.5"/><path d="M6 13.5h4M8 11v2.5"/>` },
    { id: 'light', label: 'Light', icon: svg`<circle cx="8" cy="8" r="3"/><path d="M8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1"/>` },
    { id: 'dark', label: 'Dark', icon: svg`<path d="M13 9.5A5.5 5.5 0 0 1 6.5 3a5.5 5.5 0 1 0 6.5 6.5z"/>` },
];

/** The app shell: header, theme switch, a loader while anything is in flight, and the current view. */
class ElfDesk extends LitElement {

    static properties = {
        _view: { state: true },
        _conn: { state: true },
        _update: { state: true },    // {desk, deskLatest, runner, runnerLatest, available}: a newer Elf Desk is out
        _updating: { state: true },  // the update was asked for: the desk restarts
        _updateError: { state: true },
        _versions: { state: true },  // {desk, runner, runnerProtocol}: what's running; the desk's shows by its name
        _busy: { state: true },
        _theme: { state: true },
        _tabsChanged: { state: true },
        _elfUpdate: { state: true }, // the tab whose update pop-up is open
        _reloads: { state: true },   // an elf updated from its pop-up: its tab loads again
    };

    static styles = [shared, css`
        header {
            position: sticky; top: 0; z-index: 5;
            background: color-mix(in srgb, var(--bg) 82%, transparent);
            backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--line);
        }
        .bar {
            max-width: 1180px; margin: 0 auto; padding: 10px 24px;
            display: flex; flex-wrap: wrap; align-items: center; gap: 10px 22px;
        }
        .brand { display: inline-flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; }
        .brand svg { width: 26px; height: 26px; }
        .brand span { font-family: var(--font-display); font-size: 19px; font-weight: 700; letter-spacing: -0.015em; }
        nav { display: flex; gap: 2px; }
        nav a {
            font-size: 14px; font-weight: 500; padding: 6px 12px; border-radius: var(--r-control);
            color: var(--muted); text-decoration: none;
        }
        nav a:hover { color: var(--ink); background: var(--surface-2); }
        nav a[aria-current="page"] { color: var(--ink); background: var(--surface); box-shadow: inset 0 0 0 1px var(--line); }
        nav a { position: relative; }
        /* An update waiting: a small dot on the tab, as on an app with something new. */
        .update-dot { position: absolute; top: 3px; right: 3px; width: 7px; height: 7px; border-radius: 50%;
            background: var(--bobble); box-shadow: 0 0 0 2px var(--bg); }
        nav a.add { font-size: 18px; line-height: 1; padding: 4px 11px 6px; margin-left: 4px; border: 1px dashed var(--line); }
        .right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
        .conn { font-size: 13px; color: var(--muted); display: inline-flex; align-items: center; gap: 6px; }
        .conn.lost { color: var(--bad); }
        .theme {
            display: inline-flex; padding: 6px; border-radius: 999px; color: var(--muted);
            background: var(--surface-2); border: 1px solid var(--line);
        }
        .theme:hover { color: var(--ink); }
        .theme svg { width: 16px; height: 16px; display: block; }
        /* The loader: a thin bar along the header's bottom edge while any call is in flight. */
        .progress { position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; overflow: hidden; pointer-events: none; }
        .progress span {
            position: absolute; top: 0; bottom: 0; width: 30%; background: var(--accent); border-radius: 2px;
            opacity: 0; transition: opacity 200ms;
            animation: travel 1.1s ease-in-out infinite;
        }
        .progress.on span { opacity: 1; }
        @keyframes travel { from { left: -30%; } to { left: 100%; } }
        @media (prefers-reduced-motion: reduce) {
            .progress span { animation: none; left: 0; width: 100%; }
            .progress.on span { opacity: 0.6; }
        }

        .lost {
            background: var(--bad-soft); color: var(--bad); padding: 9px 24px; text-align: center; font-size: 14px;
        }
        main { max-width: 1180px; margin: 0 auto; padding: 32px 24px 80px; }
        /* Its version, quietly, right after the name (the bar's gap would push it away). */
        button.version { position: relative; border: 0; background: none; padding: 2px 12px 2px 0; cursor: pointer; }
        button.version .update-dot { top: 0; right: 2px; }
        button.version:hover { color: var(--ink); }
        .update-dialog { width: min(32rem, calc(100vw - 32px)); padding: 22px 24px; border: 1px solid var(--line);
            border-radius: var(--r-panel); background: var(--bg); color: var(--ink); box-shadow: var(--shadow); }
        .update-dialog::backdrop { background: color-mix(in srgb, var(--ink) 35%, transparent); }
        .update-dialog h2 { font-size: 20px; font-weight: 600; margin: 0 0 12px; }
        .update-dialog ul { margin: 0 0 12px; padding-left: 18px; }
        .update-dialog .acts { display: flex; gap: 10px; margin-top: 14px; }
        .update-dialog .hint { color: var(--muted); font-size: 14px; }
        .update-dialog.elf { width: min(52rem, calc(100vw - 32px)); }
        .update-dialog code { font: 13px var(--font-code); }
        .version { font-size: 12.5px; color: var(--muted); font-variant-numeric: tabular-nums; margin-left: -14px; }
        @media (max-width: 640px) {
            .bar { padding: 10px 16px; }
            main { padding: 22px 16px 64px; }
        }
    `];

    constructor() {
        super();
        this._view = this._viewFromHash();
        this._conn = connectionState();
        this._busy = false;
        this._theme = currentTheme();
        this._onHash = () => {
            this._view = this._viewFromHash();
            window.scrollTo({ top: 0 });
        };
    }

    connectedCallback() {
        super.connectedCallback();
        window.addEventListener('hashchange', this._onHash);
        // Settings saved anywhere (a tab's dialog or setup, the + page) may finish an elf's setup: its tab shows then.
        this.addEventListener('elf-settings-saved', () => this._loadElves());
        this._offTabs = onTabsChange(() => {
            this._tabsChanged = (this._tabsChanged ?? 0) + 1;
            this._view = this._viewFromHash();
        });
        this._offConn = onConnectionChange(s => {
            this._conn = s;
            if (s === 'open') {
                let updating = false;
                try { updating = !!sessionStorage.getItem('elfdesk-updating'); sessionStorage.removeItem('elfdesk-updating'); } catch { /* no storage */ }
                if (updating && this._updating !== undefined) { location.reload(); return; }
                this._loadElves();
                this._loadVersions(); // the desk's own, so not waiting on the runner
            }
        });
        this._loadElves();
        this._loadVersions();
        // The runner came or went: what's running, and once it's back, which elves there are.
        this._offRunner = onPush('desk.runner', () => { this._loadVersions(); this._loadElves(); });
        // Which elves, and whether Elf Desk, have an update waiting (a mark): now, every 10 minutes, and when you come
        // back to the page after a while.
        this._lookForUpdates();
        this._updatesTimer = setInterval(() => this._lookForUpdates(), LOOK_EVERY);
        this._onVisible = () => {
            if (document.visibilityState === 'visible' && Date.now() - this._lookedAt > LOOK_EVERY) this._lookForUpdates();
        };
        document.addEventListener('visibilitychange', this._onVisible);
        // Show the bar only for calls that take a moment, so quick ones don't flicker.
        this._offBusy = onBusyChange(busy => {
            clearTimeout(this._busyTimer);
            if (busy) this._busyTimer = setTimeout(() => { this._busy = true; }, 150);
            else this._busy = false;
        });
    }

    disconnectedCallback() {
        window.removeEventListener('hashchange', this._onHash);
        this._offTabs?.();
        this._offConn?.();
        this._offBusy?.();
        this._offRunner?.();
        clearInterval(this._updatesTimer);
        document.removeEventListener('visibilitychange', this._onVisible);
        super.disconnectedCallback();
    }

    /**
     * Which elves are installed, so only their tabs show. On a new desk (none yet), the first visit goes to the
     * + page: the setup wizard.
     */
    async _loadElves() {
        try {
            const r = await (await api()).elves();
            setInstalledElves(r.elves ?? []);
            let welcomed = true;
            try { welcomed = !!localStorage.getItem(WELCOMED); } catch { /* no storage: never redirect */ }
            if (!r.elves?.length && !welcomed && this._view === 'dashboard') {
                try { localStorage.setItem(WELCOMED, '1'); } catch { /* no storage */ }
                location.hash = '#elves';
            }
        } catch (e) {
            // No runner: which elves there are isn't known, so their tabs wait for it (it says when it's back).
        }
    }

    async _lookForUpdates() {
        this._lookedAt = Date.now();
        try {
            this._update = await (await api()).update();
        } catch {
            // GitHub didn't answer, or an older desk: no mark
        }
        try {
            const desk = await api();
            if (installedElves() === null) setInstalledElves((await desk.elves()).elves ?? []);
            setCatalog((await desk.elfCatalog()).elves);
        } catch {
            // GitHub or the runner didn't answer: no marks until the next look
        }
    }

    async _loadVersions() {
        try {
            const v = await (await api()).versions();
            // The desk was updated while this page was open (from another tab, or house-elves install): this page is
            // the old one, which may call what the new desk no longer has. Load the new one.
            if (this._versions?.desk && v?.desk && v.desk !== this._versions.desk) {
                location.reload();
                return;
            }
            this._versions = v;
        } catch {
            // an older desk, or it's restarting: the next connection tries again
        }
    }

    /**
     * Which Elf Desk this is, next to its name (each elf shows its own version in its settings). With a newer one
     * out, it has the update mark, and opens the update.
     */
    _renderVersions() {
        const v = this._versions?.desk;
        if (!v) return '';
        const label = v.includes('SNAPSHOT') ? 'local build' : `v${v}`;
        if (!this._update?.available) return html`<span class="version" title="Elf Desk's version">${label}</span>`;
        return html`<button class="version has-update" title="A newer Elf Desk is out" aria-label="${label}, a newer Elf Desk is out"
                @click=${() => this.renderRoot.querySelector('.update-dialog').showModal()}>${label}<span class="update-dot"></span></button>
            ${this._renderUpdateDialog()}`;
    }

    /**
     * The tab you're on has an update: clicking it again shows it, as the version does for Elf Desk. Deeper in the tab
     * (a thread page), the click still takes you to the tab's top first.
     */
    _tabClick(e, t) {
        if (!this._current(t.id) || location.hash !== `#${t.id}` || !updateFor(t.elf)
                || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return;
        e.preventDefault();
        this._elfUpdate = t;
        this.updateComplete.then(() => this.renderRoot.querySelector('.update-dialog.elf')?.showModal());
    }

    /** An elf's update: its version now and the newest, with Update and the changes to read first (elf-manage). */
    _renderElfUpdate() {
        const t = this._elfUpdate;
        const e = t && installedElf(t.elf);
        const close = () => this.renderRoot.querySelector('.update-dialog.elf')?.close();
        return html`<dialog class="update-dialog elf" aria-label="Update ${t?.label ?? 'the elf'}" @close=${() => { this._elfUpdate = null; }}>
            ${e ? html`${closeX()}
                <h2>Update ${t.label}</h2>
                <p class="hint">${e.version ? `Version ${e.version}, at` : 'At'} <code>${(e.commit ?? '').slice(0, 7)}</code> of
                    <a href="https://github.com/${e.repo}" target="_blank" rel="noopener">${e.repo}</a>. Its settings, schedule and
                    data stay as they are. It's also in its settings.</p>
                <elf-manage only="update" elf=${t.elf} @elf-updated=${() => {
                    this._reloads = { ...this._reloads, [t.elf]: (this._reloads?.[t.elf] ?? 0) + 1 };
                    close();
                }}></elf-manage>
                <div class="acts"><button class="quiet" @click=${close}>Not now</button></div>` : ''}
        </dialog>`;
    }

    _renderUpdateDialog() {
        const u = this._update;
        const v = s => !s ? '?' : /^\d/.test(s) ? `v${s}` : s;
        const row = (name, now, latest, repo) => html`<li>${name}: ${v(now)}${latest && latest !== v(now)
            ? html` → <a href="https://github.com/House-elves/${repo}/releases/tag/${latest}" target="_blank" rel="noopener">${latest}</a>` : ', up to date'}</li>`;
        return html`<dialog class="update-dialog" aria-label="Update Elf Desk">
            ${closeX()}
            <h2>Update Elf Desk</h2>
            <ul>
                ${row('Elf Desk', u.desk, u.deskLatest, 'elf-desk')}
                ${row('Runner', u.runner, u.runnerLatest, 'elf-runner')}
            </ul>
            <p class="hint">This downloads them and restarts both, as <code>house-elves install</code> does. It takes a minute;
                this page reloads when the desk is back. It waits if an agent is at work.</p>
            ${this._updateError ? html`<p class="error" role="alert">${this._updateError}</p>` : ''}
            ${this._updating ? html`<p role="status">Updating… the desk is restarting.</p>` : ''}
            <div class="acts">
                <button class="primary" ?disabled=${this._updating} @click=${() => this._updateNow()}>Update</button>
                <button class="quiet" @click=${() => this.renderRoot.querySelector('.update-dialog').close()}>Not now</button>
            </div>
        </dialog>`;
    }

    async _updateNow() {
        this._updateError = null;
        try {
            await (await api()).updateNow();
            this._updating = true;
            // When the desk is back, this page loads its new version.
            try { sessionStorage.setItem('elfdesk-updating', '1'); } catch { /* reload by hand */ }
        } catch (e) {
            this._updateError = errorMessage(e);
        }
    }

    _viewFromHash() {
        // GitHub is the github elf's tab: the old links (#settings, a thread's #thread/...) go there too. Until the
        // runner has said which elves there are, they wait; the desk's own board is gone.
        if (['#github', '#settings'].includes(location.hash) || itemKeyFromHash(location.hash)) {
            return githubElf() ? 'tab:github' : installedElves() === null ? 'waiting' : 'no-tab';
        }
        if (location.hash === '#elves') return 'elves';
        // An elf's tab: #mail-triage, or a place in it: #github/thread/quarkusio/quarkus/123 (see _route). Until the
        // runner has said which elves there are, it waits.
        let id = location.hash.slice(1).split('/')[0];
        if (MOVED[id] && !findTab(id) && findTab(MOVED[id])) id = MOVED[id];
        if (/^[a-z0-9][a-z0-9-]*$/.test(id)) return findTab(id) ? `tab:${id}` : installedElves() === null ? 'waiting' : 'no-tab';
        return 'dashboard';
    }

    /** Where in an elf's tab the hash points: what follows #<elf>/, or a thread's own link for the github elf. */
    _route() {
        if (githubElf() && itemKeyFromHash(location.hash)) return location.hash.slice(1);
        const [, ...rest] = location.hash.slice(1).split('/');
        return rest.join('/');
    }

    /** An installed elf without a tab has an update: the + page is where its settings are. */
    _tablessUpdate() {
        return (installedElves() ?? []).some(e => !findTab(e.name) && updateFor(e.name));
    }

    _current(tab) {
        return this._view === `tab:${tab}`;
    }

    /** An elf's tab: its setup until it's set up, then what the elf draws. keyed: a different elf is a new frame. */
    _elfTab(t) {
        return t ? keyed(`${t.elf}:${this._reloads?.[t.elf] ?? 0}`, html`<elf-tab .tab=${t} .route=${this._route()}></elf-tab>`) : '';
    }

    /** One button: the theme it is now; each click goes to the next (match system, light, dark). */
    _renderTheme() {
        const i = Math.max(0, THEMES.findIndex(t => t.id === this._theme));
        const now = THEMES[i], next = THEMES[(i + 1) % THEMES.length];
        return html`<button class="theme" title="${now.label}. Click for ${next.label.toLowerCase()}."
                aria-label="Theme: ${now.label}. Switch to ${next.label.toLowerCase()}" @click=${() => this._pickTheme(next.id)}>
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${now.icon}</svg>
        </button>`;
    }

    _pickTheme(id) {
        this._theme = id;
        setTheme(id);
    }

    render() {
        return html`
            <header>
                <div class="bar">
                    <a class="brand" href="#" aria-label="Elf Desk, dashboard">
                        <svg viewBox="0 0 120 120" aria-hidden="true">
                            <rect width="120" height="120" rx="28" fill="#24533f"/>
                            <g transform="translate(-5 4)">
                                <path d="M22 62 L46 86 C60 66 72 46 84 30 C90 22 98 20 104 26" fill="none" stroke="#f4f6f1"
                                      stroke-width="16" stroke-linecap="round" stroke-linejoin="round"/>
                                <circle cx="104" cy="32" r="12" fill="var(--bobble)"/>
                            </g>
                        </svg>
                        <span>Elf Desk</span>
                    </a>
                    ${this._renderVersions()}
                    <nav aria-label="Views">
                        <a href="#" aria-current=${this._view === 'dashboard' ? 'page' : 'false'}>Dashboard</a>
                        ${tabs().map(t => html`
                            <a href="#${t.id}" aria-current=${this._current(t.id) ? 'page' : 'false'} @click=${e => this._tabClick(e, t)}
                               title=${!updateFor(t.elf) ? '' : this._current(t.id) ? `${t.label} has an update: click to see it`
                                   : `${t.label} has an update: click the tab again to see it`}>${t.label}${updateFor(t.elf)
                                ? html`<span class="update-dot" aria-label=", has an update"></span>` : ''}</a>`)}
                        <a href="#elves" class="add" aria-current=${this._view === 'elves' ? 'page' : 'false'}
                           aria-label="Add an elf${this._tablessUpdate() ? ', an elf without a tab has an update' : ''}"
                           title=${this._tablessUpdate() ? 'Add an elf. An elf without a tab has an update.' : 'Add an elf'}>+${this._tablessUpdate()
                                ? html`<span class="update-dot"></span>` : ''}</a>
                    </nav>
                    <div class="right">
                        ${this._conn !== 'open' ? html`
                            <span class="conn ${this._conn}">${this._conn === 'lost' ? 'Disconnected' : 'Connecting…'}</span>` : ''}
                        ${this._renderTheme()}
                    </div>
                </div>
                <div class="progress ${this._busy ? 'on' : ''}" role="progressbar" aria-label="Loading"
                     aria-hidden=${this._busy ? 'false' : 'true'}><span></span></div>
                ${this._conn === 'lost' ? html`
                    <div class="lost" role="alert">
                        Lost the connection to the desk. It may have restarted: <a href="${location.pathname}">reload</a>.
                        If it still won't connect, open it again with <code>house-elves open</code>.
                    </div>` : ''}
            </header>
            ${this._renderElfUpdate()}
            <main>
                ${this._view === 'elves' ? html`<desk-elves></desk-elves>`
                    : this._view.startsWith('tab:') ? this._elfTab(findTab(this._view.slice(4)))
                    : this._view === 'waiting' ? this._versions && !this._versions.runner
                        ? html`<p class="muted">The runner isn't connected, so the elves' tabs can't show yet. They come back when it
                            does; if it doesn't, start it again with <code>house-elves start</code>.</p>`
                        : html`<div class="skeleton" style="height:40vh"></div>`
                    : this._view === 'no-tab' ? html`<p class="muted">No elf by that name is installed. Add one with the
                        <a href="#elves">+</a> at the end of the tabs.</p>`
                    : html`<desk-dashboard></desk-dashboard>`}
            </main>
        `;
    }
}

customElements.define('elf-desk', ElfDesk);
