package com.hospital.eval.algo.datasource;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据源路由器（04 §7.7 的 Java 版，与 migration/common/excel_utils.py 的
 * route_datasource 逐行为对应，一致性由 DatasourceRouterTest 保证）。
 */
public final class DatasourceRouter {

    private static final Pattern SCHEMA_TABLE =
            Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)\\.([A-Za-z][A-Za-z0-9_]*)");
    private static final Set<String> SCHEMAS = Set.of(
            "HIS", "HISUSER", "MEDREC", "ORDADM", "COMM", "MTL", "JHEMR", "LIS", "SM", "PACS");
    private static final Set<String> HIS_GROUP = Set.of("MEDREC", "ORDADM", "COMM", "HISUSER");
    private static final Set<String> ODS_GROUP = Set.of("HIS", "MTL", "JHEMR", "LIS", "SM", "PACS");

    private DatasourceRouter() {
    }

    /** 路由结果：datasource_key / ds_flag / emr_sys。 */
    public record Route(String datasourceKey, String dsFlag, String emrSys) {
    }

    public static Route route(String sql) {
        String s = sql == null ? "" : sql;
        Set<String> schemas = new LinkedHashSet<>();
        Matcher m = SCHEMA_TABLE.matcher(s);
        while (m.find()) {
            String sch = m.group(1).toUpperCase();
            if (SCHEMAS.contains(sch)) {
                schemas.add(sch);
            }
        }
        String upper = s.toUpperCase();
        String emrSys;
        if (upper.contains("MTL.") && upper.contains("JHEMR.")) {
            emrSys = "MTL+JHEMR";
        } else if (upper.contains("MTL.")) {
            emrSys = "MTL";
        } else if (upper.contains("JHEMR.")) {
            emrSys = "JHEMR";
        } else {
            emrSys = "NONE";
        }
        boolean hitHis = schemas.stream().anyMatch(HIS_GROUP::contains);
        boolean hitOds = schemas.stream().anyMatch(ODS_GROUP::contains);
        String key;
        String flag;
        if (hitHis && hitOds) {
            key = "ODS_RO";
            flag = "DS_CONFLICT";
        } else if (hitHis) {
            key = "HIS_RO";
            flag = null;
        } else if (hitOds) {
            key = "ODS_RO";
            flag = null;
        } else {
            key = "ODS_RO";
            flag = "DS_UNKNOWN";
        }
        return new Route(key, flag, emrSys);
    }
}
