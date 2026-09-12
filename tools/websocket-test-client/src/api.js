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

  faucet() {
    return this.#request('/api/wallet/faucet', { method: 'POST' });
  }

  portfolio() {
    return this.#request('/api/portfolio');
  }

  orders() {
    return this.#request('/api/orders');
  }

  buy(krwAmount) {
    return this.#request('/api/orders/buy', {
      method: 'POST',
      body: JSON.stringify({ symbol: 'mSEC', krwAmount }),
    });
  }

  sell(tokenAmount) {
    return this.#request('/api/orders/sell', {
      method: 'POST',
      body: JSON.stringify({ symbol: 'mSEC', tokenAmount }),
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
