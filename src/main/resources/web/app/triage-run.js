import { LitElement, html, css } from 'lit';
import { shared, spinner, ago } from './shared.js';
import { api, errorMessage, onPush, onConnectionChange } from './rpc.js';

/** "Today 08:03", "Yesterday 08:00", "Mon 12 Oct 08:00": when a saved run happened (the elf's local time). */
export function runTime(at) {
    if (!at) return '';
    const d = new Date(at);
    const time = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
    const day = new Date(d); day.setHours(0, 0, 0, 0);
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const days = Math.round((today - day) / 86400000);
    if (days === 0) return `Today ${time}`;
    if (days === 1) return `Yesterday ${time}`;
    return `${d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })} ${time}`;
}

/** "3 min", "45 s": how long a run took. */
export function took(ms) {
    if (!ms) return '';
    return ms < 60000 ? `${Math.round(ms / 1000)} s` : `${Math.round(ms / 60000)} min`;
}

/**
 * The bar over the Email and Zulip pages: the run button, progress while daily-triage runs, the log of a failed
 * run, and a picker for earlier runs. Fires "run-picked" (detail: run id, or null for the newest) and
 * "run-finished" (a run of this mode just ended: show the newest).
 */
class TriageRun extends LitElement {

    static properties = {
        mode: {},          // "email" or "zulip"
        label: {},         // the run button's text
        note: {},          // what a run does, under the button
        picked: {},        // the run on show (id), null for the newest
        _state: { state: true },
        _runs: { state: true },
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
        this.picked = null;
        this._runs = [];
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._offPush = [
            onPush('desk.triage', e => { if (e.mode === this.mode) this._load(true); }),
            onConnectionChange(s => { if (s === 'open') this._load(); }),
        ];
    }

    disconnectedCallback() {
        this._offPush?.forEach(off => off());
        super.disconnectedCallback();
    }

    get running() {
        return this._state?.running?.[this.mode];
    }

    async _load(pushed = false) {
        try {
            const desk = await api();
            const wasRunning = !!this.running;
            const [state, runs] = await Promise.all([desk.triageState(), desk.triageRuns({ kind: this.mode })]);
            this._state = state;
            this._runs = runs ?? [];
            if (pushed && wasRunning && !this.running) {
                this.dispatchEvent(new CustomEvent('run-finished', { bubbles: true, composed: true }));
            }
        } catch (e) {
            this._error = errorMessage(e);
        }
    }

    async _run() {
        this._starting = true;
        this._error = null;
        try {
            const desk = await api();
            this._state = await desk.triageRun({ mode: this.mode });
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._starting = false;
        }
    }

    _pick(e) {
        const id = e.target.value || null;
        this.dispatchEvent(new CustomEvent('run-picked', { detail: id, bubbles: true, composed: true }));
    }

    render() {
        const running = this.running;
        const failed = this._state?.failed?.[this.mode];
        return html`
            <div class="bar">
                <button class="primary" ?disabled=${!!running || this._starting} @click=${this._run}>
                    ${running || this._starting ? spinner : ''}${this.label}
                </button>
                ${running ? html`<span class="status" role="status">Running, started ${ago(running)}</span>` : ''}
                ${this._runs.length > 1 ? html`
                    <label class="small muted">
                        Show
                        <select @change=${this._pick} aria-label="Run to show">
                            ${this._runs.map((r, i) => html`
                                <option value=${i === 0 ? '' : r.id} ?selected=${(this.picked ?? '') === (i === 0 ? '' : r.id)}>
                                    ${runTime(r.at)}${i === 0 ? ' (latest)' : ''}${r.status !== 'success' ? ` (${r.status})` : ''}
                                </option>`)}
                        </select>
                    </label>` : ''}
            </div>
            ${this.note ? html`<p class="note">${this.note}</p>` : ''}
            ${this._error ? html`<p class="error" role="alert" style="margin-top:12px">${this._error}</p>` : ''}
            ${failed && !running ? html`
                <div class="failed" role="alert">
                    <p class="error">The last run failed. The end of its log:</p>
                    <pre>${failed}</pre>
                </div>` : ''}
        `;
    }
}

customElements.define('triage-run', TriageRun);
