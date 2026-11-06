// A 🎤 for one of the desk's own text boxes (an item's thread: Ask the agent): the desk's page listens itself, the
// way you chose in this browser (voice.js), and the words go in at the box's cursor. Elves get the same button from
// the kit (elf-mic), through their frame.
import { LitElement, html, css } from 'lit';
import { listen, voiceMode, setVoiceMode, browserSpeech, whisperStatus } from './voice.js';

function insertAtCursor(box, text) {
    const start = box.selectionStart ?? box.value.length, end = box.selectionEnd ?? start;
    const before = box.value.slice(0, start), after = box.value.slice(end);
    const lead = before && !/\s$/.test(before) ? ' ' : '';
    const trail = after && !/^\s/.test(after) ? ' ' : '';
    box.value = before + lead + text + trail + after;
    const at = (before + lead + text).length;
    box.setSelectionRange?.(at, at);
    box.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
}

class DeskMic extends LitElement {
    static properties = { target: { attribute: false }, _on: { state: true }, _mode: { state: true }, _interim: { state: true },
        _state: { state: true }, _error: { state: true }, _whisper: { state: true } };

    static styles = css`
        :host { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--muted); }
        button {
            font: inherit; font-size: 14px; line-height: 1; padding: 6px 9px; cursor: pointer; color: var(--muted);
            background: transparent; border: 1px solid var(--line); border-radius: var(--r-control, 9px);
        }
        button:hover { color: var(--ink); border-color: var(--muted); }
        button.on { color: var(--bad); border-color: var(--bad); animation: blink 1.2s ease-in-out infinite; }
        button.link { border: 0; padding: 0; font-size: 13px; color: var(--accent); text-decoration: underline; animation: none; }
        @keyframes blink { 50% { opacity: 0.45; } }
        @media (prefers-reduced-motion: reduce) { button.on { animation: none; } }
        .interim { font-style: italic; max-width: 28rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .err { color: var(--bad); }
    `;

    disconnectedCallback() {
        this._on?.cancel();
        super.disconnectedCallback();
    }

    get _box() {
        return typeof this.target === 'function' ? this.target() : this.target;
    }

    async _start(mode) {
        const box = this._box;
        if (!box) return;
        this._error = null;
        const status = await whisperStatus();
        this._whisper = !!status.whisper;
        let use = mode ?? voiceMode();
        if (use === 'whisper' && !status.whisper) use = browserSpeech() ? 'browser' : null;
        if (use === 'browser' && !browserSpeech()) use = status.whisper ? 'whisper' : null;
        if (!use) {
            this._error = `This browser has no speech recognition, and Whisper isn't set up here: ${status.how ?? ''}`;
            return;
        }
        this._mode = use;
        this._state = 'starting';
        this._on = listen(use, {
            onText: t => {
                if (t.interim != null) this._interim = t.interim;
                if (t.final) {
                    this._interim = '';
                    if (box.isConnected) insertAtCursor(box, t.final);
                }
            },
            onState: s => {
                this._state = s;
                if (s === 'done') this._end();
            },
            onError: m => {
                this._error = m;
                this._end();
            },
        });
        box.focus();
    }

    _end() {
        this._on = null;
        this._interim = '';
        this._state = null;
    }

    _toggle() {
        if (this._on) this._on.stop();
        else this._start();
    }

    /** The other way of listening, from here on, in this browser. */
    _switch() {
        const other = this._mode === 'browser' ? 'whisper' : 'browser';
        setVoiceMode(other);
        this._on?.cancel();
        this._end();
        this._start(other);
    }

    render() {
        const on = !!this._on;
        const other = this._mode === 'browser' ? 'whisper' : 'browser';
        const canSwitch = on && this._state !== 'writing' && (other === 'whisper' ? this._whisper : !!browserSpeech());
        return html`<button type="button" class=${on ? 'on' : ''} aria-pressed=${on ? 'true' : 'false'}
                aria-label=${on ? 'Stop listening' : 'Speak'} title=${on ? 'Stop listening' : 'Speak: your words go in at the cursor'}
                @click=${this._toggle}>${on ? '■' : '🎤'}</button>
            ${on ? html`<span role="status">${this._state === 'writing' ? 'Writing it down…'
                : this._mode === 'browser' ? 'Listening (your browser\'s speech service)' : 'Listening (Whisper, here)'}</span>` : ''}
            ${canSwitch ? html`<button class="link" type="button" @click=${this._switch}>${other === 'whisper' ? 'use Whisper here' : 'use the browser\'s'}</button>` : ''}
            ${this._interim ? html`<span class="interim">${this._interim}</span>` : ''}
            ${this._error ? html`<span class="err" role="alert">${this._error}</span>` : ''}`;
    }
}

if (!customElements.get('desk-mic')) customElements.define('desk-mic', DeskMic);
