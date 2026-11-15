-- 由 TASK-04 生成，依据 01 方案第六章 + 04 契约第 5 节
-- 目标库：PostgreSQL 15，库名 hospital_eval（见 deploy/.env）
-- 顺序：sys_* → 01 方案核心表（含 04 §5.2 增补列）→ eval_metric_detail 分区 → 04 §5.2 新增表 → 全部索引

-- ===== 系统表（04 §5.2）=====
CREATE TABLE sys_dept (
    id            BIGSERIAL PRIMARY KEY,
    dept_name     VARCHAR(128) NOT NULL UNIQUE,
    dept_type     VARCHAR(16)  NOT NULL DEFAULT 'CLINICAL',   -- CLINICAL 临床医技 / FUNC 职能部门 / INFO 信息科
    parent_id     BIGINT       DEFAULT 0,
    sort_order    INT          DEFAULT 0,
    status        SMALLINT     DEFAULT 1,
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,              -- 登录名（导入用户默认=姓名，重名追加科室）
    real_name       VARCHAR(64)  NOT NULL,
    dept_id         BIGINT       REFERENCES sys_dept(id),
    password_hash   VARCHAR(100) NOT NULL,                     -- BCrypt
    must_change_pwd BOOLEAN      DEFAULT TRUE,
    phone           VARCHAR(32),
    status          SMALLINT     DEFAULT 1,                    -- 1 启用 0 停用
    last_login_time TIMESTAMP,
    create_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE sys_role (
    id         BIGSERIAL PRIMARY KEY,
    role_code  VARCHAR(32) NOT NULL UNIQUE,
    role_name  VARCHAR(64) NOT NULL
);
CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    role_id BIGINT NOT NULL REFERENCES sys_role(id),
    PRIMARY KEY (user_id, role_id)
);
CREATE TABLE sys_oper_log (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT,
    username     VARCHAR(64),
    module       VARCHAR(64),      -- 如 METRIC / ALGO / AUDIT
    action       VARCHAR(64),      -- 如 UPDATE_CALIBER / SAVE_SQL / ARCHIVE
    target_type  VARCHAR(64),
    target_id    VARCHAR(64),
    detail_json  JSONB,
    ip           VARCHAR(64),
    create_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ===== 01 方案第六章核心表（逐列照抄，04 §5.2 增补列已合并进 CREATE TABLE）=====

-- 1. 评审指标主字典表（对齐《数据指标导出调整版.xlsx》1,485条主指标 + 2,823条基础指标三层结构）
CREATE TABLE eval_metric_dict (
    id                  BIGSERIAL PRIMARY KEY,
    metric_code         VARCHAR(64)  NOT NULL UNIQUE,   -- 总表标准指标编号，如 25.2.3.3.5.1.1
    alias_code          VARCHAR(64),                    -- 重点专业/单病种英文代号，如 NEU-EPI-01 / ICU-05
    master_row_no       INT,                            -- 对应总表原始行号，保障原序1:1回填导出
    num_atom_code       VARCHAR(64),                    -- 下属分子（或单值/定性）基础指标编号，如 0.25.252.1
    den_atom_code       VARCHAR(64),                    -- 下属分母基础指标编号，如 0.25.252.2
    extra_atoms_json    JSONB,                          -- 含≥3个基础指标的复合子项映射（如ICU-05的1h/3h/6h bundle、门急住危急值等）
    chapter_part        VARCHAR(32)  NOT NULL,          -- 部分：PART1前置 / PART2管理与发展 / PART3医疗服务与质控监测
    chapter_code        VARCHAR(64),                    -- 所属总表末级章节编号，如 25.2.3.3.5.1
    chapter_path        VARCHAR(255) NOT NULL,          -- 章节全路径，如 第三章/五、神经系统疾病/(一)癫痫
    specialty_code      VARCHAR(64),                    -- 所属重点专业/单病种编码
    metric_name         VARCHAR(255) NOT NULL,          -- 指标名称
    metric_attr         VARCHAR(16)  NOT NULL,          -- 定量指标 / 定性指标
    metric_category     VARCHAR(16),                    -- 评价指标 / 监测指标
    unit                VARCHAR(32),                    -- 计量单位：%、例、天、分钟等
    direction           VARCHAR(32),                    -- 逐步提高 / 逐步降低 / 监测比较 / 符合标准
    extract_mode        VARCHAR(16)  NOT NULL,          -- SYS(系统提取) / MANUAL(人工填报) / HYBRID(系统+人工) / PROV(省级提取)
    agg_mode            VARCHAR(16)  DEFAULT 'DEDUP_DTL', -- 多科汇总策略：DEDUP_DTL(按明细PATIENT_ID+VISIT_ID全院去重) / DEPT_SUM(各科数值累加)
    is_applicable       BOOLEAN      DEFAULT TRUE,      -- 本院是否适用（承接总表803项不适用基线并记录原因）
    inapplicable_reason VARCHAR(500),                   -- 不适用原因说明
    std_formula_desc    TEXT,                           -- 国家标准计算方法（分子/分母文字定义）
    hosp_caliber_desc   TEXT,                           -- 我院实际统计口径说明（前端实时维护留痕）
    data_sys_source     VARCHAR(255),                   -- 业务系统来源说明（如 HIS / ODS / 瑞美LIS / 手麻系统）
    owner_func_dept_id  BIGINT,                         -- 归口职能部门ID（医务部/护理部/院感等）
    dedup_granularity   VARCHAR(32)  DEFAULT 'VISIT',   -- 去重粒度：VISIT(PATIENT_ID+VISIT_ID) / PATIENT / OPER / RAW
    target_value        NUMERIC(12,4),                  -- 目标参考值/阈值
    sort_order          INT          DEFAULT 0,
    status              SMALLINT     DEFAULT 1,
    create_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 2. “一标多科”科室责任分解与过滤范围表（04 §5.2 增补列：specialty_name/caliber_desc/confirm_person/source_ref/font_color）
CREATE TABLE eval_metric_dept_scope (
    id                  BIGSERIAL PRIMARY KEY,
    metric_id           BIGINT       NOT NULL REFERENCES eval_metric_dict(id),
    resp_dept_id        BIGINT       NOT NULL,          -- 临床/医技责任科室ID（对应分工表“负责部门/科室”）
    resp_dept_name      VARCHAR(128) NOT NULL,
    his_dept_codes      TEXT,                           -- 实际SQL过滤的HIS/ODS科室编码逗号串，如 '030503,030505'
    his_dept_names      TEXT,                           -- 对应“科室范围”标准名称串，供核查展示
    liaison_user_id     BIGINT,                         -- 第一级：科室数据联络员（初审人）
    auditor_user_id     BIGINT,                         -- 第二级：职能部门对接内审员（复核人）
    func_leader_user_id BIGINT,                         -- 第三级：归口职能部门终审负责人
    extract_mode_override VARCHAR(16),                  -- 允许个别科室覆盖提取方式（如未开展手术跳过）
    skip_flag           BOOLEAN      DEFAULT FALSE,     -- 是否免统/跳过（如“本科室无手术”）
    skip_reason         VARCHAR(255),
    specialty_name      VARCHAR(255),                   -- 04 §5.2 增补：所属专业名称
    caliber_desc        TEXT,                           -- 04 §5.2 增补：科室自定义统计口径（Excel“统计口径”列）
    confirm_person      VARCHAR(255),                   -- 04 §5.2 增补：口径确认人
    source_ref          VARCHAR(255),                   -- 04 §5.2 增补：来源：文件名!Sheet!R行号
    font_color          VARCHAR(16),                    -- 04 §5.2 增补：RED / BLACK / PURPLE / DEFAULT
    UNIQUE (metric_id, resp_dept_id)
);

-- 3. 指标 SQL 算法配置表（04 §5.2 增补列：source_ref/exec_mode/ds_flag/period_flag/emr_sys）
CREATE TABLE eval_metric_sql (
    id                  BIGSERIAL PRIMARY KEY,
    metric_id           BIGINT       NOT NULL REFERENCES eval_metric_dict(id),
    scope_id            BIGINT,                         -- 为空代表全院通用SQL；有值代表特定科室定制SQL
    sql_role            VARCHAR(16)  NOT NULL,          -- NUM_DTL(分子明细) / DEN_DTL(分母明细) / NUM_SUM / DEN_SUM
    datasource_key      VARCHAR(32)  NOT NULL,          -- ODS_RO / HIS_RO
    period_start        VARCHAR(6)   NOT NULL DEFAULT '202101', -- 生效起始年月 YYYYMM（如MTL配202101~202412）
    period_end          VARCHAR(6)   NOT NULL DEFAULT '999912', -- 生效截止年月 YYYYMM（如JHEMR配202501~999912）
    time_field_expr     VARCHAR(128),                   -- 分片时间字段，如 v.DISCHARGE_DATE_TIME / m.START_DATE_TIME
    sql_annotated       TEXT         NOT NULL,          -- 带中文注释的核查版 SQL（前端与专家可见）
    sql_executable      TEXT         NOT NULL,          -- 自动去注释后的执行版 SQL（含 #{startTime}, #{endTime}, #{deptCodes}）
    version_no          INT          NOT NULL DEFAULT 1,
    is_active           BOOLEAN      DEFAULT TRUE,
    last_verify_status  SMALLINT     DEFAULT 0,         -- 0未校验 1校验通过 2校验报错
    last_modify_user    VARCHAR(64),
    last_modify_reason  VARCHAR(500),
    update_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    source_ref          VARCHAR(255),                   -- 04 §5.2 增补：文件名!Sheet!R行C列
    exec_mode           VARCHAR(8)   DEFAULT 'RAW',     -- 04 §5.2 增补：RAW（存量原样执行）
    ds_flag             VARCHAR(32),                    -- 04 §5.2 增补：NULL / DS_CONFLICT / DS_UNKNOWN
    period_flag         VARCHAR(32),                    -- 04 §5.2 增补：NULL / PERIOD_CONFLICT
    emr_sys             VARCHAR(16)                     -- 04 §5.2 增补：MTL / JHEMR / MTL+JHEMR / NONE
);

-- 4. 算法修改历史记录表（对应临沂“查看历史算法调整记录”）
CREATE TABLE eval_metric_sql_his (
    id                  BIGSERIAL PRIMARY KEY,
    sql_id              BIGINT       NOT NULL,
    metric_id           BIGINT       NOT NULL,
    sql_role            VARCHAR(16)  NOT NULL,
    version_no          INT          NOT NULL,
    change_type         VARCHAR(32)  NOT NULL,          -- 新增 / 需求口径升级 / 算法Bug修复
    change_reason       VARCHAR(500) NOT NULL,
    period_start        VARCHAR(6),
    period_end          VARCHAR(6),
    old_sql_annotated   TEXT,                           -- 修改前完整 SQL，供 Diff 对比
    new_sql_annotated   TEXT,
    modify_user_id      BIGINT,
    modify_user_name    VARCHAR(64),
    modify_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 5. 数据跑批与版本快照批次表（支撑“当期版本 vs 历史版本”与“覆盖评审数据”）
CREATE TABLE eval_exec_batch (
    id                  BIGSERIAL PRIMARY KEY,
    batch_no            VARCHAR(64)  NOT NULL UNIQUE,   -- 如 BATCH_20261112_NIGHT / RECALC_20261112_01
    trigger_type        VARCHAR(16)  NOT NULL,          -- CRON(定时T+1) / MANUAL_COVER(手动覆盖) / IMPORT(历史导入)
    is_current_version  BOOLEAN      DEFAULT TRUE,      -- TRUE=当期展示版本，FALSE=归入【历史版本】供溯源对比
    version_tag         VARCHAR(64),                    -- 如 '2026年11月口径修订前快照'
    exec_status         VARCHAR(16)  NOT NULL,          -- RUNNING / SUCCESS / PARTIAL_FAIL / FAILED
    total_tasks         INT          DEFAULT 0,
    success_tasks       INT          DEFAULT 0,
    fail_tasks          INT          DEFAULT 0,
    operator_name       VARCHAR(64),
    start_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    end_time            TIMESTAMP
);

-- 6. L4 汇总数据层：指标结果汇总表
CREATE TABLE eval_metric_result (
    id                  BIGSERIAL PRIMARY KEY,
    batch_id            BIGINT       NOT NULL REFERENCES eval_exec_batch(id),
    metric_id           BIGINT       NOT NULL REFERENCES eval_metric_dict(id),
    scope_id            BIGINT       DEFAULT 0,         -- 0代表全院去重汇总行，>0代表具体临床科室行
    stat_period_type    VARCHAR(8)   NOT NULL,          -- YEAR / QUARTER / MONTH
    stat_period         VARCHAR(16)  NOT NULL,          -- 如 '2025' / '2025-Q1' / '2025-08'
    numerator_val       NUMERIC(18,4),                  -- 分子数值（系统提取或人工录入）
    denominator_val     NUMERIC(18,4),                  -- 分母数值
    result_val          NUMERIC(18,4),                  -- 计算结果现况值（百分比/比值/均数，仅展示用，上报仍传原分子分母）
    qualitative_result  VARCHAR(64),                    -- 定性指标结果：是/否/符合/部分符合/等级A等
    atom_values_json    JSONB,                          -- 含≥3个基础指标的复合子项结果键值（如 {"0.25.957.0.1": 12, ...}）
    data_source_tag     VARCHAR(16)  NOT NULL,          -- SYS / MANUAL / HYBRID
    sql_version_num     INT,                            -- 计算时使用的分子SQL版本号
    sql_version_den     INT,                            -- 计算时使用的分母SQL版本号
    audit_status        VARCHAR(16)  DEFAULT 'DRAFT',   -- DRAFT / L1_SUBMIT / L2_PASS / L2_REJECT / L3_ARCHIVED
    is_current          BOOLEAN      DEFAULT TRUE,      -- 冗余标记方便高频查询当期结果
    update_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 7. L4 明细数据层：患者级溯源明细表（按 stat_year 分区；04 §5.2 增补列 select_remark）
CREATE TABLE eval_metric_detail (
    id                  BIGSERIAL,
    batch_id            BIGINT       NOT NULL,
    metric_id           BIGINT       NOT NULL,
    scope_id            BIGINT       DEFAULT 0,
    stat_year           INT          NOT NULL,          -- 分区键：2022, 2023, 2024, 2025, 2026...
    stat_month          VARCHAR(7),                     -- YYYY-MM
    role_type           VARCHAR(8)   NOT NULL,          -- NUM(分子明细) / DEN(分母明细)
    manual_selected     BOOLEAN      DEFAULT TRUE,      -- 混合模式下供临床在分母池中勾选是否为分子
    patient_id          VARCHAR(64),                    -- 患者ID (PATIENT_ID)
    inp_no              VARCHAR(64),                    -- 住院号/门诊号 (INP_NO)
    visit_id            VARCHAR(32),                    -- 住院次数 (VISIT_ID)
    patient_name        VARCHAR(64),                    -- 姓名
    admit_dept_name     VARCHAR(128),                   -- 入院科室
    discharge_dept_name VARCHAR(128),                   -- 出院/当前科室
    admit_time          TIMESTAMP,                      -- 入院/就诊时间
    discharge_time      TIMESTAMP,                      -- 出院时间
    main_diag_code      VARCHAR(64),                    -- 出院主要诊断编码
    main_diag_name      VARCHAR(255),                   -- 主要诊断名称
    main_oper_code      VARCHAR(64),                    -- 第一位手术编码
    main_oper_name      VARCHAR(255),                   -- 第一位手术名称
    emr_sys_source      VARCHAR(16),                    -- 文书来源：MTL / JHEMR / NULL
    emr_doc_id          VARCHAR(128),                   -- 文书唯一标识（UHID+段落号 或 FILE_UNIQUE_ID）
    emr_doc_title       VARCHAR(255),                   -- 命中文书名称（如 入院记录 / 术后首次病程）
    ext_json            JSONB,                          -- 专科个性化展示列（如麻醉复苏时间、医嘱名称、危急值内容等）
    select_remark       VARCHAR(500),                   -- 04 §5.2 增补：混合指标勾选分子时的判定依据
    PRIMARY KEY (id, stat_year)
) PARTITION BY RANGE (stat_year);

-- 8. 人工填报佐证材料表
CREATE TABLE eval_manual_evidence (
    id                  BIGSERIAL PRIMARY KEY,
    metric_id           BIGINT       NOT NULL,
    scope_id            BIGINT       DEFAULT 0,
    stat_period         VARCHAR(16)  NOT NULL,
    folder_category     VARCHAR(128),                   -- 佐证分类目录：制度汇编/培训考核/督导检查/原始登记本
    file_name           VARCHAR(255) NOT NULL,
    file_path           VARCHAR(500) NOT NULL,          -- MinIO 或内网存储相对路径
    file_ext            VARCHAR(16),
    file_size           BIGINT,
    remark              VARCHAR(500),
    upload_user_id      BIGINT,
    upload_user_name    VARCHAR(64),
    upload_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 9. 三级复核签字留痕表
CREATE TABLE eval_audit_log (
    id                  BIGSERIAL PRIMARY KEY,
    result_id           BIGINT       NOT NULL,          -- 关联 eval_metric_result.id
    audit_level         VARCHAR(16)  NOT NULL,          -- L1_LIAISON(科室初审) / L2_AUDITOR(内审复核) / L3_FUNC(职能终审)
    action              VARCHAR(16)  NOT NULL,          -- SUBMIT / APPROVE / REJECT / UNLOCK
    opinion             VARCHAR(1000),                  -- 复核意见、抽查病历号记录或退回整改原因
    operator_id         BIGINT       NOT NULL,
    operator_name       VARCHAR(64)  NOT NULL,
    operator_dept       VARCHAR(128),
    operate_ip          VARCHAR(64),
    operate_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- ===== eval_metric_detail 分区：2021~2028 + DEFAULT =====
CREATE TABLE eval_metric_detail_2021 PARTITION OF eval_metric_detail FOR VALUES FROM (2021) TO (2022);
CREATE TABLE eval_metric_detail_2022 PARTITION OF eval_metric_detail FOR VALUES FROM (2022) TO (2023);
CREATE TABLE eval_metric_detail_2023 PARTITION OF eval_metric_detail FOR VALUES FROM (2023) TO (2024);
CREATE TABLE eval_metric_detail_2024 PARTITION OF eval_metric_detail FOR VALUES FROM (2024) TO (2025);
CREATE TABLE eval_metric_detail_2025 PARTITION OF eval_metric_detail FOR VALUES FROM (2025) TO (2026);
CREATE TABLE eval_metric_detail_2026 PARTITION OF eval_metric_detail FOR VALUES FROM (2026) TO (2027);
CREATE TABLE eval_metric_detail_2027 PARTITION OF eval_metric_detail FOR VALUES FROM (2027) TO (2028);
CREATE TABLE eval_metric_detail_2028 PARTITION OF eval_metric_detail FOR VALUES FROM (2028) TO (2029);
CREATE TABLE eval_metric_detail_default PARTITION OF eval_metric_detail DEFAULT;

-- ===== 04 §5.2 新增表 =====

-- 总表全量行（4,718 行，保持原始行序，用于章节树与原格式回填导出）
CREATE TABLE eval_master_row (
    row_no           INT PRIMARY KEY,            -- Excel 原始行号（数据从第 3 行开始）
    code             VARCHAR(64)  NOT NULL,      -- 原始编号，如 25.2.3.3.5.1.1 / 0.25.252.1 / 25.2.3.3.1(22)
    name             VARCHAR(500) NOT NULL,
    row_type         VARCHAR(8)   NOT NULL,      -- CHAPTER / METRIC / ATOM
    parent_code      VARCHAR(64),
    level            INT,
    metric_code      VARCHAR(64),                -- METRIC 行=自身编号；ATOM 行=所属指标编号；CHAPTER 行=NULL
    atom_role        VARCHAR(8),                 -- 仅 ATOM：NUM / DEN / SINGLE / EXTRA
    orig_values_json JSONB                       -- {"2022":{"data":"84.0","source":"医院系统提取"},...}
);

-- 跑批子任务
CREATE TABLE eval_exec_task (
    id             BIGSERIAL PRIMARY KEY,
    batch_id       BIGINT       NOT NULL REFERENCES eval_exec_batch(id),
    metric_id      BIGINT       NOT NULL,
    scope_id       BIGINT       NOT NULL DEFAULT 0,
    sql_id         BIGINT,
    sql_role       VARCHAR(16),
    datasource_key VARCHAR(32),
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING / RUNNING / SUCCESS / FAILED / SKIPPED
    row_count      INT          DEFAULT 0,
    elapsed_ms     BIGINT,
    error_msg      VARCHAR(2000),
    start_time     TIMESTAMP,
    end_time       TIMESTAMP
);

-- 省平台对账
CREATE TABLE eval_prov_reconcile (
    id           BIGSERIAL PRIMARY KEY,
    import_id    VARCHAR(64)  NOT NULL,
    stat_year    INT          NOT NULL,
    metric_code  VARCHAR(64),
    metric_name  VARCHAR(500),
    prov_num     NUMERIC(18,4),
    prov_den     NUMERIC(18,4),
    prov_val     NUMERIC(18,4),
    hosp_num     NUMERIC(18,4),
    hosp_den     NUMERIC(18,4),
    hosp_val     NUMERIC(18,4),
    flag         VARCHAR(32),     -- OK / PROV_DEN_ZERO / PROV_NUM_ZERO / DIFF_GT_5 / NOT_FOUND
    remark       VARCHAR(500),
    import_user  VARCHAR(64),
    import_time  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ===== 全部索引（01 方案 + 04 §5.2）=====
CREATE INDEX idx_dict_alias_code    ON eval_metric_dict(alias_code);
CREATE INDEX idx_res_metric_period  ON eval_metric_result(metric_id, stat_period, is_current);
CREATE INDEX idx_master_row_code    ON eval_master_row(code);
CREATE INDEX idx_master_row_metric  ON eval_master_row(metric_code);
CREATE INDEX idx_exec_task_batch    ON eval_exec_task(batch_id);
CREATE INDEX idx_res_scope_current  ON eval_metric_result(metric_id, scope_id, stat_period, is_current);
CREATE INDEX idx_detail_lookup      ON eval_metric_detail(metric_id, scope_id, stat_year, role_type, batch_id);
