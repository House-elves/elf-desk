// Which tabs there are: GitHub, and the tabs the installed elves draw themselves (their elf.yaml's tabs, such as
// daily-triage's Email and Zulip). Which of them show is remembered per browser, like the theme. A hidden tab leaves
// the header and the dashboard; its page still opens from a link.

const KEY = 'elfdesk.hidden-tabs';
const EVENT = 'elfdesk-tabs';

export const GITHUB = { id: 'github', label: 'GitHub' };

function hidden() {
    try {
        const ids = JSON.parse(localStorage.getItem(KEY) ?? '[]');
        return new Set(Array.isArray(ids) ? ids.filter(id => typeof id === 'string') : []);
    } catch (e) {
        return new Set();
    }
}

let current = hidden();
/** The installed elves (as the runner lists them), once known; null until then. */
let installed = null;
let elfTabs = [];

/**
 * Every tab: GitHub, then each installed elf's, as {id, label, elf, tab, card, refresh}. A tab's id is its name
 * (#email), or elf-name when two elves have a tab of the same name.
 */
export function tabs() {
    return [GITHUB, ...elfTabs];
}

export function findTab(id) {
    return tabs().find(t => t.id === id);
}

/** The tab with this id exists (GitHub always does). */
export function tabAvailable(id) {
    return !!findTab(id);
}

export function tabShown(id) {
    return tabAvailable(id) && !current.has(id);
}

/** What the Elves page or the shell learnt is installed: the runner's list of elves. */
export function setInstalledElves(elves) {
    const next = [];
    for (const e of elves ?? []) {
        for (const t of e.tabs ?? []) {
            const id = next.some(x => x.id === t.name) || t.name === GITHUB.id || t.name === 'elves' ? `${e.name}-${t.name}` : t.name;
            next.push({ id, label: t.label, elf: e.name, tab: t.name, card: !!t.card, refresh: t.refresh ?? null });
        }
    }
    const same = installed && JSON.stringify(next) === JSON.stringify(elfTabs)
        && JSON.stringify(elves) === JSON.stringify(installed);
    installed = elves ?? [];
    elfTabs = next;
    if (!same) window.dispatchEvent(new CustomEvent(EVENT));
}

/** The installed elves, or null before the runner has said. */
export function installedElves() {
    return installed;
}

export function installedElf(name) {
    return installed?.find(e => e.name === name) ?? null;
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

/** Calls back when tabs are turned on or off, or come and go with elves; returns the unsubscribe. */
export function onTabsChange(callback) {
    window.addEventListener(EVENT, callback);
    return () => window.removeEventListener(EVENT, callback);
}
