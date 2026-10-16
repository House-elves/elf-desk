import { LitElement, html, css } from 'lit';
import { shared, ago, spinner } from './shared.js';
import { api, errorMessage, onPush, onConnectionChange } from './rpc.js';
import { boardHeadline, count, joinParts, lastBoard } from './desk-board.js';
import { runTime } from './triage-run.js';
import { statusState } from './desk-status.js';
import { tabShown, onTabsChange } from './tabs.js';

const DATE = new Intl.DateTimeFormat('en-AU', { weekday: 'long', day: 'numeric', month: 'long' });

/** The run happened today: tells a stale run from this morning's. */
function isToday(at) {
    const d = new Date(at);
    const now = new Date();
    return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
}

/** The start page: one sentence per tab (GitHub, Email, Zulip) on what needs you there. */
class DeskDashboard extends LitElement {

    static properties = {
        _board: { state: true },
        _email: { state: true },
        _zulip: { state: true },
        _status: { state: true },
        _triage: { state: true },
        _loaded: { state: true },
        _errors: { state: true },
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
        @media (max-width: 600px) {
            h1 { font-size: 26px; }
            .say { font-size: 19px; }
            section { padding: 16px 16px; }
        }
    `];

    constructor() {
        super();
        this._board = lastBoard;
        this._loaded = {};
        this._errors = {};
    }

    connectedCallback() {
        super.connectedCallback();
        this._loadBoard();
        this._loadTriage();
        this._offPush = [
            onPush('desk.board', () => this._softBoard()),
            onPush('desk.thread', e => { if (e.type === 'changed') this._softBoard(); }),
            onPush('desk.runner', () => { this._softBoard(); this._loadTriage(); }),
            onPush('desk.triage', () => this._loadTriage()),
            onConnectionChange(s => { if (s === 'open') { this._loadBoard(); this._loadTriage(); } }),
            onTabsChange(() => this.requestUpdate()),
        ];
    }

    disconnectedCallback() {
        clearTimeout(this._soft);
        this._offPush?.forEach(off => off());
        super.disconnectedCallback();
    }

    async _loadBoard() {
        try {
            const desk = await api();
            this._board = await desk.board({ refresh: false });
            this._errors = { ...this._errors, github: null };
        } catch (e) {
            this._errors = { ...this._errors, github: errorMessage(e) };
        } finally {
            this._loaded = { ...this._loaded, github: true };
        }
    }

    /** At most once a second: board pushes come in bursts. */
    _softBoard() {
        clearTimeout(this._soft);
        this._soft = setTimeout(() => this._loadBoard(), 1000);
    }

    async _loadTriage() {
        const desk = await api().catch(() => null);
        if (!desk) return;
        const one = async (key, call) => {
            try {
                this[`_${key}`] = await call();
                this._errors = { ...this._errors, [key]: null };
            } catch (e) {
                this._errors = { ...this._errors, [key]: errorMessage(e) };
            } finally {
                this._loaded = { ...this._loaded, [key]: true };
            }
        };
        await Promise.all([
            one('triage', () => desk.triageState()),
            one('email', () => desk.email({ runId: null })),
            one('zulip', () => desk.zulip({ runId: null })),
            one('status', () => desk.weeklyStatus({ runId: null })),
        ]);
    }

    /** The morning button: fetch GitHub, and run the email triage and the Zulip digest, all at once. */
    async _refreshAll() {
        this._refreshError = null;
        const desk = await api().catch(e => { this._refreshError = errorMessage(e); return null; });
        if (!desk) return;
        const problems = [];
        const triage = mode => this._triage?.running?.[mode] || !tabShown(mode) ? null
            : desk.triageRun({ mode }).then(state => { this._triage = state; })
                .catch(e => problems.push(`${mode === 'email' ? 'Email' : 'Zulip'}: ${errorMessage(e)}`));
        this._fetching = tabShown('github');
        const github = !tabShown('github') ? null : desk.board({ refresh: true })
            .then(b => { this._board = b; this._errors = { ...this._errors, github: null }; })
            .catch(e => problems.push(`GitHub: ${errorMessage(e)}`))
            .finally(() => { this._fetching = false; });
        await Promise.all([github, triage('email'), triage('zulip')]);
        if (problems.length) this._refreshError = `Not everything refreshed. ${problems.join(' ')}`;
    }

    _busy() {
        return this._fetching || (tabShown('email') && !!this._triage?.running?.email)
            || (tabShown('zulip') && !!this._triage?.running?.zulip);
    }

    _running(mode) {
        const since = this._triage?.running?.[mode];
        return since ? html`<span class="running" role="status">${spinner}Running, started ${ago(since)}</span>` : '';
    }

    _failed(mode) {
        return this._triage?.failed?.[mode] && !this._triage?.running?.[mode]
            ? html`<span class="error">The last run failed</span>` : '';
    }

    _card({ id, title, href, open, say, meta, attention, key }) {
        const error = this._errors[key];
        return html`
            <section class="panel ${attention ? 'attention' : ''}" aria-labelledby="d-${id}">
                <div class="head">
                    <h2 id="d-${id}"><a href=${href}>${title}</a></h2>
                    <a class="open" href=${href}>${open}</a>
                </div>
                ${error ? html`<p class="error" role="alert">${error}</p>`
                    : !this._loaded[key] && say == null ? html`<div class="skeleton say-sk"></div>`
                    : html`<p class="say">${say}</p>`}
                ${meta ? html`<div class="meta">${meta}</div>` : ''}
            </section>`;
    }

    _github() {
        const b = this._board;
        let say = null, attention = false;
        if (b?.scopeEmpty) say = html`Pick what to watch in <a href="#settings">GitHub's settings</a>, and your work shows up here.`;
        else if (b) {
            say = boardHeadline(b, (_, text) => html`<a href="#github">${text}</a>`);
            attention = typeof say !== 'string';
        }
        const meta = b ? html`
            <span>Runner ${b.runnerConnected ? 'online' : 'offline'}</span>
            <span>${b.refreshing || this._fetching ? html`<span class="running" role="status">${spinner}Fetching from GitHub</span>`
                : html`Updated ${ago(b.fetchedAt)}`}</span>` : '';
        return this._card({ id: 'github', key: 'github', title: 'GitHub', href: '#github', open: 'Open the board', say, meta, attention });
    }

    _emailCard() {
        const b = this._email;
        let say = null, attention = false, meta = [];
        if (b) {
            const decisions = (b.accounts ?? []).flatMap(a => a.decisions ?? []);
            const need = decisions.filter(d => d.decision !== 'MARK_READ' && !d.done).length;
            const read = decisions.filter(d => d.decision === 'MARK_READ').length;
            attention = need > 0;
            say = !decisions.length ? 'No new email in that run.'
                : need ? html`<a href="#email">${count(need, 'email')}</a> ${need === 1 ? 'needs' : 'need'} you${read ? html`, and ${read} ${read === 1 ? 'was' : 'were'} marked read` : ''}.`
                : `Nothing in your inbox needs you; ${count(read, 'email')} ${read === 1 ? 'was' : 'were'} marked read.`;
            meta.push(html`<span>Triaged ${runTime(b.run.at)}${isToday(b.run.at) ? '' : ', not today yet'}</span>`);
        } else if (this._loaded.email) {
            say = 'Not triaged yet. Run it from the Email tab.';
        }
        meta.push(this._running('email'), this._failed('email'));
        return this._card({ id: 'email', key: 'email', title: 'Email', href: '#email', open: 'Open email', say, meta, attention });
    }

    _zulipCard() {
        const z = this._zulip;
        let say = null, attention = false, meta = [];
        if (z) {
            const topics = new Map();
            for (const m of z.messages ?? []) {
                const key = `${m.stream} > ${m.topic}`;
                const t = topics.get(key) ?? { key, you: false, mention: false, unread: false, last: 0 };
                t.last = Math.max(t.last, m.time);
                t.you ||= m.mention || m.involvesMe;
                t.mention ||= m.mention;
                t.unread ||= m.unread;
                topics.set(key, t);
            }
            // Topics you marked Done count again once they have newer messages.
            const all = [...topics.values()].filter(t => !(z.done?.[t.key] && Date.parse(z.done[t.key]) >= t.last * 1000));
            const mentions = all.filter(t => t.mention).length;
            const you = all.filter(t => t.you && !t.mention).length;
            const unread = all.filter(t => t.unread).length;
            attention = mentions + you + unread > 0;
            const parts = [];
            if (mentions) parts.push(html`<a href="#zulip">${count(mentions, 'topic')}</a> ${mentions === 1 ? 'mentions' : 'mention'} you`);
            if (you) parts.push(html`<a href="#zulip">${count(you, 'topic')}</a> ${you === 1 ? 'involves' : 'involve'} you`);
            if (unread) parts.push(html`${count(unread, 'topic')} ${unread === 1 ? 'has' : 'have'} unread messages`);
            say = !all.length ? 'Nothing happened on Zulip.'
                : parts.length ? html`${joinParts(parts)}, out of ${count(all.length, 'active topic')}.`
                : `Nothing involves you; ${count(all.length, 'topic')} ${all.length === 1 ? 'was' : 'were'} active.`;
            meta.push(html`<span>The ${z.hours} hours up to ${runTime(z.run.at)}${isToday(z.run.at) ? '' : ', not checked today yet'}</span>`);
        } else if (this._loaded.zulip) {
            say = 'Not checked yet. Check from the Zulip tab.';
        }
        meta.push(this._running('zulip'), this._failed('zulip'));
        return this._card({ id: 'zulip', key: 'zulip', title: 'Zulip', href: '#zulip', open: 'Open Zulip', say, meta, attention });
    }

    /** The weekly status: from Monday on, it asks for your attention until this week's is sent. */
    _statusCard() {
        const s = this._status;
        const state = statusState(s);
        let say = null;
        if (state === 'sent') say = `This week's status went to the team ${ago(s.sends.find(x => x.target === 'team').at)}.`;
        else if (state === 'self') say = html`<a href="#status">This week's status</a> went to you, not to the team yet.`;
        else if (state === 'draft') say = html`<a href="#status">This week's status</a> is drafted and ready to send.`;
        else if (this._loaded.status) say = html`Nothing drafted this week yet. <a href="#status">Draft it now</a>, or it drafts itself Monday at 08:00.`;
        const meta = [this._running('status'), this._failed('status')];
        return this._card({ id: 'status', key: 'status', title: 'Weekly status', href: '#status', open: 'Open status',
            say, meta, attention: this._loaded.status && state !== 'sent' });
    }

    /** What Refresh everything does, for the tabs that show. */
    _refreshHint() {
        const parts = [tabShown('github') && 'fetches GitHub', tabShown('email') && 'triages new email',
            tabShown('zulip') && 'catches up on Zulip'].filter(Boolean);
        if (!parts.length) return 'Nothing to refresh with GitHub, Email and Zulip hidden.';
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
                        <button class="primary" ?disabled=${this._busy() || !['github', 'email', 'zulip'].some(tabShown)}
                                @click=${this._refreshAll}>
                            ${this._busy() ? spinner : ''}${this._busy() ? 'Refreshing' : 'Refresh everything'}</button>
                        <p class="hint">${this._refreshHint()}</p>
                    </div>
                </div>
                ${this._refreshError ? html`<p class="error" role="alert">${this._refreshError}</p>` : ''}
                <div class="tabs">
                    ${tabShown('github') ? this._github() : ''}
                    ${tabShown('email') ? this._emailCard() : ''}
                    ${tabShown('zulip') ? this._zulipCard() : ''}
                    ${tabShown('status') ? this._statusCard() : ''}
                    ${!['github', 'email', 'zulip', 'status'].some(tabShown)
                        ? html`<p class="hint">Every tab with a card here is hidden. Turn tabs back on with the gear at the top right.</p>` : ''}
                </div>
            </div>
        `;
    }
}

customElements.define('desk-dashboard', DeskDashboard);
