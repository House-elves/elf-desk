import { LitElement, html, css } from 'lit';
import { isDependabot, linkedPrChips, linksFor, prRef, rememberLinks, shared, ago, itemChips, ciFailing, ciNeedsApproval, hasConflicts, behind, mergeable, mergeableAnyway, kindIcon, spinner, threadHref, rememberItem } from './shared.js';
import { api, errorMessage, onPush, onConnectionChange } from './rpc.js';
import './merge-control.js';
import './ci-menu.js';
import './conflict-menu.js';
import './pr-activity.js';

const VERDICTS = {
    SAFE: { cls: 'ok', label: 'Safe' },
    NOT_SAFE: { cls: 'bad', label: 'Not safe' },
    NEEDS_HUMAN: { cls: 'warn', label: 'Needs a human look' },
};

const DATE = new Intl.DateTimeFormat('en-AU', { weekday: 'long', day: 'numeric', month: 'long' });

/** The last board this page saw: going back to the board shows it at once while it updates. */
let lastBoard = null;

/** "3 reviews", "1 review". */
function count(n, one, many = `${one}s`) {
    return `${n} ${n === 1 ? one : many}`;
}

/** The morning board: what needs you in one sentence, then the lanes. */
class DeskBoard extends LitElement {

    static properties = {
        _board: { state: true },
        _loading: { state: true },
        _refreshing: { state: true },
        _error: { state: true },
        _watching: { state: true },
        _starting: { state: true },
        _closing: { state: true },
        _confirmClose: { state: true },
        _moreFor: { state: true },
        _mergeAnyway: { state: true },
        _mvnpmStarting: { state: true },
        _activityFor: { state: true },
        _assigning: { state: true },
        _rebasing: { state: true },
        _rebaseAsked: { state: true },
        _ignoring: { state: true },
    };

    static styles = [shared, css`
        .hero { display: grid; gap: 10px; margin-bottom: 34px; max-width: 46rem; }
        .date { color: var(--muted); font-size: 15px; }
        .hero h1 { font-size: 34px; line-height: 1.18; font-weight: 600; letter-spacing: -0.022em; }
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

        .layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 36px; align-items: start; }
        aside { position: sticky; top: 84px; display: grid; gap: 2px; }
        aside button {
            justify-content: space-between; border: 0; background: transparent; padding: 8px 12px;
            font-weight: 500; color: var(--muted); width: 100%;
        }
        aside button:hover:not(:disabled) { color: var(--ink); }
        aside button .n { color: var(--ink); font-weight: 600; font-variant-numeric: tabular-nums; }
        aside button.zero .n { color: var(--muted); font-weight: 500; }
        aside hr { border: 0; border-top: 1px solid var(--line); margin: 10px 12px; }

        .lanes { display: grid; gap: 34px; min-width: 0; }
        .lane-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
        .lane-head h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .lane-head .n { color: var(--muted); font-size: 15px; }
        .lane-head .note { color: var(--muted); font-size: 13px; }
        section { scroll-margin-top: 90px; }

        ul { list-style: none; margin: 0; padding: 4px 0; }
        li {
            display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 4px 14px; align-items: start;
            padding: 13px 18px;
        }
        li + li { border-top: 1px solid var(--line); }
        li:hover { background: color-mix(in srgb, var(--surface-2) 55%, transparent); }
        li .icon { margin-top: 3px; }
        .main { min-width: 0; display: grid; gap: 6px; }
        .title { color: var(--ink); font-weight: 500; text-decoration: none; overflow-wrap: anywhere; }
        .title:hover { text-decoration: underline; }
        .meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; font-size: 13px; color: var(--muted); }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; font-size: 14px; }

        .notice { padding: 18px 20px; margin-bottom: 26px; display: grid; gap: 8px; max-width: 46rem; }
        .notice p { margin: 0; }

        .quiet-lane .panel { background: transparent; }
        details summary {
            cursor: pointer; color: var(--muted); font-weight: 500; padding: 4px 0; list-style-position: outside;
        }
        details summary:hover { color: var(--ink); }
        details[open] summary { margin-bottom: 12px; }

        .sk-row { display: grid; grid-template-columns: 16px 1fr 90px; gap: 14px; padding: 15px 18px; }
        .sk-row + .sk-row { border-top: 1px solid var(--line); }
        .sk-line { height: 12px; }

        @media (max-width: 960px) {
            .layout { grid-template-columns: minmax(0, 1fr); gap: 24px; }
            aside { position: static; display: flex; flex-wrap: wrap; gap: 6px; }
            aside button { width: auto; gap: 8px; background: var(--surface); border: 1px solid var(--line); padding: 6px 12px; }
            aside hr { display: none; }
        }
        @media (max-width: 600px) {
            .hero h1 { font-size: 26px; }
            li { grid-template-columns: 16px minmax(0, 1fr); padding: 13px 14px; }
            .acts { grid-column: 2; justify-content: flex-start; }
        }
    `];

    constructor() {
        super();
        this._board = lastBoard;
        this._loading = !lastBoard;
        this._watching = null;
        this._starting = null;
        this._closing = null;
        this._confirmClose = null;
        this._moreFor = null;
        this._ignoring = null;
        this._mvnpmStarting = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load(false);
        // Thread states change while the board is open (agent finished, draft posted): refresh the chips.
        this._offPush = [
            onPush('desk.thread', e => { if (e.type === 'changed') this._softReload(); }),
            onPush('desk.runner', () => this._softReload()),
            // The desk fetched fresh GitHub data in the background.
            onPush('desk.board', () => { if (this._error && !this._board) this._load(false); else this._softReload(); }),
            // Back after a dropped connection (the desk restarted, dev mode reloaded the page's code): if the board
            // didn't load, load it now rather than leaving the error up.
            onConnectionChange(state => {
                if (state === 'open' && (this._error || !this._board)) this._load(false);
            }),
        ];
    }

    disconnectedCallback() {
        clearTimeout(this._timer);
        clearTimeout(this._soft);
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
        this._soft = setTimeout(async () => {
            try {
                const desk = await api();
                this._setBoard(await desk.board({ refresh: false }));
            } catch (e) {
                // keep what we have
            }
        }, 1000);
    }

    _setBoard(b) {
        rememberLinks(b?.linkedPrs);
        this._board = b;
        lastBoard = b;
    }

    /** Run mvnpm-dependabot now: every repo, or one PR (item). */
    async _mvnpmRun(item) {
        this._mvnpmStarting = item ? item.key : 'all';
        this._error = null;
        try {
            const desk = await api();
            const checks = await desk.mvnpmRun(item ? { repo: item.repo, pr: item.number } : { repo: null, pr: null });
            this._setBoard({ ...this._board, mvnpmChecks: checks });
        } catch (e) {
            this._error = `Couldn't start the mvnpm check: ${errorMessage(e)}`;
        } finally {
            this._mvnpmStarting = null;
        }
    }

    /** Is a check running that covers this mvnpm PR (its own unit, or a full round)? */
    _mvnpmChecking(item) {
        const running = this._board.mvnpmChecks?.running ?? [];
        return running.includes('mvnpm-dependabot.service')
            || running.includes(`mvnpm-dependabot-pr-${item.repo.replace('/', '-')}-${item.number}.service`);
    }

    async _ignore(item, ignore) {
        this._ignoring = item.key;
        this._error = null;
        try {
            const desk = await api();
            if (ignore) await desk.ignore({ itemKey: item.key });
            else await desk.unignore({ itemKey: item.key });
            this._moreFor = null;
            this._setBoard(await desk.board({ refresh: false }));
        } catch (e) {
            this._error = `Couldn't ${ignore ? 'ignore' : 'stop ignoring'} ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._ignoring = null;
        }
    }

    async _askRebase(item) {
        this._rebasing = item.key;
        this._error = null;
        try {
            const desk = await api();
            await desk.askDependabotToRebase({ itemKey: item.key });
            this._rebaseAsked = new Set([...(this._rebaseAsked ?? []), item.key]);
        } catch (e) {
            this._error = `Couldn't ask Dependabot to rebase ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._rebasing = null;
        }
    }

    /**
     * Dependabot PRs: Rebase posts "@dependabot rebase". Not shown when the Conflicts or Behind base menu is there,
     * which already offers it.
     */
    _rebaseButton(item) {
        if (!isDependabot(item) || hasConflicts(item) || behind(item) || this._board.merges?.[item.key]) return '';
        if (this._rebaseAsked?.has(item.key)) return html`<span class="chip run">${spinner}Dependabot is rebasing</span>`;
        return html`<button class="sm quiet" ?disabled=${this._rebasing !== null} @click=${() => this._askRebase(item)}
                title='Comment "@dependabot rebase" on the PR, as you'>${this._rebasing === item.key ? spinner : ''}Rebase</button>`;
    }

    async _assignMe(item) {
        this._assigning = item.key;
        this._error = null;
        try {
            const desk = await api();
            await desk.assignMe({ itemKey: item.key });
            this._setBoard(await desk.board({ refresh: false }));
        } catch (e) {
            this._error = `Couldn't assign you to ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._assigning = null;
        }
    }

    /** Mentioned / involved: take it on. Issues then move to Assigned issues. */
    _assignButton(item, laneId) {
        if (laneId !== 'involved') return '';
        const me = this._board.login?.toLowerCase();
        if ((item.assignees ?? []).some(a => a.toLowerCase() === me)) return html`<span class="chip">Assigned to you</span>`;
        return html`<button class="sm" ?disabled=${this._assigning !== null} @click=${() => this._assignMe(item)}
                title="Assign yourself on GitHub">${this._assigning === item.key ? spinner : ''}Assign me</button>`;
    }

    async _close(item) {
        this._closing = item.key;
        this._error = null;
        try {
            const desk = await api();
            await desk.close({ itemKey: item.key });
            this._confirmClose = null;
            this._setBoard(await desk.board({ refresh: false }));
        } catch (e) {
            this._error = `Couldn't close ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._closing = null;
        }
    }

    async _start(item, action) {
        this._starting = item.key;
        this._error = null;
        try {
            const desk = await api();
            await desk.start({ itemKey: item.key, action, question: null });
            location.hash = threadHref(item.key);
        } catch (e) {
            this._error = `Couldn't start the agent on ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._starting = null;
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

    _jump(id) {
        this.renderRoot.querySelector(`#lane-${id}`)?.scrollIntoView({
            behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start',
        });
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
            <div class="lanes">
                <div class="panel">${rows(3)}</div>
                <div class="panel">${rows(2)}</div>
            </div>
        `;
    }

    _headline(b) {
        const lane = id => b.lanes.find(l => l.id === id);
        const review = lane('review').items.length;
        const mine = lane('mine').items.filter(i => i.reviewDecision === 'CHANGES_REQUESTED'
            || i.ci === 'FAILURE' || i.ci === 'ERROR' || i.mergeable === 'CONFLICTING').length;
        const states = Object.values(b.threads ?? {});
        const drafts = states.filter(t => t.state === 'draft').length;
        const working = states.filter(t => t.state === 'working').length;

        const link = (id, text) => html`<button @click=${() => this._jump(id)}>${text}</button>`;
        const parts = [];
        if (review) parts.push(html`${link('review', count(review, 'review'))} ${review === 1 ? 'is' : 'are'} waiting for you`);
        if (mine) parts.push(html`${link('mine', count(mine, 'of your PRs', 'of your PRs'))} ${mine === 1 ? 'needs' : 'need'} attention`);
        if (drafts) parts.push(html`${count(drafts, 'draft')} ${drafts === 1 ? 'is' : 'are'} ready to post`);
        if (working) parts.push(html`${parts.length ? 'an' : 'An'} agent is working on ${count(working, 'item')}`);
        if (parts.length === 0) return 'Nothing needs you right now.';
        const joined = [];
        parts.forEach((p, i) => {
            if (i > 0) joined.push(i === parts.length - 1 ? ' and ' : ', ');
            joined.push(p);
        });
        return html`${joined}.`;
    }

    _renderBoard(b) {
        const lanes = b.lanes;
        const mvnpmCount = b.mvnpm.installed ? b.mvnpm.rows.length : 0;
        return html`
            <div class="hero">
                <span class="date">${DATE.format(new Date())}</span>
                <h1>${b.scopeEmpty ? 'Pick what to watch, and your work shows up here.' : this._headline(b)}</h1>
                <div class="status">
                    <span class="runner ${b.runnerConnected ? 'on' : ''}">Runner ${b.runnerConnected ? 'online' : 'offline'}</span>
                    <span class="muted updated">${b.refreshing
                        ? html`${spinner}Updating, showing data from ${ago(b.fetchedAt)}`
                        : html`Updated ${ago(b.fetchedAt)}`}</span>
                    <button class="sm" @click=${() => this._load(true)} ?disabled=${this._refreshing}>
                        ${this._refreshing ? spinner : ''}${this._refreshing ? 'Refreshing' : 'Refresh'}
                    </button>
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
                <aside aria-label="Lanes">
                    ${lanes.filter(l => l.id !== 'involved').map(l => html`
                        <button class=${l.items.length ? '' : 'zero'} @click=${() => this._jump(l.id)}>
                            <span>${l.title}</span><span class="n">${l.items.length}</span></button>`)}
                    <button class=${mvnpmCount ? '' : 'zero'} @click=${() => this._jump('mvnpm')}>
                        <span>mvnpm updates</span><span class="n">${mvnpmCount}</span></button>
                    ${lanes.filter(l => l.id === 'involved').map(l => html`
                        <button class=${l.items.length ? '' : 'zero'} @click=${() => this._jump(l.id)}>
                            <span>${l.title}</span><span class="n">${l.items.length}</span></button>`)}
                    <hr>
                    ${b.hidden.length ? html`
                        <button class="zero" @click=${() => this._jump('hidden')}>
                            <span>Not watched</span><span class="n">${b.hidden.length}</span></button>` : ''}
                    ${b.ignored.length ? html`
                        <button class="zero" @click=${() => this._jump('ignored')}>
                            <span>Ignored</span><span class="n">${b.ignored.length}</span></button>` : ''}
                </aside>
                <div class="lanes">
                    ${lanes.filter(l => l.id !== 'involved').map(lane => this._renderLane(lane))}
                    ${this._renderMvnpm(b.mvnpm)}
                    ${lanes.filter(l => l.id === 'involved').map(lane => this._renderLane(lane))}
                    ${this._renderHidden(b.hidden)}
                    ${this._renderIgnored(b.ignored)}
                </div>
            </div>
        `;
    }

    _renderLane(lane) {
        const empty = {
            review: 'No reviews waiting.',
            mine: 'You have no open PRs.',
            assigned: 'No issues assigned to you.',
            involved: 'Nothing else involves you.',
        }[lane.id];
        return html`
            <section id="lane-${lane.id}" aria-labelledby="h-${lane.id}">
                <div class="lane-head"><h2 id="h-${lane.id}">${lane.title}</h2><span class="n">${lane.items.length}</span></div>
                <div class="panel">
                    ${lane.items.length === 0
                        ? html`<p class="empty">${empty}</p>`
                        : html`<ul>${lane.items.map(item =>
                            this._renderItem(item, [...this._threadChip(item), ...this._ciChips(item)],
                                this._actions(item, lane.id)))}</ul>`}
                </div>
            </section>
        `;
    }

    _renderItem(item, chips, actions = '') {
        rememberItem(item);
        return html`
            <li>
                ${kindIcon(item)}
                <div class="main">
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

    _threadChip(item) {
        const chips = [];
        const m = this._board?.merges?.[item.key];
        const since = this._board?.sinceReview?.[item.key];
        if (since && this._activityFor !== item.key) {
            chips.push(html`<pr-activity .itemKey=${item.key} .label=${since}></pr-activity>`);
        }
        const back = this._board?.returned?.[item.key];
        if (back) chips.push(html`<span class="chip run" title="It was ignored; this brought it back">Back: ${back.toLowerCase()}</span>`);
        if (m?.state === 'merged') chips.push(html`<span class="chip merged" title=${m.outcome ?? ''}>Merged</span>`);
        if (m?.state === 'failed') chips.push(html`<span class="chip bad" title=${m.outcome ?? ''}>Merge stopped: ${m.outcome}</span>`);
        return [...chips, ...this._agentChip(item)];
    }

    _agentChip(item) {
        const t = this._board?.threads?.[item.key];
        if (!t) return [];
        if (t.state === 'working') return [html`<span class="chip run">${spinner}Agent working</span>`];
        if (t.state === 'draft') return [html`<span class="chip ok">Draft ready</span>`];
        return [];
    }

    /** Status chips, with failing CI, CI awaiting approval, conflicts and "behind" as menus of what to do. */
    _ciChips(item, { approval = true } = {}) {
        const live = this._board.merges?.[item.key]?.state !== 'merged';
        return [...itemChips(item, { ciMenu: true, conflictMenu: true, approval }), ...(item.kind === 'ISSUE' ? linkedPrChips(item.key) : []),
            ...(ciFailing(item) && live ? [html`<ci-menu .itemKey=${item.key} @ci-changed=${() => this._softReload()}></ci-menu>`] : []),
            ...(ciNeedsApproval(item) ? [html`<ci-menu approval .itemKey=${item.key} @ci-changed=${() => this._softReload()}></ci-menu>`] : []),
            ...(hasConflicts(item) && live ? [html`<conflict-menu .itemKey=${item.key}></conflict-menu>`] : []),
            ...(behind(item) && live ? [html`<conflict-menu behind .itemKey=${item.key} @ci-changed=${() => this._softReload()}></conflict-menu>`] : [])];
    }

    /** Ignore and Close, behind "More" so rows stay calm. Close still asks to confirm. */
    _more(item, { withClose = true, withMergeAnyway = true, withIgnore = true } = {}) {
        if (this._moreFor !== item.key) {
            return html`<button class="sm quiet" aria-expanded="false" @click=${() => { this._moreFor = item.key; }}>More ▾</button>`;
        }
        return html`
            ${item.kind === 'PR' ? html`<button class="sm quiet" @click=${() => { this._activityFor = item.key; this._moreFor = null; }}>What's new</button>` : ''}
            ${withMergeAnyway && mergeableAnyway(item) && !this._board.merges?.[item.key] ? html`
                <button class="sm quiet" title="CI is green but nobody approved it: merge on your own judgement"
                        @click=${() => { this._mergeAnyway = item.key; this._moreFor = null; }}>Merge without approval</button>` : ''}
            ${withIgnore ? html`<button class="sm quiet" ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, true)}
                    title="Someone else is handling it: move it to Ignored">
                ${this._ignoring === item.key ? spinner : ''}Ignore</button>` : ''}
            ${withClose ? this._closeButton(item) : ''}
            <button class="sm quiet" aria-expanded="true" aria-label="Fewer actions" @click=${() => { this._moreFor = null; }}>×</button>`;
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

    /** Open the thread if there is one; otherwise the action that fits: Review for PRs, Investigate for issues. Close on both. */
    _actions(item, laneId) {
        // Merged here: nothing left to do. The row leaves the board on the next refresh.
        if (this._board.merges?.[item.key]?.state === 'merged') return '';
        if (this._mergeAnyway === item.key) return this._mergeAnywayControl(item);
        if (this._activityFor === item.key) {
            return html`<pr-activity autoOpen .itemKey=${item.key} @activity-closed=${() => { this._activityFor = null; }}></pr-activity>`;
        }
        if (this._confirmClose === item.key) return this._closeButton(item);
        // Merge only for approved PRs with green CI (or one already merging, so it can be cancelled).
        const waiting = this._board.merges?.[item.key]?.state === 'waiting';
        // Your own green PR without an approval: merging it yourself is common, so it's on the row, not under More.
        const ownReady = laneId === 'mine' && !waiting && mergeableAnyway(item) && item.reviewDecision !== 'CHANGES_REQUESTED';
        const merge = mergeable(item) || waiting ? this._mergeControl(item)
            : ownReady ? html`<button class="sm" @click=${() => { this._mergeAnyway = item.key; }}
                title="CI is green but nobody has approved it. Merge it yourself.">Merge without approval</button>` : '';
        const t = this._board.threads?.[item.key];
        // An issue that already has a PR fixing it: review that PR, or leave the issue to it.
        const fix = item.kind === 'ISSUE' ? linksFor(item.key).find(pr => pr.fixes) : null;
        const fixActs = fix ? html`<a class="btn sm ${t ? '' : 'primary'}" href=${threadHref(fix.key)}
                title="Open the PR here: ${fix.title}">Review PR ${prRef(item.key, fix.key)}</a>
            <button class="sm quiet" ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, true)}
                title="The PR covers it: move the issue to Ignored">${this._ignoring === item.key ? spinner : ''}Ignore</button>` : '';
        if (t) {
            return html`<a class="btn sm ${t.state === 'draft' ? 'primary' : ''}" href=${threadHref(item.key)}>
                ${t.state === 'draft' ? 'Review draft' : 'Open thread'}</a>${fixActs}${merge}${this._rebaseButton(item)}${this._assignButton(item, laneId)}${this._more(item, { withIgnore: !fix, withMergeAnyway: !ownReady })}`;
        }
        if (fix) return html`${fixActs}${this._assignButton(item, laneId)}${this._more(item, { withIgnore: false })}`;
        const starting = this._starting === item.key;
        const busy = this._starting !== null;
        let main = '';
        // Approved PRs offer Merge on green instead of another review.
        // Others' PRs: Review until approved. Your own PRs: always (a self-review before others look).
        if (item.kind === 'PR' && (laneId === 'mine' || !item.approved)) {
            main = html`<button class="sm primary" ?disabled=${busy} @click=${() => this._start(item, 'review')}>
                ${starting ? spinner : ''}Review</button>`;
        } else if (item.kind === 'ISSUE') {
            main = html`<button class="sm primary" ?disabled=${busy} @click=${() => this._start(item, 'investigate')}>
                ${starting ? spinner : ''}Investigate</button>`;
        }
        return html`${main}<a class="btn sm quiet" href=${threadHref(item.key)}>Ask</a>${merge}${this._rebaseButton(item)}${this._assignButton(item, laneId)}${this._more(item, { withMergeAnyway: !ownReady })}`;
    }

    _mergeAnywayControl(item) {
        return html`<merge-control anyway autoOpen .itemKey=${item.key} .status=${null}
            @merge-changed=${() => { this._mergeAnyway = null; this._softReload(); }}
            @merge-dismissed=${() => { this._mergeAnyway = null; }}></merge-control>`;
    }

    _mergeControl(item) {
        return html`<merge-control .itemKey=${item.key} .status=${this._board.merges?.[item.key] ?? null}
            @merge-changed=${() => this._softReload()}></merge-control>`;
    }

    _renderMvnpm(m) {
        if (!m.installed) {
            return html`
                <section id="lane-mvnpm" class="quiet-lane" aria-labelledby="h-mvnpm">
                    <div class="lane-head"><h2 id="h-mvnpm">mvnpm updates</h2></div>
                    <p class="muted small">mvnpm-dependabot isn't set up on this machine.</p>
                </section>`;
        }
        const tally = { SAFE: 0, NOT_SAFE: 0, NEEDS_HUMAN: 0, pending: 0 };
        m.rows.forEach(r => {
            if (r.check && r.current && tally[r.check.verdict] !== undefined) tally[r.check.verdict]++;
            else tally.pending++;
        });
        return html`
            <section id="lane-mvnpm" class="quiet-lane" aria-labelledby="h-mvnpm">
                <div class="lane-head">
                    <h2 id="h-mvnpm">mvnpm updates</h2><span class="n">${m.rows.length}</span>
                    <span class="note">Handled by mvnpm-dependabot in ${m.repos.join(', ')}</span>
                    ${this._renderMvnpmRun()}
                </div>
                ${m.rows.length ? html`
                    <div class="chips" style="margin-bottom:12px">
                        ${tally.SAFE ? html`<span class="chip ok">${tally.SAFE} safe</span>` : ''}
                        ${tally.NOT_SAFE ? html`<span class="chip bad">${tally.NOT_SAFE} not safe</span>` : ''}
                        ${tally.NEEDS_HUMAN ? html`<span class="chip warn">${tally.NEEDS_HUMAN} need a look</span>` : ''}
                        ${tally.pending ? html`<span class="chip">${tally.pending} not checked yet</span>` : ''}
                    </div>` : ''}
                <div class="panel">
                    ${m.rows.length === 0
                        ? html`<p class="empty">No open mvnpm PRs.</p>`
                        : html`<ul>${m.rows.map(r => this._renderItem(r.item, [...this._threadChip(r.item), ...this._mvnpmChips(r),
                            ...this._ciChips(r.item, { approval: false })], this._mvnpmActions(r)))}</ul>`}
                </div>
            </section>
        `;
    }

    _renderMvnpmRun() {
        const c = this._board.mvnpmChecks;
        if (c?.running?.length) {
            const all = c.running.includes('mvnpm-dependabot.service');
            return html`<span class="chip run" style="margin-left:auto">${spinner}${all ? 'Checks running' : `Checking ${c.running.length} PR${c.running.length === 1 ? '' : 's'}`}${c.since ? html`, started ${ago(c.since)}` : ''}</span>`;
        }
        return html`<button class="sm" style="margin-left:auto" ?disabled=${this._mvnpmStarting != null || !this._board.runnerConnected}
                title=${this._board.runnerConnected ? 'Run mvnpm-dependabot over every repo now' : 'The runner is offline'}
                @click=${() => this._mvnpmRun(null)}>${this._mvnpmStarting === 'all' ? spinner : ''}Run checks now</button>`;
    }

    _mvnpmActions(r) {
        if (this._board.merges?.[r.item.key]?.state === 'merged') return '';
        if (this._mergeAnyway === r.item.key) return this._mergeAnywayControl(r.item);
        if (this._activityFor === r.item.key) {
            return html`<pr-activity autoOpen .itemKey=${r.item.key} @activity-closed=${() => { this._activityFor = null; }}></pr-activity>`;
        }
        const waiting = this._board.merges?.[r.item.key]?.state === 'waiting';
        const checking = this._mvnpmChecking(r.item);
        return html`
            ${checking ? html`<span class="chip run">${spinner}Checking</span>`
                : html`<button class="sm quiet" ?disabled=${this._mvnpmStarting != null || !this._board.runnerConnected}
                    title="Run mvnpm-dependabot on this PR now (even if it was checked before)"
                    @click=${() => this._mvnpmRun(r.item)}>${this._mvnpmStarting === r.item.key ? spinner : ''}Check now</button>`}
            ${r.check?.commentUrl
                ? html`<a class="btn sm quiet" href=${r.check.commentUrl} target="_blank" rel="noopener">Elf's comment</a>` : ''}
            ${this._rebaseButton(r.item)}
            ${mergeable(r.item) || waiting ? this._mergeControl(r.item)
                // All green but not approved: mvnpm bumps are routine, so Merge without approval is right on the row, not under More.
                : mergeableAnyway(r.item) && !this._board.merges?.[r.item.key] ? html`<merge-control anyway .itemKey=${r.item.key}
                    .status=${null} @merge-changed=${() => this._softReload()}></merge-control>` : ''}
            ${this._confirmClose === r.item.key ? this._closeButton(r.item)
                // The elf says this bump isn't safe: closing it is the usual next step, so offer it directly.
                : r.check?.verdict === 'NOT_SAFE' && r.current
                    ? html`${this._closeButton(r.item)}${this._more(r.item, { withClose: false, withMergeAnyway: false })}`
                : this._more(r.item, { withMergeAnyway: false })}`;
    }

    _mvnpmChips(r) {
        if (!r.check) return [html`<span class="chip">Not checked yet</span>`];
        if (!r.current) return [html`<span class="chip">Not checked at this commit</span>`];
        const v = VERDICTS[r.check.verdict] ?? { cls: '', label: r.check.verdict };
        const chips = [html`<span class="chip ${v.cls}">${v.label}</span>`];
        // GitHub's "Approved" chip comes with the CI chips.
        if (r.check.verdict === 'SAFE' && r.item.reviewDecision !== 'APPROVED') {
            chips.push(r.check.approved ? html`<span class="chip ok">Approved</span>` : html`<span class="chip">Not approved</span>`);
        }
        return chips;
    }

    _renderIgnored(ignored) {
        if (!ignored.length) return '';
        return html`
            <section id="lane-ignored" aria-label="Ignored">
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
            <section id="lane-hidden" aria-label="Not watched">
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
