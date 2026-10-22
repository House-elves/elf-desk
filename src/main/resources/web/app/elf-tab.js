import { LitElement, html, css } from 'lit';
import { shared } from './shared.js';
import { api } from './rpc.js';
import { installedElf, setInstalledElves } from './tabs.js';
import './elf-frame.js';
import './elf-settings.js';
import './elf-schedule.js';
import './elf-settings-dialog.js';

/**
 * An elf's tab on the desk. Until the settings it can't run without are filled in, the desk draws its setup here
 * (its own settings form, so secrets never reach the elf's code); once they're saved the runner starts its first
 * refresh, and the elf draws the tab itself, in its sandboxed frame.
 */
class ElfTab extends LitElement {

    static properties = {
        tab: { attribute: false },   // from tabs.js: {id, label, elf, missing, …}
        route: {},                   // where in the tab the desk's link points (#<elf>/<route>)
    };

    static styles = [shared, css`
        :host { display: block; }
        .setup { display: grid; gap: 22px; max-width: 52rem; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 15px; max-width: 44rem; }
        .when { padding: 16px 18px; }
        ol.steps { margin: 12px 0 0; padding-left: 20px; display: grid; gap: 4px; color: var(--muted); font-size: 14px; max-width: 44rem; }
        ol.steps strong { color: var(--ink); font-weight: 600; }
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
        if (!t.missing?.length) return html`<elf-frame elf=${t.elf} .route=${this.route ?? ''}></elf-frame>`;
        const e = installedElf(t.elf);
        return html`<div class="setup">
            <div>
                <h1>Set up ${t.label}</h1>
                <p class="sub">${e?.description ?? ''}</p>
                <ol class="steps">
                    <li><strong>Fill in what it needs</strong>, marked Needed. The rest have a sensible default.
                        Secrets are only ever written, never shown again.</li>
                    ${e?.refresh ? html`<li><strong>Pick when it refreshes</strong> on its own, or leave it as it comes.</li>` : ''}
                    <li><strong>Save.</strong> ${t.label} then runs for the first time${t.refresh ? ' and fills this tab' : ''}.</li>
                </ol>
            </div>
            <elf-settings elf=${t.elf} heading="Its settings" @elf-settings-saved=${this._saved}
                note="Only readable by you. Secrets are never shown again."></elf-settings>
            ${e?.refresh ? html`<div class="panel when"><elf-schedule .elf=${e}></elf-schedule></div>` : ''}
            <div><button class="sm quiet" @click=${() => this.renderRoot.querySelector('elf-settings-dialog').show()}>Its version, or remove it</button></div>
            <elf-settings-dialog elf=${t.elf} manageOnly heading=${t.label}></elf-settings-dialog>
        </div>`;
    }
}

customElements.define('elf-tab', ElfTab);
