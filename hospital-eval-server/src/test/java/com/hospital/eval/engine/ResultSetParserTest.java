package com.hospital.eval.engine;

import com.hospital.eval.engine.service.ResultSetParser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结果集解析器用例（TASK-07 步骤 7，Mockito 模拟 ResultSet，不连数据库）。
 */
class ResultSetParserTest {

    private ResultSetMetaData meta(String[] labels) throws Exception {
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(labels.length);
        for (int i = 1; i <= labels.length; i++) {
            when(md.getColumnLabel(i)).thenReturn(labels[i - 1]);
        }
        return md;
    }

    /** 按行数据打桩：next() 序列与每列的逐行返回值（Mockito thenReturn 链）。 */
    private void stubRows(ResultSet rs, Object[][] data) throws Exception {
        Boolean[] seq = new Boolean[data.length + 1];
        Arrays.fill(seq, true);
        seq[data.length] = false;
        when(rs.next()).thenReturn(seq[0], Arrays.copyOfRange(seq, 1, seq.length));
        for (int c = 1; c <= data[0].length; c++) {
            Object[] col = new Object[data.length];
            for (int r = 0; r < data.length; r++) {
                col[r] = data[r][c - 1];
            }
            when(rs.getObject(c)).thenReturn(col[0], Arrays.copyOfRange(col, 1, col.length));
        }
    }

    // 1. SUM：列 年份/分子，两行 2024/2025
    @Test
    void sumTwoYears() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = meta(new String[]{"年份", "分子"});
        when(rs.getMetaData()).thenReturn(md);
        stubRows(rs, new Object[][]{{2024, 10}, {2025, 20}});
        ResultSetParser parser = new ResultSetParser();
        ResultSetParser.SumResult out = new ResultSetParser.SumResult();
        RowCallbackHandler handler = parser.sumHandler(
                new ResultSetParser.ParseContext("NUM_SUM", 2022, 2025, Set.of(2024, 2025)), out);
        for (int i = 0; i < 2; i++) {
            handler.processRow(rs);
        }
        assertEquals(new BigDecimal("10"), out.getSumByYear().get(2024));
        assertEquals(new BigDecimal("20"), out.getSumByYear().get(2025));
        assertEquals(2, out.getRawRowCount());
        assertFalse(out.isMultiRowsSummed());
    }

    // 2. SUM：同一年两行 → 相加并标记 MULTI_ROWS_SUMMED
    @Test
    void sumSameYearAccumulates() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = meta(new String[]{"年份", "分子"});
        when(rs.getMetaData()).thenReturn(md);
        stubRows(rs, new Object[][]{{2025, 5}, {2025, 8}});
        ResultSetParser parser = new ResultSetParser();
        ResultSetParser.SumResult out = new ResultSetParser.SumResult();
        RowCallbackHandler handler = parser.sumHandler(
                new ResultSetParser.ParseContext("DEN_SUM", 2022, 2025, Set.of(2025)), out);
        for (int i = 0; i < 2; i++) {
            handler.processRow(rs);
        }
        assertEquals(new BigDecimal("13"), out.getSumByYear().get(2025));
        assertTrue(out.isMultiRowsSummed());
    }

    // 3. DTL：600 字的“文书内容”进 ext 且截断为 500
    @Test
    void dtlExtTruncated() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        String longText = "文".repeat(600);
        ResultSetMetaData md = meta(new String[]{"年份", "住院号", "患者ID", "姓名", "住院次数", "出院时间", "文书内容"});
        when(rs.getMetaData()).thenReturn(md);
        stubRows(rs, new Object[][]{{2025, "INP001", "P001", "张三", 1,
                Timestamp.valueOf("2025-03-04 10:20:30"), longText}});
        ResultSetParser parser = new ResultSetParser();
        List<ResultSetParser.ParsedDetail> collected = new ArrayList<>();
        RowCallbackHandler handler = parser.detailHandler(
                new ResultSetParser.ParseContext("NUM_DTL", 2022, 2025, Set.of(2025)), collected::add);
        handler.processRow(rs);
        assertEquals(1, collected.size());
        ResultSetParser.ParsedDetail d = collected.get(0);
        assertEquals("INP001", d.getInpNo());
        assertEquals("P001", d.getPatientId());
        assertEquals("张三", d.getPatientName());
        assertEquals("2025-03", d.getStatMonth());
        assertEquals("文书内容", new ArrayList<>(d.getExt().keySet()).get(0));
        assertEquals(500, new ArrayList<>(d.getExt().values()).get(0).length());
    }

    // 4. 无年份列且 year_from == year_to == 2025 → 全部归 2025
    @Test
    void noYearColumnFixedYear() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = meta(new String[]{"分子", "人次"});
        when(rs.getMetaData()).thenReturn(md);
        stubRows(rs, new Object[][]{{7, 3}});
        ResultSetParser parser = new ResultSetParser();
        ResultSetParser.SumResult out = new ResultSetParser.SumResult();
        RowCallbackHandler handler = parser.sumHandler(
                new ResultSetParser.ParseContext("NUM_SUM", 2025, 2025, Set.of(2025)), out);
        handler.processRow(rs);
        assertEquals(new BigDecimal("7"), out.getSumByYear().get(2025));
    }

    // 5. 无年份列且范围 2022~2025 → 抛出 NO_YEAR_COLUMN
    @Test
    void noYearColumnWideRangeThrows() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = meta(new String[]{"分子", "人次"});
        when(rs.getMetaData()).thenReturn(md);
        stubRows(rs, new Object[][]{{7, 3}});
        ResultSetParser parser = new ResultSetParser();
        ResultSetParser.SumResult out = new ResultSetParser.SumResult();
        RowCallbackHandler handler = parser.sumHandler(
                new ResultSetParser.ParseContext("NUM_SUM", 2022, 2025, Set.of(2022)), out);
        assertThrows(ResultSetParser.NoYearColumnException.class, () -> handler.processRow(rs));
    }

    // 6. 年份 2021 的行（SQL 范围 2022~2024）被过滤掉；原始行数含被过滤行
    @Test
    void outOfRangeYearFiltered() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = meta(new String[]{"年份", "分子"});
        when(rs.getMetaData()).thenReturn(md);
        stubRows(rs, new Object[][]{{2021, 99}, {2023, 11}});
        // 解析器对被过滤行（2021）不读数值列：数值列首个读点属于 2023 行 → 桩直接给 11
        when(rs.getObject(2)).thenReturn(11);
        ResultSetParser parser = new ResultSetParser();
        ResultSetParser.SumResult out = new ResultSetParser.SumResult();
        RowCallbackHandler handler = parser.sumHandler(
                new ResultSetParser.ParseContext("NUM_SUM", 2022, 2024, Set.of(2023)), out);
        for (int i = 0; i < 2; i++) {
            handler.processRow(rs);
        }
        assertFalse(out.getSumByYear().containsKey(2021));
        assertEquals(new BigDecimal("11"), out.getSumByYear().get(2023));
        assertEquals(2, out.getRawRowCount());
    }
}
