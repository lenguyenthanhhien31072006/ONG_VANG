import assert from 'node:assert/strict';
import { randomInt, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const base = (process.env.API_BASE_URL || 'http://127.0.0.1:18081').replace(/\/$/, '');
const health = await (await fetch(base + '/api/health')).json();
assert.equal(health.databaseName, 'mini_ong_vang_test', 'Dispatch smoke writes fixtures: dedicated test DB required');
assert(process.env.DEMO_PASSWORD, 'Seed isolated DB with DEMO_PASSWORD before dispatch smoke');
const spec = JSON.parse(readFileSync(new URL('../../src/backend/src/main/webapp/openapi.json', import.meta.url), 'utf8'));
const ajv = new Ajv2020({ strict: false }); addFormats(ajv);
const schemas = Object.fromEntries(['Order', 'OrderDetail', 'OrderPage', 'DriverPage', 'CustomerPage', 'Error'].map(name => [name,
  ajv.compile({ $ref: '#/components/schemas/' + name, components: spec.components })]));
let checks = 0;
class Client {
  cookie = ''; csrf = '';
  async raw(path, body, headers = {}) {
    const response = await fetch(base + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Cookie: this.cookie, 'Content-Type': 'application/json', 'X-CSRF-Token': this.csrf, ...headers },
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(20000)
    });
    for (const cookie of response.headers.getSetCookie()) if (cookie.startsWith('JSESSIONID=')) this.cookie = cookie.split(';')[0];
    return { status: response.status, body: await response.json() };
  }
  async call(path, body, expected = 200, schema, code, headers) {
    const r = await this.raw(path, body, headers);
    assert.equal(r.status, expected, `${path}: ${JSON.stringify(r.body)}`);
    if (schema) assert(schemas[schema](r.body), `${path}: ${JSON.stringify(schemas[schema].errors)}`);
    if (code) assert.equal(r.body.code, code);
    checks++; return r.body;
  }
  async login(username, password) {
    this.csrf = (await this.call('/api/auth/csrf')).csrfToken;
    const user = await this.call('/api/auth/login', { username, password });
    this.csrf = (await this.call('/api/auth/csrf')).csrfToken; return user;
  }
  async headWithBody(path, body) {
    // fetch forbids HEAD bodies; raw HTTP reproduces Servlet HEAD -> doGet routing.
    const url = new URL(base + path), payload = JSON.stringify(body);
    const status = await new Promise((resolve, reject) => {
      const req = (url.protocol === 'https:' ? httpsRequest : httpRequest)(url, {
        method: 'HEAD', headers: { Cookie: this.cookie, 'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(payload) } // Deliberately no CSRF token.
      }, response => { response.resume(); response.on('end', () => resolve(response.statusCode)); });
      req.setTimeout(15000, () => req.destroy(new Error('HEAD regression request timed out')));
      req.on('error', reject); req.end(payload);
    });
    assert.equal(status, 404, 'HEAD on a write-only path must not run assignment/rejection'); checks++;
  }
}
const anon = new Client(), customer = new Client(), dispatcher = new Client(), driver1 = new Client(), driver2 = new Client();
await anon.call('/api/orders', undefined, 401, 'Error', 'AUTH_REQUIRED');
await anon.call('/api/orders/missing/driver-suggestions', undefined, 401, 'Error', 'AUTH_REQUIRED');
customer.csrf = (await customer.call('/api/auth/csrf')).csrfToken;
const username = '0' + randomInt(100000000, 999999999), password = randomUUID();
await customer.call('/api/auth/register', { hoTen: 'T11 HTTP test', soDienThoai: username, password }, 201);
await customer.login(username, password);
await dispatcher.login('demo_dispatcher', process.env.DEMO_PASSWORD);
await driver1.login('0800000001', process.env.DEMO_PASSWORD);
await driver2.login('0800000002', process.env.DEMO_PASSWORD);
await anon.call('/api/customers', undefined, 401, 'Error', 'AUTH_REQUIRED');
await customer.call('/api/customers', undefined, 403, 'Error', 'FORBIDDEN');
await driver1.call('/api/customers', undefined, 403, 'Error', 'FORBIDDEN');
const customers = await dispatcher.call('/api/customers?size=1', undefined, 200, 'CustomerPage');
assert(customers.totalElements >= 4); // Three seed customers and the newly registered test customer.
assert.equal(customers.totalPages, customers.totalElements);
assert.equal(customers.items.length, 1);
const selectedCustomer = await dispatcher.call('/api/customers?q=0900000002', undefined, 200, 'CustomerPage');
assert.deepEqual(selectedCustomer.items.map(c => c.maKh), ['KH-DEMO-2']);
assert.equal(selectedCustomer.items[0].hangThanhVien.maHang, 'VANG');
await dispatcher.call('/api/customers?q=not-found', undefined, 200, 'CustomerPage');
await dispatcher.call('/api/customers?page=-1', undefined, 400, 'Error', 'VALIDATION_ERROR');
await dispatcher.call('/api/customers?size=101', undefined, 400, 'Error', 'VALIDATION_ERROR');
await dispatcher.call('/api/customers?q=', undefined, 400, 'Error', 'VALIDATION_ERROR');
const quoteInput = { diemLayHang: 'Điểm mẫu A', diemGiaoHang: 'Điểm mẫu B', kienHang: [{ loaiHangHoa: 'Hoa', khoiLuongKg: '1.00' }] };
async function create() {
  const quote = await customer.call('/api/quotes', quoteInput);
  return customer.call('/api/orders', { ...quoteInput, maBaoGia: quote.maBaoGia, sdtNguoiNhan: '0901234567' }, 201, 'Order', undefined, { 'Idempotency-Key': randomUUID() });
}
const order = await create(), path = '/api/orders/' + order.maDon;
await customer.call('/api/drivers', undefined, 403, 'Error', 'FORBIDDEN');
await driver1.call(path + '/driver-suggestions', undefined, 403, 'Error', 'FORBIDDEN');
await dispatcher.call('/api/drivers?dangBanChuyen=wrong', undefined, 400, 'Error', 'VALIDATION_ERROR');
await dispatcher.call('/api/drivers?size=101', undefined, 400, 'Error', 'VALIDATION_ERROR');
await dispatcher.call('/api/orders?tuNgay=2026-02-30', undefined, 400, 'Error', 'DATE_RANGE_INVALID');
await dispatcher.call('/api/orders?tuNgay=2026-10-09&denNgay=2026-10-09', undefined, 400, 'Error', 'DATE_RANGE_INVALID');
await dispatcher.call('/api/orders?page=-1', undefined, 400, 'Error', 'VALIDATION_ERROR');
await dispatcher.call(path + '/assignments', { maTx: 'TX-DEMO-1' }, 403, 'Error', 'CSRF_INVALID', { 'X-CSRF-Token': '' });
await customer.call(path + '/assignments', { maTx: 'TX-DEMO-1' }, 403, 'Error', 'FORBIDDEN');
await dispatcher.call(path + '/assignments', '{', 400, 'Error', 'JSON_INVALID');
await dispatcher.call(path + '/assignments', { maTx: 'TX-DEMO-1', force: true }, 400, 'Error', 'VALIDATION_ERROR');
// TX4: missing Goong key must never accept invented coordinates or advertise road ranking.
const demoLocation = '/api/drivers/TX-DEMO-1/demo-location';
await anon.call(demoLocation, { diaChi: '1 Công xã Paris, Quận 1, TP.HCM' }, 401, 'Error', 'AUTH_REQUIRED');
await customer.call(demoLocation, { diaChi: 'test' }, 403, 'Error', 'FORBIDDEN');
await driver1.call(demoLocation, { diaChi: 'test' }, 403, 'Error', 'FORBIDDEN');
await dispatcher.call(demoLocation, { diaChi: 'test' }, 403, 'Error', 'CSRF_INVALID', { 'X-CSRF-Token': '' });
await dispatcher.call(demoLocation, { diaChi: 'test', viDoDemo: '10.77' }, 400, 'Error', 'VALIDATION_ERROR');
await dispatcher.call(demoLocation, { diaChi: '   ' }, 400, 'Error', 'VALIDATION_ERROR');
await dispatcher.call(demoLocation, { diaChi: 'test' }, 503, 'Error', 'GOONG_NOT_CONFIGURED');
await dispatcher.headWithBody(demoLocation, { diaChi: 'test' });
const suggestions = await dispatcher.call(path + '/driver-suggestions', undefined, 200, 'DriverPage');
assert.equal(suggestions.cachSapXep, 'THOI_GIAN_RANH_DU_PHONG');
assert.equal(suggestions.lyDoDuPhong, 'GOONG_NOT_CONFIGURED');
assert(suggestions.items.every(d => d.nguonLoTrinh === null && d.quangDuongDenDiemLayKm === null));
assert(suggestions.items.every(d => d.nguonViTri === null && d.viTriCapNhatLuc === null));
checks += 4;

assert.deepEqual(suggestions.items.map(d => d.maTx), ['TX-DEMO-3', 'TX-DEMO-2', 'TX-DEMO-1']);
const free = await dispatcher.call('/api/drivers?trangThaiHoatDong=ONLINE&dangBanChuyen=false', undefined, 200, 'DriverPage');
assert.equal(free.totalElements, 3);
assert(free.items.every(d => !('cccd' in d) && !d.dangBanChuyen));
await dispatcher.call('/api/orders?size=1', undefined, 200, 'OrderPage');
await customer.call(path, undefined, 200, 'OrderDetail');
await driver1.call(path, undefined, 404, 'Error', 'RESOURCE_NOT_FOUND');
await dispatcher.headWithBody(path + '/assignments', { maTx: 'TX-DEMO-1' });
assert.equal((await customer.call(path, undefined, 200, 'OrderDetail')).trangThai, 'CHO_GAN');
const assignments = await Promise.all(Array.from({ length: 8 }, () => dispatcher.raw(path + '/assignments', { maTx: 'TX-DEMO-1' })));
assert.equal(assignments.filter(r => r.status === 200).length, 1);
assert.equal(assignments.filter(r => r.status === 409 && r.body.code === 'ORDER_STATE_CONFLICT').length, 7);
assignments.forEach(r => { assert(schemas[r.status === 200 ? 'Order' : 'Error'](r.body)); checks++; });
const assigned = await driver1.call(path, undefined, 200, 'OrderDetail');
assert.equal(assigned.maTx, 'TX-DEMO-1'); assert.equal(assigned.trangThai, 'DA_GAN'); assert.deepEqual(assigned.cuoc, order.cuoc);
const driverPage = await driver1.call('/api/orders?trangThai=DA_GAN', undefined, 200, 'OrderPage');
assert.deepEqual(driverPage.items.map(o => o.maDon), [order.maDon]);
await dispatcher.call(path + '/driver-suggestions', undefined, 409, 'Error', 'ORDER_STATE_CONFLICT');
await driver2.call(path + '/reject', { lyDo: 'Xe hỏng' }, 404, 'Error', 'RESOURCE_NOT_FOUND');
await dispatcher.call(path + '/reject', { lyDo: 'Xe hỏng' }, 403, 'Error', 'FORBIDDEN');
await driver1.call(path + '/reject', { lyDo: 'Xe hỏng' }, 403, 'Error', 'CSRF_INVALID', { 'X-CSRF-Token': '' });
await driver1.call(path + '/reject', { lyDo: '  ' }, 400, 'Error', 'VALIDATION_ERROR');
await driver1.call(path + '/reject', { lyDo: 'x'.repeat(501) }, 400, 'Error', 'VALIDATION_ERROR');
await driver1.headWithBody(path + '/reject', { lyDo: 'HEAD must never reject' });
assert.equal((await driver1.call(path, undefined, 200, 'OrderDetail')).trangThai, 'DA_GAN');
const rejected = await driver1.call(path + '/reject', { lyDo: 'Xe hỏng' }, 200, 'Order');
assert.equal(rejected.trangThai, 'CHO_GAN'); assert.equal(rejected.maTx, null);
await driver1.call(path, undefined, 404, 'Error', 'RESOURCE_NOT_FOUND');
await dispatcher.call(path + '/assignments', { maTx: 'TX-DEMO-1' }, 409, 'Error', 'DRIVER_UNAVAILABLE');
const next = await dispatcher.call(path + '/driver-suggestions', undefined, 200, 'DriverPage');
assert.deepEqual(next.items.map(d => d.maTx), ['TX-DEMO-3', 'TX-DEMO-2']);
await dispatcher.call(path + '/assignments', { maTx: 'TX-DEMO-2' }, 200, 'Order');
const competitors = []; for (let i = 0; i < 8; i++) competitors.push(await create());
const races = await Promise.all(competitors.map(o => dispatcher.raw(`/api/orders/${o.maDon}/assignments`, { maTx: 'TX-DEMO-1' })));
assert.equal(races.filter(r => r.status === 200).length, 1);
assert.equal(races.filter(r => r.status === 409 && r.body.code === 'DRIVER_UNAVAILABLE').length, 7);
races.forEach(r => { assert(schemas[r.status === 200 ? 'Order' : 'Error'](r.body)); checks++; });
const busy = await driver1.call('/api/orders?trangThai=DA_GAN', undefined, 200, 'OrderPage');
assert.equal(busy.items.length, 1); assert.equal(busy.totalElements, 1);
const remaining = competitors.filter(o => o.maDon !== busy.items[0].maDon);
const thirdDriver = await dispatcher.call(`/api/orders/${remaining[0].maDon}/driver-suggestions`, undefined, 200, 'DriverPage');
assert.deepEqual(thirdDriver.items.map(d => d.maTx), ['TX-DEMO-3']);
await dispatcher.call(`/api/orders/${remaining[0].maDon}/assignments`, { maTx: 'TX-DEMO-3' }, 200, 'Order');
const none = await dispatcher.call(`/api/orders/${remaining[1].maDon}/driver-suggestions`, undefined, 200, 'DriverPage');
assert.deepEqual(none.items, []); assert.equal(none.totalElements, 0);
console.log(`PASS: ${checks} T11 HTTP checks; role/session/CSRF, schema, filtering, assignment/rejection and two 8-request races.`);
