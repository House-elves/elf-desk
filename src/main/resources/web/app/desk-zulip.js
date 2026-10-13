import { LitElement, html, css } from 'lit';
import { shared, markdown, markdownStyles } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { runTime, took } from './triage-run.js';
import './triage-run.js';

/**
 * The last day of Zulip, by topic, with a digest of each: daily-triage's Zulip run. Topics that involve you come
 * first (mentions, DMs, followed topics), then those with unread messages, then the most recently active.
 */
class DeskZulip extends LitElement {

    static properties = {
        _run: { state: true },
        _picked: { state: true },
        _loading: { state: true },
        _error: { state: true },
    };

    static styles = [shared, markdownStyles, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .topics { display: grid; gap: 12px; }
        article { padding: 16px 18px; display: grid; gap: 8px; min-width: 0; }
        article.you { box-shadow: inset 3px 0 0 var(--accent); }
        .where { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: baseline; }
        .stream { font-size: 13px; color: var(--muted); }
        .topic { font-weight: 600; font-size: 15.5px; overflow-wrap: anywhere; color: var(--ink); text-decoration: none; }
        a.topic:hover { text-decoration: underline; }
        .digest { margin: 0; max-width: 46rem; }
        details > summary { cursor: pointer; font-size: 13.5px; color: var(--muted); width: fit-content; }
        ol.msgs { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 12px; }
        .msg { display: grid; gap: 2px; min-width: 0; padding-left: 12px; border-left: 2px solid var(--line); }
        .msg.unread { border-left-color: var(--accent); }
        .who { font-size: 13.5px; font-weight: 600; }
        .who .muted { font-weight: 400; }
        .md { font-size: 14px; overflow-wrap: anywhere; }
        .md :is(p, ul, ol, pre, blockquote) { margin: 4px 0; }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton { height: 92px; }
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
            this._run = await desk.zulip({ runId: this._picked });
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._loading = false;
        }
    }

    /** Messages grouped by "stream > topic", in the order described above. */
    _topics(run) {
        const byKey = new Map();
        for (const m of run.messages ?? []) {
            const key = `${m.stream} > ${m.topic}`;
            if (!byKey.has(key)) byKey.set(key, { key, stream: m.stream, topic: m.topic, messages: [] });
            byKey.get(key).messages.push(m);
        }
        const topics = [...byKey.values()].map(t => {
            t.messages.sort((a, b) => a.time - b.time);
            t.last = t.messages[t.messages.length - 1];
            t.mention = t.messages.some(m => m.mention);
            t.you = t.mention || t.messages.some(m => m.involvesMe);
            t.unread = t.messages.filter(m => m.unread).length;
            t.digest = run.digests?.[t.key];
            return t;
        });
        const rank = t => (t.you ? 2 : 0) + (t.unread ? 1 : 0);
        return topics.sort((a, b) => rank(b) - rank(a) || b.last.time - a.last.time);
    }

    _time(seconds) {
        return new Date(seconds * 1000).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
    }

    _topic(t) {
        return html`
            <article class="panel ${t.you ? 'you' : ''}">
                <div class="where">
                    <span class="stream">${t.stream === 'DM' ? 'Direct message' : `#${t.stream}`}</span>
                    <a class="topic" href=${t.last.url} target="_blank" rel="noopener">${t.topic || '(no topic)'}</a>
                </div>
                <div class="chips">
                    ${t.mention ? html`<span class="chip run">Mentions you</span>`
                        : t.you ? html`<span class="chip run">Involves you</span>` : ''}
                    ${t.unread ? html`<span class="chip warn">${t.unread} unread</span>` : ''}
                    <span class="chip">${t.messages.length} ${t.messages.length === 1 ? 'message' : 'messages'}</span>
                    <span class="chip">Last at ${this._time(t.last.time)} by ${t.last.sender}</span>
                </div>
                ${t.digest ? html`<p class="digest">${t.digest}</p>` : ''}
                <details>
                    <summary>${t.digest ? 'Read the messages' : 'Messages'}</summary>
                    <ol class="msgs">
                        ${t.messages.map(m => html`
                            <li class="msg ${m.unread ? 'unread' : ''}">
                                <span class="who">${m.sender} <span class="muted">${this._time(m.time)}</span></span>
                                <div class="md">${markdown(m.content)}</div>
                            </li>`)}
                    </ol>
                </details>
            </article>`;
    }

    render() {
        const run = this._run;
        const topics = run ? this._topics(run) : [];
        return html`
            <div class="page">
                <div>
                    <h1>Zulip</h1>
                    <p class="sub">
                        ${run ? html`The ${run.hours} hours up to ${runTime(run.run.at)}: ${topics.length}
                                ${topics.length === 1 ? 'topic' : 'topics'}, ${run.messages?.length ?? 0} messages${run.durationMs ? `, took ${took(run.durationMs)}` : ''}`
                            : this._loading ? '' : 'Not checked yet.'}
                    </p>
                </div>
                <triage-run mode="zulip" label="Check Zulip now" .picked=${this._picked}
                            note="Reads the last 24 hours of your streams and DMs, plus any older unread mentions, and digests each topic. Marks nothing read."
                            @run-picked=${e => { this._picked = e.detail; this._load(); }}
                            @run-finished=${() => { this._picked = null; this._load(); }}></triage-run>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !run ? html`<div class="topics">${[1, 2, 3].map(() => html`<div class="skeleton"></div>`)}</div>` : ''}
                ${run ? html`
                    <div class="topics">
                        ${topics.length ? topics.map(t => this._topic(t)) : html`<p class="panel empty">Nothing happened on Zulip in that time.</p>`}
                    </div>` : ''}
            </div>
        `;
    }
}

customElements.define('desk-zulip', DeskZulip);
