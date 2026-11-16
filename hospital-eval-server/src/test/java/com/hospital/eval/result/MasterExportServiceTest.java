package com.hospital.eval.result;

import com.hospital.eval.result.service.MasterExportService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 总表回填导出核心用例（TASK-10 步骤 9，纯内存构造，不连数据库）。
 */
class MasterExportServiceTest {

    private MasterExportService.MasterRowData row(int rowNo, String code, String name,
                                                  String type, String metricCode, String atomRole) {
        var r = new MasterExportService.MasterRowData();
        r.rowNo = rowNo;
        r.code = code;
        r.name = name;
        r.rowType = type;
        r.metricCode = metricCode;
        r.atomRole = atomRole;
        return r;
    }

    @Test
    void buildCoreFillsAndFallsBack() {
        List<Integer> years = List.of(2024, 2025);

        // 1 章节 + 1 指标（NUM/DEN 两原子）+ 1 个 3 原子复合指标
        var chapter = row(3, "25.2.1", "第一章", "CHAPTER", null, null);
        var metric = row(4, "25.2.1.1", "示例指标", "METRIC", "25.2.1.1", null);
        metric.applicable = true;
        metric.origData.put("2024", "88.0");
        metric.origSource.put("2024", "医院系统提取");
        var numAtom = row(5, "0.25.1.1", "分子", "ATOM", "25.2.1.1", "NUM");
        var denAtom = row(6, "0.25.1.2", "分母", "ATOM", "25.2.1.1", "DEN");
        var compound = row(7, "25.2.1.2", "复合指标", "METRIC", "25.2.1.2", null);
        var extra1 = row(8, "0.25.1.3", "子项1", "ATOM", "25.2.1.2", "EXTRA");
        var extra2 = row(9, "0.25.1.4", "子项2", "ATOM", "25.2.1.2", "EXTRA");
        var extra3 = row(10, "0.25.1.5", "子项3", "ATOM", "25.2.1.2", "EXTRA");
        compound.origData.put("2025", "77.7");
        compound.origSource.put("2025", "手工统计");

        List<MasterExportService.MasterRowData> rows = List.of(
                chapter, metric, numAtom, denAtom, compound, extra1, extra2, extra3);

        // 结果：metric(4) 2025 有系统结果；NUM 原子有值；DEN 原子 2025 = 0；EXTRA 无
        Map<String, MasterExportService.HospitalYearValue> results = new TreeMap<>();
        results.put("4|2025", hv(null, new BigDecimal("50"), "SYS", true));
        results.put("5|2025", hv(new BigDecimal("10"), null, "SYS", true));
        var den0 = new MasterExportService.HospitalYearValue();
        den0.denominatorVal = new BigDecimal("0");
        den0.dataSourceTag = "SYS";
        den0.applicable = true;
        results.put("6|2025", den0);

        MasterExportService.ExportCore core =
                MasterExportService.buildCore(rows, years, results);

        // METRIC 行：2024 无系统结果 → 兜底原表 88.0；2025 → 系统结果 50
        assertEquals("88.0", core.cellKeys.get("4|2024").data());
        assertEquals("ORIG", core.cellKeys.get("4|2024").origin());
        assertEquals("50", core.cellKeys.get("4|2025").data());
        assertEquals("SYS", core.cellKeys.get("4|2025").origin());
        assertEquals("医院系统提取", core.cellKeys.get("4|2025").source());

        // NUM 原子行 2025 = 10；DEN 原子行 2025 = 0（0 也是系统结果，非兜底）
        assertEquals("10", core.cellKeys.get("5|2025").data());
        assertEquals("0", core.cellKeys.get("6|2025").data());
        assertEquals("SYS", core.cellKeys.get("6|2025").origin());

        // 复合指标 EXTRA 原子取 atom_values_json 键值（本用例未给 → 兜底/空）
        // compound(7) 2025 兜底 77.7
        assertEquals("77.7", core.cellKeys.get("7|2025").data());
        assertEquals("ORIG", core.cellKeys.get("7|2025").origin());

        // 计数：SYS = metric2025 + num + den + extra 部分
        assertEquals(3, core.counters.sysCells);
        assertEquals(2, core.counters.origCells); // metric 2024 + compound 2025
    }

    private MasterExportService.HospitalYearValue hv(BigDecimal num, BigDecimal val,
                                                     String tag, boolean applicable) {
        var v = new MasterExportService.HospitalYearValue();
        v.numeratorVal = num;
        v.resultVal = val;
        v.dataSourceTag = tag;
        v.applicable = applicable;
        return v;
    }
}
