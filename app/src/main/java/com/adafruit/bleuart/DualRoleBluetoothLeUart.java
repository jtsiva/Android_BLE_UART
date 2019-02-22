package com.adafruit.bleuart;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import android.util.Log;

public class DualRoleBluetoothLeUart implements UartBase {
    public static final int CENTRAL = 0;
    public static final int PERIPHERAL = 1;
    public static final int BRIDGE = 2;

    private BluetoothLeUartServer server;
    private BluetoothLeUart client;
    private int gapRole;
    private int myRandomNumber;

    public DualRoleBluetoothLeUart (Context context, int gapRole) {
        server = new BluetoothLeUartServer (context);
        client = new BluetoothLeUart (context);

        this.gapRole = gapRole;

        SecureRandom random = new SecureRandom();
        myRandomNumber = random.nextInt();
    }

    public DualRoleBluetoothLeUart (Context context) {
        this(context, BRIDGE);
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
        //start scanning and advertising
        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(myRandomNumber);


        if (PERIPHERAL != gapRole) {
            Log.i ("Central", "Scanning...");
            client.start(myRandomNumber);
        }

        if (CENTRAL != gapRole) {
            Log.i ("Peripheral", "Advertising ID = " + String.valueOf(myRandomNumber));
            server.start(b.array());
        }

    }

    public void connect(BluetoothDevice device) {
        client.connect(device);
    }

    public void disconnect() {
        client.disconnect();
    }

    public void stop() {
        //stop scanning and advertising
        if (PERIPHERAL != gapRole) {
            client.stop();
        }

        if (CENTRAL != gapRole) {
            server.stop();
        }

    }
    public String getDeviceInfo() {
        return "";
    }
    public void send(byte[] data) {
        if (PERIPHERAL != gapRole) {
            client.send(data);
        }

        if (CENTRAL != gapRole) {
            server.send(data);
        }
    }
    public void send(String data) {
        if (PERIPHERAL != gapRole) {
            client.send(data);
        }

        if (CENTRAL != gapRole) {
            server.send(data);
        }
    }

    public int getNumConnections(){
        return server.getNumConnections() + client.getNumConnections();
    }

    public int getMtu() {
        return server.getMtu() <= client.getMtu() ? server.getMtu() : client.getMtu();
    }

    public void setOpts (int logging, int role, int connectable, int advInterval, int scanSetting) {

        client.setAdvLogging(logging);
        gapRole = role;

        server.setConnectable(connectable);
        server.setAdvertisingInterval(advInterval);
        server.setScanSetting(scanSetting);

        client.setConnectable(connectable);
    }
}
