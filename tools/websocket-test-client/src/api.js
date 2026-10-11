const JSON_HEADERS = { 'Content-Type': 'application/json' };

export class ApiClient {
  #token = null;

  get token() {
    return this.#token;
  }

  setToken(token) {
    this.#token = token || null;
  }

  clearToken() {
    this.#token = null;
  }

  signup(loginId, password, nickname) {
    return this.#request('/api/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ loginId, password, nickname }),
    });
  }

  login(loginId, password) {
    return this.#request('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ loginId, password }),
    });
  }

  me() {
    return this.#request('/api/me');
  }

  agent(question, target = null, skillId = null) {
    return this.#request('/api/ai/agent/answers', {
      method: 'POST',
      body: JSON.stringify({ question, ...(target ? { target } : {}), ...(skillId ? { skillId } : {}) }),
    });
  }

  observation() { return this.#request('/api/ai/observability'); }
  startAudit() { return this.#request('/api/admin/trade-audits', { method: 'POST' }); }
  auditHistory(before = null) { return this.#request('/api/admin/trade-audits'+(before?'?before='+encodeURIComponent(before):'')); }
  auditDetail(id) { return this.#request('/api/admin/trade-audits/'+encodeURIComponent(id)); }
  auditItems(id, before = null, filters = {}) {
    const query = new URLSearchParams();
    if (before) query.set('before', String(before));
    for (const key of ['verdict', 'mode', 'side', 'orderId']) if (filters[key]) query.set(key, String(filters[key]));
    return this.#request('/api/admin/trade-audits/'+encodeURIComponent(id)+'/items'+(query.size?'?'+query:''));
  }
  reserveBaseline() { return this.#request('/api/admin/reserve-reconciliations/baseline'); }
  createReserveBaseline() { return this.#request('/api/admin/reserve-reconciliations/baseline', { method: 'POST' }); }
  reconcileAssets() { return this.#request('/api/admin/reserve-reconciliations', { method: 'POST' }); }
  reserveHistory() { return this.#request('/api/admin/reserve-reconciliations'); }
  reserveDetail(id) { return this.#request('/api/admin/reserve-reconciliations/'+encodeURIComponent(id)); }

  order(id) { return this.#request(`/api/orders/${encodeURIComponent(id)}`); }

  diagnoses(orderId = null, before = null) {
    const query = new URLSearchParams();
    if (orderId) query.set('orderId', String(orderId));
    if (before) query.set('before', String(before));
    return this.#request(`/api/ai/diagnoses${query.size ? `?${query}` : ''}`);
  }

  diagnosis(id) { return this.#request(`/api/ai/diagnoses/${encodeURIComponent(id)}`); }

  diagnoseOrder(orderId) {
    return this.#request('/api/ai/agent/answers', {
      method: 'POST', body: JSON.stringify({ question: '이 주문의 검토 필요 상태와 정산 근거를 조사해줘',
        target: { orderId }, skillId: 'settlement-debugging' }),
    });
  }

  market() {
    return this.#request('/api/markets/mSEC');
  }

  candles(interval, count = 100, before = null) {
    const query = new URLSearchParams({ interval, count: String(count) });
    if (before) query.set('before', before);
    return this.#request(`/api/markets/mSEC/candles?${query}`);
  }

  faucet(key) {
    return this.#request('/api/wallet/faucet', { method: 'POST', headers: { 'Idempotency-Key': key } });
  }
  faucetStatus() { return this.#request('/api/wallet/faucet/status'); }
  faucetClaim(key) { return this.#request('/api/wallet/faucet/claims', { headers: { 'Idempotency-Key': key } }); }
  faucetGrants(before=null) { return this.#request('/api/wallet/faucet/grants'+(before?'?before='+encodeURIComponent(before):'')); }
  faucetAdmin() { return this.#request('/api/admin/faucet'); }
  faucetAdminAccount(userId) { return this.#request('/api/admin/faucet/accounts/'+encodeURIComponent(userId)); }
  faucetAdminGrants(userId=null,before=null) {
    const query=new URLSearchParams();if(userId)query.set('userId',userId);if(before)query.set('before',before);
    return this.#request('/api/admin/faucet/grants'+(query.size?'?'+query:''));
  }

  portfolio() {
    return this.#request('/api/portfolio');
  }

  conditionalCapabilities() { return this.#request('/api/conditional-orders/capabilities'); }
  conditionalOrders(before=null) { return this.#request('/api/conditional-orders'+(before?'?before='+encodeURIComponent(before):'')); }
  conditionalAdmin(before=null) { return this.#request('/api/admin/conditional-orders'+(before?'?before='+encodeURIComponent(before):'')); }
  createConditional(request,key) { return this.#request('/api/conditional-orders',{method:'POST',headers:{'Idempotency-Key':key},body:JSON.stringify(request)}); }
  conditionalRequest(key) { return this.#request('/api/conditional-orders/requests',{headers:{'Idempotency-Key':key}}); }
  cancelConditional(id) { return this.#request('/api/conditional-orders/'+encodeURIComponent(id)+'/cancel',{method:'POST'}); }

  orders() {
    return this.#request('/api/orders');
  }

  trades() {
    return this.#request('/api/trades');
  }

  quoteBuy(krwAmount) {
    return this.#request('/api/quotes/buy', {
      method: 'POST',
      body: JSON.stringify({ symbol: 'mSEC', krwAmount }),
    });
  }

  quoteSell(tokenAmount) {
    return this.#request('/api/quotes/sell', {
      method: 'POST',
      body: JSON.stringify({ symbol: 'mSEC', tokenAmount }),
    });
  }

  buy(krwAmount, quoteId = null) {
    return this.#request('/api/orders/buy', {
      method: 'POST',
      body: JSON.stringify({ symbol: 'mSEC', krwAmount, quoteId }),
    });
  }

  sell(tokenAmount, quoteId = null) {
    return this.#request('/api/orders/sell', {
      method: 'POST',
      body: JSON.stringify({ symbol: 'mSEC', tokenAmount, quoteId }),
    });
  }

  async #request(path, options = {}) {
    const headers = { ...JSON_HEADERS, ...(options.headers || {}) };
    if (this.#token) headers.Authorization = `Bearer ${this.#token}`;
    const response = await fetch(path, { ...options, headers });
    const text = await response.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = text;
      }
    }
    if (!response.ok) {
      const message = body?.message || `${response.status} ${response.statusText}`;
      throw new ApiError(message, response.status, body);
    }
    return { status: response.status, body };
  }
}

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}
