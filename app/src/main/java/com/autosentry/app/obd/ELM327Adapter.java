package com.autosentry.app.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Real ELM327 / OBDLink Bluetooth adapter layer.
 * Replaces OBDSimulator for production use.
 */
public class ELM327Adapter {
    private static final String TAG = "ELM327Adapter";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket socket = null;
    private InputStream inputStream = null;
    private OutputStream outputStream = null;
    private String adapterAddress = null;
    private boolean connected = false;

    public ELM327Adapter(String adapterAddress) {
        this.adapterAddress = adapterAddress;
    }

    public ELM327Adapter() {}

    public void setAdapterAddress(String adapterAddress) {
        this.adapterAddress = adapterAddress;
    }

    public boolean connect() throws IOException, InterruptedException {
        if (connected) disconnect();
        if (this.adapterAddress == null || this.adapterAddress.isEmpty()) {
            throw new IOException("Adapter address not set — pass via ELM327Adapter(address) constructor");
        }

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            throw new IOException("Bluetooth adapter not available or disabled");
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(adapterAddress);
        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
        socket.connect();

        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        connected = true;

        sendCommand("AT Z");
        readResponse();
        Thread.sleep(100);
        sendCommand("AT E0");
        sendCommand("AT L0");
        sendCommand("AT S0");
        sendCommand("AT H0");
        sendCommand("AT CAF0");
        sendCommand("AT CFC0");
        sendCommand("AT SP 6");

        return true;
    }

    public void disconnect() {
        try {
            if (socket != null) socket.close();
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Disconnect error", e);
        }
        connected = false;
        socket = null;
        inputStream = null;
        outputStream = null;
    }

    public boolean isConnected() {
        return connected && socket != null && socket.isConnected();
    }

    public int readRPM() throws IOException {
        String response = readPid("01 0C");
        return parseRPMResponse(response);
    }

    public int readCoolantTemp() throws IOException {
        String response = readPid("01 05");
        return parseTempResponse(response);
    }

    public float readMAF() throws IOException {
        String response = readPid("01 10");
        return parseMAFResponse(response);
    }

    public int readEngineLoad() throws IOException {
        String response = readPid("01 04");
        return parseSingleByteResponse(response);
    }

    public int readBatteryVoltage() throws IOException {
        String response = readPid("01 42");
        return parseBatteryVoltageResponse(response);
    }

    public String readPid(String pid) throws IOException {
        sendCommand(pid);
        String response = readResponse();
        return response;
    }

    public void sendCommand(String cmd) throws IOException {
        if (outputStream == null) throw new IOException("Not connected to adapter");
        outputStream.write((cmd + "\r").getBytes());
        outputStream.flush();
    }

    public String readResponse() throws IOException {
        if (inputStream == null) throw new IOException("Not connected");
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[1024];
        long deadline = System.currentTimeMillis() + 2000L;

        while (System.currentTimeMillis() < deadline) {
            int available = inputStream.available();
            if (available <= 0) {
                Thread.sleep(25);
                continue;
            }
            int read = inputStream.read(buffer, 0, Math.min(available, buffer.length));
            if (read <= 0) continue;
            response.append(new String(buffer, 0, read));
            if (response.toString().contains(">")) {
                break;
            }
        }

        return response.toString();
    }

    private int parseRPMResponse(String response) {
        try {
            String[] values = extractHexValues(response);
            int idx = findHeader(values, "41", "0C");
            if (idx >= 0 && idx + 3 < values.length) {
                int a = parseHexByte(values[idx + 2]);
                int b = parseHexByte(values[idx + 3]);
                return ((a * 256) + b) / 4;
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse RPM error", e);
        }
        return 0;
    }

    private int parseTempResponse(String response) {
        try {
            String[] values = extractHexValues(response);
            int idx = findHeader(values, "41", "05");
            if (idx >= 0 && idx + 2 < values.length) {
                return parseHexByte(values[idx + 2]) - 40;
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse coolant temp error", e);
        }
        return -1;
    }

    private float parseMAFResponse(String response) {
        try {
            String[] values = extractHexValues(response);
            int idx = findHeader(values, "41", "10");
            if (idx >= 0 && idx + 3 < values.length) {
                int a = parseHexByte(values[idx + 2]);
                int b = parseHexByte(values[idx + 3]);
                return ((a * 256) + b) / 100f;
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse MAF error", e);
        }
        return 0f;
    }

    private int parseBatteryVoltageResponse(String response) {
        try {
            String[] values = extractHexValues(response);
            int idx = findHeader(values, "41", "42");
            if (idx >= 0 && idx + 3 < values.length) {
                int a = parseHexByte(values[idx + 2]);
                int b = parseHexByte(values[idx + 3]);
                return ((a * 256) + b) / 1000;
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse battery voltage error", e);
        }
        return 0;
    }

    private int parseSingleByteResponse(String response) {
        try {
            String[] values = extractHexValues(response);
            int idx = findHeader(values, "41", "04");
            if (idx >= 0 && idx + 2 < values.length) {
                return parseHexByte(values[idx + 2]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse single byte response error", e);
        }
        return 0;
    }

    private int findHeader(String[] values, String... expected) {
        if (values == null || expected == null) return -1;
        for (int i = 0; i <= values.length - expected.length; i++) {
            boolean matched = true;
            for (int j = 0; j < expected.length; j++) {
                if (!values[i + j].equalsIgnoreCase(expected[j])) {
                    matched = false;
                    break;
                }
            }
            if (matched) return i;
        }
        return -1;
    }

    private String[] extractHexValues(String fullResponse) {
        String cleaned = fullResponse
                .replaceAll("[>\\r\\n]", " ")
                .replaceAll("[^0-9A-Fa-f\\s]", " ")
                .trim();
        if (cleaned.isEmpty()) return new String[0];
        return cleaned.split("\\s+");
    }

    private int parseHexByte(String value) {
        return Integer.parseInt(value, 16);
    }
}
