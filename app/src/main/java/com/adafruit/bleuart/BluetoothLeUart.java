package com.adafruit.bleuart;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.content.Context;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.HashMap;
import java.lang.String;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Objects;

import android.util.Log;

public class BluetoothLeUart extends BluetoothGattCallback implements UartBase {

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

    // Internal UART state.
    private Context context;
    private WeakHashMap<UartBase.HostCallback, Object> callbacks = new WeakHashMap<UartBase.HostCallback, Object>();
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private Map<String, BluetoothGatt> mConnectedDevices = new HashMap<String, BluetoothGatt>();
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private boolean connectFirst;
    private boolean writeInProgress; // Flag to indicate a write is currently in progress
    private int mMtu = 512;

    private boolean mConnectable = false;

    private Map<BluetoothDevice, Integer> mDiscoveredDevices = new HashMap<BluetoothDevice, Integer>();

    // Device Information state.
    private BluetoothGattCharacteristic disManuf;
    private BluetoothGattCharacteristic disModel;
    private BluetoothGattCharacteristic disHWRev;
    private BluetoothGattCharacteristic disSWRev;
    private boolean disAvailable;


    // Queues for characteristic write (synchronous)
    private Queue<BluetoothGattCharacteristic> readQueue;
    private Queue<WriteData> writeQueue = new ConcurrentLinkedQueue<WriteData>();
    private boolean idle = true;

    private int myID = 0;
    private int scanSetting;

    File mOutFile = null;

    public class WriteData {
        public BluetoothGatt gatt;
        byte [] data;

        public WriteData (BluetoothGatt gatt, byte [] data) {
            this.gatt = gatt;
            this.data = data;
        }
    }

    public BluetoothLeUart(Context context) {
        super();
        this.context = context;
        this.mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        this.disManuf = null;
        this.disModel = null;
        this.disHWRev = null;
        this.disSWRev = null;
        this.disAvailable = false;
        this.connectFirst = false;

        this.readQueue = new ConcurrentLinkedQueue<BluetoothGattCharacteristic>();
    }

    public void setConnectable(int c) {
        mConnectable = (c == ArgumentSplash.CONNECTABLE);
    }

    public void setScanSetting(int scanSetting) {
        this.scanSetting = scanSetting;
        Log.i(INFO_TAG, "WE SET THE SCAN SETTING");
    }

    // Return true if connected to UART device, false otherwise.
    public boolean isConnected() {
        return (!mConnectedDevices.isEmpty());
    }

    public String getDeviceInfo() {
        if (!disAvailable ) {
            // Do nothing if there is no connection.
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Manufacturer : " + disManuf.getStringValue(0) + "\n");
        sb.append("Model        : " + disModel.getStringValue(0) + "\n");
        sb.append("Firmware     : " + disSWRev.getStringValue(0) + "\n");
        return sb.toString();
    };

    public boolean deviceInfoAvailable() { return disAvailable; }

    public int getNumConnections() {
        return mConnectedDevices.size();
    }

    // Send data to connected UART device.

    public void doWrite (WriteData writeData) {
        Log.i("BlueNet", "writing " + new String(writeData.data));
        BluetoothGattService service = writeData.gatt.getService(UART_UUID);
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(TX_UUID);
        characteristic.setValue(writeData.data);
        idle = false;

        writeData.gatt.writeCharacteristic(characteristic);
    }
    private void send (WriteData data) {
        Log.i("gatt-client", "sending " + new String(data.data));
        if (null != data) {
            writeQueue.offer(data);
            if (idle && !writeQueue.isEmpty()) {
                WriteData writeData = writeQueue.poll();
                doWrite(writeData);
            }
        }
    }

    // Send data to connected UART devices.
    public void send(byte[] data) {
        Log.i("gatt-client", "sending " + new String(data));
        if (data == null || data.length == 0) {
            // Do nothing if there is no connection or message to send.
            return;
        }

        for (Map.Entry<String, BluetoothGatt> entry : mConnectedDevices.entrySet()) {
            //could expand to communicate to other periphs
            writeQueue.offer(new WriteData(entry.getValue(), data));
        }

        if (idle && !writeQueue.isEmpty()) {
            WriteData writeData = writeQueue.poll();
            doWrite(writeData);
        }
    }

    // Send data to connected UART device.
    public void send(String data) {
        if (data != null && !data.isEmpty()) {
            send(data.getBytes(Charset.forName("UTF-8")));
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

    public void connect(BluetoothDevice device) {
        if (!mConnectedDevices.containsKey(device.getAddress())) {
            device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        }
    }

    // Disconnect to a device if currently connected.
    public void disconnect() {
        for (Map.Entry<String, BluetoothGatt> entry : mConnectedDevices.entrySet()) {
            //could expand to communicate to other periphs
            entry.getValue().disconnect();
        }

        mConnectedDevices.clear();
    }

    public void start(int myID) {
        this.myID = myID;
        start();
    }
    public void start(){
        startLeScan();

    }

    public void stop() {
        stopLeScan();
    }

    public int getMtu() {
        return mMtu;
    }

    private void startLeScan(){
        //scan filters
        ScanFilter ResultsFilter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(UART_UUID))
                .build();

        ArrayList<ScanFilter> filters = new ArrayList<ScanFilter>();
        filters.add(ResultsFilter);
        Log.i("Central","BLE SCAN STARTED");

        //scan settings
        ScanSettings settings = new ScanSettings.Builder()
                //.setReportDelay(0) //0: no delay; >0: queue up
                .setScanMode(this.scanSetting) //LOW_POWER, BALANCED, LOW_LATENCY
                .build();

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (mBluetoothLeScanner == null){
            Log.e("Central", "no BLE scanner assigned!!!");
            return;
        }
        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);
    }

    private void stopLeScan() {
        if (mBluetoothLeScanner != null)
            mBluetoothLeScanner.stopScan(mScanCallback);
        Log.i("Central","LE scan stopped");
    }

    private ScanCallback mScanCallback = new ScanCallback () {
        @Override
        public void onScanResult ( int callbackType, ScanResult result){
            //bleHandler.obtainMessage(MSG_CONNECT, result.getDevice()).sendToTarget();
            handleResult(result);
        }

        @Override
        public void onBatchScanResults (List < ScanResult > results) {
            Log.i("Central", "onBatchScanResults: " + results.size() + " results");
            for (ScanResult result : results) {
                //bleHandler.obtainMessage(MSG_CONNECT, result.getDevice()).sendToTarget();
                handleResult(result);
            }
        }

        @Override
        public void onScanFailed ( int errorCode){
            Log.e("Central", "LE Scan Failed: " + errorCode);
        }
    };

    private long advCount = 0;
    private long tsDiffInter = 0;
    private long tsDiffTotal = 0;
    private long lastTS = 0;
    private void handleResult(ScanResult result) {
        FileOutputStream stream = null;

        if (1 == mLoggingOpt) {
            long ts = result.getTimestampNanos();
            long diff = 0;

            advCount += 1;
            if (0 == lastTS) {
                lastTS = ts;
                if (null != mOutFile) {
                    try {
                        stream = new FileOutputStream(mOutFile, false);
                        stream.write("window avg    overall\n".getBytes());
                        stream.close();
                    } catch(IOException e) {
                    }
                }
            } else {
                diff = ts - lastTS;
                lastTS = ts;
                tsDiffInter += diff;
                tsDiffTotal += diff;

                if (0 == advCount % 100) {
                    Log.i("AdvInfo", "window avg: " + String.valueOf(tsDiffInter/100) + "     overall: " + String.valueOf(tsDiffTotal/advCount));
                    if (null != mOutFile) {

                        try {
                            stream = new FileOutputStream(mOutFile, true);
                            stream.write((String.valueOf(tsDiffInter/100) + "\t" + String.valueOf(tsDiffTotal/advCount) + "\t" + String.valueOf(advCount) + "\n").getBytes());
                            stream.close();
                        } catch (IOException e) {
                        }
                    }
                    tsDiffInter = 0;
                }
            }
        }



        // Connect to first found device if required.
        if (connectFirst) {
            // Notify registered callbacks of found device.
            notifyOnDeviceFound(result.getDevice());
            // Stop scanning for devices.
            stop();
            // Prevent connections to future found devices.
            connectFirst = false;
        }
        else if (!mConnectedDevices.containsKey(result.getDevice().getAddress())){


            byte[] data = result.getScanRecord().getServiceData(new ParcelUuid(UART_UUID));
            int id = ByteBuffer.wrap(data).getInt();

            if (!mDiscoveredDevices.containsKey(result.getDevice())
                    && !mDiscoveredDevices.containsValue(id)){
                Log.i("Central", "advertised: " + result.toString());
                mDiscoveredDevices.put(result.getDevice(), id);

                // Notify registered callbacks of found device.
                if (myID > id) {
                    if (mConnectable) {
                        notifyOnDeviceFound(result.getDevice());
                    }

                } else if (myID == id) {
                    /*how do we handle?
                        both sides re-roll IDs
                        The old advertisement may be seen while new ID is set locally
                            Old ID < new ID
                                another device has ID < old -> consistent
                                another device has ID == old -> other re-rolls
                                another device has ID > old && < new -> both sides attempt to connect XXX
                                another deivce has ID > new -> consistent
                            old ID > new ID
                                another device has ID < old && > new -> neither side connects XXX
                                another device has ID == old -> other re-rolls
                                another device has ID > old -> consistent
                                another deivce has ID < new -> consistent

                        possible solution: ignore all connection attempts for X advertisement iterations
                    */
                }
            }
        }
    }

    // Connect to the first available UART device.
    public void connectFirstAvailable() {
        // Disconnect to any connected device.
        disconnect();
        // Stop any in progress device scan.
        stop();
        // Start scan and connect to first available device.
        connectFirst = true;
        start();
    }

    // Handlers for BluetoothGatt and LeScan events.
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mConnectedDevices.put(gatt.getDevice().getAddress(), gatt);

                // Connected to device, start discovering services.
                if (!gatt.discoverServices()) {
                    // Error starting service discovery.
                    Log.e("", "error discovering services!");
                    connectFailure();
                }
            }
            else {
                // Error connecting to device.
                connectFailure();
            }
        }
        else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            // Disconnected, notify callbacks of disconnection.
            mConnectedDevices.remove(gatt.getDevice().getAddress());

            notifyOnDisconnected(this);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        // Notify connection failure if service discovery failed.
        if (status == BluetoothGatt.GATT_FAILURE) {
            connectFailure();
            Log.e("", "onServicesDiscovered gatt failure");
            return;
        } else {
            Log.e("", "onServicesDiscovered gatt success");
        }

        BluetoothDevice device = gatt.getDevice();
        String address = device.getAddress();
        final BluetoothGatt bluetoothGatt = mConnectedDevices.get(address);

        //reference to each UART characteristic
        if (null == bluetoothGatt.getService(UART_UUID)) {
            connectFailure();
            Log.e("", "onServicesDiscovered gatt failure");
            return;
        }
        BluetoothGattCharacteristic rx = bluetoothGatt.getService(UART_UUID).getCharacteristic(RX_UUID);

        // Save reference to each DIS characteristic.
        if (null != gatt.getService(DIS_UUID)) {
            disManuf = gatt.getService(DIS_UUID).getCharacteristic(DIS_MANUF_UUID);
            disModel = gatt.getService(DIS_UUID).getCharacteristic(DIS_MODEL_UUID);
            disHWRev = gatt.getService(DIS_UUID).getCharacteristic(DIS_HWREV_UUID);
            disSWRev = gatt.getService(DIS_UUID).getCharacteristic(DIS_SWREV_UUID);

            // Add device information characteristics to the read queue
            // These need to be queued because we have to wait for the response to the first
            // read request before a second one can be processed (which makes you wonder why they
            // implemented this with async logic to begin with???)
            readQueue.offer(disManuf);
            readQueue.offer(disModel);
            readQueue.offer(disHWRev);
            readQueue.offer(disSWRev);

            // Request a dummy read to get the device information queue going
            gatt.readCharacteristic(disModel);
        } else {
            Log.w("Central", "null service!");
        }



        // Setup notifications on RX characteristic changes (i.e. data received).
        // First call setCharacteristicNotification to enable notification.
        if (!bluetoothGatt.setCharacteristicNotification(rx, true)) {
            // Stop if the characteristic notification setup failed.
            connectFailure();
            Log.e("", "onServicesDiscovered notification setup failed");
            return;
        }
        // Next update the RX characteristic's client descriptor to enable notifications.
        BluetoothGattDescriptor desc = rx.getDescriptor(CLIENT_UUID);
        if (desc == null) {
            // Stop if the RX characteristic has no client descriptor.
            connectFailure();
            Log.e("", "onServicesDiscovered no client descriptor");
            return;
        }
        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!bluetoothGatt.writeDescriptor(desc)) {
            // Stop if the client descriptor could not be written.
            connectFailure();
            Log.e("", "onServicesDiscovered descriptor could not be written");
            return;
        }
        // Notify of connection completion.

    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt,
                                  BluetoothGattDescriptor descriptor, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.w("Central", "Descriptor written!");

            BluetoothDevice device = gatt.getDevice();
            String address = device.getAddress();
            final BluetoothGatt bluetoothGatt = mConnectedDevices.get(address);

            bluetoothGatt.requestMtu(512);

            notifyOnConnected(this);
        } else {
            Log.w("Central", "Descriptor NOT written!");
        }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i("Gatt", "MTU set to: " + String.valueOf(mtu));
            mMtu = mtu < mMtu ? mtu : mMtu;
        }

    }


    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        notifyOnReceive(this, characteristic);
    }

    @Override
    public void onCharacteristicRead (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);

        BluetoothDevice device = gatt.getDevice();
        String address = device.getAddress();
        final BluetoothGatt bluetoothGatt = mConnectedDevices.get(address);

        if (status == BluetoothGatt.GATT_SUCCESS) {
            //Log.w("DIS", characteristic.getStringValue(0));
            // Check if there is anything left in the queue
            BluetoothGattCharacteristic nextRequest = readQueue.poll();
            if(nextRequest != null){
                // Send a read request for the next item in the queue
                bluetoothGatt.readCharacteristic(nextRequest);
            }
            else {
                // We've reached the end of the queue
                disAvailable = true;
                notifyOnDeviceInfoAvailable();
            }
        }
        else {
            Log.w("DIS", "Failed reading characteristic " + characteristic.getUuid().toString());
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);

        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d("BlueNet","Characteristic write successful");


        } else {
            Log.d("BlueNet","Characteristic write FAILED");
        }

        WriteData writeData = writeQueue.poll();

        if (null == writeData) { //empty!
            idle = true;
        } else {
            doWrite(writeData);
        }

    }

    private int mLoggingOpt = 0;
    public void setAdvLogging(int opt) {
        mLoggingOpt = opt;

        File path = context.getExternalFilesDir(null);
        mOutFile = new File(path, "cap.txt");

    }

    // Private functions to simplify the notification of all callbacks of a certain event.
    private void notifyOnConnected(BluetoothLeUart uart) {

        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnected(uart);
            }
        }
    }

    private void notifyOnConnectFailed(BluetoothLeUart uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnectFailed(uart);
            }
        }
    }

    private void notifyOnDisconnected(BluetoothLeUart uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onDisconnected(uart);
            }
        }
    }

    private void notifyOnReceive(BluetoothLeUart uart, BluetoothGattCharacteristic rx) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null ) {
                cb.onReceive(uart, rx);
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
