// Voice input for an elf's tab: the desk's page listens (an elf's frame has no microphone and no network) and hands
// the words to the elf as you speak. Two ways, chosen per browser:
//  - browser: the browser's own speech recognition. Words come as you speak; in Chrome and Edge the audio goes to
//    Google's or Microsoft's speech service. Not in Firefox.
//  - whisper: the desk records you and Whisper on the desk's machine writes it down (VoiceRoutes): the audio never
//    leaves it, and the words come when you stop.
import { withKey } from './rpc.js';

const KEY = 'elfdesk-voice';
/** A recording for Whisper stops itself after this long. */
const MAX_RECORDING_MS = 5 * 60 * 1000;

export function voiceMode() {
    try {
        return localStorage.getItem(KEY) === 'whisper' ? 'whisper' : 'browser';
    } catch {
        return 'browser';
    }
}

export function setVoiceMode(mode) {
    try {
        localStorage.setItem(KEY, mode === 'whisper' ? 'whisper' : 'browser');
    } catch {
        // storage blocked: it stays the browser's speech
    }
}

export const browserSpeech = () => window.SpeechRecognition || window.webkitSpeechRecognition || null;

/** Whether Whisper is set up on the desk's machine: {whisper, model, how}. */
export async function whisperStatus() {
    try {
        const r = await fetch(withKey('/voice/status'), { credentials: 'same-origin' });
        return r.ok ? await r.json() : { whisper: false };
    } catch {
        return { whisper: false };
    }
}

/**
 * Starts listening. onText({final}) for words it's sure of (added to what you're writing), onText({interim}) for what
 * it's still hearing (shown, then replaced); onState('listening' | 'writing' | 'done'); onError(message), after which
 * it's done. Answers {stop}: stop listening and keep what was said; {cancel}: stop and drop it.
 */
export function listen(mode, { onText, onState, onError }) {
    return mode === 'whisper' ? whisper({ onText, onState, onError }) : browser({ onText, onState, onError });
}

function browser({ onText, onState, onError }) {
    const Speech = browserSpeech();
    if (!Speech) {
        onError('This browser has no speech recognition: use Chrome, Edge or Safari, or Whisper on the desk\'s machine.');
        return { stop() {}, cancel() {} };
    }
    const rec = new Speech();
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = navigator.language || 'en-US';
    let ended = false, failed = false, cancelled = false;
    rec.onstart = () => onState('listening');
    rec.onresult = e => {
        if (cancelled) return;
        let interim = '';
        for (let i = e.resultIndex; i < e.results.length; i++) {
            const t = e.results[i][0]?.transcript ?? '';
            if (e.results[i].isFinal) onText({ final: t.trim() });
            else interim += t;
        }
        onText({ interim: interim.trim() });
    };
    rec.onerror = e => {
        if (e.error === 'no-speech' || e.error === 'aborted') return;
        failed = true;
        onError({
            'not-allowed': 'The browser didn\'t let the desk use the microphone: allow it for this site, then try again.',
            'service-not-allowed': 'The browser didn\'t let the desk use its speech recognition.',
            'audio-capture': 'No microphone was found.',
            network: 'The browser\'s speech recognition needs the internet. Whisper on the desk\'s machine doesn\'t.',
        }[e.error] ?? `Speech recognition stopped: ${e.error}`);
    };
    // It ends on its own after a pause: that's done, like Stop.
    rec.onend = () => {
        if (ended) return;
        ended = true;
        if (!failed) onState('done');
    };
    try {
        rec.start();
    } catch (e) {
        onError(`Couldn't start listening: ${e.message}`);
    }
    return {
        stop() { rec.stop(); },
        cancel() { cancelled = true; rec.abort(); },
    };
}

function whisper({ onText, onState, onError }) {
    let recorder = null, stream = null, cancelled = false, timer = null;
    const chunks = [];
    const release = () => {
        clearTimeout(timer);
        stream?.getTracks().forEach(t => t.stop());
    };
    (async () => {
        try {
            stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
        } catch (e) {
            onError(e.name === 'NotAllowedError' ? 'The browser didn\'t let the desk use the microphone: allow it for this site, then try again.'
                : !navigator.mediaDevices ? 'The browser only allows the microphone on a secure page (https, or localhost).'
                : `No microphone: ${e.message}`);
            return;
        }
        if (cancelled) return release();
        recorder = new MediaRecorder(stream);
        recorder.ondataavailable = e => { if (e.data.size) chunks.push(e.data); };
        recorder.onstop = async () => {
            release();
            if (cancelled) return;
            if (!chunks.length) return onState('done');
            onState('writing');
            try {
                const blob = new Blob(chunks, { type: recorder.mimeType || 'audio/webm' });
                const r = await fetch(withKey('/voice/transcribe'), {
                    method: 'POST', body: blob, credentials: 'same-origin', headers: { 'Content-Type': blob.type || 'application/octet-stream' },
                });
                const answer = await r.json().catch(() => ({}));
                if (!r.ok) return onError(answer.error ?? `Whisper couldn't write it down (${r.status})`);
                if (answer.text) onText({ final: answer.text });
                onState('done');
            } catch (e) {
                onError(`Couldn't reach the desk: ${e.message}`);
            }
        };
        recorder.start();
        onState('listening');
        timer = setTimeout(() => recorder?.state === 'recording' && recorder.stop(), MAX_RECORDING_MS);
    })();
    return {
        stop() {
            if (recorder?.state === 'recording') recorder.stop();
            else { cancelled = true; release(); onState('done'); }
        },
        cancel() {
            cancelled = true;
            if (recorder?.state === 'recording') recorder.stop();
            release();
        },
    };
}
