package com.nifty.analysis.collector.client.impl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Validates the SmartWebSocketV2 LTP-mode binary frame parser offline, by hand-building a
 * frame to the documented layout. The live connection must still be verified during market hours.
 */
class AngelOneStreamClientTest {

    /** Builds an LTP-mode frame: mode@0, exch@1, token@2(25), ltp(paise)@43(int64 LE). */
    private byte[] frame(int mode, int exch, String token, long ltpPaise) {
        byte[] data = new byte[51];
        data[0] = (byte) mode;
        data[1] = (byte) exch;
        byte[] tok = token.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tok, 0, data, 2, Math.min(tok.length, 25));
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(43, ltpPaise);
        return data;
    }

    @Test
    void parsesTokenAndLtp() {
        // 23550.75 -> 2355075 paise
        AngelOneStreamClient.Tick t = AngelOneStreamClient.parseTick(frame(1, 1, "99926000", 2355075L));
        assertEquals(1, t.mode());
        assertEquals(1, t.exchangeType());
        assertEquals("99926000", t.token());
        assertEquals(23550.75, t.ltp(), 1e-9);
    }

    @Test
    void parsesVixToken() {
        AngelOneStreamClient.Tick t = AngelOneStreamClient.parseTick(frame(1, 1, "99926017", 1342L));
        assertEquals("99926017", t.token());
        assertEquals(13.42, t.ltp(), 1e-9); // 1342 paise
    }

    @Test
    void returnsNullForShortFrame() {
        assertNull(AngelOneStreamClient.parseTick(new byte[10]));
        assertNull(AngelOneStreamClient.parseTick(null));
    }

    /** SnapQuote frame: token@2(25), LTP@43, Volume@67, Open Interest@131 (all int64 LE). */
    private byte[] snapQuoteFrame(String token, long ltpPaise, long volume, long oi) {
        byte[] data = new byte[379];
        data[0] = 3; // SnapQuote mode
        data[1] = 2; // NSE_FO
        byte[] tok = token.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tok, 0, data, 2, Math.min(tok.length, 25));
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(43, ltpPaise);
        bb.putLong(67, volume);
        bb.putLong(131, oi);
        return data;
    }

    @Test
    void parsesSnapQuoteOiAndLtp() {
        AngelOneStreamClient.SnapQuoteTick t =
                AngelOneStreamClient.parseSnapQuote(snapQuoteFrame("44012", 15075L, 1200000L, 4500000L));
        assertEquals("44012", t.token());
        assertEquals(150.75, t.ltp(), 1e-9); // 15075 paise
        assertEquals(1200000L, t.volume());
        assertEquals(4500000L, t.oi());
    }

    @Test
    void returnsNullForShortSnapQuoteFrame() {
        assertNull(AngelOneStreamClient.parseSnapQuote(new byte[100]));
        assertNull(AngelOneStreamClient.parseSnapQuote(null));
    }
}
