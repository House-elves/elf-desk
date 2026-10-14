import { LitElement, html, css } from 'lit';
import { shared, spinner, ago } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { runTime } from './triage-run.js';
import './triage-run.js';

/** Monday 00:00 of this week, local time: a status is "this week's" from then on. */
export function weekStart(now = new Date()) {
    const d = new Date(now);
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
    return d;
}

/** Where this week's status is: "sent", "draft" (ready to send), or "missing" (nothing generated this week). */
export function statusState(s, now = new Date()) {
    const monday = weekStart(now);
    if (s?.sentAt && new Date(s.sentAt) >= monday) return 'sent';
    if (s && !s.sentAt && new Date(s.at) >= monday) return 'draft';
    return 'missing';
}

/** Unsent edits, per draft, kept in this browser until you send or discard them. */
const EDITS = 'elf-desk.status-edit.';

function loadEdit(id) {
    try { return localStorage.getItem(EDITS + id); } catch { return null; }
}

function saveEdit(id, body) {
    try {
        if (body == null) localStorage.removeItem(EDITS + id);
        else localStorage.setItem(EDITS + id, body);
    } catch { /* storage unavailable: edits just don't survive a reload */ }
}

/**
 * The weekly status: the weekly-status elf drafts it every Monday morning (and on Generate); you read it, edit it,
 * and send it from here. Nothing is sent until you click Send and confirm.
 */
class DeskStatus extends LitElement {

    static properties = {
        _status: { state: true },
        _picked: { state: true },
        _body: { state: true },
        _loading: { state: true },
        _confirm: { state: true },
        _sending: { state: true },
        _copied: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .draft { padding: 18px; display: grid; gap: 14px; min-width: 0; }
        .head { display: grid; gap: 4px; }
        .subject { font-weight: 600; font-size: 16px; overflow-wrap: anywhere; }
        .to { font-size: 13.5px; color: var(--muted); overflow-wrap: anywhere; }
        textarea, pre.sent {
            width: 100%; box-sizing: border-box; min-height: 26rem; resize: vertical; margin: 0;
            font: 13.5px/1.6 var(--font-code); color: var(--ink); background: var(--surface-2);
            border: 1px solid var(--line); border-radius: var(--r-control); padding: 14px 16px;
        }
        pre.sent { white-space: pre-wrap; overflow-wrap: anywhere; min-height: 0; }
        .acts { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
        .confirm { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 14px; }
        .note { margin: 0; font-size: 13px; color: var(--muted); }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton.big { height: 50vh; }
    `];

    constructor() {
        super();
        this._picked = null;
        this._loading = true;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        this._loading = true;
        this._error = null;
        try {
            const desk = await api();
            this._status = await desk.weeklyStatus({ runId: this._picked });
            const s = this._status;
            this._body = s && !s.sentAt ? (loadEdit(s.id) ?? s.body) : null;
            this._confirm = false;
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._loading = false;
        }
    }

    _edit(e) {
        this._body = e.target.value;
        saveEdit(this._status.id, this._body === this._status.body ? null : this._body);
    }

    _discard() {
        saveEdit(this._status.id, null);
        this._body = this._status.body;
    }

    async _send() {
        this._sending = true;
        this._error = null;
        try {
            const desk = await api();
            this._status = await desk.sendWeeklyStatus({ runId: this._status.id, body: this._body });
            saveEdit(this._status.id, null);
            this._confirm = false;
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._sending = false;
        }
    }

    async _copy() {
        const s = this._status;
        const text = s.sentAt ? s.sentBody ?? s.body : this._body;
        try {
            await navigator.clipboard.writeText(`${s.subject}\n\n${text}`);
            this._copied = true;
            setTimeout(() => { this._copied = false; }, 2000);
        } catch (e) {
            this._error = `Couldn't copy: ${errorMessage(e)}`;
        }
    }

    _draft(s) {
        const edited = !s.sentAt && this._body !== s.body;
        return html`
            <section class="panel draft" aria-label="The status">
                <div class="head">
                    <span class="subject">${s.subject}</span>
                    <span class="to">${s.sentAt ? html`Sent ${ago(s.sentAt)} to ${s.sentTo ?? s.to}` : html`To ${s.to}`}</span>
                </div>
                ${s.sentAt ? html`<pre class="sent">${s.sentBody ?? s.body}</pre>`
                    : html`<textarea aria-label="Status text" .value=${this._body ?? ''} @input=${this._edit}
                        ?disabled=${this._sending}></textarea>`}
                ${this._confirm ? html`
                    <div class="confirm" role="group" aria-label="Confirm send">
                        Send this status to ${s.to} now?
                        <button class="primary" ?disabled=${this._sending} @click=${this._send}>
                            ${this._sending ? spinner : ''}Send it</button>
                        <button class="quiet" ?disabled=${this._sending} @click=${() => { this._confirm = false; }}>Cancel</button>
                    </div>` : html`
                    <div class="acts">
                        ${s.sentAt ? '' : html`<button class="primary" ?disabled=${!this._body?.trim()}
                            @click=${() => { this._confirm = true; }}>Send to ${s.to}</button>`}
                        <button @click=${this._copy}>${this._copied ? 'Copied' : 'Copy'}</button>
                        ${edited ? html`<button class="quiet" @click=${this._discard}>Discard my edits</button>` : ''}
                    </div>`}
                ${!s.sentAt ? html`<p class="note">${edited ? 'Your edits are kept in this browser until you send. ' : ''}Send
                    emails it from your Gmail with this subject. Nothing goes out until you confirm.</p>` : ''}
            </section>`;
    }

    render() {
        const s = this._status;
        const state = statusState(s);
        return html`
            <div class="page">
                <div>
                    <h1>Weekly status</h1>
                    <p class="sub">
                        ${s ? html`${s.weekStart} to ${s.weekEnd}, drafted ${runTime(s.at)}${this._picked ? ''
                            : state === 'sent' ? '. Sent for this week.' : state === 'draft' ? '. Ready to send.'
                            : '. Nothing drafted this week yet.'}`
                            : this._loading ? '' : 'No status drafted yet.'}
                    </p>
                </div>
                <triage-run mode="status" label="Draft it now" .picked=${this._picked}
                            note="Gathers your GitHub work of the last 7 days and drafts the status with Claude. Sends nothing. It also runs every Monday at 08:00 and pops up a desktop reminder."
                            @run-picked=${e => { this._picked = e.detail; this._load(); }}
                            @run-finished=${() => { this._picked = null; this._load(); }}></triage-run>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !s ? html`<div class="skeleton big"></div>` : ''}
                ${s ? this._draft(s) : !this._loading ? html`<p class="panel empty">Draft it now, or wait for Monday morning.</p>` : ''}
            </div>
        `;
    }
}

customElements.define('desk-status', DeskStatus);
