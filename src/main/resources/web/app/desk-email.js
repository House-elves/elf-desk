import { LitElement, html, css } from 'lit';
import { shared, spinner, settingsToggle } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { runTime, took } from './triage-run.js';
import './triage-run.js';
import './elf-settings.js';

/**
 * daily-triage's email triage, run from here instead of its 8 AM email: per Gmail account, what needs you and what it
 * marked read (and labelled).
 */
class DeskEmail extends LitElement {

    static properties = {
        _run: { state: true },
        _picked: { state: true },
        _doing: { state: true },
        _all: { state: true },
        _loading: { state: true },
        _error: { state: true },
        _settingsOpen: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        section { display: grid; gap: 14px; }
        .account { font-weight: 600; font-size: 15px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li { padding: 11px 18px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 3px 14px; align-items: center; }
        li > .text { display: grid; gap: 3px; min-width: 0; }
        li + li { border-top: 1px solid var(--line); }
        .subject { font-weight: 500; overflow-wrap: anywhere; color: var(--ink); text-decoration: none; }
        a.subject:hover { text-decoration: underline; }
        .from, .why { font-size: 13.5px; color: var(--muted); overflow-wrap: anywhere; }
        details > summary { cursor: pointer; padding: 11px 18px; font-size: 14px; color: var(--muted); }
        details[open] > summary { border-bottom: 1px solid var(--line); }
        .empty { color: var(--muted); padding: 14px 18px; margin: 0; font-size: 14px; }
        .skeleton.big { height: 40vh; }
        .all { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 14px; }
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
            this._run = await desk.email({ runId: this._picked });
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._loading = false;
        }
    }

    _gmail(account, d) {
        if (!d.messageId) return null;
        const id = d.messageId.replace(/^<|>$/g, '');
        return `https://mail.google.com/mail/?authuser=${encodeURIComponent(account)}#search/rfc822msgid:${encodeURIComponent(id)}`;
    }

    /** Done: Gmail marks the thread read, and it moves to the done list. */
    async _done(account, d) {
        this._doing = d.messageId;
        this._error = null;
        try {
            const desk = await api();
            const r = await desk.emailDone({ account, messageId: d.messageId });
            if (!this._picked) this._run = r;
            else await this._load();
        } catch (e) {
            this._error = `${d.subject || 'That email'}: ${errorMessage(e)}`;
        } finally {
            this._doing = null;
        }
    }

    /** What still needs you in the newest run, across accounts: what Done all marks. */
    _open() {
        return (this._run?.accounts ?? []).flatMap(a => (a.decisions ?? [])
            .filter(d => d.decision !== 'MARK_READ' && !d.done && d.messageId).map(d => ({ account: a.email, d })));
    }

    /** Done all: each email in turn, as if you clicked its Done. One that fails is left for you; the rest go on. */
    async _doneAll() {
        const todo = this._open();
        const failed = [];
        this._error = null;
        for (let i = 0; i < todo.length; i++) {
            const { account, d } = todo[i];
            this._all = { at: i + 1, of: todo.length };
            this._doing = d.messageId;
            try {
                const desk = await api();
                this._run = await desk.emailDone({ account, messageId: d.messageId });
            } catch (e) {
                failed.push(`${d.subject || 'An email'}: ${errorMessage(e)}`);
            }
        }
        this._doing = null;
        this._all = null;
        if (failed.length) this._error = 'Not marked read: ' + failed.join('; ');
    }

    _allBar() {
        const n = this._open().length;
        if (this._picked || (!n && !this._all)) return '';
        if (this._all?.of) return html`<div class="all" role="status">${spinner} Marking ${this._all.at} of ${this._all.of} read…</div>`;
        if (this._all === 'confirm') return html`<div class="all" role="group" aria-label="Confirm Done all">
            Mark all ${n} ${n === 1 ? 'email' : 'emails'} read in Gmail?
            <button class="sm primary" @click=${this._doneAll}>Mark all read</button>
            <button class="sm quiet" @click=${() => { this._all = null; }}>Cancel</button></div>`;
        return html`<div class="all"><button class="sm" ?disabled=${this._doing}
            @click=${() => { this._all = 'confirm'; }}>Done all (${n})</button></div>`;
    }

    _email(account, d, done) {
        const href = this._gmail(account, d);
        const subject = (d.subject || '(no subject)').replace(/\s+/g, ' ');
        return html`<li>
            <span class="text">
                ${href ? html`<a class="subject" href=${href} target="_blank" rel="noopener">${subject}</a>`
                    : html`<span class="subject">${subject}</span>`}
                <span class="from">${d.from}</span>
                <span class="why">${d.reason}</span>
            </span>
            ${done && d.messageId ? html`<button class="sm" ?disabled=${this._doing}
                title="Marks the thread read in Gmail" @click=${() => this._done(account, d)}>
                ${this._doing === d.messageId ? spinner : ''}Done</button>` : ''}
        </li>`;
    }

    _triage(r) {
        const accounts = r.accounts ?? [];
        if (!accounts.length) return html`<p class="muted">This run had no Gmail accounts to triage.</p>`;
        return html`
            <section aria-label="Email triage">
                ${accounts.map(a => {
                    const kept = (a.decisions ?? []).filter(d => d.decision !== 'MARK_READ' && !d.done);
                    const done = (a.decisions ?? []).filter(d => d.decision !== 'MARK_READ' && d.done);
                    const read = (a.decisions ?? []).filter(d => d.decision === 'MARK_READ');
                    return html`
                        <div class="account">
                            ${a.email}
                            <span class="chip ${kept.length ? 'warn' : 'ok'}">${kept.length} need you</span>
                            <span class="chip">${read.length} marked read</span>
                        </div>
                        <div class="panel">
                            ${kept.length ? html`<ul>${kept.map(d => this._email(a.email, d, true))}</ul>`
                                : html`<p class="empty">${done.length ? 'All done.' : 'Nothing in this inbox needs you.'}</p>`}
                        </div>
                        ${done.length ? html`
                            <details class="panel">
                                <summary>Done (${done.length})</summary>
                                <ul>${done.map(d => this._email(a.email, d))}</ul>
                            </details>` : ''}
                        ${read.length ? html`
                            <details class="panel">
                                <summary>Marked read and labelled (${read.length})</summary>
                                <ul>${read.map(d => this._email(a.email, d))}</ul>
                            </details>` : ''}
                    `;
                })}
            </section>`;
    }

    render() {
        const r = this._run;
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Email</h1>
                    <p class="sub">
                        ${r ? html`Triaged ${runTime(r.run.at)}${r.durationMs ? `, took ${took(r.durationMs)}` : ''}${r.emailed ? ', also emailed' : ''}`
                            : this._loading ? '' : 'Not triaged yet.'}
                    </p>
                </div>${settingsToggle(this._settingsOpen, () => { this._settingsOpen = !this._settingsOpen; })}</div>
                ${this._settingsOpen ? html`<div class="settings-area">
                    <elf-settings elf="daily-triage" .keys=${['GMAIL_ADDRESS', 'GMAIL_APP_PASSWORD', 'MAX_EMAILS', 'TRIAGE_LABEL']} heading="Email triage settings" note="daily-triage reads this inbox."></elf-settings>
                </div>` : ''}
                <triage-run mode="email" label="Triage email now" .picked=${this._picked}
                            note="Goes through your unread Gmail: noise is marked read and labelled, the rest stays unread and is listed here with the reason. Nothing is emailed."
                            @run-picked=${e => { this._picked = e.detail; this._load(); }}
                            @run-finished=${() => { this._picked = null; this._load(); }}></triage-run>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !r ? html`<div class="skeleton big"></div>` : ''}
                ${r ? html`${this._allBar()}${this._triage(r)}` : ''}
            </div>
        `;
    }
}

customElements.define('desk-email', DeskEmail);
