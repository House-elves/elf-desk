import { LitElement, html, css } from 'lit';
import { shared, spinner } from './shared.js';
import { api, errorMessage, onPush, onConnectionChange } from './rpc.js';
import { tabs, onTabsChange, installedElves } from './tabs.js';
import './elf-frame.js';

const DATE = new Intl.DateTimeFormat('en-AU', { weekday: 'long', day: 'numeric', month: 'long' });

/**
 * The start page: one sentence per tab on what needs you there. GitHub's is the desk's own; every other card is
 * drawn by its elf (the card view of its tab, in a sandboxed frame), in a panel the desk draws around it. An elf that
 * isn't set up yet gets a line saying so instead.
 */
class DeskDashboard extends LitElement {

    static properties = {
        _elves: { state: true },     // the elves' runs: {running: {elf:command: since}}
        _attention: { state: true }, // per tab id: its card says it needs you
        /** Refresh everything: GitHub is being fetched. */
        _fetching: { state: true },
        _refreshError: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 34px; max-width: 52rem; }
        .top { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 14px 24px; }
        .refresh { display: grid; gap: 6px; justify-items: end; }
        .refresh .hint { font-size: 13px; color: var(--muted); margin: 0; text-align: right; }
        @media (max-width: 600px) { .refresh { justify-items: start; } .refresh .hint { text-align: left; } }
        .date { color: var(--muted); font-size: 15px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; margin-top: 8px; }
        .tabs { display: grid; gap: 14px; }
        .tabs > .hint { margin: 0; color: var(--muted); font-size: 15px; }
        section { padding: 20px 22px; display: grid; gap: 8px; min-width: 0; }
        section.attention { box-shadow: inset 3px 0 0 var(--accent); }
        .head { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 6px 14px; }
        h2 { font-size: 15px; font-weight: 600; color: var(--muted); }
        h2 a { color: inherit; text-decoration: none; }
        h2 a:hover { color: var(--ink); text-decoration: underline; }
        .say { margin: 0; font-size: 22px; line-height: 1.35; font-weight: 500; letter-spacing: -0.012em; text-wrap: balance; }
        .say a { color: inherit; text-decoration: underline; text-decoration-thickness: 2px; text-underline-offset: 4px;
            text-decoration-color: var(--line); }
        .say a:hover { text-decoration-color: var(--accent); }
        .meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; font-size: 13.5px; color: var(--muted); }
        .meta .running { display: inline-flex; align-items: center; gap: 7px; color: var(--accent); }
        .open { font-size: 14px; white-space: nowrap; }
        .skeleton.say-sk { height: 28px; width: min(30rem, 100%); }
        section.elf { padding-bottom: 14px; }
        @media (max-width: 600px) {
            h1 { font-size: 26px; }
            .say { font-size: 19px; }
            section { padding: 16px 16px; }
        }
    `];

    constructor() {
        super();
        this._attention = {};
    }

    connectedCallback() {
        super.connectedCallback();
        this._loadElves();
        this._offPush = [
            onPush('desk.runner', () => this._loadElves()),
            onPush('desk.elves', () => this._loadElves()),
            onConnectionChange(s => { if (s === 'open') this._loadElves(); }),
            onTabsChange(() => this.requestUpdate()),
        ];
    }

    disconnectedCallback() {
        this._offPush?.forEach(off => off());
        super.disconnectedCallback();
    }

    async _loadElves() {
        try {
            this._elves = await (await api()).elvesState();
        } catch (e) {
            // No runner: the cards say so themselves.
        }
    }

    /** The elves with a card on the dashboard. */
    _cards() {
        return tabs().filter(t => t.card);
    }

    /**
     * What Refresh everything runs: the elves on the dashboard that refresh and are set up. One without a card (a
     * quarterly review, say) only refreshes from its own tab or its schedule.
     */
    _refreshable() {
        return this._cards().filter(t => t.refresh && !t.missing?.length);
    }

    _refreshing(t) {
        return t.refresh && !!this._elves?.running?.[`${t.elf}:${t.refresh}`];
    }

    /** The morning button: fetch GitHub, and refresh every elf that's set up (the mail triage, the Zulip digest) at once. */
    async _refreshAll() {
        this._refreshError = null;
        const desk = await api().catch(e => { this._refreshError = errorMessage(e); return null; });
        if (!desk) return;
        const problems = [];
        const elves = this._refreshable().filter(t => !this._refreshing(t)).map(t =>
            desk.runElf({ elf: t.elf, command: t.refresh, params: null })
                .catch(e => problems.push(`${t.label}: ${errorMessage(e)}`)));
        await Promise.all(elves);
        await this._loadElves();
        if (problems.length) this._refreshError = `Not everything refreshed. ${problems.join(' ')}`;
    }

    _busy() {
        return this._refreshable().some(t => this._refreshing(t));
    }

    /** An elf's card: the desk draws the panel and its title, the elf draws what's in it (once it's set up). */
    _elfCard(t) {
        const setup = t.missing?.length;
        return html`
            <section class="panel elf ${this._attention[t.id] || setup ? 'attention' : ''}" aria-labelledby="d-${t.id}">
                <div class="head">
                    <h2 id="d-${t.id}"><a href="#${t.id}">${t.label}</a></h2>
                    <a class="open" href="#${t.id}">${setup ? `Set up ${t.label}` : `Open ${t.label}`}</a>
                </div>
                ${setup ? html`<p class="say"><a href="#${t.id}">Set it up</a>: it needs a few settings before its first run.</p>`
                    : html`<elf-frame elf=${t.elf} view="card"
                           @elf-card=${e => { this._attention = { ...this._attention, [t.id]: e.detail.attention }; }}></elf-frame>`}
            </section>`;
    }

    /** What Refresh everything does. */
    _refreshHint() {
        const parts = this._refreshable().map(t => `runs ${t.label}`);
        if (!parts.length) return 'Add an elf with the + to refresh it from here.';
        const text = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts.at(-1)}`;
        return `${text[0].toUpperCase()}${text.slice(1)}.`;
    }

    render() {
        return html`
            <div class="page">
                <div class="top">
                    <div>
                        <span class="date">${DATE.format(new Date())}</span>
                        <h1>Your desk</h1>
                    </div>
                    <div class="refresh">
                        <button class="primary" ?disabled=${this._busy()}
                                @click=${this._refreshAll}>
                            ${this._busy() ? spinner : ''}${this._busy() ? 'Refreshing' : 'Refresh everything'}</button>
                        <p class="hint">${this._refreshHint()}</p>
                    </div>
                </div>
                ${this._refreshError ? html`<p class="error" role="alert">${this._refreshError}</p>` : ''}
                <div class="tabs">
                    ${this._cards().map(t => this._elfCard(t))}
                    ${installedElves() === null ? html`<p class="hint">Waiting for the runner to say which elves there are.</p>`
                        : !this._cards().length ? html`<p class="hint">Elves you add with the <a href="#elves">+</a> at the end of the tabs show
                        what needs you here.</p>` : ''}
                </div>
            </div>
        `;
    }
}

customElements.define('desk-dashboard', DeskDashboard);
