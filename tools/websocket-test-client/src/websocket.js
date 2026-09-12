import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

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

  constructor({ onStatus, onEvent, onDebug }) {
    this.#onStatus = onStatus;
    this.#onEvent = onEvent;
    this.#onDebug = onDebug;
  }

  connect({ transport, token, debug }) {
    this.disconnect();
    this.#onStatus('CONNECTING');
    const connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    this.#client = new Client({
      webSocketFactory: transport === 'sockjs'
        ? () => new SockJS('/ws-sockjs')
        : () => new WebSocket(webSocketUrl('/ws')),
      connectHeaders,
      reconnectDelay: 0,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      debug: debug ? this.#onDebug : () => {},
      onConnect: () => {
        this.#onStatus('CONNECTED');
        this.#subscribe(PUBLIC_SUBSCRIPTIONS);
        if (token) this.#subscribe(PRIVATE_SUBSCRIPTIONS);
      },
      onStompError: (frame) => {
        this.#onStatus('ERROR', frame.headers.message || frame.body || 'STOMP error');
      },
      onWebSocketError: () => this.#onStatus('ERROR', 'WebSocket connection failed'),
      onWebSocketClose: () => this.#onStatus('DISCONNECTED'),
    });
    this.#client.activate();
  }

  disconnect() {
    const active = this.#client;
    this.#client = null;
    if (active) void active.deactivate();
    this.#onStatus('DISCONNECTED');
  }

  #subscribe(subscriptions) {
    subscriptions.forEach(([destination, channel]) => {
      this.#client.subscribe(destination, (message) => {
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
