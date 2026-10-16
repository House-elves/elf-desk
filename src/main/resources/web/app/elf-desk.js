import { LitElement, html, css, svg } from 'lit';
import { shared, itemKeyFromHash } from './shared.js';
import { connectionState, onConnectionChange, onBusyChange } from './rpc.js';
import { currentTheme, setTheme } from './theme.js';
import { TABS, tabShown, showTab, onTabsChange } from './tabs.js';
import './desk-board.js';
import './desk-settings.js';
import './desk-thread.js';
import './desk-email.js';
import './desk-zulip.js';
import './desk-status.js';
import './desk-quarterly.js';
import './desk-dashboard.js';

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
        _busy: { state: true },
        _theme: { state: true },
        _tabsOpen: { state: true },
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
        .brand span { font-size: 17px; font-weight: 700; letter-spacing: -0.01em; }
        nav { display: flex; gap: 2px; }
        nav a {
            font-size: 14px; font-weight: 500; padding: 6px 12px; border-radius: var(--r-control);
            color: var(--muted); text-decoration: none;
        }
        nav a:hover { color: var(--ink); background: var(--surface-2); }
        nav a[aria-current="page"] { color: var(--ink); background: var(--surface); box-shadow: inset 0 0 0 1px var(--line); }
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
        .tabs-pick { position: relative; }
        .gear {
            border: 1px solid var(--line); background: var(--surface-2); color: var(--muted);
            padding: 6px; border-radius: 999px; display: inline-flex;
        }
        .gear:hover, .gear[aria-expanded="true"] { color: var(--ink); }
        .gear svg { width: 16px; height: 16px; display: block; }
        .tabs-panel {
            position: absolute; right: 0; top: calc(100% + 8px); z-index: 6; min-width: 13rem;
            background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-card, 10px);
            box-shadow: 0 8px 24px color-mix(in srgb, var(--ink) 14%, transparent);
            padding: 12px 14px; display: grid; gap: 8px;
        }
        .tabs-panel h2 { font-size: 13px; font-weight: 600; color: var(--muted); margin: 0 0 2px; }
        .tabs-panel label { display: flex; align-items: center; gap: 10px; font-size: 14px; }
        .tabs-panel input { width: 16px; height: 16px; accent-color: var(--accent); margin: 0; }
        .tabs-panel p { margin: 4px 0 0; font-size: 12.5px; color: var(--muted); }

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
        this._onDocClick = e => {
            if (this._tabsOpen && !e.composedPath().includes(this.renderRoot.querySelector('.tabs-pick'))) this._tabsOpen = false;
        };
        this._onKey = e => { if (e.key === 'Escape' && this._tabsOpen) this._tabsOpen = false; };
        this._onHash = () => {
            this._view = this._viewFromHash();
            window.scrollTo({ top: 0 });
        };
    }

    connectedCallback() {
        super.connectedCallback();
        window.addEventListener('hashchange', this._onHash);
        document.addEventListener('click', this._onDocClick);
        document.addEventListener('keydown', this._onKey);
        this._offTabs = onTabsChange(() => { this._tabsChanged = (this._tabsChanged ?? 0) + 1; });
        this._offConn = onConnectionChange(s => { this._conn = s; });
        // Show the bar only for calls that take a moment, so quick ones don't flicker.
        this._offBusy = onBusyChange(busy => {
            clearTimeout(this._busyTimer);
            if (busy) this._busyTimer = setTimeout(() => { this._busy = true; }, 150);
            else this._busy = false;
        });
    }

    disconnectedCallback() {
        window.removeEventListener('hashchange', this._onHash);
        document.removeEventListener('click', this._onDocClick);
        document.removeEventListener('keydown', this._onKey);
        this._offTabs?.();
        this._offConn?.();
        this._offBusy?.();
        super.disconnectedCallback();
    }

    _viewFromHash() {
        if (location.hash === '#settings') return 'github'; // the board's settings now live under GitHub
        if (['#github', '#email', '#zulip', '#status', '#quarterly'].includes(location.hash)) return location.hash.slice(1);
        return itemKeyFromHash(location.hash) ? 'thread' : 'dashboard';
    }

    _current(tab) {
        return tab === 'github' ? ['github', 'thread'].includes(this._view) : this._view === tab;
    }

    /** The gear next to the theme switch: which tabs show. Dashboard always does. */
    _tabsPicker() {
        const shown = TABS.filter(t => tabShown(t.id)).length;
        return html`<div class="tabs-pick">
            <button class="gear" title="Tabs" aria-label="Choose which tabs show" aria-expanded=${this._tabsOpen ? 'true' : 'false'}
                    aria-controls="tabs-panel" @click=${() => { this._tabsOpen = !this._tabsOpen; }}>
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"
                     stroke-linejoin="round" aria-hidden="true"><circle cx="8" cy="8" r="2.2"/>
                    <path d="M8 1.5v1.6M8 12.9v1.6M1.5 8h1.6M12.9 8h1.6M3.4 3.4l1.1 1.1M11.5 11.5l1.1 1.1M3.4 12.6l1.1-1.1M11.5 4.5l1.1-1.1"/>
                    <circle cx="8" cy="8" r="4.6"/></svg>
            </button>
            ${this._tabsOpen ? html`<div class="tabs-panel" id="tabs-panel" role="group" aria-labelledby="tabs-h">
                <h2 id="tabs-h">Tabs</h2>
                ${TABS.map(t => html`<label><input type="checkbox" .checked=${tabShown(t.id)}
                        ?disabled=${tabShown(t.id) && shown === 1}
                        @change=${e => showTab(t.id, e.target.checked)}>${t.label}</label>`)}
                <p>Hidden tabs leave the header and the dashboard, in this browser.</p>
            </div>` : ''}
        </div>`;
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
                        <svg viewBox="0 0 26 26" aria-hidden="true">
                            <rect width="26" height="26" rx="7" fill="var(--accent)"/>
                            <path d="M7 17.5h12M9 17.5V11l4-3.5 4 3.5v6.5" fill="none" stroke="var(--accent-ink)"
                                  stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                        <span>Elf Desk</span>
                    </a>
                    <nav aria-label="Views">
                        <a href="#" aria-current=${this._view === 'dashboard' ? 'page' : 'false'}>Dashboard</a>
                        ${TABS.filter(t => tabShown(t.id) || this._current(t.id)).map(t => html`
                            <a href="#${t.id}" aria-current=${this._current(t.id) ? 'page' : 'false'}>${t.label}</a>`)}
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
                        ${this._tabsPicker()}
                    </div>
                </div>
                <div class="progress ${this._busy ? 'on' : ''}" role="progressbar" aria-label="Loading"
                     aria-hidden=${this._busy ? 'false' : 'true'}><span></span></div>
                ${this._conn === 'lost' ? html`
                    <div class="lost" role="alert">
                        Lost the connection to the desk. It may have restarted, or your sign-in expired.
                        <a href="${location.pathname}">Reload to sign in again</a>
                    </div>` : ''}
            </header>
            <main>
                ${this._view === 'email' ? html`<desk-email></desk-email>`
                    : this._view === 'zulip' ? html`<desk-zulip></desk-zulip>`
                    : this._view === 'status' ? html`<desk-status></desk-status>`
                    : this._view === 'quarterly' ? html`<desk-quarterly></desk-quarterly>`
                    : this._view === 'thread' ? html`<desk-thread .itemKey=${itemKeyFromHash(location.hash)}></desk-thread>`
                    : this._view === 'github' ? html`<desk-board ?settingsOpen=${location.hash === '#settings'}></desk-board>`
                    : html`<desk-dashboard></desk-dashboard>`}
            </main>
        `;
    }
}

customElements.define('elf-desk', ElfDesk);
