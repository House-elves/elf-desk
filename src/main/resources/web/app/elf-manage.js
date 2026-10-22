import { LitElement, html, css } from 'lit';
import { shared, spinner, ago } from './shared.js';
import { api, errorMessage } from './rpc.js';
import { installedElf, setInstalledElves } from './tabs.js';
import './diff-view.js';

const short = sha => (sha ?? '').slice(0, 7);

/**
 * The rest of an elf's settings: a dry run, its version (with the changes to read before you update it) and removing
 * it. In each elf's settings, its setup, and on the add page for an elf without a tab.
 */
class ElfManage extends LitElement {

    static properties = {
        elf: {},                        // the installed elf's name
        _latest: { state: true },       // its catalog entry: the newest commit, or null while looking
        _changes: { state: true },      // {from, to, commits, diff, truncated} being looked at
        _removing: { state: true },
        _busy: { state: true },         // dry, changes, update or remove
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: grid; gap: 14px; }
        h3 { font-size: 15px; font-weight: 600; margin: 0; }
        section { display: grid; gap: 8px; }
        .row { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; font-size: 14px; }
        .hint { font-size: 13.5px; color: var(--muted); margin: 0; }
        .confirm { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; font-size: 14px;
            padding: 10px 12px; border-radius: var(--r-control); background: var(--surface-2); }
        pre.out { margin: 0; max-height: 16rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere;
            font: 12.5px/1.5 var(--font-code); background: var(--surface-2); border: 1px solid var(--line);
            border-radius: var(--r-control); padding: 10px 12px; }
        .commits { margin: 0; padding-left: 18px; font: 13px var(--font-code); }
        code { font: 13px var(--font-code); }
    `];

    connectedCallback() {
        super.connectedCallback();
        this._lookForUpdate();
    }

    disconnectedCallback() {
        clearTimeout(this._poll);
        super.disconnectedCallback();
    }

    async _lookForUpdate() {
        try {
            const e = installedElf(this.elf);
            this._latest = ((await (await api()).elfCatalog()).elves ?? []).find(c => c.repo === e?.repo) ?? false;
        } catch {
            this._latest = false; // GitHub didn't answer: no update to offer now
        }
    }

    async _do(busy, call) {
        this._busy = busy;
        this._error = null;
        try {
            const r = await call(await api());
            if (r?.elves) setInstalledElves(r.elves);
            return r;
        } catch (e) {
            this._error = errorMessage(e);
            return null;
        } finally {
            this._busy = null;
        }
    }

    async _dryRun(e) {
        const r = await this._do('dry', desk => desk.runElf({ elf: e.name, command: e.dryRun, params: null }));
        if (r) this._watch();
    }

    /** While the dry run goes, look again every few seconds, so its output shows when it ends. */
    _watch() {
        clearTimeout(this._poll);
        this._poll = setTimeout(async () => {
            try {
                const elves = (await (await api()).elves()).elves ?? [];
                setInstalledElves(elves);
                this.requestUpdate();
                const e = elves.find(x => x.name === this.elf);
                if ((e?.runs ?? []).some(x => x.command === e.dryRun && x.running)) this._watch();
            } catch {
                // the next look says what's wrong
            }
        }, 4000);
    }

    async _showChanges(e) {
        const r = await this._do('changes', desk => desk.elfChanges({ elf: e.name, commit: this._latest.commit }));
        if (r) this._changes = r;
    }

    async _update(e) {
        const r = await this._do('update', desk => desk.updateElf({ elf: e.name, commit: this._changes.to }));
        if (r) this._changes = null;
    }

    async _remove(e) {
        const r = await this._do('remove', desk => desk.removeElf({ elf: e.name }));
        if (r) {
            this.dispatchEvent(new CustomEvent('elf-removed', { detail: e.name, bubbles: true, composed: true }));
            location.hash = '#';
        }
    }

    render() {
        const e = installedElf(this.elf);
        if (!e) return '';
        const dry = (e.runs ?? []).find(r => r.command === e.dryRun);
        const behind = this._latest && this._latest.commit !== e.commit;
        const c = this._changes;
        return html`
            ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            ${e.dryRun ? html`<section aria-label="Dry run">
                <h3>Try it</h3>
                <div class="row">
                    <button class="sm" ?disabled=${!!this._busy || dry?.running} @click=${() => this._dryRun(e)}>
                        ${dry?.running || this._busy === 'dry' ? html`${spinner} Dry run going` : 'Dry run'}</button>
                    <span class="hint">Runs it on your own data, and sends nothing.</span>
                </div>
                ${dry?.output && !dry.running ? html`<p class="hint">${dry.failed ? 'The dry run failed' : 'Dry run done'}
                    ${dry.at ? ago(dry.at) : ''}</p><pre class="out">${dry.output}</pre>` : ''}
            </section>` : ''}
            <section aria-label="Version">
                <h3>Version</h3>
                <div class="row">
                    <span>${e.version ? html`Version ${e.version}, at` : 'At'} <code title=${e.commit}>${short(e.commit)}</code> of
                        <a href="https://github.com/${e.repo}" target="_blank" rel="noopener">${e.repo}</a>${e.author ? html`,
                        by <a href="https://github.com/${e.author}" target="_blank" rel="noopener">@${e.author}</a>` : ''}.</span>
                    ${this._latest === undefined ? html`<span class="hint">Looking for updates…</span>`
                        : behind ? html`<button class="sm" ?disabled=${!!this._busy} aria-expanded=${c ? 'true' : 'false'}
                                @click=${() => c ? (this._changes = null) : this._showChanges(e)}>
                            ${this._busy === 'changes' ? spinner : ''}${c ? 'Hide the changes' : `See the changes to ${short(this._latest.commit)}`}</button>`
                        : this._latest ? html`<span class="hint">Up to date.</span>` : ''}
                </div>
                ${c ? html`
                    <p class="hint">${c.commits.length} new commit${c.commits.length === 1 ? '' : 's'} since ${short(c.from)}.
                        This is the code that will run on your machine.</p>
                    <ul class="commits">${c.commits.map(x => html`<li>${x}</li>`)}</ul>
                    <diff-view .diff=${c.diff} ?cut=${c.truncated} url="https://github.com/${e.repo}/compare/${c.from}...${c.to}"></diff-view>
                    <div class="row">
                        <button class="primary sm" ?disabled=${!!this._busy} @click=${() => this._update(e)}>
                            ${this._busy === 'update' ? spinner : ''}Update to ${short(c.to)}</button>
                        <button class="sm quiet" @click=${() => { this._changes = null; }}>Not now</button>
                    </div>` : ''}
            </section>
            <section aria-label="Remove">
                <h3>Remove</h3>
                ${this._removing ? html`<div class="confirm" role="group" aria-label="Remove ${e.title || e.name}">
                    Remove ${e.title || e.name}${e.tab ? ' and its tab' : ''}? Its settings stay, for if you add it again.
                    <button class="sm danger" ?disabled=${!!this._busy} @click=${() => this._remove(e)}>
                        ${this._busy === 'remove' ? spinner : ''}Remove</button>
                    <button class="sm quiet" @click=${() => { this._removing = false; }}>Keep it</button>
                </div>` : html`<div class="row">
                    <button class="sm quiet danger" ?disabled=${!!this._busy} @click=${() => { this._removing = true; }}>Remove ${e.title || e.name}</button>
                </div>`}
            </section>`;
    }
}

customElements.define('elf-manage', ElfManage);
