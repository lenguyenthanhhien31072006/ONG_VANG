import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";

const spec = JSON.parse(readFileSync("../backend/src/main/webapp/openapi.json", "utf8"));
const example = spec.paths["/api/orders/{maDon}"].get.responses["200"].content["application/json"].example;
const driver = spec.components.schemas.Driver.examples[0];
const id = "DH-TX4-TEST";

async function fixture(page: Page, { conflict = false, goong = false, role = "TONG_DAI", noDrivers = false } = {}) {
  let state = "CHO_GAN", writes = 0;
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    let body: unknown;
    if (path === "/api/auth/me") body = { maNguoiDung: "TK-TEST", hoTen: "Người kiểm thử", vaiTro: role, maKh: null, maTx: role === "TAI_XE" ? "TX1" : null };
    else if (path === "/api/auth/csrf") body = { csrfToken: "csrf-test" };
    else if (path.endsWith("/driver-suggestions")) body = {
      items: noDrivers ? [] : [{ ...driver, maTx: "TX1", hoTen: "Tài xế A", diaChiViTriDemo: goong ? "Địa chỉ đã xác minh thử nghiệm" : null,
        viDoDemo: goong ? "10.7700000" : null, kinhDoDemo: goong ? "106.7000000" : null, nguonViTri: goong ? "DEMO_CO_DINH" : null, viTriCapNhatLuc: goong ? "2026-10-14T00:00:00Z" : null,
        quangDuongDenDiemLayKm: goong ? "1.234" : null, thoiGianDenDiemLayGiay: goong ? 180 : null, nguonLoTrinh: goong ? "GOONG" : null }],
      page: 0, size: 100, totalElements: noDrivers ? 0 : 1, totalPages: noDrivers ? 0 : 1,
      cachSapXep: noDrivers ? "KHONG_CO_TAI_XE" : goong ? "GOONG_DEN_DIEM_LAY" : "THOI_GIAN_RANH_DU_PHONG", lyDoDuPhong: goong || noDrivers ? null : "GOONG_NOT_CONFIGURED",
    };
    else if (path.endsWith("/assignments")) {
      writes++;
      expect(route.request().headers()["x-csrf-token"]).toBe("csrf-test");
      expect(route.request().postDataJSON()).toEqual({ maTx: "TX1" });
      state = "DA_GAN";
      if (conflict) { await route.fulfill({ status: 409, json: { code: "ORDER_STATE_CONFLICT" } }); return; }
      body = { ...example, trangThai: state, maTx: "TX1" };
    } else if (path.endsWith("/demo-location")) {
      writes++;
      expect(route.request().postDataJSON()).toEqual({ diaChi: "Địa chỉ kiểm thử" });
      await route.fulfill({ status: 503, json: { code: "GOONG_NOT_CONFIGURED" } }); return;
    } else if (path === "/api/drivers") body = { items: [{ ...driver, maTx: "TX1", hoTen: "Tài xế A" }], page: 0, size: 20, totalElements: 1, totalPages: 1, cachSapXep: "TEN_TAI_XE", lyDoDuPhong: null };
    else if (path === "/api/orders") body = { items: [{ ...example, maDon: id, trangThai: "DA_GAN", maTx: "TX1" }], page: 0, size: 20, totalElements: 1, totalPages: 1, tongHop: { soDon: 1, tongCuoc: "45000.00" } };
    else if (path === `/api/orders/${id}`) body = { ...example, maDon: id, thoiGianTao: new Date().toISOString(), trangThai: state, maTx: state === "DA_GAN" ? "TX1" : null, phanCong: [], nhatKy: [] };
    else { await route.fulfill({ status: 404, json: { code: "RESOURCE_NOT_FOUND" } }); return; }
    await route.fulfill({ status: 200, json: body });
  });
  return { writes: () => writes };
}

test("fallback labeled; explicit choice required; one write despite double click", async ({ page }) => {
  const f = await fixture(page);
  await page.goto(`/dispatcher/live/orders/${id}`);
  await page.getByRole("button", { name: "Tìm tài xế khả dụng" }).click();
  await expect(page.getByText("Thứ tự dự phòng theo thời gian rảnh, không phải gần nhất theo Goong.", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Xác nhận gán", exact: true })).toBeDisabled();
  await page.getByRole("radio", { name: "Chọn tài xế TX1" }).check();
  await page.getByRole("button", { name: "Xác nhận gán", exact: true }).evaluate((el: HTMLButtonElement) => { el.click(); el.click(); });
  await expect(page.getByText("DA_GAN", { exact: true })).toBeVisible();
  expect(f.writes()).toBe(1);
  await expect(page.getByRole("button", { name: "Xác nhận gán", exact: true })).toHaveCount(0);
});

test("conflicting assignment reloads winner and preserves error without auto retry", async ({ page }) => {
  const f = await fixture(page, { conflict: true });
  await page.goto(`/dispatcher/live/orders/${id}`);
  await page.getByRole("button", { name: "Tìm tài xế khả dụng" }).click();
  await page.getByRole("radio", { name: "Chọn tài xế TX1" }).check();
  await page.getByRole("button", { name: "Xác nhận gán", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("Trạng thái đơn đã đổi");
  await expect(page.getByText("DA_GAN", { exact: true })).toBeVisible(); expect(f.writes()).toBe(1);
});

test("Goong suggestions show road distance and time to pickup with simulated position label", async ({ page }) => {
  await fixture(page, { goong: true }); await page.goto(`/dispatcher/live/orders/${id}`);
  await page.getByRole("button", { name: "Tìm tài xế khả dụng" }).click();
  await expect(page.getByText("1.234 km đến điểm lấy · 180 giây · GOONG")).toBeVisible();
  await expect(page.getByText(/Đây không phải GPS trực tiếp/)).toBeVisible();
  await expect(page.getByText(/Xếp gần nhất theo đường đi Goong/)).toBeVisible();
});

test("no eligible drivers offers no assignment", async ({ page }) => {
  await fixture(page, { noDrivers: true }); await page.goto(`/dispatcher/live/orders/${id}`);
  await page.getByRole("button", { name: "Tìm tài xế khả dụng" }).click();
  await expect(page.getByText(/Không có tài xế đủ điều kiện/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Xác nhận gán", exact: true })).toHaveCount(0);
});

test("location configuration error stays visible and does not pretend position was saved", async ({ page }) => {
  const f = await fixture(page); await page.goto("/dispatcher/live/fleet");
  await page.getByRole("button", { name: /Cấu hình vị trí/ }).click();
  await page.getByLabel(/Địa chỉ cố định/).fill("Địa chỉ kiểm thử");
  await page.getByRole("button", { name: /Xác minh/ }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("Chưa cấu hình Goong REST key");
  await expect(page.getByText(/Chưa có vị trí đã xác minh/)).toBeVisible(); expect(f.writes()).toBe(1);
});

test("driver reads API assigned order; no sample orders", async ({ page }) => {
  await fixture(page, { role: "TAI_XE" }); await page.goto("/driver/assigned");
  await expect(page.getByRole("link", { name: new RegExp(id) })).toBeVisible();
  await expect(page.getByText(/DH-2026/)).toHaveCount(0);
});
