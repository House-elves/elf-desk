// The kit an elf builds its own desk tab with, as /elf-kit/1/kit.js: Lit, the desk's look, and `elf`, the bridge to
// the desk. The tab runs in a sandboxed frame with no origin of its own and no network: everything it reads or does
// goes through the bridge, to the desk's page, which allows only what the elf's elf.yaml declares (elf-frame.js).
//
//   import { LitElement, html, css, shared, elf } from '/elf-kit/1/kit.js';
//
// Version 1: what's exported here stays, so elves keep working as the desk changes.

import { LitElement, css, html } from 'lit';
import { shared, spinner, Ticking, ago } from '../app/shared.js';

export { LitElement, html, css, svg, nothing } from 'lit';
export { unsafeHTML } from 'lit/directives/unsafe-html.js';
export { repeat } from 'lit/directives/repeat.js';
export {
    shared, markdown, markdownHtml, markdownStyles, spinner, ago, runTime, took, calendarText, Ticking, copyText, copyHtml,
} from '../app/shared.js';

// ---- where it runs: /elf-frame/<elf>?view=tab|card ----

const [, , name] = location.pathname.split('/');
/** Kept for elves written when an elf had several tabs: now its one tab, named after the elf. */
const tab = name;
const query = new URLSearchParams(location.search);
const view = query.get('view') === 'card' ? 'card' : 'tab';
/** The desk's key for this load of the frame: it answers only messages that carry it. */
const key = query.get('key');
/** The key the desk serves this elf's files under: in its page's own script's URL (/elf-files/<key>/ui/...). */
const filesKey = (/\/elf-files\/([0-9a-f]{32})\//.exec(document.querySelector('script[type="module"]')?.src ?? '') ?? [])[1] ?? '';
/** Where in the tab the desk's link pointed when it loaded: what follows #<elf>/ (see elf.go). */
let route = query.get('route') ?? '';
const routeListeners = new Set();

// ---- the desk's look: the same tokens as the desk's index.html ----

const tokens = document.createElement('style');
tokens.textContent = `
    :root {
        --bg: #f4f6f1; --surface: #ffffff; --surface-2: #edf1ea; --ink: #17201b; --muted: #56635b; --line: #dde3da;
        --accent: #24533f; --accent-ink: #ffffff; --accent-soft: #e2eee6; --bobble: #f0c33c;
        --ok: #1d8049; --ok-soft: #e1f3e8; --bad: #c3352a; --bad-soft: #fbe8e6; --warn: #965c00; --warn-soft: #faefd8;
        --idle: #6b7280; --idle-soft: #edf0f3; --merged: #8250df; --merged-soft: #f1eafe;
        --log-bg: #121813; --log-ink: #c9d4cc;
        --shadow: 0 1px 2px rgb(23 32 27 / 0.05), 0 6px 24px rgb(23 32 27 / 0.05);
        --font: "IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif;
        --font-display: "Bricolage Grotesque", "IBM Plex Sans", system-ui, sans-serif;
        --font-code: "JetBrains Mono", ui-monospace, "SFMono-Regular", Menlo, monospace;
        --r-panel: 14px; --r-control: 9px;
        color-scheme: light;
    }
    @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { DARK } }
    :root[data-theme="dark"] { DARK }
    html, body { margin: 0; background: transparent; color: var(--ink); }
    body { font: 15px/1.55 var(--font); -webkit-font-smoothing: antialiased; display: flow-root; }
`.replaceAll('DARK', `
    --bg: #0f1411; --surface: #161d19; --surface-2: #1d2621; --ink: #e6ece7; --muted: #94a39a;
    --line: #27322c; --accent: #7db68a; --accent-ink: #0e1f15; --accent-soft: #1a2d22;
    --ok: #52c58a; --ok-soft: #12291d; --bad: #ff7d72; --bad-soft: #351816;
    --warn: #e8b14c; --warn-soft: #332610; --idle: #949dab; --idle-soft: #20252e;
    --merged: #a371f7; --merged-soft: #2a1f45; --log-bg: #0a0e0b; --log-ink: #c9d4cc;
    --shadow: 0 1px 2px rgb(0 0 0 / 0.3), 0 6px 24px rgb(0 0 0 / 0.25);
    color-scheme: dark;`);
document.head.append(tokens);

// ---- the bridge ----

let next = 0;
const waiting = new Map();
const changeListeners = new Set();

function post(op, args) {
    parent.postMessage({ elfdesk: 1, key, op, args }, '*');
}

function call(op, args = {}) {
    const id = ++next;
    return new Promise((resolve, reject) => {
        waiting.set(id, { resolve, reject });
        parent.postMessage({ elfdesk: 1, key, id, op, args }, '*');
    });
}

window.addEventListener('message', e => {
    if (e.source !== parent || e.data?.elfdesk !== 1) return;
    const m = e.data;
    if (m.id != null) {
        const w = waiting.get(m.id);
        if (!w) return;
        waiting.delete(m.id);
        if (m.error) {
            const err = new Error(m.error);
            err.cancelled = !!m.cancelled;
            w.reject(err);
        } else {
            w.resolve(m.result);
        }
    } else if (m.event === 'changed') {
        changeListeners.forEach(l => l(m.what ?? null));
    } else if (m.event === 'route') {
        route = String(m.route ?? '');
        routeListeners.forEach(l => l(route));
    } else if (m.event === 'theme') {
        if (m.data === 'light' || m.data === 'dark') document.documentElement.dataset.theme = m.data;
        else delete document.documentElement.dataset.theme;
    }
});

/**
 * The desk, as far as this elf may use it. Every call goes to the desk's page and comes back as a promise.
 */
export const elf = {
    /** This elf's name, and whether this is its tab ("tab") or its card on the dashboard ("card"). */
    name, tab, view,

    /**
     * Its runs: {running: {command: since}, failed: {command: the end of its log}}, and when it refreshes on its own:
     * refresh: {command, calendar (null: only when you refresh it), next}, or null if it doesn't refresh.
     */
    state: () => call('state'),
    /**
     * Calls back when one of its runs starts or ends, or (with the folder's name, such as "agents") when something
     * else changed its data; returns the unsubscribe.
     */
    onChange(callback) {
        changeListeners.add(callback);
        return () => changeListeners.delete(callback);
    },

    /** The files in one of the folders its elf.yaml lists under data, newest name first: [{name, size, modified}]. */
    list: folder => call('list', { folder }).then(r => r.files ?? []),
    /** Some of those files: {name: text}, or with fields, {name: {field: value}} of each JSON file. */
    read: (folder, files, fields) => call('read', { folder, files, fields }).then(r => r.files ?? {}),
    /** One JSON file, parsed, or null if it isn't there. */
    async readJson(folder, file) {
        const text = (await elf.read(folder, [file]))[file];
        return text == null ? null : JSON.parse(text);
    },

    /** Starts one of its commands in the background, with the values it takes. Resolves with state(). */
    run: (command, params) => call('run', { command, params }),
    /**
     * One of its actions, with values. The desk asks you first unless the elf.yaml says ask: false; Cancel there
     * rejects with an error whose `cancelled` is true. Resolves with what the elf printed last (JSON if it was).
     */
    act: (action, params) => call('act', { action, params }),

    /**
     * Agents, for an elf whose elf.yaml says agents: true. One of its actions starts one by answering with
     * {agent: {id, checkout: {repo, number, kind: PR | ISSUE}, prompt, untrusted, schema, session}}; the runner runs
     * it in its sandbox and reports in agents/<id>.json: {id, status: queued | running | done | failed | cancelled,
     * lines, output, session, git, files, error, worktree, created, started, finished}. onChange calls back with
     * "agents" as it goes.
     */
    agent: id => elf.readJson('agents', `${id}.json`),
    /**
     * A URL for an image in one of its data folders (an agent's screenshot: agents/<id>--<name>), for an img's src.
     * Only images (png, jpg, gif, webp), only from the folders its elf.yaml lists.
     */
    imageUrl: (folder, name) => `/elf-data/${filesKey}/${encodeURIComponent(folder)}/${encodeURIComponent(name)}`,
    /** Stops one of its agents. */
    cancelAgent: id => call('cancelAgent', { id }),

    /** Opens the desk's settings for this elf, with only these keys (default: all). Secrets never reach the tab. */
    settings: (keys, heading) => call('settings', { keys, heading }),
    /** Its settings' values, secrets left out. */
    values: () => call('values'),
    /**
     * Places in its tab, as desk links (#<elf>/<route>), so Back and a shared link work: route() is where it is now,
     * go(route) goes somewhere (replace: true, without a new history entry), and onRoute calls back when the desk
     * goes somewhere else in it (Back, a link). A route is letters, digits and / - . _ ~ %.
     */
    route: () => route,
    go: (to, { replace = false } = {}) => {
        route = String(to ?? '');
        return call('go', { route, replace });
    },
    onRoute(callback) {
        routeListeners.add(callback);
        return () => routeListeners.delete(callback);
    },

    /** Goes to its tab on the desk (from its dashboard card, say). */
    open: () => call('open'),

    /** A little memory for the tab (unsent edits, say), kept by the desk in this browser. Values are strings. */
    store: {
        get: key => call('store-get', { key }),
        set: (key, value) => call('store-set', { key, value }),
    },

    /** On the dashboard: whether the card needs you (the desk marks it). */
    card: ({ attention = false } = {}) => post('card', { attention: !!attention }),
};

// The frame is as tall as what's in it.
new ResizeObserver(() => post('size', { height: Math.ceil(document.body.getBoundingClientRect().height) }))
    .observe(document.body);

// Links: other sites open in a new tab of their own; a link to # (or #anything) goes to this elf's tab on the desk.
document.addEventListener('click', e => {
    const a = e.composedPath().find(n => n instanceof HTMLAnchorElement);
    if (!a || e.defaultPrevented) return;
    const href = a.getAttribute('href') ?? '';
    if (href.startsWith('#')) {
        e.preventDefault();
        elf.open().catch(() => { /* the desk said no */ });
    } else if (/^https?:/i.test(href)) {
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
    }
});

// ---- pieces most tabs have ----

/** A dashboard card's look: one sentence (.say) on what needs you, and a line of details under it (.meta). */
export const cardStyles = css`
    .say { margin: 0; font-size: 22px; line-height: 1.35; font-weight: 500; letter-spacing: -0.012em; text-wrap: balance; }
    .say a { color: inherit; text-decoration: underline; text-decoration-thickness: 2px; text-underline-offset: 4px;
        text-decoration-color: var(--line); }
    .say a:hover { text-decoration-color: var(--accent); }
    .meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; font-size: 13.5px; color: var(--muted); margin-top: 8px; }
    .meta:empty { display: none; }
    .meta .running { display: inline-flex; align-items: center; gap: 7px; color: var(--accent); }
    .skeleton.say-sk { height: 28px; width: min(30rem, 100%); }
    @media (max-width: 600px) { .say { font-size: 19px; } }
`;

/** "3 emails", "1 topic". (Not desk-board.js's: that one brings the desk's socket along.) */
export function count(n, one, many = `${one}s`) {
    return `${n} ${n === 1 ? one : many}`;
}

/** "a, b and c". */
export function joinParts(parts) {
    if (parts.length <= 1) return parts[0] ?? '';
    return html`${parts.slice(0, -1).map((p, i) => html`${i ? ', ' : ''}${p}`)} and ${parts.at(-1)}`;
}

/** The run happened today: tells a stale run from this morning's. */
export function isToday(at) {
    const d = new Date(at);
    const now = new Date();
    return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
}

/** The Settings button in a tab's title row: the desk's settings for these keys. */
export function settingsButton(keys, heading) {
    return html`<button class="quiet settings-toggle" @click=${() => elf.settings(keys, heading)}>
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true">
            <path d="M2 4h7M12.5 4H14M2 8h2.5M8 8h6M2 12h7M12.5 12H14"/>
            <circle cx="10.75" cy="4" r="1.6"/><circle cx="6.25" cy="8" r="1.6"/><circle cx="10.75" cy="12" r="1.6"/>
        </svg>Settings</button>`;
}

/**
 * The bar over a tab: the button that runs one of its commands, progress while it runs, the log of a failed run, and
 * a picker for earlier runs. Fires "run-picked" (detail: the run's id, or null for the newest) and "run-finished".
 *
 *   <elf-run-bar command="email" label="Triage email now" .runs=${[{id, label}]} .picked=${id}></elf-run-bar>
 */
class ElfRunBar extends LitElement {

    static properties = {
        command: {},
        label: {},
        note: {},
        runs: { attribute: false },   // [{id, label}], newest first
        picked: {},
        params: { attribute: false }, // optional: () => the values the command takes
        _state: { state: true },
        _starting: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
        .bar select { max-width: 100%; }
        .status { display: inline-flex; align-items: center; gap: 8px; font-size: 14px; color: var(--accent); }
        .note { margin: 8px 0 0; font-size: 13px; color: var(--muted); max-width: 46rem; }
        .failed { margin-top: 12px; display: grid; gap: 8px; }
        pre {
            margin: 0; padding: 12px 14px; border-radius: var(--r-control); overflow-x: auto;
            background: var(--log-bg); color: var(--log-ink); font: 12.5px/1.5 var(--font-code); white-space: pre-wrap;
        }
    `];

    constructor() {
        super();
        new Ticking(this);
        this.runs = [];
        this.picked = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load(true));
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    get running() {
        return this._state?.running?.[this.command];
    }

    async _load(pushed = false) {
        try {
            const was = !!this.running;
            this._state = await elf.state();
            if (pushed && was && !this.running) this.dispatchEvent(new CustomEvent('run-finished', { bubbles: true, composed: true }));
        } catch (e) {
            this._error = e.message;
        }
    }

    async _run() {
        this._starting = true;
        this._error = null;
        try {
            this._state = await elf.run(this.command, this.params?.());
        } catch (e) {
            this._error = e.message;
        } finally {
            this._starting = false;
        }
    }

    render() {
        const running = this.running;
        const failed = this._state?.failed?.[this.command];
        return html`
            <div class="bar">
                <button class="primary" ?disabled=${!!running || this._starting} @click=${this._run}>
                    ${running || this._starting ? spinner : ''}${this.label}</button>
                ${running ? html`<span class="status" role="status">Running, started ${ago(running)}</span>` : ''}
                ${this.runs.length > 1 ? html`<label class="small muted">Show
                    <select aria-label="Run to show" @change=${e => this.dispatchEvent(new CustomEvent('run-picked',
                        { detail: e.target.value || null, bubbles: true, composed: true }))}>
                        ${this.runs.map((r, i) => html`<option value=${i === 0 ? '' : r.id}
                            ?selected=${(this.picked ?? '') === (i === 0 ? '' : r.id)}>${r.label}${i === 0 ? ' (latest)' : ''}</option>`)}
                    </select></label>` : ''}
            </div>
            ${this.note ? html`<p class="note">${this.note}</p>` : ''}
            ${this._error ? html`<p class="error" role="alert" style="margin-top:12px">${this._error}</p>` : ''}
            ${failed && !running ? html`<div class="failed" role="alert">
                <p class="error">The last run failed. The end of its log:</p><pre>${failed}</pre></div>` : ''}
        `;
    }
}

customElements.define('elf-run-bar', ElfRunBar);
