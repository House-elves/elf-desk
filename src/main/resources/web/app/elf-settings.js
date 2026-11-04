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
        elf: {},                        // an installed elf's name, or elf-runner
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
        _install: { state: true },      // {agent, label, command, phase: confirm | busy | done | failed, text}
    };

    static styles = [shared, css`
        :host { display: block; }
        section { display: grid; gap: 12px; }
        .head { display: grid; gap: 2px; padding-right: var(--dialog-x-room, 0px); }
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
        .field select { max-width: 100%; }
        .help { font-size: 13px; color: var(--muted); margin: 0; }
        .wrong { font-size: 13px; color: var(--bad); margin: 0; }
        .secret { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; padding-top: 4px; }
        .install { display: grid; gap: 8px; padding-top: 6px; font-size: 13px; }
        .install code { font-family: var(--font-code); font-size: 12px; overflow-wrap: anywhere; }
        .install .acts { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
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
                this.dispatchEvent(new CustomEvent('elf-settings-saved', { detail: r, bubbles: true, composed: true }));
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
        // Needed: it can't run without it, and there's nothing in it yet.
        const needed = s.needed && (s.kind === 'secret' ? !s.set : !(s.value ?? '').trim())
            ? html` <span class="chip warn">Needed</span>` : '';
        const help = html`${s.help ? html`<p class="help" id="${id}-help">${s.help}</p>` : ''}
            ${wrong ? html`<p class="wrong" role="alert">${wrong}</p>` : ''}`;
        if (s.kind === 'bool') {
            return html`<div class="row"><span class="name">${s.label}${needed}</span><div class="field">
                <label class="check"><input type="checkbox" id=${id} aria-describedby="${id}-help" .checked=${value === 'true'}
                    @change=${e => this._set(s.key, e.target.checked ? 'true' : 'false')}>${value === 'true' ? 'On' : 'Off'}</label>
                ${help}</div></div>`;
        }
        if (s.kind === 'agent') {
            // A coding agent: the ones not installed here are listed, but say so.
            const options = s.options ?? [];
            const current = options.some(o => o.value === value) ? value : (options[0]?.value ?? '');
            return html`<div class="row"><label for=${id}>${s.label}${needed}</label><div class="field">
                <select id=${id} aria-describedby="${id}-help" @change=${e => { this._install = null; this._set(s.key, e.target.value); }}>
                    ${options.map(o => html`<option value=${o.value} ?selected=${o.value === current}>
                        ${o.label}${o.installed ? '' : ' (not installed)'}</option>`)}
                </select>
                ${this._installer(options.find(o => o.value === current))}
                ${help}</div></div>`;
        }
        if (s.kind === 'secret') {
            const replacing = this._replacing.has(s.key);
            return html`<div class="row"><label for=${id}>${s.label}${needed}</label><div class="field">
                ${replacing ? html`<input type="password" id=${id} autocomplete="new-password" aria-describedby="${id}-help"
                        .value=${this._values[s.key] ?? ''} @input=${e => this._set(s.key, e.target.value)}>`
                    : html`<div class="secret"><span class="chip ${s.set ? 'ok' : 'warn'}">${s.set ? 'Set' : 'Not set'}</span>
                        <button type="button" class="sm" id=${id} @click=${() => { this._replacing = new Set([...this._replacing, s.key]); }}>
                            ${s.set ? 'Replace' : 'Set it'}</button></div>`}
                ${help}</div></div>`;
        }
        return html`<div class="row"><label for=${id}>${s.label}${needed}</label><div class="field">
            <input type=${s.kind === 'int' ? 'number' : 'text'} id=${id} aria-describedby="${id}-help" .value=${value}
                min=${s.min ?? ''} max=${s.max ?? ''} spellcheck="false" aria-invalid=${wrong ? 'true' : 'false'}
                @input=${e => this._set(s.key, e.target.value)}>
            ${help}</div></div>`;
    }

    /**
     * The chosen agent isn't installed here: Install, which says exactly what it runs and asks once more, then what's
     * left for you (its login, its licence). The runner installs only that agent's own npm package.
     */
    _installer(o) {
        const i = this._install;
        if (i && i.phase === 'done' && (!o || o.value === i.agent || o.installed)) {
            return html`<div class="install" role="status"><span class="chip ok">${i.label} installed</span>${i.text ? html`<span>${i.text}</span>` : ''}</div>`;
        }
        if (!o || o.installed || !o.value) return '';
        if (!o.install) return o.installNeeds ? html`<div class="install"><span>${o.installNeeds}.</span></div>` : '';
        if (i?.agent === o.value && i.phase === 'confirm') {
            return html`<div class="install" role="group" aria-label="Install ${o.label}">
                <span>Install ${o.label} on this machine? It runs <code>${o.install}</code> as you, outside the sandbox: ${o.label}'s own package, from npm.</span>
                <div class="acts"><button type="button" class="sm primary" @click=${() => this._installAgent(o)}>Install ${o.label}</button>
                    <button type="button" class="sm quiet" @click=${() => { this._install = null; }}>Cancel</button></div></div>`;
        }
        if (i?.agent === o.value && i.phase === 'busy') {
            return html`<div class="install" role="status"><span>${spinner}Installing ${o.label}… npm is downloading it, which can take a few minutes.</span></div>`;
        }
        return html`<div class="install">
            ${i?.agent === o.value && i.phase === 'failed' ? html`<span class="wrong" role="alert">${i.text}</span>` : ''}
            <div class="acts"><button type="button" class="sm" @click=${() => { this._install = { agent: o.value, label: o.label, phase: 'confirm' }; }}>
                Install ${o.label}</button></div></div>`;
    }

    async _installAgent(o) {
        this._install = { agent: o.value, label: o.label, phase: 'busy' };
        try {
            const desk = await api();
            const r = await desk.installAgent({ agent: o.value });
            this._install = { agent: o.value, label: o.label, phase: 'done', text: r?.next ?? '' };
            this.dispatchEvent(new CustomEvent('agent-installed', { detail: { agent: o.value }, bubbles: true, composed: true }));
            // Read again (it's installed now), keeping what you changed and haven't saved, such as picking this agent.
            const unsaved = this._values;
            await this._load();
            this._values = unsaved;
        } catch (err) {
            this._install = { agent: o.value, label: o.label, phase: 'failed', text: errorMessage(err) };
        }
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
