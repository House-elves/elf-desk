import { LitElement, html, css, svg } from 'lit';
import { keyed } from 'lit/directives/keyed.js';
import { shared, itemKeyFromHash } from './shared.js';
import { api, connectionState, onConnectionChange, onBusyChange, onPush } from './rpc.js';
import { currentTheme, setTheme } from './theme.js';
import { tabs, findTab, onTabsChange, setInstalledElves, installedElves, githubElf } from './tabs.js';
import './desk-board.js';
import './desk-settings.js';
import './desk-thread.js';
import './elf-tab.js';
import './desk-dashboard.js';
import './desk-elves.js';

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
        _versions: { state: true },  // {desk, runner, runnerProtocol}: what's running, at the bottom of the page
        _busy: { state: true },
        _theme: { state: true },
        _tabsChanged: { state: true },
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
        nav a.add { font-size: 18px; line-height: 1; padding: 4px 11px 6px; margin-left: 4px; border: 1px dashed var(--line); }
        .right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
        .conn { font-size: 13px; color: var(--muted); display: inline-flex; align-items: center; gap: 6px; }
        .conn.lost { color: var(--bad); }
        .themes {
            display: inline-flex; padding: 2px; border-radius: 999px;
            background: var(--surface-2); border: 1px solid var(--line);
        }
        .themes button {
            border: 0; background: transparent; padding: 5px; border-radius: 999px; color: var(--muted);
        }
        .themes button[aria-pressed="true"] { background: var(--accent-soft); color: var(--accent); }
        .themes svg { width: 16px; height: 16px; display: block; }
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
        main { max-width: 1180px; margin: 0 auto; padding: 32px 24px 48px; }
        .versions { max-width: 1180px; margin: 0 auto; padding: 0 24px 32px; display: flex; flex-wrap: wrap; gap: 4px 18px;
            font-size: 12.5px; color: var(--muted); font-variant-numeric: tabular-nums; }
        .versions a { color: inherit; text-decoration: none; }
        .versions a:hover { color: var(--ink); text-decoration: underline; }
        @media (max-width: 640px) {
            .bar { padding: 10px 16px; }
            main { padding: 22px 16px 40px; }
            .versions { padding: 0 16px 24px; }
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
            if (s === 'open') this._loadElves();
        });
        this._loadElves();
        this._offRunner = onPush('desk.runner', () => this._loadVersions());
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
            this._loadVersions();
            let welcomed = true;
            try { welcomed = !!localStorage.getItem(WELCOMED); } catch { /* no storage: never redirect */ }
            if (!r.elves?.length && !welcomed && this._view === 'dashboard') {
                try { localStorage.setItem(WELCOMED, '1'); } catch { /* no storage */ }
                location.hash = '#elves';
            }
        } catch (e) {
            // No runner: only GitHub's tab, until it's back.
            if (installedElves() === null) setInstalledElves([]);
        }
    }

    async _loadVersions() {
        try {
            this._versions = await (await api()).versions();
        } catch {
            // an older desk, or it's restarting: the next connection tries again
        }
    }

    /** Which Elf Desk this is (each elf shows its own version in its settings). */
    _renderVersions() {
        const v = this._versions?.desk;
        if (!v) return '';
        return html`<footer class="versions">Elf Desk ${v.includes('SNAPSHOT') ? 'local build' : `v${v}`}</footer>`;
    }

    _viewFromHash() {
        // With the github elf installed, its tab is the board: GitHub's old links (its settings, a thread) go there.
        if (githubElf() && (['#github', '#settings'].includes(location.hash) || itemKeyFromHash(location.hash))) return 'tab:github';
        if (location.hash === '#settings') return 'github'; // the board's settings now live under GitHub
        if (location.hash === '#github' || location.hash === '#elves') return location.hash.slice(1);
        if (itemKeyFromHash(location.hash)) return 'thread';
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

    _current(tab) {
        return tab === 'github' && !githubElf() ? ['github', 'thread'].includes(this._view) : this._view === `tab:${tab}`;
    }

    /** An elf's tab: its setup until it's set up, then what the elf draws. keyed: a different elf is a new frame. */
    _elfTab(t) {
        return t ? keyed(t.elf, html`<elf-tab .tab=${t} .route=${this._route()}></elf-tab>`) : '';
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
                    <nav aria-label="Views">
                        <a href="#" aria-current=${this._view === 'dashboard' ? 'page' : 'false'}>Dashboard</a>
                        ${tabs().map(t => html`
                            <a href="#${t.id}" aria-current=${this._current(t.id) ? 'page' : 'false'}>${t.label}</a>`)}
                        <a href="#elves" class="add" aria-current=${this._view === 'elves' ? 'page' : 'false'}
                           aria-label="Add an elf" title="Add an elf">+</a>
                    </nav>
                    <div class="right">
                        ${this._conn !== 'open' ? html`
                            <span class="conn ${this._conn}">${this._conn === 'lost' ? 'Disconnected' : 'Connecting…'}</span>` : ''}
                        <div class="themes" role="group" aria-label="Theme">
                            ${THEMES.map(t => html`
                                <button title=${t.label} aria-label=${t.label} aria-pressed=${this._theme === t.id ? 'true' : 'false'}
                                        @click=${() => this._pickTheme(t.id)}>
                                    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"
                                         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${t.icon}</svg>
                                </button>`)}
                        </div>
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
            <main>
                ${this._view === 'elves' ? html`<desk-elves></desk-elves>`
                    : this._view.startsWith('tab:') ? this._elfTab(findTab(this._view.slice(4)))
                    : this._view === 'waiting' ? html`<div class="skeleton" style="height:40vh"></div>`
                    : this._view === 'no-tab' ? html`<p class="muted">No elf by that name is installed. Add one with the
                        <a href="#elves">+</a> at the end of the tabs.</p>`
                    : this._view === 'thread' ? html`<desk-thread .itemKey=${itemKeyFromHash(location.hash)}></desk-thread>`
                    : this._view === 'github' ? html`<desk-board ?settingsOpen=${location.hash === '#settings'}></desk-board>`
                    : html`<desk-dashboard></desk-dashboard>`}
            </main>
            ${this._renderVersions()}
        `;
    }
}

customElements.define('elf-desk', ElfDesk);
