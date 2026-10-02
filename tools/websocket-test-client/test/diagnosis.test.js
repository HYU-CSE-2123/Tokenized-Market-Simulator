import test from 'node:test';
import assert from 'node:assert/strict';
import { DiagnosisPanel } from '../src/diagnosis.js';

function fixture(role = 'ADMIN') {
  const elements = { panel: { hidden: true }, output: { textContent: '' }, order: { value: '153' }, id: { value: '1' } };
  const api = { me: async () => ({ body: { role } }), diagnoses: async () => ({ body: { answer: '<script>secret</script>' } }),
    diagnosis: async () => ({ body: { automaticallyModified: false } }), diagnoseOrder: async () => ({ body: { skill: 'settlement-debugging' } }) };
  return { api, elements, panel: new DiagnosisPanel(api, elements) };
}
test('ADMIN panel renders literal text, history and manual fixed Skill', async () => {
  const { panel, elements } = fixture(); await panel.authorize(); assert.equal(elements.panel.hidden, false);
  await panel.list(); assert.match(elements.output.textContent, /<script>/);
  await panel.detail(); assert.match(elements.output.textContent, /automaticallyModified/);
  await panel.manual(); assert.match(elements.output.textContent, /settlement-debugging/);
});
test('USER cannot read and logout discards stale diagnosis response', async () => {
  const user = fixture('USER'); await user.panel.authorize(); await user.panel.list(); assert.equal(user.elements.output.textContent, '');
  const { panel, api, elements } = fixture(); await panel.authorize();
  let resolve; api.diagnoses = () => new Promise((r) => { resolve = r; });
  const request = panel.list(); panel.clear(); resolve({ body: { secret: 'ADMIN_FACT' } }); await request;
  assert.equal(elements.output.textContent, ''); assert.equal(elements.panel.hidden, true);
});
test('invalid detail and manual target never call APIs', async () => {
  const { panel, api, elements } = fixture(); await panel.authorize();
  api.diagnosis = api.diagnoseOrder = () => { throw new Error('must not call'); };
  elements.id.value = '0'; elements.order.value = 'bad'; await panel.detail(); await panel.manual();
  assert.match(elements.output.textContent, /양의 정수/);
});
test('history uses server keyset cursor and clear removes it', async () => {
  const { panel, api } = fixture(); await panel.authorize(); const calls = [];
  api.diagnoses = async (order, before) => { calls.push([order, before]); return { body: { nextBefore: before ? null : 20 } }; };
  await panel.list(); await panel.older(); assert.deepEqual(calls, [[153, null], [153, 20]]);
  panel.clear(); assert.equal(panel.nextBefore, null);
});
