package com.adafruit.bleuart;

import android.bluetooth.BluetoothDevice;

public class DualRoleBluetoothLeUart implements UartBase {
    public static final int CENTRAL = 0;
    public static final int PERIPHERAL = 1;

    private BluetoothLeUartServer server;
    private BluetoothLeUart client;
    private int gapRole;

    public DualRoleBluetoothLeUart (Context context, int gapRole) {
        server = new BluetoothLeUartServer (context);
        client = new BluetoothLeUart (context);

        this.gapRole = gapRole;
    }

    public void registerCallback(UartBase.HostCallback callback) {
        server.registerCallback(callback);
        client.registerCallback(callback);
    }
    public void unregisterCallback(UartBase.HostCallback callback){
        server.unregisterCallback(callback);
        client.unregisterCallback(callback);
    }

    public void start() {
        //start either scanning or advertising
        if (CENTRAL == this.gapRole) {
            client.start();
        }
        else if (PERIPHERAL == this.gapRole) {
            server.start();
        }
    }

    public void connect(BluetoothDevice device) {
        client.connect(device);
    }

    public void disconnect() {
        client.disconnect();
    }

    public void stop() {
        //stop either scanning or advertising
        if (CENTRAL == this.gapRole) {
            client.stop();
        }
        else if (PERIPHERAL == this.gapRole) {
            server.stop();
        }
    }
    public String getDeviceInfo() {
        return "";
    }
    public void send(byte[] data) {
        server.send(data);
        client.send(data);
    }
    public void send(String data) {
        server.send(data);
        client.send(data);
    }
}
