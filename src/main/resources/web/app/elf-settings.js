import { LitElement, html, css } from 'lit';
import { shared, spinner } from './shared.js';
import { api, errorMessage } from './rpc.js';

/**
 * Settings from an elf's own config file, read and written through the runner (which decides what may change and
 * checks every value). Shows the settings named in `keys`, or all of them. Secrets are never shown: only whether
 * they're set, with Replace to type a new one.
 */
class ElfSettings extends LitElement {

    static properties = {
        elf: {},                        // daily-triage, weekly-status, quarterly-review, mvnpm-dependabot, elf-runner
        heading: {},
        note: {},                       // a line under the heading
        keys: { attribute: false },     // which settings, in this order (default: all)
        _config: { state: true },
        _values: { state: true },
        _replacing: { state: true },
        _wrong: { state: true },
        _busy: { state: true },
        _saved: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: block; }
        section { display: grid; gap: 12px; }
        .head { display: grid; gap: 2px; }
        h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .hint { font-size: 14px; color: var(--muted); margin: 0; }
        code { font: 13px var(--font-code); background: var(--surface-2); padding: 2px 6px; border-radius: 5px; }
        form { display: grid; }
        .row { display: grid; grid-template-columns: minmax(0, 15rem) minmax(0, 1fr); gap: 6px 20px; padding: 14px 18px; align-items: start; }
        .row + .row { border-top: 1px solid var(--line); }
        .row > label, .row > .name { font-weight: 500; padding-top: 7px; }
        .field { display: grid; gap: 5px; min-width: 0; }
        .field input[type="text"], .field input[type="password"], .field input[type="number"] { width: 100%; box-sizing: border-box; }
        .field input[type="number"] { max-width: 8rem; }
        .help { font-size: 13px; color: var(--muted); margin: 0; }
        .wrong { font-size: 13px; color: var(--bad); margin: 0; }
        .secret { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; padding-top: 4px; }
        input[type="checkbox"] { width: 17px; height: 17px; accent-color: var(--accent); margin: 0; }
        .check { display: flex; gap: 10px; align-items: center; padding-top: 7px; }
        .acts { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; padding: 14px 18px; border-top: 1px solid var(--line); }
        .saved { font-size: 14px; color: var(--ok); }
        .sk { height: 52px; }
        @media (max-width: 600px) {
            .row { grid-template-columns: minmax(0, 1fr); padding: 12px 14px; }
            .row > label, .row > .name { padding-top: 0; }
        }
    `];

    constructor() {
        super();
        this._values = {};
        this._replacing = new Set();
        this._wrong = {};
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        this._error = null;
        try {
            const desk = await api();
            this._show(await desk.elfSettings({ elf: this.elf }));
        } catch (e) {
            this._error = errorMessage(e);
        }
    }

    _show(config) {
        this._config = config;
        this._values = {};
        this._replacing = new Set();
        this._wrong = {};
    }

    get _settings() {
        const all = this._config?.settings ?? [];
        return this.keys ? this.keys.map(k => all.find(s => s.key === k)).filter(Boolean) : all;
    }

    _set(key, value) {
        this._values = { ...this._values, [key]: value };
        this._saved = false;
    }

    /** What changed: values that differ from the file, and secrets typed in. */
    _changes() {
        const out = {};
        for (const s of this._settings) {
            const v = this._values[s.key];
            if (v === undefined) continue;
            if (s.kind === 'secret' ? v.trim() !== '' : v !== s.value) out[s.key] = v;
        }
        return out;
    }

    async _save(e) {
        e.preventDefault();
        const values = this._changes();
        if (!Object.keys(values).length) return;
        this._busy = true;
        this._error = null;
        try {
            const desk = await api();
            const r = await desk.saveElfSettings({ elf: this.elf, values });
            if (r.wrong) {
                this._wrong = r.wrong;
            } else {
                this._show(r);
                this._saved = true;
            }
        } catch (err) {
            this._error = errorMessage(err);
        } finally {
            this._busy = false;
        }
    }

    _field(s) {
        const id = `${this.elf}-${s.key}`;
        const value = this._values[s.key] ?? s.value ?? '';
        const wrong = this._wrong[s.key];
        const help = html`${s.help ? html`<p class="help" id="${id}-help">${s.help}</p>` : ''}
            ${wrong ? html`<p class="wrong" role="alert">${wrong}</p>` : ''}`;
        if (s.kind === 'bool') {
            return html`<div class="row"><span class="name">${s.label}</span><div class="field">
                <label class="check"><input type="checkbox" id=${id} aria-describedby="${id}-help" .checked=${value === 'true'}
                    @change=${e => this._set(s.key, e.target.checked ? 'true' : 'false')}>${value === 'true' ? 'On' : 'Off'}</label>
                ${help}</div></div>`;
        }
        if (s.kind === 'secret') {
            const replacing = this._replacing.has(s.key);
            return html`<div class="row"><label for=${id}>${s.label}</label><div class="field">
                ${replacing ? html`<input type="password" id=${id} autocomplete="new-password" aria-describedby="${id}-help"
                        .value=${this._values[s.key] ?? ''} @input=${e => this._set(s.key, e.target.value)}>`
                    : html`<div class="secret"><span class="chip ${s.set ? 'ok' : 'warn'}">${s.set ? 'Set' : 'Not set'}</span>
                        <button type="button" class="sm" id=${id} @click=${() => { this._replacing = new Set([...this._replacing, s.key]); }}>
                            ${s.set ? 'Replace' : 'Set it'}</button></div>`}
                ${help}</div></div>`;
        }
        return html`<div class="row"><label for=${id}>${s.label}</label><div class="field">
            <input type=${s.kind === 'int' ? 'number' : 'text'} id=${id} aria-describedby="${id}-help" .value=${value}
                min=${s.min ?? ''} max=${s.max ?? ''} spellcheck="false" aria-invalid=${wrong ? 'true' : 'false'}
                @input=${e => this._set(s.key, e.target.value)}>
            ${help}</div></div>`;
    }

    render() {
        const c = this._config;
        const changed = Object.keys(this._changes()).length > 0;
        return html`
            <section aria-label=${this.heading}>
                <div class="head"><h2>${this.heading}</h2>
                    <p class="hint">${this.note ? html`${this.note} ` : ''}${c ? html`Saved in <code>${c.file}</code>.` : ''}</p></div>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${!c && !this._error ? html`<div class="skeleton sk"></div>` : ''}
                ${c && !c.installed ? html`<p class="hint">${this.elf} isn't set up on this machine.</p>` : ''}
                ${c?.installed ? html`
                    <form class="panel" @submit=${this._save}>
                        ${this._settings.map(s => this._field(s))}
                        <div class="acts">
                            <button class="primary" type="submit" ?disabled=${!changed || this._busy}>${this._busy ? spinner : ''}Save</button>
                            ${changed ? html`<button type="button" class="quiet" @click=${() => this._show(this._config)}>Undo changes</button>` : ''}
                            ${this._saved && !changed ? html`<span class="saved" role="status">Saved</span>` : ''}
                        </div>
                    </form>` : ''}
            </section>
        `;
    }
}

customElements.define('elf-settings', ElfSettings);
