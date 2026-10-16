// Which tabs show (GitHub, Email, Zulip, Status, Quarterly), remembered per browser like the theme. A hidden tab
// leaves the header and the dashboard; its page still opens from a link.

const KEY = 'elfdesk.hidden-tabs';
const EVENT = 'elfdesk-tabs';

export const TABS = [
    { id: 'github', label: 'GitHub' },
    { id: 'email', label: 'Email' },
    { id: 'zulip', label: 'Zulip' },
    { id: 'status', label: 'Status' },
    { id: 'quarterly', label: 'Quarterly' },
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

export function tabShown(id) {
    return !current.has(id);
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
