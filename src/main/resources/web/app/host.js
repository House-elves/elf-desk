// The desk's desktop app (House-elves/elf-desk-ide) shows the elves in its own menus, side bar and status bar. It
// holds a desk page opened with ?host, and reads them here (window.elfDeskHost): it needs no access of its own, and
// can do only what this page can. A page opened without ?host or ?embed doesn't have it.
import { api, onPush, onConnectionChange, connectionState } from './rpc.js';
import { shownTabs, onTabsChange } from './tabs.js';

const EVENT = 'elfdesk-host';

/** In the desktop app: the page that tells it the desk's state (?host), or one elf's tab in it (?embed). */
export function hostMode() {
    const p = new URLSearchParams(location.search);
    return p.has('host') ? 'host' : p.has('embed') ? 'embed' : null;
}

let attention = {};
let running = {};
let failed = {};

const changed = () => window.dispatchEvent(new CustomEvent(EVENT));

async function loadAttention() {
    try {
        attention = await (await api()).elvesAttention() ?? {};
        changed();
    } catch {
        // No runner: no dots until it's back.
    }
}

async function loadRuns() {
    try {
        const s = await (await api()).elvesState();
        running = s?.running ?? {};
        failed = s?.failed ?? {};
        changed();
    } catch {
        // No runner.
    }
}

/** Runs an elf's refresh, as its tab's button does. */
async function refresh(elf) {
    const t = shownTabs().find(x => x.elf === elf);
    if (!t?.refresh || t.missing?.length) return false;
    await (await api()).runElf({ elf, command: t.refresh, params: null });
    await loadRuns();
    return true;
}

export function startHost() {
    if (hostMode() !== 'host') return;
    onPush('desk.attention', d => { attention = d?.attention ?? {}; changed(); });
    onPush('desk.runner', () => { loadAttention(); loadRuns(); });
    onPush('desk.elves', () => loadRuns());
    onConnectionChange(s => { if (s === 'open') { loadAttention(); loadRuns(); } changed(); });
    onTabsChange(changed);
    loadAttention();
    loadRuns();
    window.elfDeskHost = Object.freeze({
        version: 1,
        event: EVENT,
        state: () => ({
            connection: connectionState(),
            tabs: shownTabs().map(t => ({ id: t.id, label: t.label, elf: t.elf, card: t.card, refresh: t.refresh, missing: t.missing ?? [] })),
            attention: { ...attention },
            running: Object.keys(running),
            failed: Object.keys(failed),
        }),
        refresh,
        refreshAll: async () => {
            const done = await Promise.allSettled(shownTabs().filter(t => t.card && t.refresh && !t.missing?.length
                && !running[`${t.elf}:${t.refresh}`]).map(t => refresh(t.elf)));
            return done.filter(d => d.status === 'rejected').map(d => String(d.reason?.message ?? d.reason));
        },
    });
    changed();
}
