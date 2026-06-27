import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useEffect, useState } from 'react';

// Same base as the REST client; '' => same origin (nginx proxies /ws in prod).
const base = import.meta.env.VITE_API_BASE_URL ?? '';
const WS_URL = base ? `${base}/ws` : '/ws';

let client = null;
let connected = false;
const connListeners = new Set();
const subs = new Map(); // id -> { topic, cb, stompSub }
let nextId = 1;
let teardownTimer = null; // deferred deactivation when no subscriptions remain

function notifyConn(state) {
  connected = state;
  connListeners.forEach((f) => f(state));
}

function bindStomp(entry) {
  entry.stompSub = client.subscribe(entry.topic, (msg) => {
    try {
      entry.cb(JSON.parse(msg.body));
    } catch (e) {
      /* ignore malformed frame */
    }
  });
}

function ensureClient() {
  if (client) return client;
  client = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      notifyConn(true);
      // (re)bind every subscription — handles initial connect AND reconnects
      subs.forEach((entry) => bindStomp(entry));
    },
    onWebSocketClose: () => notifyConn(false),
    onStompError: () => notifyConn(false),
  });
  client.activate();
  return client;
}

/** Subscribe to a STOMP topic. Returns an unsubscribe function. */
export function subscribe(topic, cb) {
  // A new subscriber cancels any pending teardown.
  if (teardownTimer) { clearTimeout(teardownTimer); teardownTimer = null; }
  const id = nextId++;
  const entry = { topic, cb, stompSub: null };
  subs.set(id, entry);
  const c = ensureClient();
  if (c.connected) bindStomp(entry);
  return () => {
    const e = subs.get(id);
    if (e?.stompSub) {
      try { e.stompSub.unsubscribe(); } catch (err) { /* noop */ }
    }
    subs.delete(id);
    // When nothing is listening, deactivate the socket after a short grace period
    // (avoids churn during route changes / StrictMode mount→unmount→remount).
    if (subs.size === 0 && client && !teardownTimer) {
      teardownTimer = setTimeout(() => {
        teardownTimer = null;
        if (subs.size === 0 && client) {
          try { client.deactivate(); } catch (err) { /* noop */ }
          client = null;
          connected = false;
        }
      }, 30000);
    }
  };
}

/** React hook: latest message for a topic (or null until the first push). */
export function useStreamTopic(topic) {
  const [data, setData] = useState(null);
  useEffect(() => subscribe(topic, setData), [topic]);
  return data;
}

/** React hook: live WebSocket connection state. */
export function useStreamConnected() {
  const [isUp, setIsUp] = useState(connected);
  useEffect(() => {
    connListeners.add(setIsUp);
    setIsUp(connected);
    return () => connListeners.delete(setIsUp);
  }, []);
  return isUp;
}
