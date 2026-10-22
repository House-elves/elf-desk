import { LitElement, html, css } from 'lit';
import { shared } from './shared.js';
import { installedElf } from './tabs.js';
import './elf-settings.js';
import './elf-schedule.js';
import './elf-manage.js';

/**
 * An elf's settings as a pop-up, the same as its tab's Settings: its settings, when it refreshes on its own, and a
 * dry run, its version and removing it. For the elves without a tab, the runner, and an elf's setup.
 *
 *   <elf-settings-dialog elf="mvnpm-dependabot"></elf-settings-dialog>, then .show()
 *
 * {@code manageOnly}: just the dry run, version and removing (the setup page shows the settings itself).
 */
class ElfSettingsDialog extends LitElement {

    static properties = {
        elf: {},
        heading: {},
        note: {},
        manageOnly: { type: Boolean },
        _open: { state: true },
    };

    static styles = [shared, css`
        dialog {
            width: min(46rem, calc(100vw - 32px)); max-height: calc(100vh - 48px); padding: 22px 24px 20px;
            border: 1px solid var(--line); border-radius: var(--r-panel); background: var(--bg); color: var(--ink);
            box-shadow: var(--shadow);
        }
        dialog::backdrop { background: color-mix(in srgb, var(--ink) 35%, transparent); }
        dialog[open] { display: grid; gap: 20px; }
        .when { padding: 16px 18px; border: 1px solid var(--line); border-radius: var(--r-panel); background: var(--surface); }
        .close { display: flex; justify-content: flex-end; }
    `];

    show() {
        this._open = true;
        this.updateComplete.then(() => this.renderRoot.querySelector('dialog')?.showModal());
    }

    render() {
        const e = this.elf === 'elf-runner' ? null : installedElf(this.elf);
        return html`<dialog aria-label=${this.heading ?? `${e?.title ?? this.elf} settings`} @close=${() => { this._open = false; }}>
            ${this._open ? html`
                ${this.manageOnly ? html`<h2>${this.heading ?? `${e?.title ?? this.elf}`}</h2>` : html`
                    <elf-settings elf=${this.elf} heading=${this.heading ?? `${e?.title ?? this.elf} settings`}
                        note=${this.note ?? 'Only readable by you. Secrets are never shown.'}></elf-settings>
                    ${e?.refresh ? html`<div class="when"><elf-schedule .elf=${e}></elf-schedule></div>` : ''}`}
                ${e ? html`<elf-manage class="when" elf=${this.elf}
                    @elf-removed=${() => this.renderRoot.querySelector('dialog').close()}></elf-manage>` : ''}
                <div class="close"><button @click=${() => this.renderRoot.querySelector('dialog').close()}>Close</button></div>` : ''}
        </dialog>`;
    }
}

customElements.define('elf-settings-dialog', ElfSettingsDialog);
