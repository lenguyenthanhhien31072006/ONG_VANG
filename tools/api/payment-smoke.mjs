import assert from 'node:assert/strict';
import { randomInt, randomUUID } from 'node:crypto';

const base = (process.env.API_BASE_URL || 'http://127.0.0.1:18081').replace(/\/$/, '');
const health = await (await fetch(base + '/api/health')).json();
assert.equal(health.databaseName, 'mini_ong_vang_test', 'Payment smoke requires the isolated test DB');
assert(process.env.DEMO_PASSWORD, 'Set DEMO_PASSWORD for seeded dispatcher and driver');
let checks = 0;

class Client {
  cookie = ''; csrf = '';
  async call(path, body, expected = 200, code, headers = {}) {
    const response = await fetch(base + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Cookie: this.cookie, 'Content-Type': 'application/json', 'X-CSRF-Token': this.csrf, ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(20000)
    });
    for (const cookie of response.headers.getSetCookie())
      if (cookie.startsWith('JSESSIONID=')) this.cookie = cookie.split(';')[0];
    const result = await response.json();
    assert.equal(response.status, expected, `${path}: ${JSON.stringify(result)}`);
    if (code) assert.equal(result.code, code);
    checks++; return result;
  }
  async login(username, password) {
    this.csrf = (await this.call('/api/auth/csrf')).csrfToken;
    await this.call('/api/auth/login', { username, password });
    this.csrf = (await this.call('/api/auth/csrf')).csrfToken;
  }
}

const customer = new Client(), dispatcher = new Client(), driver = new Client(), other = new Client();
customer.csrf = (await customer.call('/api/auth/csrf')).csrfToken;
const username = '0' + randomInt(100000000, 999999999), password = randomUUID();
await customer.call('/api/auth/register', { hoTen: 'T14 HTTP test', soDienThoai: username, password, email: `${username}@example.test` }, 201);
await customer.login(username, password);
await dispatcher.login('demo_dispatcher', process.env.DEMO_PASSWORD);
await driver.login('0800000001', process.env.DEMO_PASSWORD);
await other.login('0800000002', process.env.DEMO_PASSWORD);

const input = { diemLayHang: 'Điểm mẫu A', diemGiaoHang: 'Điểm mẫu B',
  kienHang: [{ loaiHangHoa: 'Hoa', khoiLuongKg: '1.00' }] };
const quote = await customer.call('/api/quotes', input);
const order = await customer.call('/api/orders', { ...input, maBaoGia: quote.maBaoGia,
  sdtNguoiNhan: '0901234567' }, 201, undefined, { 'Idempotency-Key': randomUUID() });
const path = '/api/orders/' + order.maDon;
const request = { phuongThuc: 'TIEN_MAT' }, key = randomUUID();
await customer.call(path + '/payments', request, 403, 'FORBIDDEN', { 'Idempotency-Key': key });
await dispatcher.call(path + '/assignments', { maTx: 'TX-DEMO-1' });
await driver.call(path + '/payments', request, 409, 'PAYMENT_MILESTONE_REQUIRED', { 'Idempotency-Key': key });
await driver.call(path + '/transitions', { trangThai: 'DA_LAY_HANG' });
await driver.call(path + '/transitions', { trangThai: 'DANG_GIAO' }, 409, 'PAYMENT_REQUIRED');
const payment = await driver.call(path + '/payments', request, 201, undefined, { 'Idempotency-Key': key });
assert.equal(payment.trangThai, 'CHO_XU_LY');
assert.equal((await driver.call(path + '/payments', request, 201, undefined,
  { 'Idempotency-Key': key })).maGiaoDich, payment.maGiaoDich);
await driver.call(path + '/payments', request, 409, 'PAYMENT_PENDING',
  { 'Idempotency-Key': randomUUID() });
assert.equal((await customer.call(path + '/payments')).totalElements, 1);
await customer.call('/api/payments/' + payment.maGiaoDich + '/confirm-cash',
  { soTienDaThu: payment.soTien }, 403, 'FORBIDDEN');
await other.call('/api/payments/' + payment.maGiaoDich + '/confirm-cash',
  { soTienDaThu: payment.soTien }, 403, 'FORBIDDEN');
const confirmed = await driver.call('/api/payments/' + payment.maGiaoDich + '/confirm-cash',
  { soTienDaThu: payment.soTien });
assert.equal(confirmed.daXacNhanDu, true);
assert.equal((await customer.call('/api/payments/' + payment.maGiaoDich)).soTienThucNhan, payment.soTien);
assert.equal((await customer.call(path)).thanhToan.trangThai, 'DA_THANH_TOAN');
await dispatcher.call('/api/payments/' + payment.maGiaoDich + '/confirm-cash',
  { soTienDaThu: payment.soTien }, 403, 'FORBIDDEN');
assert.equal((await driver.call('/api/payments/' + payment.maGiaoDich + '/confirm-cash',
  { soTienDaThu: payment.soTien })).maGiaoDich, payment.maGiaoDich);
await driver.call(path + '/payments', request, 409, 'PAYMENT_ALREADY_PAID',
  { 'Idempotency-Key': randomUUID() });
await driver.call(path + '/transitions', { trangThai: 'DANG_GIAO' });
await driver.call(path + '/arrival', {});
await driver.call(path + '/transitions', { trangThai: 'HOAN_TAT' });
// Fare completion and earnings must remain available without remittance tables.
const from = '2000-01-01', to = '2100-01-01';
const earnings = await driver.call(`/api/drivers/me/earnings?tuNgay=${from}&denNgay=${to}`);
assert(Number(earnings.phanTaiXePhatSinh) > 0);
assert(!('thuNhapDangChoCod' in earnings));
const removed = await fetch(base + '/api/cod/drivers', { headers: { Cookie: dispatcher.cookie } });
assert.equal(removed.status, 404);
checks++;
console.log(`T14 payment flow: ${checks} HTTP checks passed`);
