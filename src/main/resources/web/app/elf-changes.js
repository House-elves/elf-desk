import { LitElement, html, css } from 'lit';
import { shared } from './shared.js';
import './diff-view.js';

/**
 * A CHANGES.md section's text as blocks: paragraphs, and lists of "- " or "* " items (an indented line goes on with
 * the item above). Plain text only: what an elf's author wrote is never read as HTML.
 */
export function noteBlocks(text) {
    const blocks = [];
    let para = null, list = null;
    for (const raw of (text ?? '').split('\n')) {
        const line = raw.replace(/\*\*|__|`/g, '');
        const item = /^\s{0,3}[-*]\s+(.*)$/.exec(line);
        if (!line.trim()) {
            para = null;
            if (list) list.done = true;
        } else if (item) {
            if (!list || list.done) blocks.push(list = { items: [] });
            list.items.push(item[1].trim());
            para = null;
        } else if (list && !list.done && /^\s/.test(line)) {
            list.items[list.items.length - 1] += ' ' + line.trim();
        } else if (para) {
            para.text += ' ' + line.trim();
        } else {
            blocks.push(para = { text: line.trim() });
            list = null;
        }
    }
    return blocks;
}

/**
 * What an update brings: what's new, in the words of the elf's CHANGES.md, and below it the code that will run on
 * your machine. An elf without a CHANGES.md shows its commit messages instead, with the code open.
 */
class ElfChanges extends LitElement {

    static properties = {
        changes: { attribute: false },  // {from, to, commits, diff, truncated, hasNotes, notes: [{title, text}]}
        repo: {},
    };

    static styles = [shared, css`
        :host { display: grid; gap: 10px; }
        h3 { font-size: 15px; font-weight: 600; margin: 0; }
        h4 { font-size: 14px; font-weight: 600; margin: 0; }
        .new { display: grid; gap: 12px; padding: 12px 14px; border-radius: var(--r-control); background: var(--surface); border: 1px solid var(--line); }
        .note { display: grid; gap: 4px; font-size: 14px; line-height: 1.5; }
        .note p, .note ul { margin: 0; }
        .note ul { padding-left: 20px; }
        .hint { font-size: 13.5px; color: var(--muted); margin: 0; }
        .commits { margin: 0; padding-left: 18px; font: 13px var(--font-code); }
        details { display: grid; gap: 8px; }
        details[open] > summary { margin-bottom: 8px; }
        summary { cursor: pointer; font-size: 14px; }
    `];

    render() {
        const c = this.changes;
        if (!c) return '';
        const notes = c.notes ?? [];
        const commits = c.commits ?? [];
        const count = `${commits.length} commit${commits.length === 1 ? '' : 's'}`;
        const code = html`
            <ul class="commits">${commits.map(x => html`<li>${x}</li>`)}</ul>
            <diff-view .diff=${c.diff} ?cut=${c.truncated} url="https://github.com/${this.repo}/compare/${c.from}...${c.to}"></diff-view>`;
        if (!notes.length) {
            return html`<p class="hint">${c.hasNotes === undefined ? '' // a runner from before CHANGES.md
                    : c.hasNotes ? html`Its CHANGES.md has nothing new since your version: these changes are behind the scenes.`
                    : html`This elf doesn't say what's new (it has no CHANGES.md).`} Here are its ${count}
                    since your version, and the code that will run on your machine.</p>${code}`;
        }
        return html`
            <section class="new" aria-labelledby="new-h">
                <h3 id="new-h">What's new</h3>
                ${notes.map(n => html`<div class="note">
                    <h4>${/^v?\d/.test(n.title) ? `Version ${n.title.replace(/^v/, '')}` : n.title}</h4>
                    ${noteBlocks(n.text).map(b => b.items ? html`<ul>${b.items.map(i => html`<li>${i}</li>`)}</ul>` : html`<p>${b.text}</p>`)}
                </div>`)}
            </section>
            <details>
                <summary>The code that will run on your machine (${count})</summary>
                ${code}
            </details>`;
    }
}

customElements.define('elf-changes', ElfChanges);
