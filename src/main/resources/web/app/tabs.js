// Which tabs there are: one per installed elf that has a tab, named by its title (GitHub, Mail, Zulip, Weekly
// status), in the order you dragged them to; one you never moved goes after, the github elf first and then in the
// order you added them. Adding an elf adds its tab; removing it takes
// the tab away. The desk's own GitHub board is gone: GitHub is the github elf's tab.

const EVENT = 'elfdesk-tabs';

export const GITHUB = { id: 'github', label: 'GitHub' };

/** Elf Maker comes with the desk: its page opens from the + page, not from a tab of its own. */
export const BUILTIN = 'elf-maker';

/** The installed elves (as the runner lists them), once known; null until then. */
let installed = null;
let elfTabs = [];
/** The order you dragged the tabs to, by elf name, as the desk keeps it. */
let order = [];

/**
 * Every tab: each installed elf's, as {id, label, elf, card, refresh, missing}. A tab's id is its
 * elf's name (#mail-triage). refresh: the command that brings it up to date; missing: settings it needs first.
 */
export function tabs() {
    return elfTabs;
}

/** The tabs in the bar and on the dashboard: all but Elf Maker's. */
export function shownTabs() {
    return elfTabs.filter(t => !t.hidden);
}

/** The github elf's tab, once it's installed. */
export function githubElf() {
    return elfTabs.find(t => t.elf === GITHUB.id) ?? null;
}

export function findTab(id) {
    return tabs().find(t => t.id === id);
}

/** An elf's tab: from its elf.yaml's tab (a runner before 0.32 lists tabs, and the first is it). */
export function tabOf(e) {
    return e.tab ?? e.tabs?.[0] ?? null;
}

/** What the Elves page or the shell learnt is installed: the runner's list of elves. */
export function setInstalledElves(elves) {
    const placed = name => {
        const i = order.indexOf(name);
        return i < 0 ? Number.MAX_SAFE_INTEGER : i;
    };
    const next = [...(elves ?? [])]
        .filter(e => tabOf(e) && e.name !== 'elves')
        .sort((a, b) => placed(a.name) - placed(b.name) || (b.name === GITHUB.id) - (a.name === GITHUB.id)
            || (a.installedAt ?? '').localeCompare(b.installedAt ?? '') || a.name.localeCompare(b.name))
        .map(e => ({
            id: e.name, label: e.title || e.name, elf: e.name, card: !!tabOf(e).card,
            refresh: e.refresh?.command ?? tabOf(e).refresh ?? null, missing: e.missing ?? [], hidden: e.name === BUILTIN,
        }));
    const same = installed && JSON.stringify(next) === JSON.stringify(elfTabs)
        && JSON.stringify(elves) === JSON.stringify(installed);
    installed = elves ?? [];
    elfTabs = next;
    recomputeUpdates();
    if (!same) window.dispatchEvent(new CustomEvent(EVENT));
}

/** The order the desk keeps, once it's loaded or you moved a tab: the tabs follow it straight away. */
export function setTabOrder(names) {
    order = [...(names ?? [])];
    if (installed) setInstalledElves(installed);
}

/** The tabs' order now, by elf name, for saving after a move. */
export function tabOrder() {
    return elfTabs.map(t => t.elf);
}

/**
 * Elves with an update waiting: name -> the newest commit of their repo (from the + page's catalog, which the
 * shell asks for now and then). An elf only moves when you update it.
 */
let updates = {};
let lastCatalog = [];

export function setCatalog(catalog) {
    lastCatalog = catalog ?? [];
    recomputeUpdates();
}

/** Against the elves as installed now: an elf just updated loses its mark straight away. */
function recomputeUpdates() {
    const next = {};
    for (const c of lastCatalog) {
        const e = (installed ?? []).find(x => x.repo === c.repo);
        if (e && c.commit && c.commit !== e.commit) next[e.name] = c.commit;
    }
    if (JSON.stringify(next) === JSON.stringify(updates)) return;
    updates = next;
    window.dispatchEvent(new CustomEvent(EVENT));
}

/** The newest commit of an installed elf's repo, if it's newer than the one installed; null if it's up to date. */
export function updateFor(name) {
    return updates[name] ?? null;
}

/** The installed elves, or null before the runner has said. */
export function installedElves() {
    return installed;
}

export function installedElf(name) {
    return installed?.find(e => e.name === name) ?? null;
}

/** Calls back when tabs come and go with elves, or an elf's setup or schedule changes; returns the unsubscribe. */
export function onTabsChange(callback) {
    window.addEventListener(EVENT, callback);
    return () => window.removeEventListener(EVENT, callback);
}
