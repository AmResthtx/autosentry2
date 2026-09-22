package com.autosentry.app.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Real ELM327 / OBDLink Bluetooth adapter layer. */
public class ELM327Adapter {
    private static final String TAG = "ELM327Adapter";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private String adapterAddress;
    private boolean connected;

    public ELM327Adapter(String adapterAddress) { this.adapterAddress = adapterAddress; }
    public ELM327Adapter() {}
    public void setAdapterAddress(String address) { this.adapterAddress = address; }

    public boolean connect() throws IOException, InterruptedException {
        if (connected) disconnect();
        if (adapterAddress == null || adapterAddress.isEmpty()) throw new IOException("Adapter address not set");
        BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
        if (bluetooth == null || !bluetooth.isEnabled()) throw new IOException("Bluetooth unavailable or disabled");
        socket = bluetooth.getRemoteDevice(adapterAddress).createRfcommSocketToServiceRecord(SPP_UUID);
        socket.connect();
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        connected = true;
        sendCommand("AT Z"); readResponse(); Thread.sleep(100);
        sendCommand("AT E0"); sendCommand("AT L0"); sendCommand("AT S0");
        sendCommand("AT H0"); sendCommand("AT CAF0"); sendCommand("AT CFC0"); sendCommand("AT SP 6");
        return true;
    }

    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException e) { Log.e(TAG, "Disconnect error", e); }
        connected = false; socket = null; inputStream = null; outputStream = null;
    }

    public boolean isConnected() { return connected && socket != null && socket.isConnected(); }

    public int readRPM() throws IOException { return parseRPM(readPid("01 0C")); }
    public int readCoolantTemp() throws IOException { return parseTemp(readPid("01 05")); }
    public float readMAF() throws IOException { return parseMAF(readPid("01 10")); }
    public int readEngineLoad() throws IOException { return parseSingleByte(readPid("01 04"), "41", "04"); }
    public int readBatteryVoltage() throws IOException { return parseBattery(readPid("01 42")); }

    /** Ford enhanced EOT for the 2000 7.3L Power Stroke. Returns Celsius. */
    public int readEngineOilTemp() throws IOException { return parseEnhancedTemp(readPid("22 194F")); }

    public String readPid(String pid) throws IOException { sendCommand(pid); return readResponse(); }

    public void sendCommand(String command) throws IOException {
        if (outputStream == null) throw new IOException("Not connected to adapter");
        outputStream.write((command + "\r").getBytes()); outputStream.flush();
    }

    public String readResponse() throws IOException {
        if (inputStream == null) throw new IOException("Not connected");
        StringBuilder result = new StringBuilder(); byte[] buffer = new byte[1024];
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            int available = inputStream.available();
            if (available <= 0) { try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } continue; }
            int count = inputStream.read(buffer, 0, Math.min(available, buffer.length));
            if (count > 0) result.append(new String(buffer, 0, count));
            if (result.indexOf(">") >= 0) break;
        }
        return result.toString();
    }

    private int parseRPM(String response) { int i = find(extract(response), "41", "0C"); return i >= 0 ? pair(extract(response), i + 2) / 4 : 0; }
    private int parseTemp(String response) { String[] v = extract(response); int i = find(v, "41", "05"); return i >= 0 && i + 2 < v.length ? hex(v[i + 2]) - 40 : -1; }
    private float parseMAF(String response) { String[] v = extract(response); int i = find(v, "41", "10"); return i >= 0 ? pair(v, i + 2) / 100f : 0f; }
    private int parseBattery(String response) { String[] v = extract(response); int i = find(v, "41", "42"); return i >= 0 ? pair(v, i + 2) / 1000 : 0; }

    private int parseEnhancedTemp(String response) {
        String[] v = extract(response); int i = find(v, "62", "19", "4F");
        if (i >= 0 && i + 3 < v.length) return hex(v[i + 3]) - 40;
        return -1;
    }

    private int parseSingleByte(String response, String... header) { String[] v = extract(response); int i = find(v, header); return i >= 0 && i + header.length < v.length ? hex(v[i + header.length]) : 0; }
    private int pair(String[] v, int i) { return i + 1 < v.length ? hex(v[i]) * 256 + hex(v[i + 1]) : 0; }
    private int hex(String value) { return Integer.parseInt(value, 16); }

    private int find(String[] values, String... header) {
        for (int i = 0; i <= values.length - header.length; i++) {
            boolean match = true; for (int j = 0; j < header.length; j++) if (!values[i + j].equalsIgnoreCase(header[j])) { match = false; break; }
            if (match) return i;
        }
        return -1;
    }

    private String[] extract(String response) {
        String clean = response.replaceAll("[>\\r\\n]", " ").replaceAll("[^0-9A-Fa-f\\s]", " ").trim();
        return clean.isEmpty() ? new String[0] : clean.split("\\s+");
    }
}
