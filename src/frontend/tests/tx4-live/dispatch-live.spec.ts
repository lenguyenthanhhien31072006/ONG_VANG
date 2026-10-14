import { test, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
const baseURL = "http://127.0.0.1:3101";
async function call(context: BrowserContext, path: string, body?: object, expected = 200, key?: string) {
  const csrf = body ? (await (await context.request.get(`${baseURL}/api/auth/csrf`)).json()).csrfToken : "";
  const response = body ? await context.request.post(baseURL + path, { data: body, headers: { "X-CSRF-Token": csrf, ...(key ? { "Idempotency-Key": key } : {}) } }) : await context.request.get(baseURL + path);
  expect(response.status(), `${path}: ${await response.text()}`).toBe(expected); return response.json();
}
async function session(browser: Browser, username: string) {
  const context = await browser.newContext({ baseURL });
  expect((await call(context,"/api/health")).databaseName).toBe("mini_ong_vang_test");
  expect(process.env.DEMO_PASSWORD, "Seed the isolated DB and set DEMO_PASSWORD").toBeTruthy();
  await call(context,"/api/auth/login",{ username,password: process.env.DEMO_PASSWORD }); return context;
}
async function order(context: BrowserContext) {
  const input={ diemLayHang:"Điểm mẫu A",diemGiaoHang:"Điểm mẫu B",kienHang:[{loaiHangHoa:"TX4 live",khoiLuongKg:"1.00"}] };
  const quote=await call(context,"/api/quotes",input);
  return (await call(context,"/api/orders",{...input,maBaoGia:quote.maBaoGia,sdtNguoiNhan:"0901234567"},201,randomUUID())).maDon as string;
}
async function select(page: Page,id: string,driver: string) {
  await page.goto(`/dispatcher/live/orders/${id}`); await page.getByRole("button",{name:"Tìm tài xế khả dụng"}).click();
  await page.getByRole("radio",{name:`Chọn tài xế ${driver}`,exact:true}).check();
}
async function assign(page: Page) {
  const pending=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith("/assignments")&&r.request().method()==="POST");
  await page.getByRole("button",{name:"Xác nhận gán",exact:true}).click(); return (await pending).status();
}

test("hotline form creates a real order visible in SQL queue",async({browser})=>{
  const context=await session(browser,"demo_dispatcher");
  try {
    const page=await context.newPage();await page.goto("/dispatcher/live/create-order");
    await page.getByPlaceholder("Tên hoặc SĐT khách").fill("0900000001");await page.getByRole("button",{name:"Tìm DB",exact:true}).click();
    await page.getByRole("combobox").filter({has:page.locator('option[value="KH-DEMO-1"]')}).selectOption("KH-DEMO-1");
    await page.getByLabel("Địa chỉ lấy hàng đầy đủ",{exact:true}).fill("Điểm mẫu A");
    await page.getByLabel("Địa chỉ giao hàng đầy đủ",{exact:true}).fill("Điểm mẫu B");
    await page.getByLabel("Tên người nhận",{exact:true}).fill("Người nhận TX4");
    await page.getByLabel("SĐT người nhận",{exact:true}).fill("0901234567");
    await page.getByLabel("Loại hàng hóa",{exact:true}).fill("TX4 live hotline");
    await page.getByRole("button",{name:"Kiểm tra địa chỉ và báo giá",exact:true}).click();
    const responsePromise=page.waitForResponse(r=>new URL(r.url()).pathname==="/api/orders"&&r.request().method()==="POST");
    await page.getByRole("button",{name:"Xác nhận tạo đơn",exact:true}).click();
    const response=await responsePromise;expect(response.status()).toBe(201);const created=await response.json();
    await expect(page).toHaveURL(new RegExp(`/dispatcher/live/orders/${created.maDon}$`));
    await page.goto("/dispatcher/live");await expect(page.getByRole("link",{name:new RegExp(created.maDon)})).toBeVisible();
    expect((await call(context,`/api/orders/${created.maDon}`)).trangThai).toBe("CHO_GAN");
    await call(context,`/api/orders/${created.maDon}/cancel`,{lyDo:"TX4 live cleanup"});
  } finally {await context.close();}
});

test("real UI assigns SQL order; correct driver alone sees it; cancel releases driver",async({browser})=>{
  const contexts=await Promise.all(["0900000001","demo_dispatcher","0800000001","0800000002"].map(u=>session(browser,u)));
  try {
    const [customer,dispatcher,driver1,driver2]=contexts; const id=await order(customer);const page=await dispatcher.newPage();
    await select(page,id,"TX-DEMO-1");expect(await assign(page)).toBe(200);
    await expect(page.getByText("DA_GAN",{exact:true})).toBeVisible();
    const driverPage=await driver1.newPage();await driverPage.goto("/driver/assigned");await expect(driverPage.getByRole("link",{name:new RegExp(id)})).toBeVisible();
    const other=await driver2.newPage();await other.goto("/driver/assigned");await expect(other.getByRole("link",{name:new RegExp(id)})).toHaveCount(0);
    await call(driver2,`/api/orders/${id}`,undefined,404);
    await page.getByLabel("Lý do hủy").fill("TX4 live cleanup");await page.getByRole("button",{name:"Hủy đơn",exact:true}).click();
    await expect(page.getByText("DA_HUY",{exact:true})).toBeVisible();
    expect((await call(dispatcher,"/api/drivers?dangBanChuyen=false")).items.some((d:{maTx:string})=>d.maTx==="TX-DEMO-1")).toBe(true);
  } finally {await Promise.all(contexts.map(c=>c.close()));}
});

test("two real dispatcher sessions assigning same order have one winner",async({browser})=>{
  const contexts=await Promise.all(["0900000001","demo_dispatcher","demo_dispatcher"].map(u=>session(browser,u)));
  try {
    const [customer,a,b]=contexts;const id=await order(customer);const pages=await Promise.all([a.newPage(),b.newPage()]);
    await Promise.all(pages.map(p=>select(p,id,"TX-DEMO-1")));
    expect((await Promise.all(pages.map(assign))).sort()).toEqual([200,409]);
    const detail=await call(a,`/api/orders/${id}`);expect(detail.phanCong.filter((p:{ketThucLuc:string|null})=>p.ketThucLuc===null)).toHaveLength(1);
    await call(a,`/api/orders/${id}/cancel`,{lyDo:"TX4 live cleanup"});
  } finally {await Promise.all(contexts.map(c=>c.close()));}
});

test("two real orders race for same driver and retain one active assignment",async({browser})=>{
  const contexts=await Promise.all(["0900000001","demo_dispatcher","demo_dispatcher"].map(u=>session(browser,u)));
  try {
    const [customer,a,b]=contexts;const ids=[await order(customer),await order(customer)];const pages=await Promise.all([a.newPage(),b.newPage()]);
    await Promise.all(pages.map((p,i)=>select(p,ids[i],"TX-DEMO-1")));
    expect((await Promise.all(pages.map(assign))).sort()).toEqual([200,409]);
    const details=await Promise.all(ids.map(id=>call(a,`/api/orders/${id}`)));
    expect(details.flatMap(d=>d.phanCong).filter(p=>p.ketThucLuc===null)).toHaveLength(1);
    for(const id of ids) await call(a,`/api/orders/${id}/cancel`,{lyDo:"TX4 live cleanup"});
  } finally {await Promise.all(contexts.map(c=>c.close()));}
});
