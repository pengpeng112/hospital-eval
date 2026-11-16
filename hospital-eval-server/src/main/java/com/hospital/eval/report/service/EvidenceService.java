package com.hospital.eval.report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.eval.common.BizException;
import com.hospital.eval.metric.entity.MetricDeptScope;
import com.hospital.eval.metric.mapper.MetricDeptScopeMapper;
import com.hospital.eval.report.entity.ManualEvidence;
import com.hospital.eval.report.mapper.ManualEvidenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 佐证附件（TASK-11 步骤 5）：上传 / 列表 / 删除（移入 _deleted）/ 预览 ticket。
 * ticket = Base64Url(HMAC_SHA256(JWT_SECRET, id + ":" + expire)) + "." + expire，10 分钟有效。
 */
@Service
@RequiredArgsConstructor
public class EvidenceService {

    private static final Set<String> EXT_WHITELIST = Set.of("doc", "docx", "xls", "xlsx", "pdf", "jpg", "jpeg", "png");
    private static final long TICKET_TTL_SECONDS = 600;

    private final ManualEvidenceMapper evidenceMapper;
    private final MetricDeptScopeMapper scopeMapper;
    private final com.hospital.eval.report.service.ScopePermission permission;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Value("${eval.jwt-secret:}")
    private String jwtSecret;

    @Value("${eval.upload-dir:/data/uploads}")
    private String uploadDir;

    @Value("${EVAL_PREVIEW_FILE_BASE_URL:}")
    private String previewFileBaseUrl;

    // ---------------- 上传 ----------------

    public long upload(org.springframework.web.multipart.MultipartFile file, long metricId, long scopeId,
                       String statPeriod, String folderCategory, String remark,
                       long userId, String userName) throws java.io.IOException {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new BizException(400, "未配置 JWT_SECRET");
        }
        MetricDeptScope scope = scopeMapper.selectById(scopeId);
        if (scope == null) {
            throw new BizException(400, "科室实例不存在");
        }
        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extOf(originalName);
        if (!EXT_WHITELIST.contains(ext)) {
            throw new BizException(400, "不支持的文件类型：" + ext);
        }
        if (file.getSize() > 100L * 1024 * 1024) {
            throw new BizException(400, "文件超过 100MB 上限");
        }
        checkStateEditable(metricId, scopeId, statPeriod);

        LocalDateTime now = LocalDateTime.now();
        String storedName = UUID.randomUUID() + "." + ext;
        String relativePath = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy"))
                + "/" + now.format(java.time.format.DateTimeFormatter.ofPattern("MM"))
                + "/" + storedName;
        Path target = Path.of(uploadDir, relativePath);
        Files.createDirectories(target.getParent());
        file.transferTo(target.toAbsolutePath().toFile());

        ManualEvidence ev = new ManualEvidence();
        ev.setMetricId(metricId);
        ev.setScopeId(scopeId);
        ev.setStatPeriod(statPeriod);
        ev.setFolderCategory(folderCategory);
        ev.setFileName(originalName);
        ev.setFilePath(relativePath);
        ev.setFileExt(ext);
        ev.setFileSize(file.getSize());
        ev.setRemark(remark);
        ev.setUploadUserId(userId);
        ev.setUploadUserName(userName);
        ev.setUploadTime(now);
        evidenceMapper.insert(ev);
        return ev.getId();
    }

    public List<Map<String, Object>> list(Long metricId, Long scopeId, String statPeriod) {
        QueryWrapper<ManualEvidence> qw = new QueryWrapper<>();
        if (metricId != null) {
            qw.eq("metric_id", metricId);
        }
        if (scopeId != null) {
            qw.eq("scope_id", scopeId);
        }
        if (statPeriod != null && !statPeriod.isBlank()) {
            qw.eq("stat_period", statPeriod);
        }
        qw.orderByDesc("upload_time");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ManualEvidence e : evidenceMapper.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("folderCategory", e.getFolderCategory());
            m.put("fileName", e.getFileName());
            m.put("fileExt", e.getFileExt());
            m.put("fileSize", e.getFileSize());
            m.put("uploadUserName", e.getUploadUserName());
            m.put("uploadTime", e.getUploadTime());
            out.add(m);
        }
        return out;
    }

    public ManualEvidence requireEvidence(long id) {
        ManualEvidence e = evidenceMapper.selectById(id);
        if (e == null) {
            throw new BizException(400, "附件不存在");
        }
        return e;
    }

    /** 删除：DB 记录删除，物理文件移入 {upload-dir}/_deleted/（不真正删除，便于追溯）。 */
    public void delete(long id, long userId, boolean admin, long currentMetricId, long currentScopeId,
                       String currentPeriod) throws java.io.IOException {
        ManualEvidence e = requireEvidence(id);
        boolean uploader = e.getUploadUserId() != null && e.getUploadUserId() == userId;
        if (!uploader && !admin) {
            throw new BizException(403, "仅上传人或管理员可删除");
        }
        checkStateEditable(currentMetricId, currentScopeId, currentPeriod);
        Path source = Path.of(uploadDir, e.getFilePath());
        if (Files.exists(source)) {
            Path deletedDir = Path.of(uploadDir, "_deleted");
            Files.createDirectories(deletedDir);
            Path target = deletedDir.resolve(source.getFileName());
            java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        evidenceMapper.deleteById(id);
    }

    // ---------------- 预览 ticket ----------------

    public Map<String, Object> previewUrl(long id) {
        ManualEvidence e = requireEvidence(id);
        if (previewFileBaseUrl == null || previewFileBaseUrl.isBlank()
                || previewFileBaseUrl.contains("<HOSP_360_HOST>")) {
            throw new BizException(400, "未配置预览地址");
        }
        long expire = System.currentTimeMillis() / 1000 + TICKET_TTL_SECONDS;
        String ticket = createTicket(id, expire);
        String rawUrl = previewFileBaseUrl + "/api/evidence/" + id + "/file?ticket=" + ticket
                + "&fullfilename=" + java.net.URLEncoder.encode(
                        e.getFileName() == null ? "file" : e.getFileName(), java.nio.charset.StandardCharsets.UTF_8);
        String url = "/kkview/onlinePreview?url=" + java.net.URLEncoder.encode(
                Base64.getEncoder().encodeToString(rawUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("url", url);
        resp.put("rawUrl", rawUrl);
        return resp;
    }

    public String createTicket(long id, long expireEpochSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((id + ":" + expireEpochSeconds)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig) + "." + expireEpochSeconds;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("ticket 生成失败", e);
        }
    }

    /** 校验 ticket：格式、有效期、签名。失败抛 BizException(403)。 */
    public void verifyTicket(long id, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BizException(403, "ticket 缺失");
        }
        int dot = ticket.lastIndexOf('.');
        if (dot <= 0 || dot == ticket.length() - 1) {
            throw new BizException(403, "ticket 格式非法");
        }
        String sigB64 = ticket.substring(0, dot);
        long expire;
        try {
            expire = Long.parseLong(ticket.substring(dot + 1));
        } catch (NumberFormatException e) {
            throw new BizException(403, "ticket 有效期非法");
        }
        if (expire < System.currentTimeMillis() / 1000) {
            throw new BizException(403, "ticket 已过期");
        }
        String expected = createTicket(id, expire);
        String expectedSig = expected.substring(0, expected.lastIndexOf('.'));
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                sigB64.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new BizException(403, "ticket 校验失败");
        }
    }

    public Path filePathOf(long id) {
        ManualEvidence e = requireEvidence(id);
        Path path = Path.of(uploadDir, e.getFilePath() == null ? "" : e.getFilePath());
        if (!Files.exists(path)) {
            throw new BizException(400, "文件不存在于存储");
        }
        return path;
    }

    public String fileExtOf(long id) {
        ManualEvidence e = requireEvidence(id);
        return e.getFileExt() == null ? "" : e.getFileExt().toLowerCase();
    }

    public String fileNameOf(long id) {
        ManualEvidence e = requireEvidence(id);
        return e.getFileName() == null ? "file" : e.getFileName();
    }

    // ---------------- 内部 ----------------

    /** 状态校验：该实例该年当期行必须是无结果 / DRAFT / L2_REJECT（TASK-11 步骤 5）。 */
    public void checkStateEditable(long metricId, long scopeId, String statPeriod) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM eval_metric_result WHERE metric_id = ? AND scope_id = ? "
                        + "AND stat_period = ? AND is_current = true",
                Integer.class, metricId, scopeId, statPeriod);
        if (n != null && n > 0) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT audit_status FROM eval_metric_result WHERE metric_id = ? AND scope_id = ? "
                            + "AND stat_period = ? AND is_current = true",
                    String.class, metricId, scopeId, statPeriod);
            if (!"DRAFT".equals(status) && !"L2_REJECT".equals(status)) {
                throw new BizException(400, "当前状态不允许修改");
            }
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    /** 按扩展名返回 Content-Type。 */
    public String contentTypeOf(String ext) {
        return switch (ext == null ? "" : ext) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/octet-stream";
        };
    }
}
