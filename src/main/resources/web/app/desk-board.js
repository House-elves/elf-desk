import { LitElement, html, css } from 'lit';
import { linkedPrChips, rememberLinks, shared, ago, itemChips, ciFailing, hasConflicts, mergeableAnyway, kindIcon, spinner, threadHref, rememberItem } from './shared.js';
import { api, errorMessage, onPush, onConnectionChange } from './rpc.js';
import './merge-control.js';
import './ci-menu.js';
import './conflict-menu.js';
import './pr-activity.js';
import './desk-thread.js';

const DATE = new Intl.DateTimeFormat('en-AU', { weekday: 'long', day: 'numeric', month: 'long' });

/** The board's sections, by whose move it is (Flow.waiting). Folded ones start closed. */
const SECTIONS = [
    { id: 'you', title: 'Your move', empty: 'Nothing needs you right now.' },
    { id: 'agent', title: 'Agent working' },
    { id: 'ci', title: 'Waiting on CI' },
    { id: 'others', title: 'Waiting on others', fold: true },
    { id: 'done', title: 'Done', fold: true },
    { id: 'watching', title: 'Just involved', fold: true },
];

const ROLES = { mine: 'My PR', review: 'Review', issue: 'Issue', mvnpm: 'mvnpm', involved: 'Involved' };

/** Agent actions started straight from a row: the row then shows the agent working. */
const CALLS = {
    review: (d, key) => d.start({ itemKey: key, action: 'review', question: null }),
    investigate: (d, key) => d.start({ itemKey: key, action: 'investigate', question: null }),
    fix: (d, key) => d.start({ itemKey: key, action: 'fix', question: null }),
    'ci-why': (d, key) => d.ciWhy({ itemKey: key }),
    'ci-fix': (d, key) => d.ciFix({ itemKey: key }),
    'ci-rerun': (d, key) => d.ciRerun({ itemKey: key }),
    'ci-approve': (d, key) => d.ciApprove({ itemKey: key }),
    conflicts: (d, key) => d.resolveConflicts({ itemKey: key }),
    'dependabot-rebase': (d, key) => d.askDependabotToRebase({ itemKey: key }),
    ready: (d, key) => d.readyForReview({ itemKey: key }),
    assign: (d, key) => d.assignMe({ itemKey: key }),
};

/** The last board this page saw: going back to the board shows it at once while it updates. */
export let lastBoard = null;

/** "3 reviews", "1 review". */
export function count(n, one, many = `${one}s`) {
    return `${n} ${n === 1 ? one : many}`;
}

/** "a, b and c". */
export function joinParts(parts) {
    const joined = [];
    parts.forEach((p, i) => {
        if (i > 0) joined.push(i === parts.length - 1 ? ' and ' : ', ');
        joined.push(p);
    });
    return joined;
}

/** How many items wait on each: { you: 3, agent: 1, ... }. */
export function waitingCounts(b) {
    const n = {};
    Object.values(b?.flows ?? {}).forEach(f => { n[f.waiting] = (n[f.waiting] ?? 0) + 1; });
    return n;
}

/**
 * Where things stand, in one sentence: "3 things need your move, an agent is working on 1 and 2 wait on CI."
 * `link(sectionId, text)` renders the counts that lead to a section.
 */
export function boardHeadline(b, link) {
    const n = waitingCounts(b);
    const parts = [];
    if (n.you) parts.push(html`${link('you', count(n.you, 'thing'))} ${n.you === 1 ? 'needs' : 'need'} your move`);
    if (n.agent) parts.push(html`${parts.length ? 'an' : 'An'} agent is working on ${link('agent', count(n.agent, 'item'))}`);
    if (n.ci) parts.push(html`${link('ci', count(n.ci, 'item'))} ${n.ci === 1 ? 'waits' : 'wait'} on CI`);
    if (parts.length === 0) return 'Nothing needs you right now.';
    return html`${joinParts(parts)}.`;
}

/**
 * The GitHub board, by whose move it is. Every item is in a flow (your PR, a PR to review, an assigned issue, an
 * mvnpm bump); the desk works out its stage and next step (FlowClassifier), and the row shows that with one main
 * button. A row opens in place to read what the agent made and send it, so you rarely leave the board.
 * Keys: j/k move between rows, Enter runs the main step, o opens or closes the row.
 */
class DeskBoard extends LitElement {

    static properties = {
        _board: { state: true },
        _loading: { state: true },
        _refreshing: { state: true },
        _error: { state: true },
        _open: { state: true },
        _doing: { state: true },
        _watching: { state: true },
        _closing: { state: true },
        _confirmClose: { state: true },
        _moreFor: { state: true },
        _mergeAnyway: { state: true },
        _activityFor: { state: true },
        _mvnpmStarting: { state: true },
        _ignoring: { state: true },
        _unfolded: { state: true },
    };

    static styles = [shared, css`
        .hero { display: grid; gap: 10px; margin-bottom: 34px; max-width: 46rem; }
        .date { color: var(--muted); font-size: 15px; }
        .hero h1 { font-size: 34px; line-height: 1.18; font-weight: 600; letter-spacing: -0.022em; text-wrap: balance; }
        .hero h1 button {
            all: unset; cursor: pointer; text-decoration: underline; text-decoration-thickness: 2px;
            text-underline-offset: 5px; text-decoration-color: var(--line);
        }
        .hero h1 button:hover { text-decoration-color: var(--accent); }
        .hero h1 button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; border-radius: 4px; }
        .status { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; margin-top: 6px; font-size: 14px; }
        .runner { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); }
        .runner::before { content: ""; width: 8px; height: 8px; border-radius: 50%; background: var(--idle); }
        .runner.on::before { background: var(--ok); box-shadow: 0 0 0 3px var(--ok-soft); }
        .updated { display: inline-flex; align-items: center; gap: 7px; }
        .confirm-close {
            display: flex; flex-wrap: wrap; align-items: center; gap: 8px; justify-content: flex-end;
            font-size: 13px; color: var(--ink);
        }

        .layout { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: 36px; align-items: start; }
        aside { position: sticky; top: 84px; display: grid; gap: 2px; }
        aside button {
            justify-content: space-between; border: 0; background: transparent; padding: 8px 12px;
            font-weight: 500; color: var(--muted); width: 100%;
        }
        aside button:hover:not(:disabled) { color: var(--ink); }
        aside button .n { color: var(--ink); font-weight: 600; font-variant-numeric: tabular-nums; }
        aside button.zero .n { color: var(--muted); font-weight: 500; }
        aside button.you:not(.zero) { color: var(--ink); }
        aside hr { border: 0; border-top: 1px solid var(--line); margin: 10px 12px; }
        .keys { font-size: 12px; color: var(--muted); padding: 10px 12px 0; line-height: 1.6; }
        kbd { font: 11px var(--font-code); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; }

        .sections { display: grid; gap: 34px; min-width: 0; }
        .sec-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
        .sec-head h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .sec-head .n { color: var(--muted); font-size: 15px; }
        section { scroll-margin-top: 90px; }
        section.you > .panel { box-shadow: inset 3px 0 0 var(--accent); }

        ul { list-style: none; margin: 0; padding: 4px 0; }
        li {
            display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 4px 14px; align-items: start;
            padding: 12px 18px;
        }
        li + li { border-top: 1px solid var(--line); }
        li:hover { background: color-mix(in srgb, var(--surface-2) 55%, transparent); }
        li:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
        li.open { background: color-mix(in srgb, var(--surface-2) 70%, transparent); }
        li .icon { margin-top: 3px; }
        .main { min-width: 0; display: grid; gap: 5px; cursor: pointer; }
        .line1 { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
        .role {
            flex: none; font-size: 11.5px; font-weight: 600; color: var(--muted); border: 1px solid var(--line);
            border-radius: 999px; padding: 0 7px; line-height: 18px;
        }
        .title { color: var(--ink); font-weight: 500; text-decoration: none; overflow-wrap: anywhere; }
        .title:hover { text-decoration: underline; }
        .meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; font-size: 13px; color: var(--muted); }
        .say { color: var(--ink); }
        .steps { display: inline-flex; align-items: center; gap: 6px; }
        .seg { display: inline-flex; gap: 3px; }
        .seg i { width: 14px; height: 4px; border-radius: 2px; background: var(--line); }
        .seg i.done { background: color-mix(in srgb, var(--accent) 45%, var(--line)); }
        .seg i.now { background: var(--accent); }
        .stage { font-weight: 500; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; align-items: center; }
        .inline { grid-column: 2 / -1; padding: 8px 0 4px; min-width: 0; }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; font-size: 14px; }

        .notice { padding: 18px 20px; margin-bottom: 26px; display: grid; gap: 8px; max-width: 46rem; }
        .notice p { margin: 0; }

        details summary {
            cursor: pointer; color: var(--muted); font-weight: 500; padding: 4px 0; list-style-position: outside;
        }
        details summary:hover { color: var(--ink); }
        details summary .n { font-variant-numeric: tabular-nums; }
        details[open] summary { margin-bottom: 12px; }

        .sk-row { display: grid; grid-template-columns: 16px 1fr 90px; gap: 14px; padding: 15px 18px; }
        .sk-row + .sk-row { border-top: 1px solid var(--line); }
        .sk-line { height: 12px; }

        @media (max-width: 960px) {
            .layout { grid-template-columns: minmax(0, 1fr); gap: 24px; }
            aside { position: static; display: flex; flex-wrap: wrap; gap: 6px; }
            aside button { width: auto; gap: 8px; background: var(--surface); border: 1px solid var(--line); padding: 6px 12px; }
            aside hr, .keys { display: none; }
        }
        @media (max-width: 600px) {
            .hero h1 { font-size: 26px; }
            li { grid-template-columns: 16px minmax(0, 1fr); padding: 12px 14px; }
            .acts { grid-column: 2; justify-content: flex-start; }
            .inline { grid-column: 1 / -1; }
        }
    `];

    constructor() {
        super();
        this._board = lastBoard;
        this._loading = !lastBoard;
        this._open = null;
        this._doing = null;
        this._watching = null;
        this._closing = null;
        this._confirmClose = null;
        this._moreFor = null;
        this._mergeAnyway = null;
        this._activityFor = null;
        this._mvnpmStarting = null;
        this._ignoring = null;
        this._unfolded = new Set();
        this._onKey = e => this._key(e);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load(false);
        this.addEventListener('keydown', this._onKey);
        // Thread states change while the board is open (agent finished, draft posted): the flows move on.
        this._offPush = [
            onPush('desk.thread', e => { if (e.type === 'changed') this._softReload(); }),
            onPush('desk.runner', () => this._softReload()),
            // The desk fetched fresh GitHub data in the background.
            onPush('desk.board', () => { if (this._error && !this._board) this._load(false); else this._softReload(); }),
            // Back after a dropped connection: if the board didn't load, load it now rather than leaving the error up.
            onConnectionChange(state => {
                if (state === 'open' && (this._error || !this._board)) this._load(false);
            }),
        ];
    }

    disconnectedCallback() {
        clearTimeout(this._timer);
        clearTimeout(this._soft);
        this.removeEventListener('keydown', this._onKey);
        this._offPush?.forEach(off => off());
        super.disconnectedCallback();
    }

    async _load(refresh) {
        if (refresh) this._refreshing = true;
        else if (!this._board) this._loading = true;
        this._error = null;
        clearTimeout(this._timer);
        try {
            const desk = await api();
            this._setBoard(await desk.board({ refresh }));
        } catch (e) {
            this._error = `Couldn't load the board: ${errorMessage(e)}`;
        } finally {
            this._loading = false;
            this._refreshing = false;
            const minutes = this._board?.refreshMinutes ?? 5;
            this._timer = setTimeout(() => this._load(false), minutes * 60_000);
        }
    }

    /** Reload from the desk's cache (no GitHub calls), at most once a second. */
    _softReload() {
        clearTimeout(this._soft);
        this._soft = setTimeout(() => this._reloadNow(), 1000);
    }

    /** Right after one of your clicks: the row shows its new state at once. */
    async _reloadNow() {
        try {
            const desk = await api();
            this._setBoard(await desk.board({ refresh: false }));
        } catch (e) {
            // keep what we have
        }
    }

    _setBoard(b) {
        rememberLinks(b?.linkedPrs);
        this._board = b;
        lastBoard = b;
    }

    // ---- actions ----

    /** Runs a row's step. Agent steps leave the row showing the agent at work; nothing navigates. */
    async _run(item, next) {
        if (next.id === 'mvnpm-check') return this._mvnpmRun(item);
        const call = CALLS[next.id];
        if (!call) return;
        this._doing = `${item.key} ${next.id}`;
        this._error = null;
        try {
            const desk = await api();
            const r = await call(desk, item.key);
            if (next.id === 'ready' && r) rememberItem(r);
            this._moreFor = null;
            await this._reloadNow();
        } catch (e) {
            this._error = `${item.key}: ${errorMessage(e)}`;
        } finally {
            this._doing = null;
        }
    }

    /** Run mvnpm-dependabot now: every repo, or one PR (item). */
    async _mvnpmRun(item) {
        this._mvnpmStarting = item ? item.key : 'all';
        this._error = null;
        try {
            const desk = await api();
            await desk.mvnpmRun(item ? { repo: item.repo, pr: item.number } : { repo: null, pr: null });
            await this._reloadNow(); // the row moves to "checking" with its new flow
        } catch (e) {
            this._error = `Couldn't start the mvnpm check: ${errorMessage(e)}`;
        } finally {
            this._mvnpmStarting = null;
        }
    }

    async _ignore(item, ignore) {
        this._ignoring = item.key;
        this._error = null;
        try {
            const desk = await api();
            if (ignore) await desk.ignore({ itemKey: item.key });
            else await desk.unignore({ itemKey: item.key });
            this._moreFor = null;
            if (this._open === item.key) this._open = null;
            this._setBoard(await desk.board({ refresh: false }));
        } catch (e) {
            this._error = `Couldn't ${ignore ? 'ignore' : 'stop ignoring'} ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._ignoring = null;
        }
    }

    async _close(item) {
        this._closing = item.key;
        this._error = null;
        try {
            const desk = await api();
            await desk.close({ itemKey: item.key });
            this._confirmClose = null;
            if (this._open === item.key) this._open = null;
            this._setBoard(await desk.board({ refresh: false }));
        } catch (e) {
            this._error = `Couldn't close ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._closing = null;
        }
    }

    async _watch(name) {
        this._watching = name;
        try {
            const desk = await api();
            await desk.watch({ name });
            await this._load(false);
        } catch (e) {
            this._error = `Couldn't watch ${name}: ${errorMessage(e)}`;
        } finally {
            this._watching = null;
        }
    }

    _toggle(key) {
        this._open = this._open === key ? null : key;
    }

    _jump(id) {
        if (SECTIONS.find(s => s.id === id)?.fold) this._unfolded = new Set([...this._unfolded, id]);
        this.updateComplete.then(() => this.renderRoot.querySelector(`#sec-${id}`)?.scrollIntoView({
            behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start',
        }));
    }

    /** j/k: next or previous row; Enter: the row's main step; o: open or close the row. Not while typing. */
    _key(e) {
        const origin = e.composedPath()[0];
        if (e.ctrlKey || e.metaKey || e.altKey || origin?.closest?.('input, textarea, select, [contenteditable]')
                || ['INPUT', 'TEXTAREA', 'SELECT'].includes(origin?.tagName)) return;
        const rows = [...this.renderRoot.querySelectorAll('li.row')].filter(r => r.offsetParent !== null);
        if (!rows.length) return;
        const at = rows.indexOf(this.renderRoot.activeElement?.closest?.('li.row') ?? null);
        if (e.key === 'j' || e.key === 'k') {
            e.preventDefault();
            const to = e.key === 'j' ? Math.min(rows.length - 1, at + 1) : Math.max(0, at < 0 ? 0 : at - 1);
            rows[to].focus();
            rows[to].scrollIntoView({ block: 'nearest' });
        } else if (at >= 0 && origin === rows[at] && e.key === 'Enter') {
            e.preventDefault();
            rows[at].querySelector(':scope > .acts .primary')?.click();
        } else if (at >= 0 && e.key === 'o') {
            e.preventDefault();
            this._toggle(rows[at].dataset.key);
        }
    }

    // ---- rendering ----

    render() {
        const b = this._board;
        if (!b && this._loading) return this._renderSkeleton();
        return html`
            ${this._error ? html`<p class="error" role="alert" style="margin-bottom:20px">${this._error}</p>` : ''}
            ${b ? this._renderBoard(b) : ''}
        `;
    }

    _renderSkeleton() {
        const rows = n => Array.from({ length: n }, () => html`
            <div class="sk-row"><div class="skeleton" style="height:16px"></div>
                <div style="display:grid;gap:8px"><div class="skeleton sk-line" style="width:70%"></div>
                <div class="skeleton sk-line" style="width:40%"></div></div>
                <div class="skeleton" style="height:30px"></div></div>`);
        return html`
            <div class="hero" aria-busy="true" aria-label="Loading the board">
                <div class="skeleton" style="height:14px;width:160px"></div>
                <div class="skeleton" style="height:38px;width:min(36rem,100%)"></div>
            </div>
            <div class="sections">
                <div class="panel">${rows(3)}</div>
                <div class="panel">${rows(2)}</div>
            </div>
        `;
    }

    /** Every item with its flow: the lanes, then the mvnpm bumps. */
    _rows(b) {
        const items = [...b.lanes.flatMap(l => l.items), ...(b.mvnpm?.rows ?? []).map(r => r.item)];
        return items.map(item => ({ item, flow: b.flows?.[item.key] })).filter(r => r.flow);
    }

    _renderBoard(b) {
        const rows = this._rows(b);
        const by = Object.fromEntries(SECTIONS.map(s => [s.id, []]));
        rows.forEach(r => (by[r.flow.waiting] ?? by.watching).push(r));
        // Most recent first: what just changed is what you're likely to act on.
        Object.values(by).forEach(list => list.sort((x, y) => (y.flow.since ?? '').localeCompare(x.flow.since ?? '')));
        const checks = b.mvnpmChecks;
        return html`
            <div class="hero">
                <span class="date">${DATE.format(new Date())}</span>
                <h1>${b.scopeEmpty ? 'Pick what to watch, and your work shows up here.'
                    : boardHeadline(b, (id, text) => html`<button @click=${() => this._jump(id)}>${text}</button>`)}</h1>
                <div class="status">
                    <span class="runner ${b.runnerConnected ? 'on' : ''}">Runner ${b.runnerConnected ? 'online' : 'offline'}</span>
                    <span class="muted updated">${b.refreshing
                        ? html`${spinner}Updating, showing data from ${ago(b.fetchedAt)}`
                        : html`Updated ${ago(b.fetchedAt)}`}</span>
                    <button class="sm" @click=${() => this._load(true)} ?disabled=${this._refreshing}>
                        ${this._refreshing ? spinner : ''}${this._refreshing ? 'Refreshing' : 'Refresh'}</button>
                    ${b.mvnpm?.installed ? (checks?.running?.length
                        ? html`<span class="chip run">${spinner}mvnpm checks running${checks.since ? html`, started ${ago(checks.since)}` : ''}</span>`
                        : html`<button class="sm quiet" ?disabled=${this._mvnpmStarting != null || !b.runnerConnected}
                            title="Run mvnpm-dependabot over ${b.mvnpm.repos.join(', ')} now" @click=${() => this._mvnpmRun(null)}>
                            ${this._mvnpmStarting === 'all' ? spinner : ''}Run mvnpm checks</button>`) : ''}
                </div>
            </div>

            ${b.scopeEmpty ? html`
                <div class="panel notice">
                    <p>The board shows open PRs and issues from the orgs and repos you choose.</p>
                    <p><a class="btn primary" href="#settings">Choose what to watch</a></p>
                </div>` : ''}
            ${b.truncated ? html`
                <div class="panel notice"><p>GitHub returned its limit of 1000 results, so some older items are missing.</p></div>` : ''}

            <div class="layout">
                <aside aria-label="Sections">
                    ${SECTIONS.map(s => html`
                        <button class="${s.id} ${by[s.id].length ? '' : 'zero'}" @click=${() => this._jump(s.id)}>
                            <span>${s.title}</span><span class="n">${by[s.id].length}</span></button>`)}
                    ${b.hidden.length || b.ignored.length ? html`<hr>` : ''}
                    ${b.hidden.length ? html`
                        <button class="zero" @click=${() => this._jump('hidden')}>
                            <span>Not watched</span><span class="n">${b.hidden.length}</span></button>` : ''}
                    ${b.ignored.length ? html`
                        <button class="zero" @click=${() => this._jump('ignored')}>
                            <span>Ignored</span><span class="n">${b.ignored.length}</span></button>` : ''}
                    <p class="keys"><kbd>j</kbd> <kbd>k</kbd> move, <kbd>Enter</kbd> next step, <kbd>o</kbd> open</p>
                </aside>
                <div class="sections">
                    ${SECTIONS.map(s => this._renderSection(s, by[s.id]))}
                    ${this._renderHidden(b.hidden)}
                    ${this._renderIgnored(b.ignored)}
                </div>
            </div>
        `;
    }

    _renderSection(s, rows) {
        if (!rows.length && s.id !== 'you') return '';
        const list = html`<div class="panel">${rows.length
            ? html`<ul>${rows.map(r => this._renderRow(r.item, r.flow))}</ul>`
            : html`<p class="empty">${s.empty}</p>`}</div>`;
        if (s.fold) {
            return html`
                <section id="sec-${s.id}" class=${s.id} aria-label=${s.title}>
                    <details ?open=${this._unfolded.has(s.id)} @toggle=${e => this._fold(s.id, e.target.open)}>
                        <summary>${s.title} <span class="n">${rows.length}</span></summary>
                        ${this._unfolded.has(s.id) ? list : ''}
                    </details>
                </section>`;
        }
        return html`
            <section id="sec-${s.id}" class=${s.id} aria-labelledby="h-${s.id}">
                <div class="sec-head"><h2 id="h-${s.id}">${s.title}</h2><span class="n">${rows.length}</span></div>
                ${list}
            </section>`;
    }

    _fold(id, open) {
        const s = new Set(this._unfolded);
        if (open) s.add(id); else s.delete(id);
        this._unfolded = s;
    }

    _renderRow(item, f) {
        rememberItem(item);
        const open = this._open === item.key;
        const back = this._board?.returned?.[item.key];
        return html`
            <li class="row ${open ? 'open' : ''}" tabindex="0" data-key=${item.key}>
                ${kindIcon(item)}
                <div class="main" @click=${e => { if (!e.target.closest('a, button')) this._toggle(item.key); }}>
                    <div class="line1">
                        <span class="role">${ROLES[f.role] ?? f.role}</span>
                        <a class="title" href=${item.url} target="_blank" rel="noopener">${item.title}</a>
                    </div>
                    <div class="meta">
                        <span>${item.repo} #${item.number}</span>
                        ${this._stepper(f)}
                        <span class="say">${f.say}</span>
                        ${f.since ? html`<span>${ago(f.since)}</span>` : ''}
                        ${back ? html`<span class="chip run" title="It was ignored; this brought it back">Back: ${back.toLowerCase()}</span>` : ''}
                        ${item.kind === 'ISSUE' && f.role !== 'issue' ? linkedPrChips(item.key) : ''}
                    </div>
                </div>
                <div class="acts">${this._actions(item, f)}</div>
                ${open ? html`<div class="inline">
                    <desk-thread compact .itemKey=${item.key}
                        @thread-posted=${() => { this._open = null; this._reloadNow(); }}></desk-thread></div>` : ''}
            </li>
        `;
    }

    /** "▬▬▭▭ CI": how far along the flow is, and the stage it's at. */
    _stepper(f) {
        if (!f.steps?.length) return '';
        return html`<span class="steps" title=${f.steps.join(' → ')}>
            <span class="seg" aria-hidden="true">${f.steps.map((_, i) => html`<i class=${i < f.step ? 'done' : i === f.step ? 'now' : ''}></i>`)}</span>
            <span class="stage">${f.steps[f.step]}</span></span>`;
    }

    /** The main step, then More: the other steps that fit, plus Open page, Ignore and Close. */
    _actions(item, f) {
        if (this._confirmClose === item.key) return this._closeButton(item);
        if (this._mergeAnyway === item.key) return this._mergeAnywayControl(item);
        if (this._activityFor === item.key) {
            return html`<pr-activity autoOpen .itemKey=${item.key} @activity-closed=${() => { this._activityFor = null; }}></pr-activity>`;
        }
        const main = f.next ? this._action(item, f.next, true) : '';
        if (this._moreFor !== item.key) {
            return html`${main}<button class="sm quiet" aria-expanded="false" aria-label="More actions"
                @click=${() => { this._moreFor = item.key; }}>⋯</button>`;
        }
        const ids = new Set([f.next?.id, ...f.more.map(n => n.id)]);
        const merged = f.waiting === 'done';
        const me = this._board.login?.toLowerCase();
        const assigned = (item.assignees ?? []).some(a => a.toLowerCase() === me);
        return html`
            ${main}
            ${f.more.map(n => this._action(item, n, false))}
            ${!merged && item.kind === 'PR' && ciFailing(item) ? html`<ci-menu .itemKey=${item.key} @ci-changed=${() => this._reloadNow()}></ci-menu>` : ''}
            ${!merged && item.kind === 'PR' && hasConflicts(item) && !ids.has('conflicts')
                ? html`<conflict-menu .itemKey=${item.key}></conflict-menu>` : ''}
            ${!merged && mergeableAnyway(item) && !ids.has('merge-anyway') && !this._board.merges?.[item.key]
                ? this._action(item, { id: 'merge-anyway', label: 'Merge without approval' }, false) : ''}
            ${!merged && item.kind === 'PR' && !ids.has('whats-new') ? this._action(item, { id: 'whats-new', label: "What's new" }, false) : ''}
            ${f.role === 'involved' && !assigned ? this._action(item, { id: 'assign', label: 'Assign me' }, false) : ''}
            <a class="btn sm quiet" href=${threadHref(item.key)}>Open page</a>
            ${!merged && !ids.has('ignore') ? html`<button class="sm quiet" ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, true)}
                title="Someone else is handling it: move it to Ignored">${this._ignoring === item.key ? spinner : ''}Ignore</button>` : ''}
            ${!merged && !ids.has('close') ? this._closeButton(item) : ''}
            <button class="sm quiet" aria-expanded="true" aria-label="Fewer actions" @click=${() => { this._moreFor = null; }}>×</button>`;
    }

    /** One step as a button (or the control that runs it: merge, update branch). */
    _action(item, n, primary) {
        const cls = primary ? 'sm primary' : 'sm quiet';
        const busy = this._doing === `${item.key} ${n.id}` || (n.id === 'mvnpm-check' && this._mvnpmStarting === item.key);
        switch (n.id) {
            case 'open': {
                const open = this._open === item.key;
                return html`<button class=${cls} aria-expanded=${open ? 'true' : 'false'} @click=${() => this._toggle(item.key)}>
                    ${open ? 'Close' : n.label}</button>`;
            }
            case 'go':
                return html`<a class="btn ${cls}" href=${threadHref(n.target)}>${n.label}</a>`;
            case 'merge':
                return this._mergeControl(item);
            case 'merge-anyway':
                return primary ? html`<merge-control anyway .itemKey=${item.key} .status=${null}
                        @merge-changed=${() => this._reloadNow()}></merge-control>`
                    : html`<button class=${cls} @click=${() => { this._mergeAnyway = item.key; this._moreFor = null; }}>${n.label}</button>`;
            case 'update-branch':
                return html`<conflict-menu behind .itemKey=${item.key} @ci-changed=${() => this._reloadNow()}></conflict-menu>`;
            case 'whats-new':
                return html`<button class=${cls} @click=${() => { this._activityFor = item.key; this._moreFor = null; }}>${n.label}</button>`;
            case 'close':
                return html`<button class=${cls} @click=${() => { this._confirmClose = item.key; }}>${n.label}</button>`;
            case 'ignore':
                return html`<button class=${cls} ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, true)}>
                    ${this._ignoring === item.key ? spinner : ''}${n.label}</button>`;
            default:
                return html`<button class=${cls} ?disabled=${this._doing !== null || this._mvnpmStarting != null}
                    @click=${() => this._run(item, n)}>${busy ? spinner : ''}${n.label}</button>`;
        }
    }

    /** Two clicks to close: the button, then the inline confirmation. */
    _closeButton(item) {
        const what = item.kind === 'PR' ? 'PR' : 'issue';
        if (this._confirmClose === item.key) {
            return html`<span class="confirm-close" role="group" aria-label="Confirm close">
                Close this ${what} on GitHub?
                <button class="sm danger" ?disabled=${this._closing !== null} @click=${() => this._close(item)}>
                    ${this._closing === item.key ? spinner : ''}Close ${what}</button>
                <button class="sm quiet" ?disabled=${this._closing !== null} @click=${() => { this._confirmClose = null; }}>Keep open</button>
            </span>`;
        }
        return html`<button class="sm quiet" @click=${() => { this._confirmClose = item.key; }}>Close</button>`;
    }

    _mergeAnywayControl(item) {
        return html`<merge-control anyway autoOpen .itemKey=${item.key} .status=${null}
            @merge-changed=${() => { this._mergeAnyway = null; this._reloadNow(); }}
            @merge-dismissed=${() => { this._mergeAnyway = null; }}></merge-control>`;
    }

    _mergeControl(item) {
        return html`<merge-control .itemKey=${item.key} .status=${this._board.merges?.[item.key] ?? null}
            @merge-changed=${() => this._reloadNow()}></merge-control>`;
    }

    /** Ignored and not-watched items: plain rows, folded at the bottom. */
    _renderItem(item, chips, actions = '') {
        rememberItem(item);
        return html`
            <li>
                ${kindIcon(item)}
                <div class="main" style="cursor:auto">
                    <a class="title" href=${item.url} target="_blank" rel="noopener">${item.title}</a>
                    <div class="meta">
                        <span>${item.repo} #${item.number}</span>
                        ${item.author ? html`<span>by ${item.author}</span>` : ''}
                        <span>updated ${ago(item.updatedAt)}</span>
                        ${chips.length ? html`<span class="chips">${chips}</span>` : ''}
                    </div>
                </div>
                <div class="acts">${actions}</div>
            </li>
        `;
    }

    _renderIgnored(ignored) {
        if (!ignored.length) return '';
        return html`
            <section id="sec-ignored" aria-label="Ignored">
                <details ?open=${this._ignoredOpen} @toggle=${e => { this._ignoredOpen = e.target.open; }}>
                    <summary>${count(ignored.length, 'ignored item')}, handled by someone else</summary>
                    <div class="panel">
                        <ul>${ignored.map(item => this._renderItem(item, itemChips(item), html`
                            <button class="sm" ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, false)}>
                                ${this._ignoring === item.key ? spinner : ''}Stop ignoring</button>`))}
                        </ul>
                    </div>
                </details>
            </section>
        `;
    }

    _renderHidden(hidden) {
        if (hidden.length === 0) return '';
        return html`
            <section id="sec-hidden" aria-label="Not watched">
                <details>
                    <summary>${count(hidden.length, 'more item')} from repos you don't watch</summary>
                    <div class="panel">
                        <ul>${hidden.map(item => {
                            const owner = item.repo.split('/')[0];
                            return this._renderItem(item, itemChips(item), html`
                                <button class="sm" ?disabled=${this._watching !== null} @click=${() => this._watch(item.repo)}>
                                    ${this._watching === item.repo ? spinner : ''}Watch repo</button>
                                <button class="sm quiet" ?disabled=${this._watching !== null} @click=${() => this._watch(owner)}>
                                    ${this._watching === owner ? spinner : ''}Watch ${owner}</button>`);
                        })}</ul>
                    </div>
                </details>
            </section>
        `;
    }
}

customElements.define('desk-board', DeskBoard);
