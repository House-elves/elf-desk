import { css, html, svg } from 'lit';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

/**
 * Styles every view shares. Type scale: 13 / 15 / 18 / 24 / 34 px.
 * Radii: panels 14, controls 9, chips round.
 */
export const shared = css`
    :host { display: block; }
    h1 { font-family: var(--font-display); }
    a { color: var(--accent); text-underline-offset: 2px; }
    :is(a, button, input, select, textarea, summary):focus-visible {
        outline: 2px solid var(--accent); outline-offset: 2px;
    }
    h1, h2, h3 { text-wrap: balance; margin: 0; }

    .panel {
        background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-panel);
    }
    .muted { color: var(--muted); }
    .small { font-size: 13px; }
    .error {
        color: var(--bad); background: var(--bad-soft); border-radius: var(--r-control);
        padding: 10px 14px; margin: 0; font-size: 14px;
    }

    .chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
    .chip {
        display: inline-flex; align-items: center; gap: 5px; white-space: nowrap;
        font-size: 12.5px; font-weight: 500; line-height: 1; padding: 4px 9px; border-radius: 999px;
        background: var(--idle-soft); color: var(--muted); text-decoration: none;
    }
    .chip.ok { background: var(--ok-soft); color: var(--ok); }
    .chip.bad { background: var(--bad-soft); color: var(--bad); }
    .chip.warn { background: var(--warn-soft); color: var(--warn); }
    .chip.run { background: var(--accent-soft); color: var(--accent); }
    .chip.merged { background: var(--merged-soft); color: var(--merged); }

    button, .btn {
        display: inline-flex; align-items: center; justify-content: center; gap: 7px;
        font: 500 14px/1 var(--font); padding: 8px 14px; border-radius: var(--r-control); cursor: pointer;
        border: 1px solid var(--line); background: var(--surface); color: var(--ink); text-decoration: none;
        transition: background-color 120ms, border-color 120ms;
    }
    button:hover:not(:disabled), .btn:hover { background: var(--surface-2); }
    button.primary, .btn.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
    button.primary:hover:not(:disabled), .btn.primary:hover { background: var(--accent); filter: brightness(1.08); }
    button.quiet, .btn.quiet { border-color: transparent; background: transparent; color: var(--muted); }
    button.quiet:hover:not(:disabled), .btn.quiet:hover { background: var(--surface-2); color: var(--ink); }
    button.danger { color: var(--bad); }
    button:disabled { opacity: 0.55; cursor: default; }
    button.sm, .btn.sm { padding: 6px 11px; font-size: 13px; }

    input[type="text"], select, textarea {
        font: 15px/1.5 var(--font); color: var(--ink); background: var(--surface);
        border: 1px solid var(--line); border-radius: var(--r-control); padding: 8px 12px;
    }
    textarea { width: 100%; box-sizing: border-box; resize: vertical; }

    .spinner {
        width: 14px; height: 14px; border-radius: 50%; flex: none;
        border: 2px solid currentColor; border-right-color: transparent;
        animation: spin 0.7s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    .skeleton {
        border-radius: 6px; background: var(--surface-2);
        background-image: linear-gradient(90deg, transparent, rgb(255 255 255 / 0.35), transparent);
        background-size: 200% 100%; background-repeat: no-repeat;
        animation: shimmer 1.4s ease-in-out infinite;
    }
    @keyframes shimmer { from { background-position: 150% 0; } to { background-position: -50% 0; } }

    .icon { width: 16px; height: 16px; flex: none; }
    .icon.ok { color: var(--ok); }
    .icon.bad { color: var(--bad); }
    .icon.run { color: var(--accent); }
    .icon.idle { color: var(--muted); }

    /* A tab's title row: the heading, and its Settings toggle on the right. */
    .page-top { display: flex; flex-wrap: wrap; gap: 12px 20px; justify-content: space-between; align-items: flex-start; }
    .page-top > :first-child { min-width: 0; flex: 1 1 20rem; }
    button.settings-toggle { display: inline-flex; gap: 7px; align-items: center; }
    button.settings-toggle svg { width: 16px; height: 16px; }
    button.settings-toggle[aria-expanded="true"] { background: var(--accent-soft); color: var(--accent); }
    .settings-area { display: grid; gap: 30px; padding: 22px 0 8px; border-bottom: 1px solid var(--line); }

    @media (prefers-reduced-motion: reduce) {
        .spinner { animation-duration: 2.4s; }
        .skeleton { animation: none; }
        button, .btn { transition: none; }
    }
    /* A pop-up's close, in its top right corner (closeX). */
    button.dialog-x { position: absolute; top: 12px; right: 12px; width: 32px; height: 32px; padding: 0; border: 0;
        border-radius: 8px; background: none; color: var(--muted); cursor: pointer; display: grid; place-items: center; }
    button.dialog-x:hover { color: var(--ink); background: var(--surface-2); }
    button.dialog-x svg { width: 16px; height: 16px; }
    /* What comes first in the pop-up keeps clear of it. */
    button.dialog-x + * { margin-right: 36px; }
`;

/** The Settings button in a tab's title row: opens that tab's settings above its content. */
export function settingsToggle(open, onClick) {
    return html`<button class="quiet settings-toggle" aria-expanded=${open ? 'true' : 'false'} @click=${onClick}>
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true">
            <path d="M2 4h7M12.5 4H14M2 8h2.5M8 8h6M2 12h7M12.5 12H14"/>
            <circle cx="10.75" cy="4" r="1.6"/><circle cx="6.25" cy="8" r="1.6"/><circle cx="10.75" cy="12" r="1.6"/>
        </svg>Settings</button>`;
}

/** A spinner, for buttons doing work. */
export const spinner = html`<span class="spinner" aria-hidden="true"></span>`;

/** The X in a pop-up's top right corner: closes the <dialog> it's in. */
export function closeX() {
    return html`<button class="dialog-x" aria-label="Close" title="Close" @click=${e => e.currentTarget.closest('dialog')?.close()}>
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true">
            <path d="M3.5 3.5l9 9M12.5 3.5l-9 9"/></svg></button>`;
}

/** PR or issue icon, coloured by state: CI for PRs, open/closed for issues. */
export function kindIcon(item) {
    if (item.kind === 'PR') {
        const cls = { SUCCESS: 'ok', FAILURE: 'bad', ERROR: 'bad', PENDING: 'run', EXPECTED: 'run' }[item.ci] ?? 'idle';
        return html`<svg class="icon ${cls}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
                        stroke-linecap="round" role="img" aria-label="Pull request${ciLabel(item.ci)}">
            ${svg`<circle cx="4" cy="3.5" r="1.8"/><circle cx="4" cy="12.5" r="1.8"/><circle cx="12" cy="12.5" r="1.8"/>
                  <path d="M4 5.3v5.4M12 10.7V6.5a2 2 0 0 0-2-2H7.5M9 2.8 7.3 4.5 9 6.2"/>`}
        </svg>`;
    }
    return html`<svg class="icon idle" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
                    role="img" aria-label="Issue">
        ${svg`<circle cx="8" cy="8" r="6"/><circle cx="8" cy="8" r="1.4" fill="currentColor" stroke="none"/>`}
    </svg>`;
}

function ciLabel(ci) {
    return { SUCCESS: ', CI passing', FAILURE: ', CI failing', ERROR: ', CI failing', PENDING: ', CI running',
        EXPECTED: ', CI running' }[ci] ?? '';
}

const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

/** "3 days ago", "yesterday", "just now". */
/**
 * Redraws its element every 30 seconds while the page is in view, so "just now" and "5 minutes ago" keep moving.
 * One shared timer for every element showing such a time: `new Ticking(this)` in the constructor.
 */
const ticking = new Set();
let tickTimer = null;

function tickAll() {
    if (!document.hidden) ticking.forEach(host => host.requestUpdate());
}

document.addEventListener('visibilitychange', tickAll);

export class Ticking {
    constructor(host) {
        this.host = host;
        host.addController(this);
    }

    hostConnected() {
        ticking.add(this.host);
        tickTimer ??= setInterval(tickAll, 30_000);
    }

    hostDisconnected() {
        ticking.delete(this.host);
        if (!ticking.size) { clearInterval(tickTimer); tickTimer = null; }
    }
}

export function ago(iso) {
    if (!iso) return '';
    const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
    const abs = Math.abs(seconds);
    if (abs < 60) return 'just now';
    if (abs < 3600) return rtf.format(Math.round(seconds / 60), 'minute');
    if (abs < 86400) return rtf.format(Math.round(seconds / 3600), 'hour');
    if (abs < 86400 * 45) return rtf.format(Math.round(seconds / 86400), 'day');
    return rtf.format(Math.round(seconds / (86400 * 30)), 'month');
}

/** "Today 08:03", "Yesterday 08:00", "Mon 12 Oct 08:00": when a saved run happened (local time). */
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

const DAYS = { mon: 'Mondays', tue: 'Tuesdays', wed: 'Wednesdays', thu: 'Thursdays', fri: 'Fridays', sat: 'Saturdays', sun: 'Sundays' };
const DAY_NAMES = { mon: 'Mon', tue: 'Tue', wed: 'Wed', thu: 'Thu', fri: 'Fri', sat: 'Sat', sun: 'Sun' };

/** "Mon..Fri" as "weekdays", "Mon,Wed" as "Mondays and Wednesdays", none as "every day". */
function daysText(days) {
    if (!days) return 'every day';
    return days.split(',').map(d => {
        const [from, to] = d.split('..').map(x => x.toLowerCase());
        if (!to) return DAYS[from] ?? d;
        if (from === 'mon' && to === 'fri') return 'weekdays';
        if (from === 'sat' && to === 'sun') return 'weekends';
        if (from === 'mon' && to === 'sun') return 'every day';
        return `${DAY_NAMES[from] ?? from} to ${DAY_NAMES[to] ?? to}`;
    }).join(' and ');
}

/**
 * A refresh schedule's calendar time in words: "Mondays at 08:00", "weekdays at 07:30 and 13:00", "every hour from
 * 08:00 to 18:00 on weekdays". Several parts (";") are joined; anything else is shown as it is.
 */
export function calendarText(cal) {
    if (!cal) return '';
    const parts = cal.split(';').map(p => /^(?:(\S+) )?\*-\*-\* (\*|[\d,.]+):(\d{2})(?::00)?$/.exec(p.trim()));
    if (parts.some(m => !m)) return cal;
    const out = [];
    for (const m of parts) {
        const days = daysText(m[1]);
        const hours = m[2];
        const range = /^(\d{1,2})\.\.(\d{1,2})$/.exec(hours);
        if (hours === '*' || range) {
            const from = range ? range[1].padStart(2, '0') : '00', to = range ? range[2].padStart(2, '0') : '23';
            out.push(`every hour${hours === '*' ? '' : ` from ${from}:${m[3]} to ${to}:${m[3]}`}${days === 'every day' ? '' : ` on ${days}`}`);
        } else if (/^\d{1,2}(,\d{1,2})*$/.test(hours)) {
            const times = hours.split(',').map(h => `${h.padStart(2, '0')}:${m[3]}`);
            out.push(`${days} at ${times.length === 1 ? times[0] : `${times.slice(0, -1).join(', ')} and ${times.at(-1)}`}`);
        } else {
            return cal;
        }
    }
    // Same days: "weekdays at 07:00 and 13:30" rather than saying the days twice.
    const same = parts.every(m => (m[1] ?? '') === (parts[0][1] ?? '')) && out.every(x => !x.startsWith('every hour'));
    if (same && out.length > 1) {
        const days = daysText(parts[0][1]);
        const times = out.map(x => x.slice(days.length + 4));
        return `${days} at ${times.slice(0, -1).join(', ')} and ${times.at(-1)}`;
    }
    return out.join('; ');
}

/** GitHub says every required check passed and nothing else blocks the merge. */
export function requiredChecksPassed(item) {
    return item.mergeState === 'CLEAN' || item.mergeState === 'HAS_HOOKS';
}

/** Approved, green, and every required check passed: the only time Merge is offered. */
export function mergeable(item) {
    return item.kind === 'PR' && item.approved === true && item.ci === 'SUCCESS' && !item.draft && requiredChecksPassed(item);
}

/** Green but not approved: "Merge without approval" is offered (on the row for your own PRs, otherwise under More). */
export function mergeableAnyway(item) {
    return item.kind === 'PR' && item.approved !== true && item.ci === 'SUCCESS' && !item.draft && requiredChecksPassed(item);
}

/** A fork PR whose CI waits for a maintainer to approve it ("Approve and run"). */
export function ciNeedsApproval(item) {
    return item.kind === 'PR' && item.ci === 'AWAITING_APPROVAL';
}

export function ciFailing(item) {
    return item.kind === 'PR' && (item.ci === 'FAILURE' || item.ci === 'ERROR');
}

/**
 * Status chips for a row. CI is already the icon's colour, so only what the icon can't say. With
 * {@code ciMenu}, failing CI is left out so the caller can show the CI menu in its place.
 */
/** Behind its base, and the repo requires up-to-date branches (no conflicts: those have their own menu). */
export function behind(item) {
    return item.kind === 'PR' && item.mergeState === 'BEHIND' && item.mergeable !== 'CONFLICTING';
}

/** Opened by Dependabot: "@dependabot rebase" is how it gets rebased (it keeps maintaining the PR). */
export function isDependabot(item) {
    return item.kind === 'PR' && /^(app\/)?dependabot(\[bot\])?$/.test(item.author ?? '');
}

export function hasConflicts(item) {
    return item.kind === 'PR' && item.mergeable === 'CONFLICTING';
}

/** @param approval false leaves out "Not approved" (mvnpm rows show the elf's own verdict instead) */
export function itemChips(item, { ciMenu = false, conflictMenu = false, approval = true } = {}) {
    const chips = [];
    if (item.kind === 'PR') {
        if (item.draft) chips.push(html`<span class="chip">Draft</span>`);
        if (ciFailing(item) && !ciMenu) chips.push(html`<span class="chip bad">CI failing</span>`);
        if (ciNeedsApproval(item) && !ciMenu) chips.push(html`<span class="chip warn">CI needs approval</span>`);
        // Green so far, but GitHub's required checks haven't all passed (often: the real CI hasn't started yet).
        if (item.ci === 'SUCCESS' && item.mergeState === 'BLOCKED' && !item.draft
                && item.reviewDecision !== 'REVIEW_REQUIRED' && item.reviewDecision !== 'CHANGES_REQUESTED') {
            chips.push(html`<span class="chip run" title="GitHub says required checks haven't all passed yet">Waiting for required checks</span>`);
        }
        if (behind(item) && !conflictMenu) chips.push(html`<span class="chip warn" title="The repo requires the branch to be up to date before merging">Behind base</span>`);
        if (item.ci === 'PENDING' || item.ci === 'EXPECTED') chips.push(html`<span class="chip run">${spinner}CI in progress</span>`);
        if (item.ci === 'SUCCESS' && item.mergeState !== 'BLOCKED') {
            chips.push(html`<span class="chip ok" title="Every check on the latest commit passed">CI passed</span>`);
        }
        if (item.reviewDecision === 'APPROVED') chips.push(html`<span class="chip ok">Approved</span>`);
        // Green and ready, apart from an approval: say so, as that's what decides between Merge and Merge without approval.
        if (approval && mergeableAnyway(item) && item.reviewDecision !== 'CHANGES_REQUESTED') {
            chips.push(html`<span class="chip" title="Nobody with write access has approved it yet">Not approved</span>`);
        }
        if (item.reviewDecision === 'CHANGES_REQUESTED') chips.push(html`<span class="chip warn">Changes requested</span>`);
        if (hasConflicts(item) && !conflictMenu) chips.push(html`<span class="chip bad">Conflicts</span>`);
    }
    return chips;
}

/** Markdown from the agent, sanitised, as a Lit template. */
export function markdown(text) {
    return unsafeHTML(markdownHtml(text));
}

/** Markdown as sanitized HTML, as a string. */
export function markdownHtml(text) {
    return DOMPurify.sanitize(marked.parse(text ?? '', { gfm: true, breaks: false }));
}

export const markdownStyles = css`
    .md { overflow-wrap: anywhere; line-height: 1.6; min-width: 0; }
    .md img { max-width: 100%; height: auto; border-radius: 6px; }
    .md > :first-child { margin-top: 0; }
    .md > :last-child { margin-bottom: 0; }
    .md p, .md ul, .md ol { margin: 0 0 12px; }
    .md li + li { margin-top: 4px; }
    .md code { font: 0.86em var(--font-code); background: var(--surface-2); padding: 2px 5px; border-radius: 5px; }
    .md pre { background: var(--surface-2); padding: 12px 14px; border-radius: 10px; overflow-x: auto; }
    .md pre code { background: none; padding: 0; font-size: 13px; }
    .md h1, .md h2, .md h3, .md h4 { font-size: 16px; font-weight: 600; margin: 18px 0 8px; }
    .md table { border-collapse: collapse; font-size: 14px; display: block; overflow-x: auto; }
    .md th, .md td { border: 1px solid var(--line); padding: 6px 10px; text-align: left; }
    .md blockquote { margin: 0 0 12px; padding-left: 12px; border-left: 3px solid var(--line); color: var(--muted); }
`;

/** owner/repo#123 <-> #thread/owner/repo/123 */
export function threadHref(itemKey) {
    const [repo, number] = itemKey.split('#');
    return `#thread/${repo}/${number}`;
}

export function itemKeyFromHash(hash) {
    const m = /^#thread\/([^/]+\/[^/]+)\/(\d+)$/.exec(hash);
    return m ? `${m[1]}#${m[2]}` : null;
}

/** Items seen on the board, so a new thread page knows the title and kind before any thread exists. */
const seen = new Map();
export function rememberItem(item) {
    seen.set(item.key, item);
}
export function knownItem(itemKey) {
    return seen.get(itemKey);
}

/**
 * Open PRs that fix or mention each issue ({key, title, url, author, draft, fixes}), from the board, so the
 * issue's thread page can list them. Remembers each PR too, so its thread page knows it's a PR.
 */
const links = new Map();
export function rememberLinks(linkedPrs) {
    links.clear();
    for (const [issueKey, prs] of Object.entries(linkedPrs ?? {})) {
        links.set(issueKey, prs);
        for (const pr of prs) {
            if (!seen.has(pr.key)) {
                const [repo, number] = pr.key.split('#');
                seen.set(pr.key, { key: pr.key, repo, number: Number(number), kind: 'PR', title: pr.title, url: pr.url,
                    author: pr.author, draft: pr.draft });
            }
        }
    }
}
export function linksFor(issueKey) {
    return links.get(issueKey) ?? [];
}

/** "Fixed by #12" or "Mentioned in #12" chips, linking to the PR's thread here. */
export function linkedPrChips(issueKey) {
    return linksFor(issueKey).slice(0, 3).map(pr => html`<a class="chip ${pr.fixes ? 'run' : ''}" href=${threadHref(pr.key)}
        title="${pr.title} (by ${pr.author}${pr.draft ? ', draft' : ''})">${pr.fixes ? 'Fixed by' : 'Mentioned in'} ${prRef(issueKey, pr.key)}</a>`);
}

/** #12 in the same repo, owner/repo#12 elsewhere. */
export function prRef(fromKey, prKey) {
    return fromKey.split('#')[0] === prKey.split('#')[0] ? '#' + prKey.split('#')[1] : prKey;
}

/**
 * Copies text. The Clipboard API only exists on secure pages (https, localhost); the desk is often opened over
 * plain http on the LAN, so fall back to a hidden textarea and execCommand('copy'), which still works there.
 */
export async function copyText(text) {
    if (navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            return;
        } catch { /* refused: try the fallback */ }
    }
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none';
    document.body.append(area);
    area.select();
    try {
        if (!document.execCommand('copy')) throw new Error('the browser refused to copy');
    } finally {
        area.remove();
    }
}

/**
 * Copies formatted text (headings, lists) that pastes as such into Google Docs or an email, with the plain text for
 * places that take only text. Over plain http there is no async clipboard, so it selects a hidden copy and copies that.
 */
export async function copyHtml(html, text) {
    if (navigator.clipboard?.write && window.ClipboardItem) {
        try {
            await navigator.clipboard.write([new ClipboardItem({
                'text/html': new Blob([html], { type: 'text/html' }),
                'text/plain': new Blob([text], { type: 'text/plain' }),
            })]);
            return;
        } catch { /* refused: try the fallback */ }
    }
    const box = document.createElement('div');
    box.innerHTML = html;
    box.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none;white-space:normal';
    document.body.append(box);
    const range = document.createRange();
    range.selectNodeContents(box);
    const selection = getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    try {
        if (!document.execCommand('copy')) throw new Error('the browser refused to copy');
    } finally {
        selection.removeAllRanges();
        box.remove();
    }
}
