package com.hospital.eval.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.eval.report.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
