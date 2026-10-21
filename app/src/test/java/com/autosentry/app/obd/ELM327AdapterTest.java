package com.autosentry.app.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ELM327AdapterTest {
    @Test
    public void icpIsRawTimesPointFiveSixPsi() {
        int[] data = ELM327Adapter.parseReply("62 14 46 03 7D\r\r>", "621446");
        assertEquals(893 * 0.56, ELM327Adapter.decodeFordIcpPsi(data), 1e-9);
        assertTrue(Double.isNaN(ELM327Adapter.decodeFordIcpPsi(null)));
        assertTrue(Double.isNaN(ELM327Adapter.decodeFordIcpPsi(new int[]{3})));
    }

    @Test
    public void iprIsPercentOfFullScale() {
        int[] data = ELM327Adapter.parseReply("621434FF\r>", "621434");
        assertEquals(100.0, ELM327Adapter.decodeFordIprPercent(data), 1e-9);
        assertTrue(Double.isNaN(ELM327Adapter.decodeFordIprPercent(ELM327Adapter.parseReply("NO DATA\r>", "621434"))));
    }
}
