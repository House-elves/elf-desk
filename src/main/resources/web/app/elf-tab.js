import { LitElement, html, css } from 'lit';
import { shared } from './shared.js';
import { api } from './rpc.js';
import { installedElf, setInstalledElves } from './tabs.js';
import './elf-frame.js';
import './elf-settings.js';
import './elf-schedule.js';

/**
 * An elf's tab on the desk. Until the settings it can't run without are filled in, the desk draws its setup here
 * (its own settings form, so secrets never reach the elf's code); once they're saved the runner starts its first
 * refresh, and the elf draws the tab itself, in its sandboxed frame.
 */
class ElfTab extends LitElement {

    static properties = {
        tab: { attribute: false },   // from tabs.js: {id, label, elf, missing, …}
    };

    static styles = [shared, css`
        :host { display: block; }
        .setup { display: grid; gap: 22px; max-width: 52rem; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 15px; max-width: 44rem; }
        .when { padding: 16px 18px; }
        @media (max-width: 600px) { h1 { font-size: 26px; } }
    `];

    /** After a save: the runner says whether it's set up now (and has started its first refresh). */
    async _saved() {
        try {
            setInstalledElves((await (await api()).elves()).elves ?? []);
        } catch {
            // the shell loads them again when the runner is back
        }
    }

    render() {
        const t = this.tab;
        if (!t) return '';
        if (!t.missing?.length) return html`<elf-frame elf=${t.elf}></elf-frame>`;
        const e = installedElf(t.elf);
        return html`<div class="setup">
            <div>
                <h1>Set up ${t.label}</h1>
                <p class="sub">${e?.description ?? ''} Fill in what it needs (marked Needed). Once that's saved,
                    ${t.label} runs for the first time${t.refresh ? ' and fills this tab' : ''}.</p>
            </div>
            <elf-settings elf=${t.elf} heading="Its settings" @elf-settings-saved=${this._saved}
                note="Only readable by you. Secrets are never shown again."></elf-settings>
            ${e?.refresh ? html`<div class="panel when"><elf-schedule .elf=${e}></elf-schedule></div>` : ''}
        </div>`;
    }
}

customElements.define('elf-tab', ElfTab);
