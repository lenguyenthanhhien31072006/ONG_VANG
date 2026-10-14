# Mini Ong Vang

## Tài liệu và kiểm thử API (T04)

Hiện `/api/health` và nghiệp vụ T06–T17 đã triển khai trên designV1, gồm tiền mặt/VNPay, lịch sử đơn và báo cáo/Excel. T20 bổ sung tìm khách cho tổng đài; xem trạng thái từng operation trong OpenAPI và [báo cáo nghiệm thu T20](tests/T20-acceptance.md).

Xem [hướng dẫn T04](docs/api/README.md), [ánh xạ designV1 với API](docs/api/schema-mapping.md)
và [Postman collection/hướng dẫn chạy](tests/postman/README.md).

T10: [thiết kế và cách tạo đơn](docs/T10-order-creation.md).

T11: [thiết kế điều phối/gán/từ chối](docs/T11-driver-dispatch.md).

T12: [thiết kế tiến trình/sự cố/hoàn tất](docs/T12-delivery-progress.md).
T13: [thiết kế hủy đơn](docs/T13-order-cancellation.md), [phạm vi kiểm thử](tests/T13-validation.md).
T14: [nghiệp vụ thanh toán tiền mặt](docs/T14-payment.md).
T15: [tích hợp VNPay Sandbox](docs/T15-vnpay.md).
T16: [lịch sử và chi tiết đơn hàng](docs/T16-order-history.md).
T17: [báo cáo doanh thu và xuất Excel](docs/T17-revenue-reports.md).

T18: [đối chiếu tài liệu và phạm vi hoãn tới khi deploy](docs/T18-sandbox-api.md).
T19: [báo cáo hồi quy backend trên designV1](tests/T19-validation.md).
T20: [đối chiếu mốc nghiệm thu backend](tests/T20-acceptance.md).

Schema và migration hiện hành: [designV1](docs/database/designV1.md), [hướng dẫn CSDL/seed/reset](docs/database/README.md). Kết quả kiểm tra local cho T06–T12 trên schema mới: [báo cáo M1](docs/database/designV1-M1-audit-2026-10-11.md).

Mini Ong Vang là dự án môn Công nghệ Phần mềm về hệ thống đặt xe và giao hàng. Hệ thống gồm giao diện web, backend Java xử lý nghiệp vụ và cơ sở dữ liệu SQL Server. Repo có Docker cho FE/BE/DB; backend đã triển khai T06–T17, các chức năng tiếp theo theo [kế hoạch dự án](docs/project_brief.md).

## Công nghệ và phiên bản

| Thành phần | Phiên bản hiện cấu hình | Dùng để |
| --- | --- | --- |
| Docker Desktop và Docker Compose | Docker Compose v2 trở lên | Chạy FE, BE và database bằng một lệnh |
| Node.js | 22 (image `node:22-alpine`) | Chạy frontend trực tiếp trên máy |
| Next.js | 16.3.8 | Framework frontend |
| React / React DOM | 19.2.8 | Giao diện frontend |
| Java | 21 (Eclipse Temurin) | Chạy và build backend |
| Maven | 3.9 (image `maven:3.9-eclipse-temurin-21`) | Build backend thành file WAR |
| Apache Tomcat | 10.1 | Chạy backend Java |
| Jakarta Servlet API | 6.0.0 | Tạo HTTP endpoint cho backend |
| Jakarta Persistence API (JPA) | 3.1.0 | Chuẩn truy cập dữ liệu |
| Hibernate ORM | 6.6.58.Final | ORM cho JPA |
| Microsoft SQL Server JDBC Driver | 12.10.2.jre11 | Kết nối Java với SQL Server |
| SQL Server | 2022 Developer | Cơ sở dữ liệu |

Nếu chạy toàn bộ bằng Docker, không cần cài Java, Maven hay Node.js trên máy. Bạn cần Docker Desktop đang mở và có quyền chạy Docker.

## Development workflow

Quy tắc branch, commit, Pull Request và merge:
xem [CONTRIBUTING.md](./CONTRIBUTING.md)

## Lấy mã nguồn nhánh `develop`

```bash
git clone <LINK_REPOSITORY>
cd mini-ong-vang
```
chuyển qua nhánh develop và pull code mới nhất về:

```bash
git checkout develop
git pull origin develop
```

## Tạo file `.env`

Docker Compose đọc thông tin database từ file `.env` ở thư mục gốc. Tạo file này từ mẫu trước khi chạy:

**Windows PowerShell:**

```powershell
Copy-Item .env.example .env # Chỉ khi chưa có .env; giữ cấu hình cục bộ hiện tại
```

Đặt `MSSQL_SA_PASSWORD` trong `.env` bằng mật khẩu riêng đủ mạnh; dùng `MSSQL_DATABASE=mini_ong_vang_design_v1`. SQL Server Developer chỉ dùng cho phát triển/test. Xem [cách kết nối SSMS và xử lý lỗi](#kết-nối-database-bằng-ssms) bên dưới và [hướng dẫn database designV1](docs/database/README.md).

T07 mặc định dùng `ROUTE_PROVIDER=fake`: thử `Quận 1, TP.HCM` → `Quận 3, TP.HCM` để nhận 5.00 km, 900 giây. Các địa chỉ mẫu khác là Quận 5, TP.HCM và Hà Nội (ngoài phạm vi). Có thể dùng tên `Điểm mẫu A/B/C`. Khoảng cách tối đa mặc định là `ROUTE_MAX_DISTANCE_KM=30`. Khi có Goong API key, đặt `ROUTE_PROVIDER=goong` và `GOONG_API_KEY` trong `.env`, rồi tạo lại backend bằng `docker compose up --build -d backend`; Goong dùng `GOONG_TIMEOUT_MS=5000` và `GOONG_VEHICLE=car` mặc định. Tuyến ô tô chỉ là ước tính tạm thời cho giao hàng xe máy vì Goong hiện trả HTTP 400 với `motorcycle` ở Directions V2. Thiếu key ở chế độ Goong làm backend từ chối khởi động; lỗi Goong không chuyển sang kết quả mẫu. Xem [hướng dẫn T07](docs/T07-route-provider.md) và [hướng dẫn T09](docs/T09-quote.md).

## Chạy toàn bộ hệ thống bằng Docker

`docker-compose.yml` là môi trường **phát triển**, dùng volume giữ dữ liệu.
Môi trường test T05 nằm riêng trong `docker-compose.ci.yml`; xem phần CI bên dưới.

Mở terminal tại thư mục gốc dự án, nơi có `docker-compose.yml`, rồi chạy:

```bash
docker compose up --build
```

Lần đầu chạy, Docker sẽ tải các image cần thiết, build FE và BE, khởi động SQL Server, rồi lần lượt chạy backend và frontend. Đợi đến khi log có dòng báo Next.js `Ready` và Tomcat `Server startup`.

Mở các địa chỉ sau trong trình duyệt:

| Thành phần | Địa chỉ | Kết quả hiện tại |
| --- | --- | --- |
| Frontend | [http://localhost:3001](http://localhost:3001) | Trang trạng thái FE, BE và database |
| Backend health check | [http://localhost:8081/api/health](http://localhost:8081/api/health) | JSON báo backend có kết nối được database hay không |
| SQL Server từ máy host | `tcp:127.0.0.1,1433` | Nhập trong SSMS, không mở bằng trình duyệt |
| swagger | [http://localhost:8081/swagger-ui/] | trang test API



Để dừng các container, nhấn `Ctrl+C` trong terminal đang chạy Compose. Nếu chạy nền với `-d`, dùng:

```bash
docker compose down
```

Lệnh `docker compose down` giữ dữ liệu database trong Docker volume. Không thêm `-v` nếu bạn chưa muốn xóa dữ liệu database.

## Cả nhóm test VNPay Sandbox bằng Postman

Khi dự án còn chạy local, dùng **một backend và database test chung** trên máy của người mở ngrok. VNPay gửi IPN đến URL đã đăng ký, nên người tạo lần thanh toán và nơi nhận IPN phải dùng cùng database. Các thành viên khác gọi backend này qua ngrok; không cần chạy ngrok hoặc nhận authtoken/VNPay Hash Secret.

Hướng dẫn dưới đây đã có từ T15, được giữ để tham khảo khi cần. Hiện chưa yêu cầu mọi thành viên chạy thanh toán Sandbox; môi trường thanh toán dùng chung sẽ được cấu hình và kiểm tra sau khi dự án hoàn thiện và deploy.

**Người host:**

1. Trong `.env` riêng trên máy, đặt `DEMO_SEED=true`, `DEMO_PASSWORD`, `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET` và `VNPAY_RETURN_URL=https://<domain-ngrok>/api/payments/vnpay/return`. Không commit `.env`.
2. Chạy `docker compose up --build -d backend`, kiểm tra `http://localhost:8081/api/health`, rồi chạy `ngrok http 8081`. Giữ Docker, ngrok và máy host hoạt động trong lúc nhóm test.
3. Đảm bảo VNPay đã cấu hình IPN là `https://<domain-ngrok>/api/payments/vnpay/ipn`. Nếu domain đổi, cập nhật IPN với VNPay, sửa `VNPAY_RETURN_URL` và khởi động lại backend trước khi test.
4. Gửi cho nhóm **URL HTTPS gốc** của ngrok (không kèm `/api/...`), mật khẩu demo và thông tin thẻ **Sandbox** qua kênh nội bộ. Không gửi authtoken hoặc VNPay Hash Secret.

**Thành viên test:**

1. Pull nhánh hiện tại đã có T06–T17. Import `tests/postman/mini-ong-vang.postman_collection.json` và `tests/postman/local.postman_environment.json` vào Postman; chọn environment vừa import.
2. Đổi `baseUrl` trong environment thành `https://<domain-ngrok>` (không có `/` cuối), giữ `runPlanned=false`. Không dùng `localhost:8081`: địa chỉ đó sẽ trỏ về máy của chính bạn.
3. Dùng tài khoản demo để đi theo luồng `getCsrf` → `login` → `getCsrf` → `createQuote` → `createOrder` → tổng đài `assignDriver` → tài xế chuyển đơn đến `HOAN_TAT` → khách `createPayment`. Khi đổi vai trò, đăng xuất rồi đăng nhập/lấy CSRF mới. Mỗi người tạo **đơn mới và Idempotency-Key mới**, tránh dùng chung mã đơn/lần thanh toán.
4. Mở `paymentUrl` từ `createPayment`, thanh toán bằng thẻ Sandbox, rồi gọi `getPayment` bằng `maGiaoDich`. Chỉ coi là đã thu khi backend báo `trangThai=THANH_CONG`, `daXacNhanDu=true` và đơn `DA_THANH_TOAN`; trang Return của VNPay không tự xác nhận thanh toán. Nếu chưa có IPN, báo người host kiểm tra ngrok và dùng `reconcilePayment` để tra cứu khi cần.

Đây là **một database test chung**: nên test theo lượt hoặc dùng tài khoản/đơn riêng để tránh làm thay đổi dữ liệu của nhau. Khi máy host hoặc tunnel tắt, nhóm không test Sandbox trực tiếp được. Xem [hướng dẫn T15](docs/T15-vnpay.md) và [hướng dẫn Postman](tests/postman/README.md) để biết chi tiết request, tài khoản demo và cách kiểm tra IPN.

## Kết nối database bằng SSMS

Mỗi thành viên mở Docker Desktop và chạy lệnh sau tại thư mục gốc dự án để khởi động backend cùng database:

```powershell
docker compose up --build -d backend
docker compose ps -a
```

Đợi service `sqlserver` báo `healthy`. Service `db-init` kết thúc với `Exited (0)` là bình thường: nó chỉ chạy một lần để chuẩn bị database.

Mở **SQL Server Management Studio (SSMS)** → **Connect → Database Engine**, nhập:

| Mục | Giá trị |
| --- | --- |
| Server name | **`tcp:127.0.0.1,1433`** |
| Authentication | `SQL Server Authentication` |
| Login / User name | `sa` |
| Password | Giá trị sau `MSSQL_SA_PASSWORD=` trong file `.env` trên máy của bạn |
| Trust server certificate | Tích chọn cho SQL Server Docker local |

Nhấn **Connect**, mở **Databases → mini_ong_vang_design_v1 → Tables** để xem các bảng. Nếu đã đổi `MSSQL_DATABASE`, chọn tên database tương ứng. Có thể mở **New Query** và chạy:

```sql
SELECT @@VERSION AS server_version, DB_NAME() AS current_database;
```

Mật khẩu nằm trong `.env`, không phải `.env.example`; chỉ sao chép phần giá trị, không sao chép `MSSQL_SA_PASSWORD=`. Mỗi máy có database/volume và mật khẩu riêng. SSMS là công cụ quản lý, còn SQL Server chạy trong Docker; không cần cài thêm SQL Server Engine để dùng cấu hình này.

### Lỗi 1225, connection refused hoặc timeout

Nếu SSMS báo `The remote computer refused the network connection`, `server was not found` hoặc timeout:

1. Đổi **Server name** thành đúng **`tcp:127.0.0.1,1433`**. Dùng **dấu phẩy** trước cổng `1433`, không thêm `\SQLEXPRESS`. Trên máy đã kiểm tra của nhóm, `localhost,1433` bị timeout nhưng `tcp:127.0.0.1,1433` kết nối được. Kết quả này không có nghĩa mọi lỗi 1225 đều cùng một nguyên nhân.
2. Kiểm tra Docker Desktop đang chạy và service SQL Server đã `healthy` bằng các lệnh khởi động phía trên.
3. Nếu vẫn lỗi, chạy trong PowerShell:

   ```powershell
   Test-NetConnection -ComputerName 127.0.0.1 -Port 1433
   docker compose logs --tail 80 sqlserver db-init
   ```

   `TcpTestSucceeded: True` nghĩa là Windows truy cập được cổng. Nếu `False`, xem trạng thái container, port mapping và log trước khi kiểm tra tài khoản. Mapping mặc định là `127.0.0.1:1433->1433/tcp`.
4. Nếu Docker báo cổng `1433` đã bị chiếm, kiểm tra tiến trình đang nghe:

   ```powershell
   Get-NetTCPConnection -LocalPort 1433 -State Listen |
       Select-Object LocalAddress, LocalPort, OwningProcess
   ```

   Nếu cần giữ dịch vụ đang dùng cổng đó, đổi riêng port mapping của service `sqlserver` trong `docker-compose.yml` thành `127.0.0.1:14330:1433`, chạy lại `docker compose up -d backend`, rồi kết nối SSMS bằng `tcp:127.0.0.1,14330`. Backend trong Docker vẫn dùng `sqlserver:1433`; không đổi cổng nội bộ trong `DB_URL`.

### Các lỗi kết nối khác

| Hiện tượng | Cách xử lý |
| --- | --- |
| Lỗi chứng chỉ / certificate chain not trusted | Tích **Trust server certificate** trong cửa sổ kết nối SSMS cho môi trường Docker local. |
| `Login failed for user 'sa'` / lỗi 18456 | Chọn **SQL Server Authentication**, kiểm tra login `sa` và mật khẩu đã dùng để khởi tạo SQL Server. Đổi giá trị trong `.env` không tự đổi mật khẩu `sa` đã lưu trong volume; nếu đã đổi nhầm, dùng lại mật khẩu ban đầu hoặc thực hiện đổi mật khẩu trong SQL Server bằng tài khoản có quyền. |
| Kết nối được nhưng không thấy `mini_ong_vang_design_v1` | Refresh mục **Databases**, kiểm tra `MSSQL_DATABASE` trong `.env`, chạy `docker compose up -d backend` rồi xem `docker compose logs --tail 80 db-init backend`. |

Không dùng `docker compose down -v` hoặc xóa volume để thử sửa lỗi kết nối vì có thể mất dữ liệu. Cổng database hiện chỉ mở trên máy chạy Docker (`127.0.0.1`); thành viên chạy dự án trên máy mình dùng cấu hình local của máy đó.

## Chỉ chạy backend và database

Backend cần SQL Server nên lệnh này sẽ tự khởi động cả database, nhưng không khởi động frontend:

```bash
docker compose up --build backend
```

Kiểm tra backend tại [http://localhost:8081/api/health](http://localhost:8081/api/health). Nếu muốn chạy nền, thêm `-d`:


## Chỉ chạy frontend

### Chạy FE trực tiếp trên máy
tại thư mục gốc:
```bash
cd src/frontend
npm ci
npm run dev
```
Mở [http://localhost:3000](http://localhost:3000).
### Chạy FE bằng Docker Compose

```bash
docker compose up --build frontend
```

Compose sẽ chạy thêm backend và database vì frontend cần các dịch vụ đó để kiểm tra trạng thái. Mở [http://localhost:3001](http://localhost:3001).


## CI và môi trường kiểm thử (T05)

[GitHub Actions CI](.github/workflows/ci.yml) chạy khi mở/cập nhật PR, push vào
`main`/`develop`, hoặc chạy thủ công qua `workflow_dispatch` khi workflow có trên nhánh mặc định.

| Job | Kiểm tra |
| --- | --- |
| Frontend lint and build | Node 22, `npm ci`, ESLint, Next.js production build và kiểm tra TypeScript trong build |
| Backend tests and API smoke | Java 21, kiểm tra OpenAPI/Postman/CSRF, build WAR, JUnit với SQL Server test, smoke HTTP BE/Swagger |

Pipeline không cần secret của dự án hoặc VNPay. Database test là `mini_ong_vang_test`,
chạy trên tmpfs, cổng `15433`; backend test dùng cổng `18081`. Nó không dùng `.env`
để cấu hình DB và không mount volume dữ liệu phát triển. CI tự sinh mật khẩu riêng cho DB test tạm thời.
Không ghép hai file Compose bằng nhiều cờ `-f`.

Để chạy tương đương job BE trên máy, cần Java 21, Maven 3.9, Node 22 và Docker Compose v2 có `--wait`.
Từ gốc repo, PowerShell:

```powershell
npm.cmd ci --ignore-scripts --prefix tools/api
npm.cmd run check --prefix tools/api

$env:TEST_DB_URL = 'jdbc:sqlserver://127.0.0.1:15433;databaseName=mini_ong_vang_test;encrypt=true;trustServerCertificate=true'
$env:TEST_DB_USER = 'sa'
$env:TEST_DB_PASSWORD = '<mat-khau-test-manh-cua-ban>'
$env:REQUIRE_TEST_DB = 'true'
$env:DEMO_PASSWORD = 'ci-only-demo-password'
docker compose -f docker-compose.ci.yml up --wait --wait-timeout 180 db-test
docker compose -f docker-compose.ci.yml up --no-deps --exit-code-from db-init-test db-init-test
$env:API_BASE_URL = 'http://127.0.0.1:18081'
$env:API_EXPECTED_DATABASE = 'mini_ong_vang_test'
mvn.cmd --batch-mode --no-transfer-progress -f src/backend/pom.xml verify
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/reset-test-db.ps1
docker compose -f docker-compose.ci.yml up --build --wait --wait-timeout 120 backend-test
npm.cmd run smoke --prefix tools/api
npm.cmd run smoke:orders --prefix tools/api
npm.cmd run smoke:history --prefix tools/api
npm.cmd run smoke:cancel --prefix tools/api
npm.cmd run smoke:reports --prefix tools/api
npm.cmd run smoke:payment --prefix tools/api
npm.cmd run smoke:dispatch --prefix tools/api
docker compose -f docker-compose.ci.yml cp scripts/check-design-v1-invariants.sql db-test:/tmp/check-design-v1-invariants.sql
docker compose -f docker-compose.ci.yml exec -T db-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b -d mini_ong_vang_test -i /tmp/check-design-v1-invariants.sql
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/reset-test-db.ps1
docker compose -f docker-compose.ci.yml up --no-deps --wait --wait-timeout 120 backend-test
npm.cmd run smoke:progress --prefix tools/api
docker compose -f docker-compose.ci.yml cp scripts/check-design-v1-invariants.sql db-test:/tmp/check-design-v1-invariants.sql
docker compose -f docker-compose.ci.yml exec -T db-test /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b -d mini_ong_vang_test -i /tmp/check-design-v1-invariants.sql
```

Mỗi lệnh phải thành công trước khi tiếp tục. Chạy xong, kể cả khi test lỗi:

```powershell
docker compose -f docker-compose.ci.yml down --volumes --remove-orphans
Remove-Item Env:TEST_DB_URL, Env:TEST_DB_USER, Env:TEST_DB_PASSWORD, Env:REQUIRE_TEST_DB, Env:DEMO_PASSWORD, Env:API_BASE_URL, Env:API_EXPECTED_DATABASE -ErrorAction SilentlyContinue
```

Lệnh dọn trên chỉ dành cho **Compose test**. Dữ liệu test là tạm thời và mất khi container DB dừng.
Nếu chạy nhiều bản test đồng thời trên cùng máy, cần dùng project name và host ports khác nhau.

FE: chạy `npm ci`, `npm run lint`, `npm run build` trong `src/frontend`.
Build hiện tải font Google bằng `next/font`; cần mạng khi cài dependency/build.
FE hiện chưa có bộ test UI, không ghi lint/build là test chức năng đã PASS.

Test Java kiểm tra kết nối, migration designV1, Hibernate mapping, seed, constraint, cước chốt theo đơn,
commit/rollback và cạnh tranh phân công. Bộ test reset dữ liệu trên DB test riêng trước từng case;
chỉ khởi động backend-test sau khi Maven verify xong. Chạy Maven không có `TEST_DB_URL` thì test DB skip;
CI đặt `REQUIRE_TEST_DB=true` để thiếu cấu hình phải fail. T06–T09 có test tương ứng; T10 có integration test tạo đơn và HTTP smoke; T11 có test SQL Server và HTTP với cạnh tranh gán/từ chối; T12 có kiểm thử tiến trình/sự cố/hoàn tất; T13 có test SQL Server và HTTP smoke; T14–T17 đã có test thanh toán, lịch sử và báo cáo. T18 hiện chỉ đồng bộ tài liệu; chưa bổ sung seed hoặc chạy kiểm thử thanh toán trong đợt cập nhật này.

Trên GitHub, xem tab **Actions → CI** hoặc checks của PR. Job BE lưu Surefire reports và
container logs vào artifact `backend-test-results` trong 7 ngày, kể cả test thất bại;
luôn dọn stack test sau chạy. File workflow ở local chưa phải bằng chứng GitHub run thành công:
cần push/mở PR để có run thực tế.

Khi nhóm muốn chặn merge nếu CI lỗi, quản trị repo cấu hình branch rules cho `main`/`develop`
và yêu cầu hai check tên trong bảng trên. Workflow không tự thay đổi branch protection.
Khi có nghiệp vụ mới, thêm JUnit vào `src/backend/src/test/java`; bổ sung test FE khi có UI nghiệp vụ.
VNPay unit/integration mặc định dùng adapter giả lập; test sandbox thật chạy riêng theo T15/T18,
không thêm credential sandbox hay callback thật vào pipeline PR.

Tham khảo: [GitHub Actions workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax),
[Docker Compose up/wait](https://docs.docker.com/reference/cli/docker/compose/up/).

## Sơ lược cấu trúc thư mục

```text
.
├── docker-compose.yml       # Cách Docker chạy và nối các dịch vụ
├── .env.example             # Mẫu biến môi trường cho SQL Server
├── docs/                    # Tài liệu và kế hoạch dự án
├── src/
│   ├── frontend/             # Next.js: trang web và giao diện
│   │   └── app/              # Các trang, layout và CSS
│   └── backend/              # Java Servlet, Maven và Dockerfile backend
│       └── src/
│           ├── main/
│           │   ├── java/com/miniongvang/
│           │   │   ├── controller/  # Nhận HTTP request và trả response
│           │   │   ├── service/     # Quy tắc nghiệp vụ
│           │   │   ├── DAO/         # Đọc và ghi dữ liệu
│           │   │   ├── entity/      # Entity và mô hình nghiệp vụ
│           │   │   ├── dto/         # Dữ liệu trao đổi với API
│           │   │   ├── integration/ # Kết nối dịch vụ bên ngoài
│           │   │   └── config/      # Cấu hình ứng dụng
│           │   ├── resources/       # Cấu hình và tài nguyên backend
│           │   └── webapp/          # Tài nguyên web của ứng dụng Java
│           └── test/java/           # Kiểm thử Java
└── tests/                    # Kiểm thử tích hợp/toàn hệ thống
```

Package Entity là `com.miniongvang.entity`; package truy cập dữ liệu là `com.miniongvang.DAO` (giữ đúng chữ hoa theo cấu trúc thư mục). Các lớp truy cập dữ liệu dùng hậu tố `DAO`, ví dụ `DonHangDAO`.

Luồng xử lý nghiệp vụ dự kiến là:

```text
Controller → Service → DAO → Hibernate/JPA → SQL Server
```

Controller nhận yêu cầu, Service xử lý quy tắc nghiệp vụ, DAO làm việc với dữ liệu qua Hibernate/JPA. Controller không gọi DAO trực tiếp. T03 đã triển khai lớp persistence; T06 đã triển khai auth; T07–T09 đã triển khai lộ trình, chính sách thành viên và báo giá. Endpoint `/api/health` vẫn là công cụ kiểm tra hạ tầng, T10 đã có API tạo đơn, T11 có API điều phối/gán/từ chối; T12 có tiến trình, sự cố và nhật ký; T13 hủy đơn, T14–T15 thanh toán, T16 lịch sử và T17 báo cáo/Excel đã triển khai. T20 bổ sung `listCustomers` cho tổng đài.

## TX4 — điều phối theo vị trí mô phỏng

Vị trí tài xế dùng chung trong SQL Server, xác minh Goong qua backend, gợi ý tuyến tới điểm lấy có nhãn nguồn/dự phòng và gán đơn thật. Xem [khởi động và kiểm tra giao diện](docs/TX4-implementation.md), [kiểm thử và trạng thái bằng chứng](tests/TX4-validation.md). Vị trí mô phỏng cố định không phải GPS. Thiếu Goong key vẫn có thứ tự dự phòng, chưa thể xác minh/lưu địa chỉ.
