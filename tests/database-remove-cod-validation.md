# Kiểm tra bỏ nộp COD

Phạm vi: migration V13 xóa bốn bảng nộp COD; gỡ API, service, DAO, nhánh
callback và phần giao diện phụ thuộc. Giữ chia cước, thanh toán cước, điều phối.

Kết quả thực hiện trong môi trường cloud ngày 2026-10-17:

- Maven `verify`: PASS, biên dịch và đóng gói WAR; 20 test không cần DB pass.
- Frontend `npm run lint`: PASS.
- Frontend `npm run build`: PASS (Next.js production build và TypeScript).
- `npm run check --prefix tools/api`: PASS (OpenAPI, Postman, backend boundaries,
  2 test cấu hình Swagger/CSRF).
- `git diff --check`: PASS.
- Không đổi migration V1–V12, chỉ thêm V13.
- Không còn truy vấn bốn bảng COD trong mã chạy của Java/FE.

Chưa xác minh SQL Server/API end-to-end: Docker pull image
`mcr.microsoft.com/mssql/server:2022-latest` bị `Forbidden` sau các lần thử của Docker.
Integration suites yêu cầu DB không chạy trong lượt Maven trên; không tính chúng
là pass. Regression test mới đã được biên dịch nhưng chưa chạy với DB.

Khi có SQL Server, chạy CI backend với `REQUIRE_TEST_DB=true` để bắt buộc
integration test, sau đó chạy smoke orders/history/cancel/reports/payment/dispatch,
reset fixture rồi chạy progress và `scripts/check-design-v1-invariants.sql`.
Bài regression mới `removedCodTablesDoNotBreakCashCompletionRevenueAndDriverEarnings`
kiểm tra các bảng đã xóa và phần chia/thu nhập sau hoàn tất đơn tiền mặt.
`payment-smoke.mjs` còn kiểm tra API thu nhập và `/api/cod/drivers` trả 404.
