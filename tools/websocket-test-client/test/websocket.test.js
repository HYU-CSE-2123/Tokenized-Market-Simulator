import test from 'node:test';
import assert from 'node:assert/strict';
import { ReconnectionTimeMode } from '@stomp/stompjs';
import { MarketSocket } from '../src/websocket.js';

function socketFixture() {
  const statuses = [];
  const events = [];
  const clients = [];
  const socket = new MarketSocket({
    onStatus: (...args) => statuses.push(args),
    onEvent: (...args) => events.push(args),
    onDebug: () => {},
    clientFactory: (config) => {
      const client = {
        config,
        subscriptions: [],
        activated: false,
        deactivated: false,
        activate() { this.activated = true; },
        deactivate() { this.deactivated = true; return Promise.resolve(); },
        subscribe(destination, callback) { this.subscriptions.push({ destination, callback }); },
      };
      clients.push(client);
      return client;
    },
  });
  return { socket, statuses, events, clients };
}

test('configures bounded exponential reconnect and distinguishes reconnect', () => {
  const { socket, statuses, clients } = socketFixture();
  socket.connect({ transport: 'native', token: 'jwt', debug: false });
  const client = clients[0];
  assert.equal(client.config.reconnectDelay, 1000);
  assert.equal(client.config.maxReconnectDelay, 30000);
  assert.equal(client.config.reconnectTimeMode, ReconnectionTimeMode.EXPONENTIAL);

  client.config.onConnect();
  client.config.onWebSocketClose();
  client.config.onConnect();

  assert.deepEqual(statuses.at(-1), ['CONNECTED', '재연결됨', { reconnected: true }]);
  assert.deepEqual(client.subscriptions.map(({ destination }) => destination), [
    '/topic/markets/mSEC/price', '/topic/markets/mSEC/trades',
    '/user/queue/orders', '/user/queue/portfolio',
    '/topic/markets/mSEC/price', '/topic/markets/mSEC/trades',
    '/user/queue/orders', '/user/queue/portfolio',
  ]);
});

test('ignores a late event from a replaced authenticated connection', () => {
  const { socket, clients, events } = socketFixture();
  socket.connect({ transport: 'native', token: 'user-a', debug: false });
  const first = clients[0];
  first.config.onConnect();
  const oldOrderSubscription = first.subscriptions
    .find(({ destination }) => destination === '/user/queue/orders');

  socket.connect({ transport: 'native', token: 'user-b', debug: false });
  clients[1].config.onConnect();
  oldOrderSubscription.callback({ body: JSON.stringify({ type: 'ORDER_FILLED', data: { orderId: 1 } }) });

  assert.deepEqual(events, []);
});

test('does not report reconnect after an explicit disconnect', () => {
  const { socket, statuses, clients } = socketFixture();
  socket.connect({ transport: 'native', token: null, debug: false });
  const client = clients[0];
  socket.disconnect();
  client.config.onWebSocketClose();
  assert.equal(client.deactivated, true);
  assert.deepEqual(statuses.at(-1), ['DISCONNECTED']);
});

test('stops reconnecting when STOMP authentication is rejected', () => {
  const { socket, statuses, events, clients } = socketFixture();
  socket.connect({ transport: 'native', token: 'expired', debug: false });
  const client = clients[0];
  client.config.onConnect();
  const oldOrderSubscription = client.subscriptions
    .find(({ destination }) => destination === '/user/queue/orders');
  client.config.onStompError({ headers: { message: '401 Unauthorized' }, body: '' });
  oldOrderSubscription.callback({ body: JSON.stringify({ type: 'ORDER_FILLED', data: { orderId: 1 } }) });
  client.config.onWebSocketClose();
  assert.equal(client.deactivated, true);
  assert.deepEqual(events, []);
  assert.deepEqual(statuses.at(-1), [
    'AUTH_EXPIRED', '인증이 만료되었거나 거부되었습니다. 다시 로그인하세요.',
  ]);
});
