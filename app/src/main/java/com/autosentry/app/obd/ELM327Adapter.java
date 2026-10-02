package com.autosentry.app.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Real ELM327 / OBDLink Bluetooth adapter layer.
 *
 * Two things matter for a 2000 F-250: the truck talks J1850 PWM (not CAN),
 * so the protocol must be left on auto-detect, and replies must be parsed
 * without depending on spacing, since the adapter is told to drop spaces.
 * Every read has a timeout so a silent adapter can't freeze the poll loop.
 */
public class ELM327Adapter {
    private static final String TAG = "ELM327Adapter";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final long AT_TIMEOUT_MS = 2500L;
    private static final long RESET_TIMEOUT_MS = 4000L;
    // First real query makes the adapter search protocols / init the bus, which can take several seconds.
    private static final long BUS_INIT_TIMEOUT_MS = 15000L;
    private static final long PID_TIMEOUT_MS = 2500L;

    // Ford enhanced (Mode 22) PIDs on J1850 PWM answer only when addressed to the PCM (0x10)
    // from the tester (F1); Mode 01 needs the functional OBD header back afterwards.
    private static final String FORD_PCM_HEADER = "ATSHC410F1";
    private static final String OBD_PWM_HEADER = "ATSH616AF1";

    private BluetoothSocket socket = null;
    private String lastEnhancedReply = "";
    private InputStream inputStream = null;
    private OutputStream outputStream = null;
    private String adapterAddress = null;
    private boolean connected = false;
    // Which RFCOMM channel last connected; null until one has.
    private Boolean useInsecure = null;

    public ELM327Adapter(String adapterAddress) {
        this.adapterAddress = adapterAddress;
    }

    public ELM327Adapter() {}
    public void setAdapterAddress(String address) { this.adapterAddress = address; }

    public synchronized boolean connect() throws IOException, InterruptedException {
        if (connected) disconnect();
        if (this.adapterAddress == null || this.adapterAddress.isEmpty()) {
            throw new IOException("Adapter address not set");
        }

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            throw new IOException("Bluetooth adapter not available or disabled");
        }

        try {
            bluetoothAdapter.cancelDiscovery(); // discovery makes RFCOMM connects flaky
        } catch (SecurityException ignored) {
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(adapterAddress);
        if (useInsecure != null) {
            // Asleep adapters take ~5 s to time out per attempt; once one channel has
            // worked, don't double every retry by also trying the other.
            openSocket(device, useInsecure);
        } else {
            try {
                openSocket(device, false);
                useInsecure = false;
            } catch (IOException first) {
                // Many ELM327 clones only accept the insecure channel.
                openSocket(device, true);
                useInsecure = true;
            }
        }

        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        connected = true;

        try {
            // Reset, echo off, linefeeds off, spaces off, headers off, protocol AUTO.
            // No CAN-specific settings: the 7.3L is J1850 PWM.
            command("ATZ", RESET_TIMEOUT_MS);
            Thread.sleep(100);
            command("ATE0", AT_TIMEOUT_MS);
            command("ATL0", AT_TIMEOUT_MS);
            command("ATS0", AT_TIMEOUT_MS);
            command("ATH0", AT_TIMEOUT_MS);
            command("ATSP0", AT_TIMEOUT_MS);
        } catch (IOException e) {
            disconnect();
            throw e;
        }
        return true;
    }

    private void openSocket(BluetoothDevice device, boolean insecure) throws IOException {
        try {
            socket = insecure
                    ? device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    : device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
        } catch (IOException e) {
            closeQuietly();
            throw e;
        } catch (SecurityException se) {
            closeQuietly();
            throw new IOException("Bluetooth permission denied", se);
        }
    }

    public synchronized void disconnect() {
        connected = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Disconnect error", e);
        }
        socket = null;
        inputStream = null;
        outputStream = null;
    }

    public synchronized boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }

    /** Which protocol the adapter settled on (e.g. "SAE J1850 PWM"), for the debug log. */
    public synchronized String describeProtocol() {
        try {
            return command("ATDP", AT_TIMEOUT_MS).replace("\r", " ").replace("\n", " ").trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** Adapter's own ID string (e.g. "ELM327 v1.5" or "STN1155 v4.x"). Works with the key off. */
    public synchronized String identify() throws IOException {
        return clean(command("ATI", AT_TIMEOUT_MS));
    }

    /** Voltage at the OBD port as the adapter measures it (e.g. "12.6V"). Works with the key off. */
    public synchronized String readVoltage() throws IOException {
        return clean(command("ATRV", AT_TIMEOUT_MS));
    }

    /** OBD-port voltage in volts from ATRV, or 0 when the adapter's answer isn't a number. */
    public synchronized double readVolts() throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(\\.\\d+)?)").matcher(readVoltage());
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    /** Raw reply to a trouble-code request (Mode 03/07/0A); parse with DTCReader. */
    public synchronized String readTroubleCodesRaw(DTCReader.Mode mode) throws IOException {
        return command(mode.request, PID_TIMEOUT_MS);
    }

    private static String clean(String reply) {
        return reply.replace(">", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * Asks the truck which Mode 01 PIDs it answers (PIDs 00/20/40/60/80 are
     * bitmaps of what follows). Empty set means the truck didn't respond
     * (key off / bus not up yet), not "nothing supported".
     */
    public synchronized Set<Integer> readSupportedPids() throws IOException {
        Set<Integer> supported = new HashSet<>();
        for (int base = 0x00; base <= 0x80; base += 0x20) {
            int[] data = readPid(base, base == 0 ? BUS_INIT_TIMEOUT_MS : PID_TIMEOUT_MS);
            if (data == null || data.length < 4) break;
            long mask = ((long) data[0] << 24) | ((long) data[1] << 16) | ((long) data[2] << 8) | data[3];
            for (int i = 0; i < 32; i++) {
                if ((mask & (1L << (31 - i))) != 0) supported.add(base + i + 1);
            }
            if (!supported.contains(base + 0x20)) break; // no further bitmap page
        }
        return supported;
    }

    public synchronized int[] readPid(int pid) throws IOException {
        return readPid(pid, PID_TIMEOUT_MS);
    }

    /** Returns the data bytes after "41 <pid>", or null when the truck had no answer. */
    private int[] readPid(int pid, long timeoutMs) throws IOException {
        String response = command(String.format(Locale.US, "01%02X", pid), timeoutMs);
        return parseMode01(response, pid);
    }

    /**
     * Ford enhanced engine oil temperature (Mode 22, PID 1310, scaled (A*256+B)/100 - 40 °C)
     * for the 7.3L Power Stroke on J1850 PWM, which doesn't answer the standard Mode 01
     * oil-temp PID. Returns Celsius, or NaN when the truck had no usable answer.
     */
    public synchronized double readFordEngineOilTempC() throws IOException {
        String reply;
        command(FORD_PCM_HEADER, AT_TIMEOUT_MS);
        try {
            reply = command("221310", PID_TIMEOUT_MS);
        } finally {
            command(OBD_PWM_HEADER, AT_TIMEOUT_MS);
        }
        lastEnhancedReply = reply.replace(">", "").replaceAll("\\s+", " ").trim();
        return decodeFordOilTempC(parseReply(reply, "621310"));
    }

    /** Raw reply to the last enhanced request, for the debug log when a PID doesn't answer. */
    public synchronized String lastEnhancedReply() {
        return lastEnhancedReply;
    }

    static double decodeFordOilTempC(int[] data) {
        if (data == null || data.length < 2) return Double.NaN;
        double celsius = (data[0] * 256 + data[1]) / 100.0 - 40;
        return celsius > 200 ? Double.NaN : celsius; // beyond any real oil temp: open/shorted sensor
    }

    static int[] parseMode01(String response, int pid) {
        return parseReply(response, String.format(Locale.US, "41%02X", pid));
    }

    /** Data bytes after {@code header} (e.g. "410C", "62194F"), or null when absent. */
    static int[] parseReply(String response, String header) {
        if (response == null) return null;
        for (String rawLine : response.split("[\\r\\n]+")) {
            String line = rawLine.replace(">", "").replaceAll("\\s", "").toUpperCase(Locale.US);
            if (!line.startsWith(header)) continue; // skips SEARCHING..., NO DATA, BUS INIT: OK, etc.
            String payload = line.substring(header.length());
            if (payload.length() < 2 || payload.length() % 2 != 0) continue;
            int[] bytes = new int[payload.length() / 2];
            try {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = Integer.parseInt(payload.substring(i * 2, i * 2 + 2), 16);
                }
            } catch (NumberFormatException e) {
                continue;
            }
            return bytes;
        }
        return null;
    }

    private String command(String cmd, long timeoutMs) throws IOException {
        sendCommand(cmd);
        return readResponse(timeoutMs);
    }

    public synchronized void sendCommand(String cmd) throws IOException {
        if (outputStream == null) throw new IOException("Not connected to adapter");
        // Throw away anything left over from a previous timed-out command.
        while (inputStream != null && inputStream.available() > 0) {
            inputStream.skip(inputStream.available());
        }
        outputStream.write((cmd + "\r").getBytes());
        outputStream.flush();
    }

    public String readResponse() throws IOException {
        return readResponse(PID_TIMEOUT_MS);
    }

    /** Reads until the '>' prompt or the timeout, whichever comes first. Never blocks forever. */
    public synchronized String readResponse(long timeoutMs) throws IOException {
        if (inputStream == null) throw new IOException("Not connected");
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[256];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int available = inputStream.available();
            if (available > 0) {
                int read = inputStream.read(buffer, 0, Math.min(available, buffer.length));
                if (read < 0) throw new IOException("Adapter closed the connection");
                response.append(new String(buffer, 0, read));
                if (response.indexOf(">") >= 0) break;
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading adapter");
                }
            }
        }
        return response.toString();
    }
}
