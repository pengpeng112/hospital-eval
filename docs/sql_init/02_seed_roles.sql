-- 由 TASK-04 生成：角色种子数据（04 契约 §5.3，幂等）
INSERT INTO sys_role (role_code, role_name) VALUES
    ('ADMIN',       '信息科管理员'),
    ('LIAISON',     '科室联络员'),
    ('AUDITOR',     '内审员'),
    ('FUNC_LEADER', '职能终审'),
    ('EXPERT',      '迎检专家(只读)'),
    ('LEADER',      '院领导(只读)')
ON CONFLICT (role_code) DO NOTHING;
