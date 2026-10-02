/** ADMIN-only display of historical observations; never interprets model output as HTML. */
export class DiagnosisPanel {
  constructor(api, elements) {
    this.api = api; this.elements = elements; this.generation = 0; this.admin = false; this.nextBefore = null;
  }
  clear() {
    this.generation += 1; this.admin = false; this.nextBefore = null;
    this.elements.panel.hidden = true; this.elements.output.textContent = '';
  }
  async authorize() {
    const generation = ++this.generation;
    this.admin = false; this.elements.panel.hidden = true;
    try {
      const result = await this.api.me();
      if (generation !== this.generation) return;
      this.admin = result.body?.role === 'ADMIN';
      this.elements.panel.hidden = !this.admin;
    } catch { if (generation === this.generation) this.clear(); }
  }
  async run(operation) {
    if (!this.admin) return;
    const generation = this.generation;
    try {
      const result = await operation();
      if (generation === this.generation && this.admin) {
        this.elements.output.textContent = JSON.stringify(result.body, null, 2);
        return result.body;
      }
    } catch (error) {
      if (generation === this.generation && this.admin) this.elements.output.textContent = `진단 조회 실패: ${error.status || ''} ${error.message}`;
    }
  }
  async list(before = null) {
    const orderId = this.orderId(false);
    if (this.elements.order.value.trim() && orderId === null) return;
    const result = await this.run(() => this.api.diagnoses(orderId, before));
    if (result) this.nextBefore = result.nextBefore || null;
  }
  older() { if (this.nextBefore) return this.list(this.nextBefore); }
  detail() {
    const id = Number(this.elements.id.value);
    if (!Number.isSafeInteger(id) || id <= 0) { this.elements.output.textContent = '진단 ID를 입력하세요.'; return; }
    return this.run(() => this.api.diagnosis(id));
  }
  manual() {
    const orderId = this.orderId(true); if (!orderId) return;
    return this.run(() => this.api.diagnoseOrder(orderId));
  }
  orderId(required) {
    if (!required && !this.elements.order.value.trim()) return null;
    const id = Number(this.elements.order.value);
    if (!Number.isSafeInteger(id) || id <= 0) { this.elements.output.textContent = '양의 정수 주문 ID를 입력하세요.'; return null; }
    return id;
  }
}
