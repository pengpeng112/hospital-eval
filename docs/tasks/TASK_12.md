# TASK-12：前端工程骨架、登录、7 大菜单布局与接口封装

| 项 | 内容 |
| :--- | :--- |
| 排期 | Day 6 · 第 2 次 |
| 依赖 | TASK-11（后端接口齐备） |
| 预计改动 | 15~25 个前端文件 |
| 是否连数据库 / 服务器 | 只连本机后端 `http://localhost:48080` |

## 1. 目标
初始化 `hospital-eval-web`（Vue 3 + Vite + **JavaScript** + Element Plus），完成登录、强制改密、按角色显示的 7 大菜单布局、路由守卫、全部后端接口的 API 封装文件，以及 7 个菜单的**占位页**（后续任务逐个替换）。

## 2. 开工前必读
1. `04_开发契约与编码规范.md` 第 2、3、4、12（全部）、13 节
2. 本文件

## 3. 前置检查
```powershell
node -v                     # v22.x
npm config get registry     # 记录即可
powershell -ExecutionPolicy Bypass -File scripts/smoke/TASK_05.ps1   # 后端在运行
```

## 4. 允许新建 / 修改的文件
- `hospital-eval-web/**`（除 `node_modules/`、`dist/`）
- `docs/task_receipts/TASK_12_回执.md`

## 5. 禁止事项
- 禁止使用 TypeScript、禁止引入 `vue-tsc`。
- 禁止引用任何外网 CDN（`index.html` 中不得出现 `http://` 或 `https://` 的外链）。
- 禁止写死假数据；占位页只显示一行文字“本页面将在 TASK-XX 实现”。
- 禁止安装 04 §2 以外的 UI 库（如 Ant Design、Vuetify）。

## 6. 操作步骤

### 步骤 1：创建工程
```powershell
npm create vite@5 hospital-eval-web -- --template vue
cd hospital-eval-web
npm install
npm install element-plus @element-plus/icons-vue pinia vue-router@4 axios echarts vue-codemirror codemirror @codemirror/lang-sql @codemirror/merge @codemirror/state @codemirror/view
```
- `package.json` 的 `name` 改为 `hospital-eval-web`，`scripts` 保留 `dev`、`build`、`preview`。

### 步骤 2：`vite.config.js`
```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:48080', changeOrigin: true },
      '/kkview': { target: 'http://localhost:18012', changeOrigin: true, rewrite: p => p.replace(/^\/kkview/, '') }
    }
  },
  build: { chunkSizeWarningLimit: 3000 }
})
```

### 步骤 3：基础设施
- `src/main.js`：注册 Element Plus（中文语言包 `element-plus/es/locale/lang/zh-cn`）、全部图标、Pinia、Router；引入 `element-plus/dist/index.css` 和 `src/styles/index.css`。
- `src/utils/request.js`：
  - axios 实例，`baseURL=''`，`timeout=120000`；
  - 请求拦截：从 `localStorage.eval_token` 取 token，设置 `Authorization: Bearer <token>`；
  - 响应拦截：`responseType === 'blob'` 时直接返回 `response`；否则 `code === 0` 返回 `data`；`code === 401` 或 HTTP 401 → 清 token 跳 `/login`；其他 → `ElMessage.error(msg)` 并 `Promise.reject`；
  - 导出 `download(url, params, defaultName)`：以 blob 方式下载，文件名优先取响应头 `Content-Disposition`（`decodeURIComponent`）。
- `src/stores/user.js`（Pinia）：`token`、`user`、`roles`；`login()`、`logout()`、`fetchMe()`、`hasRole(...codes)`。
- `src/utils/dict.js`：提取方式、复核状态、角色、数据来源标签的中文与颜色映射（04 §13 最后一条 + 04 §10）。
- `src/utils/escape.js`：`escapeHtml(text)`（替换 `& < > " '`）和 `highlight(text, keywords[])`（**先**转义再用 `<mark>` 包裹关键词）。

### 步骤 4：API 封装（一个后端模块一个文件，函数名用下表，路径/参数严格按 04 §12）
| 文件 | 函数 |
| :--- | :--- |
| `src/api/auth.js` | `login`、`logout`、`me`、`changePassword` |
| `src/api/system.js` | `listDepts`、`createDept`、`listUsers`、`createUser`、`updateUser`、`resetPassword`、`listOperLogs` |
| `src/api/algo.js` | `listDatasources`、`pingDatasource`、`validateSql`、`trialSql`、`listSqls`、`getSql`、`createSql`、`updateSql`、`toggleSql`、`listSqlHistory`、`getSqlHis` |
| `src/api/batch.js` | `runBatch`、`getBatch`、`listBatchTasks`、`listBatches` |
| `src/api/metric.js` | `getMetricTree`、`listMetrics`、`getMetric`、`updateMetric`、`listScopes`、`createScope`、`updateScope`、`funcDeptSummary`、`exportScopes`、`importScopes`、`syncDeptCodes` |
| `src/api/result.js` | `listSummary`、`listMetricScopes`、`getMetricPanel`、`listResultBatches`、`listDetail`、`exportDetail`、`get360Url`、`listEmrDocuments`、`getEmrDocumentContent`、`exportMaster` |
| `src/api/report.js` | `listReportTasks`、`saveManualValue`、`listHybridPool`、`hybridSelect`、`uploadEvidence`、`listEvidence`、`getEvidencePreviewUrl`、`deleteEvidence`、`auditSubmit`、`auditApprove`、`auditReject`、`auditArchive`、`auditUnlock`、`listAuditLogs`、`workbenchOverview`、`workbenchTodos`、`workbenchDeptProgress`、`workbenchAlerts` |
| `src/api/dashboard.js` | `dashboardOverview`、`downloadReconcileTemplate`、`importReconcile`、`listReconcileImports`、`listReconcileRows`、`exportReconcile` |

### 步骤 5：路由 `src/router/index.js`
- 路由表严格按 04 §13 菜单表，另加 `/login`、`/change-password`、`/system/users`；
- 每个业务路由 `meta: { title, roles: [...] }`；
- 全局守卫：无 token → `/login`；有 token 但 `user` 为空 → 先 `fetchMe()`；`mustChangePwd` 为 true → 只能进 `/change-password`；`meta.roles` 不含当前用户任一角色 → 跳 `/workbench` 并提示“无权限访问”；
- 默认首页 `/workbench`。

### 步骤 6：页面
- `src/views/login/index.vue`：用户名、密码、登录按钮；居中卡片；标题“医院等级评审与质控指标数据集成系统”；登录成功若 `mustChangePwd` → 跳改密页。
- `src/views/login/ChangePassword.vue`：旧密码、新密码、确认新密码；前端校验 ≥ 8 位含字母和数字。
- `src/layout/EvalLayout.vue`：
  - 左侧 `el-menu`（宽 200px，可折叠），菜单项与顺序严格按 04 §13，按当前角色过滤；“结果汇总”“大屏展示”为二级菜单；
  - 顶部：系统名称、当前用户姓名 + 科室 + 角色中文名、下拉菜单（修改密码、用户管理[仅 ADMIN]、退出登录）；
  - 主区域 `router-view`，顶部显示面包屑（取 `meta.title`）。
- `src/views/system/users.vue`：用户列表（分页、关键字、角色筛选）、新增、编辑（姓名、科室、角色多选、状态）、重置密码（二次确认）。这是本任务唯一要求做完整功能的业务页。
- 7 个业务占位页：`src/views/workbench/index.vue`（TASK-16）、`src/views/metric/index.vue`（TASK-13）、`src/views/algorithm/index.vue`（TASK-14）、`src/views/department/index.vue`（TASK-13）、`src/views/manual/index.vue`（TASK-16）、`src/views/summary/index.vue`（TASK-15，当期/历史共用，通过路由 `meta.mode` 区分）、`src/views/screen/dashboard.vue` 与 `src/views/screen/reconcile.vue`（TASK-17）。

### 步骤 7：样式 `src/styles/index.css`
- 全局字体 `"Microsoft YaHei", "PingFang SC", sans-serif`；背景 `#f5f7fa`；内容区卡片白底、内边距 16px；表格默认 `size="small"`（在各页面使用时设置）。

## 7. 验收命令
```powershell
cd hospital-eval-web
npm install
npm run build
Select-String -Path dist/index.html -Pattern "https?://" ; if ($?) { "检查外链结果见上（应为空）" }
cd ..
git status --short
```
**预期**：`npm run build` 成功（出现 `built in`）；`dist/index.html` 中无外链。

**联调自测（执行 AI 必须做，结果写进回执）**：
1. `npm run dev` 启动后，用 PowerShell 请求 `http://localhost:5173/api/health` 返回 `status UP`（证明代理生效）；
2. 用 PowerShell 通过 `http://localhost:5173/api/auth/login` 登录 admin 成功。

> 页面截图与点击检查由主控 AI 用浏览器完成，执行 AI 不需要截图。

## 8. 回执必须包含
- `npm run build` 输出最后 15 行；外链检查结果；联调自测两条结果；
- `src/api/*.js` 每个文件导出的函数数量；
- 提交号（确认 `node_modules`、`dist` 未提交）。

提交信息：`TASK-12: 前端骨架、登录与7大菜单布局`
