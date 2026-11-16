package com.hospital.eval.report.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hospital.eval.common.BizException;
import com.hospital.eval.common.R;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.report.mapper.ManualEvidenceMapper;
import com.hospital.eval.report.service.EvidenceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 佐证附件接口（TASK-11 步骤 5；/file 不需要登录但校验 ticket）。
 */
@RestController
@RequestMapping("/api/evidence")
@RequiredArgsConstructor
public class EvidenceController {

    private final EvidenceService evidenceService;
    private final ManualEvidenceMapper evidenceMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final JdbcTemplate jdbcTemplate;

    private long userId() {
        return StpUtil.getLoginIdAsLong();
    }

    private boolean isAdmin(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? AND r.role_code = 'ADMIN'",
                Integer.class, userId);
        return n != null && n > 0;
    }

    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam long metricId,
                                         @RequestParam long scopeId,
                                         @RequestParam String statPeriod,
                                         @RequestParam(required = false) String folderCategory,
                                         @RequestParam(required = false) String remark) throws java.io.IOException {
        var scope = scopeMapper.selectById(scopeId);
        Long liaison = scope == null ? null : scope.getLiaisonUserId();
        long me = userId();
        if (!isAdmin(me) && (liaison == null || liaison != me)) {
            throw new BizException(403, "仅本科室联络员或管理员可上传");
        }
        long id = evidenceService.upload(file, metricId, scopeId, statPeriod,
                folderCategory, remark, me, "user#" + me);
        return R.ok(Map.of("id", id));
    }

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long metricId,
                                             @RequestParam(required = false) Long scopeId,
                                             @RequestParam(required = false) String statPeriod) {
        return R.ok(evidenceService.list(metricId, scopeId, statPeriod));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id, HttpServletRequest request) throws java.io.IOException {
        var ev = evidenceMapper.selectById(id);
        if (ev == null) {
            throw new BizException(400, "附件不存在");
        }
        long me = userId();
        boolean uploader = ev.getUploadUserId() != null && ev.getUploadUserId() == me;
        if (!uploader && !isAdmin(me)) {
            throw new BizException(403, "仅上传人或管理员可删除");
        }
        evidenceService.delete(id, me, isAdmin(me), ev.getMetricId(), ev.getScopeId(), ev.getStatPeriod());
        return R.ok();
    }

    @GetMapping("/{id}/preview-url")
    public R<Map<String, Object>> previewUrl(@PathVariable long id) {
        return R.ok(evidenceService.previewUrl(id));
    }

    @GetMapping("/{id}/file")
    public void file(@PathVariable long id,
                     @RequestParam(required = false) String ticket,
                     HttpServletResponse response) throws java.io.IOException {
        evidenceService.verifyTicket(id, ticket);
        java.nio.file.Path path = evidenceService.filePathOf(id);
        String mime = evidenceService.contentTypeOf(evidenceService.fileExtOf(id));
        response.setContentType(mime);
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''"
                + URLEncoder.encode(evidenceService.fileNameOf(id), StandardCharsets.UTF_8)
                .replace("+", "%20"));
        try (var in = java.nio.file.Files.newInputStream(path);
             var out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }
}
