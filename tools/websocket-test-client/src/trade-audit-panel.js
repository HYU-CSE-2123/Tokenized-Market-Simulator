import { node, fields, button } from './ui.js';
import { time } from './state.js';

export function renderAuditRun(container, run) {
  container.replaceChildren(node('h3', `거래 기록 대사 · ${run.lifecycle} · ${run.verdict}`));
  const c = run.coverage || {};
  container.append(node('p', run.reason, 'notice'), fields([
    ['시작', time(run.startedAt)], ['고정 블록', run.block?.number ?? '확인 불가'],
    ['블록 hash', run.block?.hash ?? '확인 불가'], ['DB 시점', time(run.manifest?.at)],
    ['DB 전체 수집', c.dbComplete ? '완료' : '미완료'], ['체인 근거·역방향 스캔', c.chainComplete ? '완료' : '미완료'],
    ['안정된 관측', c.cutStable ? '확인' : '확보하지 못함'], ['환경 출처', c.environmentVerified ? '확인' : '검증 불가'],
    ['대상 주문', c.sourceOrders ?? 0], ['Vault 이벤트', c.scannedEvents ?? 0], ['RPC 호출', c.rpcCalls ?? 0],
    ['일치', run.match ?? 0], ['불일치', run.mismatch ?? 0], ['판정 불가', run.inconclusive ?? 0],
  ]));
  if (!c.dbComplete || !c.chainComplete || !c.cutStable || !c.environmentVerified || run.inconclusive) {
    container.append(node('p', '전체 검증이 완료되지 않았습니다. 일부 항목이 일치해도 전수 MATCH를 뜻하지 않습니다.', 'notice warning'));
  }
  container.append(node('p', '당시 고정 범위의 관측 결과입니다. DB_ONLY는 체인 검증 대상이 아니며 UNKNOWN은 추론하지 않습니다. 거래·잔고를 보정하지 않습니다.', 'muted'));
}

export function renderAuditItems(container, items) {
  container.replaceChildren();
  if (!items.length) container.append(node('p', '해당 범위의 항목이 없습니다.'));
  for (const i of items) {
    const detail = node('details');
    detail.append(node('summary', `${i.orderId ? `주문 #${i.orderId}` : i.kind} · ${i.mode} · ${i.side || '-'} · ${i.verdict}`),
      node('p', i.reason), fields([['거래 hash', i.txHash ?? '없음'], ['receipt 블록', i.receiptBlock ?? '확인 불가'], ['receipt hash', i.receiptHash ?? '없음']]));
    for (const check of i.checks || []) detail.append(node('h4', check.code), fields([
      ['예상', check.expected], ['관측', check.actual], ['비교', check.matches ? '일치' : '불일치'],
    ]));
    for (const limit of i.limitations || []) detail.append(node('p', limit, 'notice warning'));
    container.append(detail);
  }
}

export function createTradeAuditPanel(api) {
  const panel = document.getElementById('trade-audit-panel');
  if (!panel) return { setContext() {} };
  const get = (id) => document.getElementById(id);
  let actor = null, active = false, generation = 0, busy = false, timer = null;
  let selected = null, historyCursor = null, itemCursor = null;
  const cancel = () => { clearTimeout(timer); timer = null; };
  const clear = () => { for (const id of ['audit-result', 'audit-items', 'audit-history']) get(id).replaceChildren(); get('audit-message').textContent = ''; selected = null; historyCursor = null; itemCursor = null; };
  const controls = () => { for (const id of ['audit-run', 'audit-refresh', 'audit-history-next', 'audit-items-next', 'audit-filter-submit']) get(id).disabled = busy; get('audit-history-next').disabled = busy || !historyCursor; get('audit-items-next').disabled = busy || !itemCursor; };
  async function action(operation) {
    if (!active || actor?.role !== 'ADMIN' || busy) return;
    busy = true; const current = generation; controls();
    try { await operation(() => current === generation && active); }
    catch (error) { if (current === generation && active) get('audit-message').textContent = error.status === 503 ? '감사 기능이 꺼져 있거나 조회할 수 없습니다.' : error.status === 409 ? '감사가 이미 진행 중입니다. 이력을 조회하세요.' : '조회에 실패했습니다. 자동으로 다시 실행하지 않습니다.'; }
    finally { if (current === generation) { busy = false; controls(); } }
  }
  async function items(valid, before = null) {
    if (!selected) return;
    const filters = { verdict: get('audit-verdict').value, mode: get('audit-mode').value, side: get('audit-side').value, orderId: get('audit-order').value };
    const { body } = await api.auditItems(selected, before, filters);
    if (!valid()) return;
    renderAuditItems(get('audit-items'), body.items); itemCursor = body.nextBefore;
  }
  async function detail(id, valid, deadline = Date.now() + 150000) {
    const { body } = await api.auditDetail(id);
    if (!valid()) return;
    selected = id; renderAuditRun(get('audit-result'), body); itemCursor = null;
    if (body.lifecycle === 'RUNNING') {
      get('audit-message').textContent = '읽기 전용 감사가 진행 중입니다. 거래는 계속 사용할 수 있습니다.';
      if (Date.now() < deadline) timer = setTimeout(() => { timer = null; void action((v) => detail(id, v, deadline)); }, 1000);
      else get('audit-message').textContent = '화면의 대기 시간이 끝났습니다. 이력에서 종료 결과를 확인하세요.';
    } else { get('audit-message').textContent = '결과가 저장되었습니다. 과거 관측과 현재 상태를 구분하세요.'; await items(valid); }
  }
  async function history(valid, before = null) {
    const { body } = await api.auditHistory(before);
    if (!valid()) return;
    historyCursor = body.nextBefore; get('audit-history').replaceChildren(node('h3', '감사 실행 이력'));
    for (const run of body.items) get('audit-history').append(button(`${time(run.startedAt)} · ${run.lifecycle} · ${run.verdict}`, () => { cancel(); void action((v) => detail(run.id, v)); }));
  }
  get('audit-run').onclick = () => {
    if (!active || busy || actor?.role !== 'ADMIN' || !window.confirm('거래 기록을 읽기 전용으로 대사합니다. 새 실행을 시작할까요?')) return;
    cancel(); void action(async (valid) => { const { body } = await api.startAudit(); if (valid()) await detail(body.id, valid); });
  };
  get('audit-refresh').onclick = () => { cancel(); void action((v) => history(v)); };
  get('audit-history-next').onclick = () => { if (historyCursor) void action((v) => history(v, historyCursor)); };
  get('audit-items-next').onclick = () => { if (itemCursor) void action((v) => items(v, itemCursor)); };
  get('audit-filter').onsubmit = (event) => { event.preventDefault(); void action((v) => items(v)); };
  return { setContext(user, route) {
    const nextActive = user?.role === 'ADMIN' && route === 'admin';
    if (actor !== user || active !== nextActive) { cancel(); generation++; busy = false; clear(); actor = user; active = nextActive; controls(); }
    panel.hidden = !nextActive;
  } };
}
