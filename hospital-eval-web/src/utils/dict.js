/**
 * 业务枚举中文与颜色映射（04 §10 业务枚举 + 04 §13 提取方式标签色）。
 * tag type 对应 el-tag：success=绿 / warning=橙 / primary=蓝 / info=灰 / danger=红
 */

// 提取方式（同时用作结果 dataSourceTag 的展示映射）
export const EXTRACT_MODE = {
  SYS: { label: '系统', tag: 'success' },
  MANUAL: { label: '人工', tag: 'warning' },
  HYBRID: { label: '系统+人工', tag: 'primary' },
  PROV: { label: '省级', tag: 'info' }
}

// 数据来源标签（结果行 dataSourceTag，取值与提取方式一致，缺省原样显示灰色）
export function dataSourceTagOf(value) {
  if (value && EXTRACT_MODE[value]) {
    return EXTRACT_MODE[value]
  }
  return { label: value || '-', tag: 'info' }
}

export function extractModeOf(value) {
  return EXTRACT_MODE[value] || { label: value || '-', tag: 'info' }
}

// 三级复核状态（04 §10）
export const AUDIT_STATUS = {
  DRAFT: { label: '待填报', tag: 'info' },
  L1_SUBMIT: { label: '已提交待内审', tag: 'primary' },
  L2_PASS: { label: '内审通过', tag: 'success' },
  L2_REJECT: { label: '内审退回', tag: 'danger' },
  L3_ARCHIVED: { label: '已归档', tag: 'warning' }
}

export function auditStatusOf(value) {
  return AUDIT_STATUS[value] || { label: value || '-', tag: 'info' }
}

// 复核级别
export const AUDIT_LEVEL = {
  L1_LIAISON: '一级（联络员）',
  L2_AUDITOR: '二级（内审员）',
  L3_FUNC: '三级（职能部门）',
  ADMIN: '管理员'
}

// 复核动作
export const AUDIT_ACTION = {
  SUBMIT: '提交',
  APPROVE: '通过',
  REJECT: '退回',
  ARCHIVE: '归档',
  UNLOCK: '解锁'
}

// 角色（04 §10 role_code）
export const ROLE = {
  ADMIN: '管理员',
  LIAISON: '科室联络员',
  AUDITOR: '科室审核员',
  FUNC_LEADER: '职能部门负责人',
  EXPERT: '专家',
  LEADER: '院领导'
}

export function roleOf(code) {
  return ROLE[code] || code || '-'
}

// SQL 角色（04 §10 sql_role）
export const SQL_ROLE = {
  NUM_DTL: '分子-明细',
  DEN_DTL: '分母-明细',
  NUM_SUM: '分子-聚合',
  DEN_SUM: '分母-聚合'
}

// 汇总模式
export const AGG_MODE = {
  DEDUP_DTL: '明细全院去重',
  DEPT_SUM: '分科累加'
}

// 批次触发类型 / 执行状态
export const TRIGGER_TYPE = {
  CRON: '定时跑批',
  MANUAL_COVER: '人工补跑',
  IMPORT: '基线导入',
  MANUAL_INPUT: '人工填报'
}

export const EXEC_STATUS = {
  RUNNING: { label: '运行中', tag: 'primary' },
  SUCCESS: { label: '成功', tag: 'success' },
  PARTIAL_FAIL: { label: '部分失败', tag: 'warning' },
  FAILED: { label: '失败', tag: 'danger' }
}

// SQL 版本变更类型
export const CHANGE_TYPE = {
  新增: '新增',
  口径升级: '口径升级',
  算法Bug修复: '算法Bug修复'
}
