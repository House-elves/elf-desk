import { LitElement, html, css } from 'lit';
import { shared } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { runTime, took } from './triage-run.js';
import './triage-run.js';

/**
 * daily-triage's email triage, run from here instead of its 8 AM email: per Gmail account, what needs you and what it
 * marked read (and labelled).
 */
class DeskEmail extends LitElement {

    static properties = {
        _run: { state: true },
        _picked: { state: true },
        _loading: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        section { display: grid; gap: 14px; }
        .account { font-weight: 600; font-size: 15px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li { padding: 11px 18px; display: grid; gap: 3px; min-width: 0; }
        li + li { border-top: 1px solid var(--line); }
        .subject { font-weight: 500; overflow-wrap: anywhere; color: var(--ink); text-decoration: none; }
        a.subject:hover { text-decoration: underline; }
        .from, .why { font-size: 13.5px; color: var(--muted); overflow-wrap: anywhere; }
        details > summary { cursor: pointer; padding: 11px 18px; font-size: 14px; color: var(--muted); }
        details[open] > summary { border-bottom: 1px solid var(--line); }
        .empty { color: var(--muted); padding: 14px 18px; margin: 0; font-size: 14px; }
        .skeleton.big { height: 40vh; }
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

    _email(account, d) {
        const href = this._gmail(account, d);
        const subject = (d.subject || '(no subject)').replace(/\s+/g, ' ');
        return html`<li>
            ${href ? html`<a class="subject" href=${href} target="_blank" rel="noopener">${subject}</a>`
                : html`<span class="subject">${subject}</span>`}
            <span class="from">${d.from}</span>
            <span class="why">${d.reason}</span>
        </li>`;
    }

    _triage(r) {
        const accounts = r.accounts ?? [];
        if (!accounts.length) return html`<p class="muted">This run had no Gmail accounts to triage.</p>`;
        return html`
            <section aria-label="Email triage">
                ${accounts.map(a => {
                    const kept = (a.decisions ?? []).filter(d => d.decision !== 'MARK_READ');
                    const read = (a.decisions ?? []).filter(d => d.decision === 'MARK_READ');
                    return html`
                        <div class="account">
                            ${a.email}
                            <span class="chip ${kept.length ? 'warn' : 'ok'}">${kept.length} need you</span>
                            <span class="chip">${read.length} marked read</span>
                        </div>
                        <div class="panel">
                            ${kept.length ? html`<ul>${kept.map(d => this._email(a.email, d))}</ul>`
                                : html`<p class="empty">Nothing in this inbox needs you.</p>`}
                        </div>
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
                <div>
                    <h1>Email</h1>
                    <p class="sub">
                        ${r ? html`Triaged ${runTime(r.run.at)}${r.durationMs ? `, took ${took(r.durationMs)}` : ''}${r.emailed ? ', also emailed' : ''}`
                            : this._loading ? '' : 'Not triaged yet.'}
                    </p>
                </div>
                <triage-run mode="email" label="Triage email now" .picked=${this._picked}
                            note="Goes through your unread Gmail: noise is marked read and labelled, the rest stays unread and is listed here with the reason. Nothing is emailed."
                            @run-picked=${e => { this._picked = e.detail; this._load(); }}
                            @run-finished=${() => { this._picked = null; this._load(); }}></triage-run>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !r ? html`<div class="skeleton big"></div>` : ''}
                ${r ? this._triage(r) : ''}
            </div>
        `;
    }
}

customElements.define('desk-email', DeskEmail);
