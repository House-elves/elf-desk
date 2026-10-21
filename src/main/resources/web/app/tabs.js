// Which tabs show (GitHub, Email, Zulip, Status, Quarterly), remembered per browser like the theme. A hidden tab
// leaves the header and the dashboard; its page still opens from a link. A tab whose elf isn't installed doesn't
// show at all (until the Elves page installs it).

const KEY = 'elfdesk.hidden-tabs';
const EVENT = 'elfdesk-tabs';

export const TABS = [
    { id: 'github', label: 'GitHub' },
    { id: 'email', label: 'Email', elf: 'daily-triage' },
    { id: 'zulip', label: 'Zulip', elf: 'daily-triage' },
    { id: 'status', label: 'Status', elf: 'weekly-status' },
    { id: 'quarterly', label: 'Quarterly', elf: 'quarterly-review' },
];

function hidden() {
    try {
        const ids = JSON.parse(localStorage.getItem(KEY) ?? '[]');
        return new Set(Array.isArray(ids) ? ids.filter(id => TABS.some(t => t.id === id)) : []);
    } catch (e) {
        return new Set();
    }
}

let current = hidden();
/** The installed elves, once the runner has said; null until then (every tab shows). */
let installed = null;

/** Whether a tab's elf is installed: GitHub always is. */
export function tabAvailable(id) {
    const elf = TABS.find(t => t.id === id)?.elf;
    return !elf || installed === null || installed.has(elf);
}

export function tabShown(id) {
    return tabAvailable(id) && !current.has(id);
}

/** What the Elves page or the shell learnt is installed. */
export function setInstalledElves(names) {
    const next = new Set(names);
    if (installed && next.size === installed.size && [...next].every(n => installed.has(n))) return;
    installed = next;
    window.dispatchEvent(new CustomEvent(EVENT));
}

export function installedElves() {
    return installed;
}

export function showTab(id, on) {
    const next = new Set(current);
    if (on) next.delete(id); else next.add(id);
    current = next;
    try {
        if (next.size) localStorage.setItem(KEY, JSON.stringify([...next]));
        else localStorage.removeItem(KEY);
    } catch (e) {
        // no storage: the choice lasts for this page only
    }
    window.dispatchEvent(new CustomEvent(EVENT));
}

/** Calls back when tabs are turned on or off; returns the unsubscribe. */
export function onTabsChange(callback) {
    window.addEventListener(EVENT, callback);
    return () => window.removeEventListener(EVENT, callback);
}
