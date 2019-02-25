package com.adafruit.bleuart;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Objects;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.MainThread;

import android.os.Build;
import android.util.Log;
import static android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY;
import static android.bluetooth.BluetoothGattService.SERVICE_TYPE_SECONDARY;

class BluetoothLeUartServer extends BluetoothGattServerCallback implements UartBase{
    private static final String ERR_TAG = "FATAL ERROR";
    private static final String INFO_TAG = "APP_INFO";

    //The below UUIDs are defined by Nordic and are widely used by Nordic, mbed, and Adafruit
    //(https://thejeshgn.com/2016/10/01/uart-over-bluetooth-low-energy/)
    //    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    //    public static UUID TX_UUID   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); //from central
    //    public static UUID RX_UUID   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); //to central

    //As far as Android is concerned, all UUIDs must be built from this base:
    //0000xxxx-0000-1000-8000-00805F9B34FB
    //Which means we need the following
    public static UUID UART_UUID = UUID.fromString("00000001-0000-1000-8000-00805F9B34FB");
    public static UUID TX_UUID   = UUID.fromString("00000002-0000-1000-8000-00805F9B34FB"); //from central
    public static UUID RX_UUID   = UUID.fromString("00000003-0000-1000-8000-00805F9B34FB"); //to central


    // UUID for the UART BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Device Information service and associated characeristics.
    public static UUID DIS_UUID       = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_MANUF_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_MODEL_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_HWREV_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_SWREV_UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");

    //MSG IDs
    private static final int MSG_CONNECT = 10;
    private static final int MSG_CONNECTED = 20;
    private static final int MSG_DISCONNECT = 30;
    private static final int MSG_DISCONNECTED = 40;
    private static final int MSG_NOTIFY = 50;
    private static final int MSG_NOTIFIED = 60;
    private static final int MSG_WRITE = 70;
    private static final int MSG_REGISTER = 80;
    private static final int MSG_REGISTERED = 90;

    // Internal UART state.
    private Context context;
    private WeakHashMap<UartBase.HostCallback, Object> callbacks = new WeakHashMap<UartBase.HostCallback, Object>();
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattServer mGattServer;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private boolean mConnectable = false;

//  private AdvertisingSetCallback mAdvSetCallback;

    private Set<BluetoothDevice> mRegisteredDevices = new HashSet();

    private int mMtu = 512;
    // Queues for characteristic write (synchronous)
    private Queue<WriteData> writeQueue = new ConcurrentLinkedQueue<WriteData>();
    private boolean idle = true;

    private int advertisingInterval;

    public class WriteData {
        public BluetoothDevice device;
        byte [] data;

        public WriteData (BluetoothDevice device, byte [] data) {
            this.device = device;
            this.data = data;
        }
    }

    public BluetoothLeUartServer(Context context) {
        this.context = context;

        final BluetoothManager mBluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        //create the gatt server
        mGattServer = mBluetoothManager.openGattServer(context,this);

        //add the service to the gatt server
        mGattServer.addService(createUartService());
    }

    public void start() {
        start(new byte[0]);
    }
    public void start(byte [] advData) {

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
            startLeAdvertisingSet(advData);
        } else {
            startLeAdvertising(advData);
        }
    }

    public void setConnectable(int c) {

        Log.i(INFO_TAG, "WE SET TO CONNECTABLE");
        mConnectable = (c == ArgumentSplash.CONNECTABLE);
    }

    public void setAdvertisingInterval(int advInterval) {
        this.advertisingInterval = advInterval;
        Log.i(INFO_TAG, "WE SET THE ADVERTISING INTERVAL");
    }

    public void connect(BluetoothDevice device) {
        //do nothing
    }

    public void disconnect() {
        //do nothing
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback (){
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(INFO_TAG, "LE Advertise Started");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(ERR_TAG, "LE Advertise Failed: " + errorCode);
        }
    };

    public void stop(){

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
           // mBluetoothLeAdvertiser.stopAdvertisingSet(mAdvSetCallback);
        } else {
            mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
        }

    }

    // Register the specified callback to receive UART callbacks.
    public void registerCallback(UartBase.HostCallback callback) {
        callbacks.put(callback, null);
    }

    // Unregister the specified callback.
    public void unregisterCallback(UartBase.HostCallback callback) {
        callbacks.remove(callback);
    }

    public String getDeviceInfo() {
        return "";
    };

    public boolean deviceInfoAvailable() { return false; }

    public int getNumConnections() {
        return mRegisteredDevices.size();
    }

    public int getMtu() {
        return mMtu;
    }

    public void doNotify (WriteData writeData) {
        BluetoothGattCharacteristic characteristic = mGattServer
                .getService(UART_UUID)
                .getCharacteristic(RX_UUID);
        characteristic.setValue(writeData.data);
        idle = false;

        Log.i("BlueNet", "notifying " + writeData.device.getAddress());

        try {
            boolean res = mGattServer.notifyCharacteristicChanged(writeData.device, characteristic, false);

            if (!res) {
                Log.e("BlueNet", "Notification unsuccessful!");
            }
        } catch (NullPointerException x) {
            Log.e("BlueNet", "A device disconnected unexpectedly");
        }
    }

    // Send data to connected UART device.
    public void send(byte[] data) {
        if (data == null || data.length == 0) {
            // Do nothing if there is no connection or message to send.
            return;
        }

        for (BluetoothDevice device : mRegisteredDevices) {
            writeQueue.offer(new WriteData(device, data));
        }

        if (idle && !writeQueue.isEmpty()) {
            WriteData writeData = writeQueue.poll();
            doNotify(writeData);
        }
    }

    // Send data to connected UART device.
    public void send(String data) {
        if (data != null && !data.isEmpty()) {
            send(data.getBytes(Charset.forName("UTF-8")));
        }
    }

    private BluetoothGattService createUartService() {
        //give our service it's UUID and set it to be primary
        BluetoothGattService service = new BluetoothGattService(UART_UUID, SERVICE_TYPE_PRIMARY);

        //Remember that rx means from server to client (perhipheral to central)
        BluetoothGattCharacteristic rxChar =
                new BluetoothGattCharacteristic(RX_UUID,
                        //Read-only characteristic, supports notifications
                        BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ);

        //Descriptor for read notifications
        BluetoothGattDescriptor rxDesc = new BluetoothGattDescriptor(CLIENT_UUID,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        rxChar.addDescriptor(rxDesc);

        //from gatt client to server
        BluetoothGattCharacteristic txChar =
                new BluetoothGattCharacteristic(TX_UUID,
                        //write permissions
                        BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);


        service.addCharacteristic(txChar);
        service.addCharacteristic(rxChar);

        return service;
    }


    public void startLeAdvertisingSet(byte [] extraData) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
           /* mAdvSetCallback = new AdvertisingSetCallback() {
                @Override
                public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                    Log.i(INFO_TAG, "onAdvertisingSetStarted(): txPower:" + txPower + " , status: "
                            + status);
                    //currentAdvertisingSet = advertisingSet;
                }

                @Override
                public void onAdvertisingDataSet(AdvertisingSet advertisingSet, int status) {
                    Log.i(INFO_TAG, "onAdvertisingDataSet() :status:" + status);
                }

                @Override
                public void onScanResponseDataSet(AdvertisingSet advertisingSet, int status) {
                    Log.i(INFO_TAG, "onScanResponseDataSet(): status:" + status);
                }

                @Override
                public void onAdvertisingSetStopped(AdvertisingSet advertisingSet) {
                    Log.i(INFO_TAG, "onAdvertisingSetStopped():");
                }
            };*/
        }



        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();

        AdvertisingSetParameters parameters = (new AdvertisingSetParameters.Builder())
                .setLegacyMode(true) // True by default, but set here as a reminder.
                .setConnectable(mConnectable)
                .setInterval(this.advertisingInterval)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build();

        //set of the advertising data to advertise the service!
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(UART_UUID))
                .addServiceData(new ParcelUuid(UART_UUID),extraData)
                .build();

//        mBluetoothLeAdvertiser.startAdvertisingSet(parameters, data,
//                null, null, null, mAdvSetCallback);
    }

    public void startLeAdvertising(byte [] extraData){

        //TODO: swap out advertising interval for arg
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(this.advertisingInterval) //3 modes: LOW_POWER, BALANCED, LOW_LATENCY
                .setConnectable(mConnectable)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // ULTRA_LOW, LOW, MEDIUM, HIGH
                .build();

        //set of the advertising data to advertise the service!
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(UART_UUID))
                .addServiceData(new ParcelUuid(UART_UUID),extraData)
                .build();

        //get an advertiser object
        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null){
            Log.e(ERR_TAG, "no BLE advertiser assigned!!!");
            return;
        }

        //start advertising
        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    //GATT server callbacks

    // make sure to log when other devices have connected and disconnected from the gatt server
    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        super.onConnectionStateChange(device, status, newState);
        if (status == BluetoothGatt.GATT_SUCCESS){
            switch (newState) {
                case BluetoothGatt.STATE_CONNECTED:
                    Log.i(INFO_TAG, "Connected to: " + device);
                   // bleHandler.obtainMessage(MSG_CONNECTED, device).sendToTarget();
                    break;

                case BluetoothGatt.STATE_DISCONNECTED:
                  //  bleHandler.obtainMessage(MSG_DISCONNECTED, device).sendToTarget();
                    mRegisteredDevices.remove(device);
                    notifyOnDisconnected(this);
                    break;
            }
        }
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device,
                                             int requestId,
                                             BluetoothGattCharacteristic characteristic,
                                             boolean preparedWrite,
                                             boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
        super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                responseNeeded, offset, value);
        boolean found = false;
        //Log.i("Peripheral", "onCharacteristicWriteRequest " + characteristic.getUuid().toString());
        Log.i("Peripheral", new String(value));
        String data = new String(value);
        if (data.matches("<.*>")) { //we have a neighbor address update
            data = data.replaceFirst("<", "");
            data = data.replaceFirst(">", "");

            String [] addresses = data.split("\\s");
            BluetoothDevice temp = device;

            for (int i = 0; i < addresses.length && !found; i++) {
                //see if we are already connected to that device and ignore if found
                for (BluetoothDevice dev : mRegisteredDevices) {
                    if (Objects.equals(addresses[i], dev.getAddress()) && !found) {
                        found = true;
                    }
                }
            }
            //we were sent an address of someone we don't know! Go tell someone!
            if (!found) {
                Log.i("Peripheral", "Found someone new!");
                notifyOnDeviceFound(device); //pass a random device since it'll be ignored anyway
            }
        } else {
            //handle long writes?
            //handle different receive queues
            characteristic.setValue(value);
            notifyOnReceive(this, characteristic);
        }

        if (responseNeeded) {
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
    }

    //Handle read requests to the read  characteristic. Can handle long reads
    //also marks the last read time on this characteristic. We use this for a timeout
    //on the availability of the data in this characteristic
    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device,
                                            int requestId, int offset,
                                            BluetoothGattCharacteristic characteristic) {

//        ReadRequest readReq = new ReadRequest(device, requestId, offset, characteristic);
//        bleHandler.obtainMessage(MSG_READ, readReq).sendToTarget();
    }

    //clients will try to write to the descriptor to enable notifications.
    //this doesn't need to be done per characteristic
    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        Log.i(INFO_TAG, device + " registering");
        if (CLIENT_UUID.equals(descriptor.getUuid())) {
            if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                mRegisteredDevices.add(device);
                notifyOnConnected(this);
                // TODO: send list of registered devices
            } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                mRegisteredDevices.remove(device);
                notifyOnDisconnected(this);
            }

            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        } else {
            Log.w(INFO_TAG, "Unknown descriptor write request");
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }
    }


    @Override
    public void onNotificationSent (BluetoothDevice device, int status) {
        super.onNotificationSent(device, status);
        if (status == BluetoothGatt.GATT_SUCCESS) {

            WriteData writeData = writeQueue.poll();

            if (null == writeData) { //empty!
                idle = true;
            } else {
                doNotify(writeData);
            }

            //bleHandler.obtainMessage(MSG_NOTIFIED, device).sendToTarget();
            Log.d("BlueNet", "Notification sent");
        }
        else {
            Log.i("BlueNet", String.format("Failure: %d", status));
        }
    }

    @Override
    public void onMtuChanged (BluetoothDevice device, int mtu) {

        Log.i("Gatt", "MTU set to: " + String.valueOf(mtu));
        mMtu = mtu < mMtu ? mtu : mMtu;

    }

    // Private functions to simplify the notification of all callbacks of a certain event.
    private void notifyOnConnected(BluetoothLeUartServer uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnected(uart);
            }
        }
    }

    private void notifyOnConnectFailed(BluetoothLeUartServer uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnectFailed(uart);
            }
        }
    }

    private void notifyOnDisconnected(BluetoothLeUartServer uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onDisconnected(uart);
            }
        }
    }

    private void notifyOnReceive(BluetoothLeUartServer uart, BluetoothGattCharacteristic characteristic) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null ) {
                cb.onReceive(uart, characteristic);
            }
        }
    }

    private void notifyOnDeviceFound(BluetoothDevice device) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onDeviceFound(device);
            }
        }
    }

    private void notifyOnDeviceInfoAvailable() {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                //cb.onDeviceInfoAvailable();
            }
        }
    }

    // Notify callbacks of connection failure, and reset connection state.
    private void connectFailure() {
//        rx = null;
//        tx = null;
        notifyOnConnectFailed(this);
    }

    // Filtering by custom UUID is broken in Android 4.3 and 4.4, see:
    //   http://stackoverflow.com/questions/18019161/startlescan-with-128-bit-uuids-doesnt-work-on-native-android-ble-implementation?noredirect=1#comment27879874_18019161
    // This is a workaround function from the SO thread to manually parse advertisement data.
    private List<UUID> parseUUIDs(final byte[] advertisedData) {
        List<UUID> uuids = new ArrayList<UUID>();

        int offset = 0;
        while (offset < (advertisedData.length - 2)) {
            int len = advertisedData[offset++];
            if (len == 0)
                break;

            int type = advertisedData[offset++];
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    while (len > 1) {
                        int uuid16 = advertisedData[offset++];
                        uuid16 += (advertisedData[offset++] << 8);
                        len -= 2;
                        uuids.add(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid16)));
                    }
                    break;
                case 0x06:// Partial list of 128-bit UUIDs
                case 0x07:// Complete list of 128-bit UUIDs
                    // Loop through the advertised 128-bit UUID's.
                    while (len >= 16) {
                        try {
                            // Wrap the advertised bits and order them.
                            ByteBuffer buffer = ByteBuffer.wrap(advertisedData, offset++, 16).order(ByteOrder.LITTLE_ENDIAN);
                            long mostSignificantBit = buffer.getLong();
                            long leastSignificantBit = buffer.getLong();
                            uuids.add(new UUID(leastSignificantBit,
                                    mostSignificantBit));
                        } catch (IndexOutOfBoundsException e) {
                            // Defensive programming.
                            //Log.e(LOG_TAG, e.toString());
                            continue;
                        } finally {
                            // Move the offset to read the next uuid.
                            offset += 15;
                            len -= 16;
                        }
                    }
                    break;
                default:
                    offset += (len - 1);
                    break;
            }
        }
        return uuids;
    }
}
