import { client, desk } from '@quarkiverse/json-rpc-api';

// The bundle is served from /static/bundle/, so derive the endpoint from the
// page, not from import.meta.url.
const endpoint = new URL('json-rpc', document.baseURI);
endpoint.protocol = endpoint.protocol === 'https:' ? 'wss:' : 'ws:';

// The session's page key, from the one-time link (/open). It lives in this origin's storage, which a server on
// another port can't read, and goes with everything that carries data. Without it, the desk has to be opened again.
let pageKey = null;
try { pageKey = localStorage.getItem('elfdesk-page'); } catch { /* storage blocked */ }
if (!pageKey) location.replace('/welcome');
endpoint.searchParams.set('k', pageKey ?? '');
client.url = endpoint.href;

/** A desk URL (an agent's file, say) with the page key, which the desk needs for anything but the page itself. */
export function withKey(path) {
    const url = new URL(path, document.baseURI);
    url.searchParams.set('k', pageKey ?? '');
    return url.pathname + url.search;
}

const listeners = new Set();
let state = client.connected ? 'open' : 'connecting';

function setState(next) {
    state = next;
    listeners.forEach(l => l(state));
}

client.onOpen = () => setState('open');
client.onClose = () => setState('lost');
client.onError = () => setState('lost');

/** Current socket state: 'connecting', 'open' or 'lost'. */
export function connectionState() {
    return state;
}

/** Calls listener(state) on every change; returns an unsubscribe function. */
export function onConnectionChange(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

/** Resolves once the socket is open (immediately when it already is). */
export function whenConnected() {
    if (client.connected) return Promise.resolve();
    return new Promise(resolve => {
        const off = onConnectionChange(s => {
            if (s === 'open') {
                off();
                resolve();
            }
        });
    });
}

// ---- busy tracking: every call through api() counts, so the page can show one loader ----

let inFlight = 0;
const busyListeners = new Set();

function track(promise) {
    inFlight++;
    busyListeners.forEach(l => l(inFlight > 0));
    const done = () => {
        inFlight--;
        busyListeners.forEach(l => l(inFlight > 0));
    };
    return Promise.resolve(promise).then(v => { done(); return v; }, e => { done(); throw e; });
}

/** Calls listener(busy) whenever calls start or all finish; returns an unsubscribe function. */
export function onBusyChange(listener) {
    busyListeners.add(listener);
    return () => busyListeners.delete(listener);
}

const tracked = new Proxy(desk, {
    get(target, prop) {
        const v = target[prop];
        return typeof v === 'function' ? (...args) => track(v.apply(target, args)) : v;
    },
});

/** The typed proxy for DeskApi, after the socket is up. Calls count towards the page loader. */
export async function api() {
    await track(whenConnected());
    return tracked;
}

/** Server pushes (JsonRPCBroadcaster): calls callback(data) for each; returns an unsubscribe function. */
export function onPush(method, callback) {
    return client.on(method, callback);
}

/** A readable message from a JSON-RPC error. */
export function errorMessage(e) {
    return e?.message || e?.error?.message || String(e);
}
