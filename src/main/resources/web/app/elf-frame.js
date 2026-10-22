import { LitElement, html, css } from 'lit';
import { shared, closeX } from './shared.js';
import { api, errorMessage, onPush } from './rpc.js';
import { currentTheme } from './theme.js';
import { findTab, installedElf, tabOf } from './tabs.js';
import './elf-settings.js';
import './elf-manage.js';
import './elf-schedule.js';

/** What a frame may keep in this browser (elf.store): per elf, keys like these, values up to 200 K characters. */
const STORE_KEY = /^[A-Za-z0-9._-]{1,100}$/;
const STORE_MAX = 200_000;

/**
 * An elf's tab (or its dashboard card), drawn by the elf's UI in a sandboxed frame: no origin of its own, no
 * network, no cookie, no page key. It can only post messages here, and this element answers only for that elf and
 * only what its elf.yaml declares: its runs, the folders it may read, its commands and actions, its settings (in
 * the desk's own dialog, so secrets never reach it). An action that asks waits for your click in the desk's own bar.
 */
class ElfFrame extends LitElement {

    static properties = {
        elf: {},
        view: {},          // "tab", "card", or "settings" (its own settings, in the desk's Settings pop-up)
        route: {},         // where in its tab the desk's link points (see go)
        _height: { state: true },
        _ask: { state: true },       // {action, label, text, params, resolve}
        _settings: { state: true },  // {keys, heading}
    };

    static styles = [shared, css`
        :host { display: block; }
        iframe { display: block; width: 100%; border: 0; background: transparent; }
        .ask {
            position: fixed; left: 50%; bottom: 20px; transform: translateX(-50%); z-index: 20;
            width: min(44rem, calc(100vw - 32px)); box-sizing: border-box;
            background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-panel);
            box-shadow: 0 10px 36px color-mix(in srgb, var(--ink) 22%, transparent);
            padding: 16px 18px; display: grid; gap: 12px;
        }
        .ask .from { font-size: 12.5px; color: var(--muted); display: flex; gap: 6px; align-items: center; }
        .ask .from svg { width: 14px; height: 14px; color: var(--accent); }
        .ask .text { font-weight: 600; font-size: 15px; overflow-wrap: anywhere; }
        .ask details summary { cursor: pointer; font-size: 13.5px; color: var(--muted); }
        .ask dl { margin: 8px 0 0; display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 6px 14px; font-size: 13px; }
        .ask dt { color: var(--muted); }
        .ask dd { margin: 0; max-height: 14rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--font-code); }
        .ask .acts { display: flex; flex-wrap: wrap; gap: 10px; }
        dialog {
            border: 1px solid var(--line); border-radius: var(--r-panel); background: var(--bg); color: var(--ink);
            width: min(52rem, calc(100vw - 32px)); max-height: calc(100vh - 64px); padding: 22px 24px; box-sizing: border-box;
        }
        dialog::backdrop { background: color-mix(in srgb, var(--ink) 35%, transparent); }
        dialog .close { display: flex; justify-content: flex-end; margin-top: 16px; }
        dialog .title { margin: 0 0 4px; }
        dialog > elf-frame { margin-bottom: 20px; }
        dialog .when { margin-top: 20px; padding: 16px 18px; border: 1px solid var(--line); border-radius: var(--r-panel); background: var(--surface); }
    `];

    constructor() {
        super();
        this.view = 'tab';
        this._height = 0;
        this._onMessage = e => this._message(e);
        this._onTheme = e => this._send({ event: 'theme', data: e.detail });
    }

    connectedCallback() {
        super.connectedCallback();
        window.addEventListener('message', this._onMessage);
        window.addEventListener('elfdesk-theme', this._onTheme);
        this._offPush = onPush('desk.elves', d => {
            if (d?.elf && d.elf !== this.elf) return; // another elf's data
            this._send({ event: 'changed', what: d?.what ?? null });
        });
    }

    disconnectedCallback() {
        window.removeEventListener('message', this._onMessage);
        window.removeEventListener('elfdesk-theme', this._onTheme);
        this._offPush?.();
        this._ask?.resolve(false);
        super.disconnectedCallback();
    }

    get _frame() {
        return this.renderRoot?.querySelector('iframe');
    }

    _send(message) {
        this._frame?.contentWindow?.postMessage({ elfdesk: 1, ...message }, '*');
    }

    /** Only messages from this element's own frame, which speaks for this elf and nothing else. */
    async _message(e) {
        const frame = this._frame;
        if (!frame || e.source !== frame.contentWindow || e.data?.elfdesk !== 1) return;
        // The key in this load's URL: a page the frame navigated to (a link clicked inside it) can't speak for the elf.
        if (e.data.key !== this._key) return;
        const { id, op, args = {} } = e.data;
        if (op === 'size') {
            const h = Number(args.height);
            if (Number.isFinite(h)) this._height = Math.max(0, Math.min(h, 100_000));
            return;
        }
        if (op === 'card') {
            this.dispatchEvent(new CustomEvent('elf-card', { detail: { attention: !!args.attention }, bubbles: true, composed: true }));
            return;
        }
        if (id == null) return;
        try {
            this._send({ id, result: await this._answer(op, args) ?? null });
        } catch (err) {
            this._send({ id, error: err.cancelled ? 'Cancelled' : errorMessage(err), cancelled: !!err.cancelled });
        }
    }

    async _answer(op, args) {
        const elf = this.elf;
        const desk = await api();
        switch (op) {
            case 'state': return this._state(await desk.elvesState());
            case 'list': return desk.elfData({ elf, folder: String(args.folder ?? '') });
            case 'read': return desk.elfRead({ elf, folder: String(args.folder ?? ''), files: strings(args.files),
                fields: args.fields ? strings(args.fields) : null });
            case 'run':
                await desk.runElf({ elf, command: String(args.command ?? ''), params: object(args.params) });
                return this._state(await desk.elvesState());
            case 'act': return this._act(desk, String(args.action ?? ''), object(args.params) ?? {});
            case 'cancelAgent': return desk.cancelElfAgent({ elf, id: String(args.id ?? '') });
            case 'settings':
                if (this.view === 'settings') return null; // already in them
                this._settings = { keys: args.keys ? strings(args.keys) : null, heading: String(args.heading ?? `${installedElf(elf)?.title ?? elf} settings`).slice(0, 80) };
                await this.updateComplete;
                this.renderRoot.querySelector('dialog')?.showModal();
                return null;
            case 'values': {
                const c = await desk.elfSettings({ elf });
                return Object.fromEntries((c.settings ?? []).filter(s => s.kind !== 'secret').map(s => [s.key, s.value ?? '']));
            }
            case 'open': {
                // Its own tab, or another installed elf's (the GitHub board pointing at mvnpm's, say): only a tab.
                const to = args.elf == null ? elf : String(args.elf);
                if (to !== elf && !findTab(to)) throw new Error(`There's no ${to} tab`);
                location.hash = `#${to}`;
                return null;
            }
            case 'go': {
                // A place in its own tab, as the desk's link: #<elf>/<route>, so Back and a shared link work.
                const route = String(args.route ?? '').slice(0, 500).replace(/^\/+/, '');
                if (/[\s#?]/.test(route)) throw new Error('Not a place in the tab');
                this._sentRoute = route;
                const hash = route ? `#${elf}/${route}` : `#${elf}`;
                if (args.replace) history.replaceState(null, '', hash);
                else if (location.hash !== hash) history.pushState(null, '', hash);
                return null;
            }
            case 'store-get': return storage(elf, args.key);
            case 'store-set': {
                const value = args.value == null ? null : String(args.value);
                if (value != null && value.length > STORE_MAX) throw new Error('That is too much to keep');
                storage(elf, args.key, value, true);
                return null;
            }
            default: throw new Error(`Not something the desk does: ${op}`);
        }
    }

    /**
     * This elf's runs only, by command: {running: {command: since}, failed: {command: log}}, and when it refreshes on
     * its own: {refresh: {command, calendar, next}} (calendar null: only when you refresh it).
     */
    _state(all) {
        const own = map => Object.fromEntries(Object.entries(map ?? {})
            .filter(([k]) => k.startsWith(`${this.elf}:`)).map(([k, v]) => [k.slice(this.elf.length + 1), v]));
        const r = installedElf(this.elf)?.refresh;
        return { running: own(all?.running), failed: own(all?.failed),
            refresh: r ? { command: r.command, calendar: r.calendar ?? null, next: r.next ?? null } : null };
    }

    /** An action: straight away if its elf.yaml says ask: false, otherwise once you confirm in the bar below. */
    async _act(desk, name, params) {
        const action = (installedElf(this.elf)?.actions ?? []).find(a => a.name === name);
        if (!action) throw new Error(`Not one of ${this.elf}'s actions: ${name}`);
        // Asks unless its elf.yaml says ask: false.
        if (action.ask !== false) {
            const yes = await new Promise(resolve => {
                this._ask?.resolve(false);
                this._ask = { action, text: fill(action.confirm || action.label, params), params, resolve };
            });
            this._ask = null;
            if (!yes) {
                const e = new Error('Cancelled');
                e.cancelled = true;
                throw e;
            }
        }
        return (await desk.elfAction({ elf: this.elf, action: name, params })).result;
    }

    _askBar() {
        const a = this._ask;
        if (!a) return '';
        const entries = Object.entries(a.params ?? {});
        return html`<div class="ask" role="alertdialog" aria-labelledby="ask-text">
            <span class="from"><svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
                <path d="M8 1.5 2.5 4v4c0 3.2 2.3 5.6 5.5 6.5 3.2-.9 5.5-3.3 5.5-6.5V4z"/></svg>
                The desk asks, for ${installedElf(this.elf)?.title ?? this.elf}</span>
            <span class="text" id="ask-text">${a.text}</span>
            ${entries.length ? html`<details><summary>What it sends</summary><dl>
                ${entries.map(([k, v]) => html`<dt>${k}</dt><dd>${typeof v === 'string' ? v : JSON.stringify(v)}</dd>`)}
            </dl></details>` : ''}
            <div class="acts">
                <button class="primary" @click=${() => a.resolve(true)}>${a.action.label}</button>
                <button class="quiet" @click=${() => a.resolve(false)}>Cancel</button>
            </div>
        </div>`;
    }

    _settingsDialog() {
        const s = this._settings;
        const own = !s?.keys && ownSettings(this.elf);
        return html`<dialog aria-label=${s?.heading ?? 'Settings'} @close=${() => { this._settings = null; this._send({ event: 'changed' }); }}>
            ${s ? html`${closeX()}${!s.keys ? html`<elf-manage only="update" elf=${this.elf} @elf-updated=${() => this._updated()}></elf-manage>` : ''}
                ${own ? html`<h2 class="title">${s.heading}</h2><elf-frame elf=${this.elf} view="settings"></elf-frame>` : ''}
                <elf-settings elf=${this.elf} .keys=${s.keys} heading=${own ? 'Its setup' : s.heading}
                note="Secrets are never shown."></elf-settings>
                ${installedElf(this.elf)?.refresh ? html`<div class="when"><elf-schedule .elf=${installedElf(this.elf)}></elf-schedule></div>` : ''}
                ${!s.keys ? html`<elf-manage class="when" only="rest" elf=${this.elf} @elf-updated=${() => this._updated()}
                    @elf-removed=${() => this.renderRoot.querySelector('dialog').close()}></elf-manage>` : ''}
                <div class="close"><button @click=${() => this.renderRoot.querySelector('dialog').close()}>Close</button></div>` : ''}
        </dialog>`;
    }

    /** Updated from its settings: the tab loads the new version (its files have a new key). */
    _updated() {
        this.renderRoot.querySelector('dialog')?.close();
        this._src = null;
        this.requestUpdate();
    }

    willUpdate(changed) {
        // Only when it's a different tab: a new src reloads the frame. The theme follows by message after that.
        if (changed.has('elf') || changed.has('view') || !this._src) {
            const theme = currentTheme();
            this._key = crypto.randomUUID();
            const view = ['card', 'settings'].includes(this.view) ? this.view : 'tab';
            this._src = `/elf-frame/${encodeURIComponent(this.elf)}?view=${view}`
                + (theme === 'system' ? '' : `&theme=${theme}`) + (this.route ? `&route=${encodeURIComponent(this.route)}` : '')
                + `&key=${this._key}`;
            this._sentRoute = this.route ?? '';
        } else if (changed.has('route') && (this.route ?? '') !== this._sentRoute) {
            // A new place in the same tab (a link, Back): the elf goes there without reloading.
            this._sentRoute = this.route ?? '';
            this._send({ event: 'route', route: this._sentRoute });
        }
    }

    render() {
        return html`
            <iframe src=${this._src} title=${installedElf(this.elf)?.title ?? this.elf} style="height:${this._height || { card: 60, settings: 24 }[this.view] || 400}px"
                    sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox" allow="clipboard-write"
                    referrerpolicy="no-referrer"></iframe>
            ${this._askBar()}
            ${this._settingsDialog()}
        `;
    }
}

/** Its tab draws its own settings too (tab.settings): they go at the top of its Settings pop-up. */
export function ownSettings(elf) {
    const e = installedElf(elf);
    return !!(e && tabOf(e)?.settings);
}

/** "Send the status to {target}?" with the values filled in. */
function fill(text, params) {
    return text.replace(/\{([A-Za-z0-9]+)\}/g, (m, k) => (params[k] == null ? m : String(params[k]).slice(0, 120)));
}

function strings(v) {
    return Array.isArray(v) ? v.slice(0, 100).map(String) : [];
}

function object(v) {
    return v && typeof v === 'object' && !Array.isArray(v) ? v : null;
}

/** elf.store: kept per elf in this browser's storage. */
function storage(elf, key, value, write = false) {
    if (!STORE_KEY.test(String(key ?? ''))) throw new Error('Not a key the desk keeps');
    const k = `elfdesk.elf.${elf}.${key}`;
    try {
        if (!write) return localStorage.getItem(k);
        if (value == null) localStorage.removeItem(k);
        else localStorage.setItem(k, value);
    } catch {
        if (!write) return null;
    }
    return null;
}

customElements.define('elf-frame', ElfFrame);
