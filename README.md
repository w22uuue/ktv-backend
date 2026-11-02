# ktv-backend

KTV 包厢点餐系统的 Java 后端，为 ktv-app 提供共享点餐业务，为 ktv-admin 提供统一运营管理和若依认证 API。

## 项目简介

基于 RuoYi-Vue 3.9.1 二次开发。`/ktv-api` 提供访客会话、食堂和 KTV 管理员登录、餐品与包厢管理、订单创建、接单出餐、配送确认、账号权限及餐品图片上传。

订单金额由服务端按餐品价格计算，订单保存成交快照；创建请求使用幂等键，管理数据更新检查版本，数据库事务与行锁保护共享状态。食堂仅接收分配给自己的订单，接口隐藏顾客与金额字段。KTV 会话使用不透明令牌，数据库只存令牌摘要，密码使用 BCrypt。

当前实现面向单个门店。已实现微信 code2Session、JSAPI 支付、验签通知与查单、管理员全额退款、H5 二维码及小程序码生成；正式账号和 HTTPS 域名配置后仍须真机验收。模拟支付默认关闭，生产模式禁止开启。后台推送不在当前范围内。详见 [上线配置与验收](deploy/上线配置与验收.md)。

## 技术栈

| 类别 | 实际使用 |
| --- | --- |
| 语言与构建 | Java（POM 目标 8，当前以 JDK 17 验证）、Maven 多模块 |
| Web 与安全 | Spring Boot 2.5.15、Spring Security 5.7.14、JWT、BCrypt |
| 数据访问 | MyBatis、PageHelper、Spring JDBC、Druid |
| 数据与缓存 | MySQL、Redis |
| 接口与工具 | Springfox / Swagger、Fastjson2 |

父 POM 当前启用 `ruoyi-admin`、`ruoyi-framework`、`ruoyi-system`、`ruoyi-common`。保留的 `ruoyi-generator` 与 `ruoyi-quartz` 目录未加入当前构建。

## 关联仓库

| 项目 | 说明 | GitHub |
| --- | --- | --- |
| ktv-backend | 后端 API、共享订单与权限服务 | [ktv-backend](https://github.com/w22uuue/ktv-backend) |
| ktv-admin | PC 运营管理后台 | [ktv-admin](https://github.com/w22uuue/ktv-admin) |
| ktv-app | 顾客、食堂配送及 KTV 移动管理客户端 | [ktv-app](https://github.com/w22uuue/ktv-app) |

## 快速启动

准备 JDK 17、Maven 3.9、MySQL 8 和 Redis。以下以 PowerShell 为例；macOS / Linux 可用对应的环境变量设置方式运行相同 Maven / Java 命令。

1. 创建空数据库 `ktv`（UTF-8 / utf8mb4），配置专用数据库账号并授予该库读写和建表权限。在 MySQL 客户端选择该空库，执行 `source /绝对路径/ktv-backend/sql/init.sql;`。

   `init.sql` 包含建表与最小框架种子数据，以及空密码的 PC 管理员 `admin`。脚本包含 DROP TABLE，只用于空库初始化。KTV 业务表在首次启动时自动创建，公开仓库不含实际订单、会话或本地 SQL 备份。

2. 在当前终端配置环境变量。密钥由运行环境提供，不写入 Git：

```powershell
function Read-Secret($label) {
  [System.Net.NetworkCredential]::new('', (Read-Host $label -AsSecureString)).Password
}
$env:DB_URL = 'jdbc:mysql://localhost:3306/ktv?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8'
$env:DB_USER = 'ktv'
$env:DB_PASSWORD = Read-Secret 'MySQL password'
$env:JWT_SECRET = Read-Secret 'JWT secret: provide a random secret of at least 32 characters'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
# Redis 使用密码时，设置 REDIS_PASSWORD
$env:KTV_BOOTSTRAP_ADMIN_PASSWORD = Read-Secret 'KTV administrator initial password (8+ characters)'
$env:KTV_BOOTSTRAP_SUPPLIER_PASSWORD = Read-Secret 'Supplier initial password (12+ characters)'
mvn -pl ruoyi-admin -am clean package -DskipTests
```

3. 首次初始化 PC 管理员密码，然后启动服务：

```powershell
java -Dloader.main=com.ruoyi.web.tools.BootstrapSystemAdmin -cp ruoyi-admin/target/ruoyi-admin.jar org.springframework.boot.loader.PropertiesLauncher
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

初始化工具会交互读取新密码（至少 12 个字符，最多 72 个 UTF-8 字节），仅允许设置空密码的 `admin`，不会重置已有账号。无交互控制台时可通过 `RUOYI_BOOTSTRAP_ADMIN_PASSWORD` 注入密码。PC 账号与移动 KTV 账号彼此独立。

服务默认端口 `8080`。首次空库启动时创建 KTV 管理员 `admin`（兼容旧的 `ktv-admin` 登录别名）与食堂 `A01`，使用上述环境变量中的初始密码；后续启动不覆盖已有密码。若使用 ktv-app 的 `npm run dev:api`，脚本会生成这两个账号的随机初始密码并保存在仓库外 `%LOCALAPPDATA%/KtvDining/access.json` 中，应在**首次业务初始化前选择一种启动方式**。

| 配置 | 用途 |
| --- | --- |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 数据库连接；公开配置不含固定密码 |
| `JWT_SECRET` | PC 管理 JWT 签名密钥 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 |
| `KTV_BOOTSTRAP_ADMIN_PASSWORD` / `KTV_BOOTSTRAP_SUPPLIER_PASSWORD` | 空库首次创建移动管理与食堂账号 |
| `KTV_DEMO_PAYMENT_ENABLED` | 默认 false；本地模拟支付设 true |
| `KTV_UPLOAD_PATH` / `RUOYI_UPLOAD_PATH` | KTV 图片 / 若依上传目录，需持久保存 |
| `DRUID_CONSOLE_ENABLED` | 默认 false；开启时必须另设 `DRUID_USERNAME` 和 `DRUID_PASSWORD` |

开发机可使用已忽略的 `.local/application-private.yml` 保存 Spring 配置，程序从后端根目录启动时会自动读取。该文件不会随仓库分发。正式环境应独立注入配置并使用 HTTPS。

## 项目结构

```text
ruoyi-admin/       Spring Boot 入口、REST 控制器、KTV 服务、环境变量配置
  src/main/java/com/ruoyi/web/controller/ktv/  KTV API 与事务业务
  src/main/java/com/ruoyi/web/tools/           空密码管理员初始化工具
ruoyi-framework/   安全认证、缓存、MyBatis、通用 Web 配置
ruoyi-system/      若依系统实体、Mapper 和 Service
ruoyi-common/      公共工具、异常、权限与返回类型
ruoyi-generator/   保留的代码生成模块（未启用）
ruoyi-quartz/      保留的任务模块（未启用）
sql/init.sql       无固定密码的最小初始化结构与种子数据
```

KTV 餐品、包厢与账号配置存于 `ktv_shared_state`；订单独立存于带顾客、食堂和幂等键索引的 `ktv_order`，每页100笔。`ktv_payment` 保存支付/退款编号，`ktv_audit` 保存操作日志，`ktv_qr` 保存包厢场景码，`ktv_session`/`ktv_identity` 保存会话摘要和访客恢复凭据摘要。旧 JSON 订单自动事务迁移到独立订单表，迁移前须停止旧版并备份。系统管理仍使用 `sys_*` 表。

## 验证

```bash
mvn -pl ruoyi-admin -am verify
```

后端与 H5 启动后，可在 ktv-app 运行 `npm run test:shared-api` 和 `npm run test:shared-browser` 验证多会话协同、权限、订单状态与冲突处理。集成测试会写入测试订单，只用于隔离的本地测试数据库。

## 简历描述示例

参与 KTV 包厢点餐后端开发，实现订单幂等创建、服务端计价、食堂权限隔离和多端订单状态同步。使用 Spring Boot、MySQL 事务与版本检查保障共享数据一致性，并实现会话摘要存储和图片校验上传。

## 来源与许可证

基于 [RuoYi-Vue](https://gitee.com/y_project/RuoYi-Vue) 开发，保留原项目 MIT 许可证与作者声明，详见 [LICENSE](LICENSE)。


