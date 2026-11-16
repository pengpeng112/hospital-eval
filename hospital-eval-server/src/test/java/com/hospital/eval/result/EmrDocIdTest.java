package com.hospital.eval.result;

import com.hospital.eval.result.service.EmrDocService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DOC_ID 拆分用例（TASK-10 步骤 9：按最后一个 - 拆 UHID 与 文书ID）。
 */
class EmrDocIdTest {

    @Test
    void splitsOnLastDash() {
        assertArrayEquals(new String[]{"ABC-12", "345"},
                EmrDocService.splitDocId("ABC-12-345"));
    }

    @Test
    void splitsMultiDash() {
        assertArrayEquals(new String[]{"U-1-2-3", "99"},
                EmrDocService.splitDocId("U-1-2-3-99"));
    }

    @Test
    void rejectsInvalid() {
        assertThrows(RuntimeException.class, () -> EmrDocService.splitDocId("nodash"));
        assertThrows(RuntimeException.class, () -> EmrDocService.splitDocId("-tail"));
        assertThrows(RuntimeException.class, () -> EmrDocService.splitDocId("head-"));
    }
}
