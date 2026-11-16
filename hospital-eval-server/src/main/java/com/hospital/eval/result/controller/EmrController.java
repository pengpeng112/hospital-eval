package com.hospital.eval.result.controller;

import com.hospital.eval.common.R;
import com.hospital.eval.result.service.EmrDocService;
import com.hospital.eval.system.service.OperLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 院内 360 视图链接与病历文书调阅（TASK-10 步骤 6~7，登录即可，写 EMR/VIEW_DOC 日志）。
 */
@RestController
@RequestMapping("/api/emr")
@RequiredArgsConstructor
public class EmrController {

    private final EmrDocService emrDocService;
    private final OperLogService operLogService;

    @Value("${eval.emr-360-url-template:}")
    private String emr360Template;

    @GetMapping("/360-url")
    public R<Map<String, Object>> url360(@RequestParam(required = false) String patientId,
                                         @RequestParam(required = false) String visitId,
                                         @RequestParam(required = false) String inpNo) {
        String url = null;
        if (emr360Template != null && !emr360Template.isBlank()
                && !emr360Template.contains("<HOSP_360_HOST>")) {
            url = emr360Template
                    .replace("{patientId}", enc(patientId))
                    .replace("{visitId}", enc(visitId))
                    .replace("{inpNo}", enc(inpNo));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("url", url);
        return R.ok(resp);
    }

    @GetMapping("/documents")
    public R<Map<String, Object>> documents(@RequestParam(required = false) String patientId,
                                            @RequestParam(required = false) String visitId,
                                            @RequestParam(required = false) String inpNo) {
        return R.ok(emrDocService.documents(patientId, visitId, inpNo));
    }

    @GetMapping("/document-content")
    public R<Map<String, Object>> content(@RequestParam String sys,
                                          @RequestParam String docId,
                                          @RequestParam(required = false) String patientId,
                                          @RequestParam(required = false) String visitId,
                                          @RequestParam(required = false) String inpNo) {
        Map<String, Object> content = emrDocService.content(sys, docId, patientId, visitId, inpNo);
        operLogService.log("EMR", "VIEW_DOC", "emr_doc", docId, Map.of("sys", sys));
        return R.ok(content);
    }

    private String enc(String v) {
        return v == null ? "" : URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
