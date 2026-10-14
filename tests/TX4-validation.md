# TX4 — Kế hoạch và bằng chứng kiểm thử

Ngày 14/10/2026. Nền tảng develop `1bccb67`, schema designV1 + V5 TX3 + **V6 TX4**. Không lấy dữ liệu PostgreSQL/schema cũ làm bằng chứng. Không có Goong REST key; không đánh dấu địa chỉ ứng viên là đã xác minh thật.

## Kết quả trên cloud

| Kiểm tra | Expected | Actual | Kết quả |
|---|---|---|---|
| Maven verify | Compile, unit test và WAR thành công | 20 unit/adapter test, 0 lỗi; WAR build thành công | PASS |
| OpenAPI/Postman/boundaries/CSRF | Contract mới đúng schema và artifact đồng bộ | 30 operations, 299 examples; 2 CSRF tests | PASS |
| FE lint/build | Không lỗi ESLint/TypeScript, tạo bản build | ESLint và production build thành công sau khi tách module bổ sung, giữ frontend gốc | PASS |
| Browser desktop/mobile với HTTP fixture | Nhãn nguồn, chọn trước gán, lỗi/race đọc lại, vị trí chưa lưu khi lỗi | Retest trên `/dispatcher/live` và `/driver/assigned`: 12/12 ca PASS | PASS |
| SQL integration, HTTP smoke và browser live | Dữ liệu/transaction/unique thực trên SQL Server | MCR pull `mcr.microsoft.com/mssql/server:2022-latest` bị Forbidden; chưa có image | BLOCKED / NOT RUN |
| Địa chỉ thật và tuyến Goong live | Lookup đúng, lưu tọa độ, đường tài xế → điểm lấy | Người dùng chưa có key | BLOCKED / NOT RUN |

JUnit DB classes có 0 test thực thi khi không có TEST_DB_URL. Không tính Maven xanh là SQL integration PASS. Adapter dùng HTTP server test và UI fixture không chứng minh Goong mạng thật hoặc dữ liệu SQL thật.

## Ca SQL và đồng thời

| Ca | Expected | Test |
|---|---|---|
| Vị trí dùng chung, quyền sửa, ranking trước paging | DB giữ đủ 5 trường; khách/tài xế không sửa; km gần nhất nằm trang đầu | `tx4LocationPersistsForAllSessionsAndRoutingRanksBeforePaging` |
| Geocode lỗi, CHECK tọa độ | Không ghi đè vị trí cũ; partial/null/out-of-range không hợp lệ | `tx4FailedGeocodeNeverOverwritesExistingLocationAndSqlRejectsPartialPosition` |
| Tài xế đổi khi đang routing | Không giữ transaction mạng; đọc lại, bỏ ứng viên offline, nhãn dự phòng | `tx4RoutingDoesNotHoldDatabaseTransactionAndRechecksChangesBeforeReturning` |
| Hai đơn cùng tài xế | Đúng một gán, một conflict, đúng một phân công mở | `tx4TwoOrdersRacingForSameDriverLeaveExactlyOneActiveAssignment` |
| Từ chối và gán lại | Chỉ người hiện được gán thấy DA_GAN | `tx4OnlyCurrentlyAssignedDriverSeesAssignedOrderAfterRejectionAndReassignment` |
| Hai phiên cùng đơn | Một thắng, một conflict | `concurrentAssignmentsLeaveOnlyOneActiveDriver` |
| Gán/hủy cùng lúc | Hủy thành công không còn phân công mở | `concurrentAssignmentAndCancellationLeaveNoActiveAssignment` |
| Tài khoản/xe đổi trước commit | Chờ khóa và kiểm tra lại, không gán sai | `assignmentRechecksAccountAndVehicleAfterConcurrentChange` |
| Online/GPLX/xe/từ chối | Không xuất hiện/gán người không hợp lệ | `dt12SuggestionsAndAssignmentRejectEveryIneligibleDriver`, `rejectedDriverCannotBeAssignedAgainToSameOrder` |

Các ca SQL trên đều NOT RUN trên cloud hiện tại, cần chạy trên máy có SQL image hoặc CI. Provider injected trong ca SQL chỉ kiểm chứng tích hợp/transaction, không chứng minh địa chỉ được Goong mạng thật xác minh.

## Chạy unit và browser fixture

Từ gốc repo:

```bash
mvn --batch-mode --no-transfer-progress -f src/backend/pom.xml verify
npm ci --ignore-scripts --prefix tools/api
npm run check --prefix tools/api
npm ci --prefix src/frontend
npm exec --prefix src/frontend -- playwright install chromium
npm run lint --prefix src/frontend
npm run build --prefix src/frontend
npm run test:tx4 --prefix src/frontend
```

Trên cloud có Chromium hệ thống dùng `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:tx4 --prefix src/frontend`. Fixture suite chạy 6 tình huống trên desktop và mobile; không gọi SQL, không gọi Goong thật.

## Chạy SQL và HTTP thật trên DB test riêng

Không chạy reset/seed test lên `mini_ong_vang_design_v1`. Mở Git Bash tại gốc repo, tự đặt mật khẩu test mạnh và password demo test:

```bash
export TEST_DB_PASSWORD='<mat-khau-test-manh-rieng>'
export DEMO_PASSWORD='<mat-khau-demo-test-rieng>'
export TEST_DB_URL='jdbc:sqlserver://127.0.0.1:15433;databaseName=mini_ong_vang_test;encrypt=true;trustServerCertificate=true'
export TEST_DB_USER=sa
export DB_URL="$TEST_DB_URL"
export DB_USER=sa
export DB_PASSWORD="$TEST_DB_PASSWORD"
export REQUIRE_TEST_DB=true
export API_BASE_URL=http://127.0.0.1:18081
unset GOONG_API_KEY
export ROUTE_PROVIDER=fake

docker compose -f docker-compose.ci.yml up --wait --wait-timeout 180 db-test
docker compose -f docker-compose.ci.yml up --no-deps --exit-code-from db-init-test db-init-test
mvn --batch-mode --no-transfer-progress -f src/backend/pom.xml verify
```

`REQUIRE_TEST_DB=true` buộc lỗi nếu DB chưa cấu hình. Kiểm tra surefire thật có chạy các method SQL, không chỉ thấy BUILD SUCCESS. Trước HTTP smoke, chuẩn bị fixture sạch như workflow CI (reset chỉ DB test):

```bash
docker compose -f docker-compose.ci.yml stop backend-test
MSYS_NO_PATHCONV=1 docker compose -f docker-compose.ci.yml exec -T db-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b -d mini_ong_vang_test < scripts/reset-test-db.sql
mvn --batch-mode --no-transfer-progress -f src/backend/pom.xml exec:java -Dexec.mainClass=com.miniongvang.seed.SeedCommand -Dexec.args=test
docker compose -f docker-compose.ci.yml up --build --wait --wait-timeout 120 backend-test
npm run smoke:dispatch --prefix tools/api
```

Smoke gồm schema mới, fallback thiếu key, quyền/CSRF, từ chối body có tọa độ, HEAD không ghi và 2 cuộc đua 8 requests. Sau smoke, reset/seed DB test lại theo block trên trước browser live để có tài xế rảnh ban đầu.

```bash
BACKEND_INTERNAL_URL=http://127.0.0.1:18081 npm run build --prefix src/frontend
npm run test:tx4:live --prefix src/frontend
MSYS_NO_PATHCONV=1 docker compose -f docker-compose.ci.yml exec -T db-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b -d mini_ong_vang_test < scripts/check-design-v1-invariants.sql
```

Browser live không intercept API: 4 ca tạo đơn hotline/hàng đợi; gán/đọc quyền/hủy; hai phiên cùng đơn; hai đơn cùng tài xế. Assert health đúng `mini_ong_vang_test`, số phân công qua API. SQL invariants cuối cùng phải `designV1 invariant reconciliation passed`.

## Kiểm chứng Goong thật sau khi có key

Theo [hướng dẫn chạy TX4](../docs/TX4-implementation.md), cấu hình key chỉ backend, geocode và lưu từng vị trí qua đội xe. Ghi địa chỉ input/địa chỉ chuẩn hóa/lat/lng/nguồn/thời điểm và kết quả SQL SELECT; mở hai phiên so sánh. Tạo đơn điểm lấy thật, lấy gợi ý, ghi km/giây/GOONG và thứ tự theo mét (hòa maTx). Thiếu vị trí/key hoặc lỗi/quota phải hiện lý do dự phòng và metrics NULL. Chỉ sau các bước này mới đổi hàng Goong live thành PASS, kèm commit và log đã bỏ secrets.

## Bug và retest

- Test locator `alert` ban đầu trùng route announcer Next.js; scope vào `main`, giữ nguyên assertion nội dung lỗi.
- Form vị trí ban đầu bị ẩn sau lỗi POST; sửa để giữ giá trị nhập và vị trí DB hiện có, GET lỗi xóa snapshot cũ. Retest desktop/mobile PASS.
- Build đầu có types cache của các route T24 cũ trong `.next/dev`; xóa output sinh tự động, build lại PASS, không sửa source để né TypeScript.
- Theo yêu cầu giữ frontend gốc, khôi phục các trang gốc và thêm `/dispatcher/live`, `/driver/assigned`; kiểm thử chạy trên module mới. Trang mẫu cũ không được dùng làm bằng chứng API/SQL TX4.
