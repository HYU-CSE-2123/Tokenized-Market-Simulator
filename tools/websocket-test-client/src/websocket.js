import { Client, ReconnectionTimeMode } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { isAuthenticationFailure, RECONNECT_POLICY } from './recovery.js';

const PUBLIC_SUBSCRIPTIONS = [
  ['/topic/markets/mSEC/price', 'price'],
  ['/topic/markets/mSEC/trades', 'trade'],
];
const PRIVATE_SUBSCRIPTIONS = [
  ['/user/queue/orders', 'order'],
  ['/user/queue/portfolio', 'portfolio'],
];

export class MarketSocket {
  #client = null;
  #onStatus;
  #onEvent;
  #onDebug;
  #clientFactory;
  #generation = 0;
  #allowReconnect = false;
  #hasConnected = false;

  constructor({ onStatus, onEvent, onDebug, clientFactory = (config) => new Client(config) }) {
    this.#onStatus = onStatus;
    this.#onEvent = onEvent;
    this.#onDebug = onDebug;
    this.#clientFactory = clientFactory;
  }

  connect({ transport, token, debug }) {
    const previous = this.#client;
    if (previous) void previous.deactivate();
    const generation = ++this.#generation;
    this.#allowReconnect = true;
    this.#hasConnected = false;
    this.#onStatus('CONNECTING');
    const connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    this.#client = this.#clientFactory({
      webSocketFactory: transport === 'sockjs'
        ? () => new SockJS('/ws-sockjs')
        : () => new WebSocket(webSocketUrl('/ws')),
      connectHeaders,
      reconnectDelay: RECONNECT_POLICY.initialDelayMs,
      maxReconnectDelay: RECONNECT_POLICY.maxDelayMs,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      debug: debug ? this.#onDebug : () => {},
      onConnect: () => {
        if (generation !== this.#generation) return;
        const reconnected = this.#hasConnected;
        this.#hasConnected = true;
        this.#onStatus('CONNECTED', reconnected ? '재연결됨' : '연결됨', { reconnected });
        const connectedClient = this.#client;
        this.#subscribe(connectedClient, generation, PUBLIC_SUBSCRIPTIONS);
        if (token) this.#subscribe(connectedClient, generation, PRIVATE_SUBSCRIPTIONS);
      },
      onStompError: (frame) => {
        if (generation !== this.#generation) return;
        if (isAuthenticationFailure(frame)) {
          const expiredClient = this.#client;
          this.#allowReconnect = false;
          this.#client = null;
          this.#generation += 1;
          this.#hasConnected = false;
          void expiredClient?.deactivate();
          this.#onStatus('AUTH_EXPIRED', '인증이 만료되었거나 거부되었습니다. 다시 로그인하세요.');
          return;
        }
        this.#onStatus('ERROR', frame.headers.message || frame.body || 'STOMP error');
      },
      onWebSocketError: () => {
        if (generation === this.#generation && this.#allowReconnect) {
          this.#onStatus('RECONNECTING', '연결 실패 · 자동 재시도 중');
        }
      },
      onWebSocketClose: () => {
        if (generation === this.#generation && this.#allowReconnect) {
          this.#onStatus('RECONNECTING', '연결 끊김 · 1~30초 자동 재시도');
        }
      },
    });
    this.#client.activate();
  }

  disconnect() {
    const active = this.#client;
    this.#client = null;
    this.#generation += 1;
    this.#allowReconnect = false;
    this.#hasConnected = false;
    if (active) void active.deactivate();
    this.#onStatus('DISCONNECTED');
  }

  #subscribe(client, generation, subscriptions) {
    subscriptions.forEach(([destination, channel]) => {
      client.subscribe(destination, (message) => {
        if (generation !== this.#generation || client !== this.#client) return;
        try {
          this.#onEvent(channel, JSON.parse(message.body));
        } catch {
          this.#onEvent(channel, {
            type: 'INVALID_JSON',
            occurredAt: new Date().toISOString(),
            data: { raw: message.body },
          });
        }
      });
    });
  }
}

function webSocketUrl(path) {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${path}`;
}
