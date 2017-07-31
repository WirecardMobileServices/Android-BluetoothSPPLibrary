/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.wirecard.bluetoothspp.library;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

@SuppressLint("NewApi")
public class BluetoothSPP {
    // Listener for Bluetooth Status & Connection
    private BluetoothStateListener mBluetoothStateListener = null;
    private OnDataReceivedListener mDataReceivedListener = null;
    private BluetoothConnectionListener mBluetoothConnectionListener = null;
    private AutoConnectionListener mAutoConnectionListener = null;

    // Context from activity which call this class
    private Context mContext;

    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;

    // Member object for the chat services
    private BluetoothService mChatService = null;
    private final Handler mHandler;

    // Name and Address of the connected device
    private String mDeviceName = null;
    private String mDeviceAddress = null;

    private boolean isAutoConnecting = false;
    private boolean isAutoConnectionEnabled = false;
    private boolean isConnected = false;
    private boolean isConnecting = false;
    private boolean isServiceRunning = false;

    private String keyword = "";
    private boolean isAndroid = BluetoothState.DEVICE_ANDROID;
    private HandReader handReader = HandReader.GENERIC;

    // Carriage Return and Line Feed
    private static final byte[] CRLF = (("\r\n").getBytes());//{ 0x0D, 0x0A }; // \r\n

    // Prefix for Tertium Hand Readers
    private static final byte[] TERTIUM_PREFIX = (("$:").getBytes());//{ 0x0D, 0x0A }; // \r\n

    // This is where we store the callback if AutoConnection is enabled
    private BluetoothConnectionListener mBluetoothConnectionListenerSecondary = null;

    private int c = 0;

    @SuppressWarnings("HandlerLeak")
    public BluetoothSPP(Context context, HandReader handReader) {
        this.handReader = handReader;
        mContext = context;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case BluetoothState.MESSAGE_WRITE:
                        break;
                    case BluetoothState.MESSAGE_READ:
                        int bytes = msg.arg1;
                        if (bytes > 0 && mDataReceivedListener != null) {
                            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                            try {
                                outputStream.write((byte[]) msg.obj);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            byte newByte[] = outputStream.toByteArray();
                            mDataReceivedListener.onDataReceived(newByte, bytes);
                        }
                        break;
                    case BluetoothState.MESSAGE_DEVICE_NAME:
                        mDeviceName = msg.getData().getString(BluetoothState.DEVICE_NAME);
                        mDeviceAddress = msg.getData().getString(BluetoothState.DEVICE_ADDRESS);
                        if (mBluetoothConnectionListener != null)
                            mBluetoothConnectionListener.onDeviceConnected(mDeviceName, mDeviceAddress);
                        isConnected = true;
                        break;
                    case BluetoothState.MESSAGE_TOAST:
                        Toast.makeText(mContext, msg.getData().getString(BluetoothState.TOAST)
                                , Toast.LENGTH_SHORT).show();
                        break;
                    case BluetoothState.MESSAGE_STATE_CHANGE:
                        if (mBluetoothStateListener != null)
                            mBluetoothStateListener.onServiceStateChanged(msg.arg1);
                        if (isConnected && msg.arg1 != BluetoothState.STATE_CONNECTED) {
                            if (mBluetoothConnectionListener != null)
                                mBluetoothConnectionListener.onDeviceDisconnected();
                            if (isAutoConnectionEnabled) {
                                isAutoConnectionEnabled = false;
                                autoConnect(keyword);
                            }
                            isConnected = false;
                            mDeviceName = null;
                            mDeviceAddress = null;
                        }

                        if (!isConnecting && msg.arg1 == BluetoothState.STATE_CONNECTING) {
                            isConnecting = true;
                        } else if (isConnecting) {
                            if (msg.arg1 != BluetoothState.STATE_CONNECTED) {
                                if (mBluetoothConnectionListener != null)
                                    mBluetoothConnectionListener.onDeviceConnectionFailed();
                            }
                            isConnecting = false;
                        }
                        break;
                }
            }
        };
    }

    public interface BluetoothStateListener {
        void onServiceStateChanged(int state);
    }

    public interface OnDataReceivedListener {
        void onDataReceived(byte[] data, int length);
    }

    public interface BluetoothConnectionListener {
        void onDeviceConnected(String name, String address);

        void onDeviceDisconnected();

        void onDeviceConnectionFailed();
    }

    public interface AutoConnectionListener {
        void onAutoConnectionStarted();

        void onNewConnection(String name, String address);
    }

    public boolean isBluetoothAvailable() {
        try {
            if (mBluetoothAdapter == null || mBluetoothAdapter.getAddress().equals(null))
                return false;
        } catch (NullPointerException e) {
            return false;
        }
        return true;
    }

    public boolean isBluetoothEnabled() {
        return mBluetoothAdapter.isEnabled();
    }

    public boolean isServiceAvailable() {
        return mChatService != null;
    }

    public boolean isAutoConnecting() {
        return isAutoConnecting;
    }

    public boolean startDiscovery() {
        return mBluetoothAdapter.startDiscovery();
    }

    public boolean isDiscovery() {
        return mBluetoothAdapter.isDiscovering();
    }

    public boolean cancelDiscovery() {
        return mBluetoothAdapter.cancelDiscovery();
    }

    public void setupService() {
        mChatService = new BluetoothService(mContext, mHandler);
    }

    public BluetoothAdapter getBluetoothAdapter() {
        return mBluetoothAdapter;
    }

    public int getServiceState() {
        if (mChatService != null)
            return mChatService.getState();
        else
            return -1;
    }

    public void startService(boolean isAndroid) {
        if (mChatService != null) {
            if (mChatService.getState() == BluetoothState.STATE_NONE) {
                isServiceRunning = true;
                mChatService.start(isAndroid);
                BluetoothSPP.this.isAndroid = isAndroid;
            }
        }
    }

    public void stopService() {
        if (mChatService != null) {
            isServiceRunning = false;
            mChatService.stop();
        }
        new Handler().postDelayed(new Runnable() {
            public void run() {
                if (mChatService != null) {
                    isServiceRunning = false;
                    mChatService.stop();
                }
            }
        }, 500);
    }

    public void setDeviceTarget(boolean isAndroid) {
        stopService();
        startService(isAndroid);
        BluetoothSPP.this.isAndroid = isAndroid;
    }

    public void stopAutoConnect() {
        if (isAutoConnectionEnabled) {
            isAutoConnectionEnabled = false;
            // Restore the previous callback
            mBluetoothConnectionListener = mBluetoothConnectionListenerSecondary;
        }
    }

    public void connect(Intent data) {
        String address = data.getExtras().getString(BluetoothState.EXTRA_DEVICE_ADDRESS);
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        mChatService.connect(device);
    }

    public void connect(String address) {
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        mChatService.connect(device);
    }

    public void disconnect() {
        if (mChatService != null) {
            isServiceRunning = false;
            mChatService.stop();
            if (mChatService.getState() == BluetoothState.STATE_NONE) {
                isServiceRunning = true;
                mChatService.start(BluetoothSPP.this.isAndroid);
            }
        }
    }

    public void setBluetoothStateListener(BluetoothStateListener listener) {
        mBluetoothStateListener = listener;
    }

    public void setOnDataReceivedListener(OnDataReceivedListener listener) {
        mDataReceivedListener = listener;
    }

    public void setBluetoothConnectionListener(BluetoothConnectionListener listener) {
        // We can't replace the primary callback if AutoConnection is enabled
        if (isAutoConnectionEnabled) {
            mBluetoothConnectionListenerSecondary = listener;
        } else {
            mBluetoothConnectionListener = listener;
        }
    }

    public void setAutoConnectionListener(AutoConnectionListener listener) {
        mAutoConnectionListener = listener;
    }

    public void enable() {
        mBluetoothAdapter.enable();
    }

    public void send(byte[] data) {
        if (mChatService.getState() == BluetoothState.STATE_CONNECTED) {

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                if (handReader == HandReader.BLUEBERRY) outputStream.write(TERTIUM_PREFIX);
                outputStream.write(data);
                outputStream.write(CRLF);
            } catch (IOException e) {
                e.printStackTrace();
            }

            byte newByte[] = outputStream.toByteArray();
            mChatService.write(newByte);
        }
    }

    public void send(String data) {
        if (mChatService.getState() == BluetoothState.STATE_CONNECTED) {
            mChatService.write(data.getBytes());
        }
    }

    public String getConnectedDeviceName() {
        return mDeviceName;
    }

    public String getConnectedDeviceAddress() {
        return mDeviceAddress;
    }

    public String[] getPairedDeviceName() {
        int c = 0;
        Set<BluetoothDevice> devices = mBluetoothAdapter.getBondedDevices();
        String[] name_list = new String[devices.size()];
        for (BluetoothDevice device : devices) {
            name_list[c] = device.getName();
            c++;
        }
        return name_list;
    }

    public String[] getPairedDeviceAddress() {
        int c = 0;
        Set<BluetoothDevice> devices = mBluetoothAdapter.getBondedDevices();
        String[] address_list = new String[devices.size()];
        for (BluetoothDevice device : devices) {
            address_list[c] = device.getAddress();
            c++;
        }
        return address_list;
    }

    /**
     * Auto Connect
     *
     * @param keywordName - keyword to search for the device to auto connect
     */
    public void autoConnect(String keywordName) {
        if (!isAutoConnectionEnabled) {
            keyword = keywordName;
            isAutoConnectionEnabled = true;
            if (mAutoConnectionListener != null)
                mAutoConnectionListener.onAutoConnectionStarted();
            final ArrayList<String> arr_filter_address = new ArrayList<String>();
            final ArrayList<String> arr_filter_name = new ArrayList<String>();
            String[] arr_name = getPairedDeviceName();
            String[] arr_address = getPairedDeviceAddress();
            for (int i = 0; i < arr_name.length; i++) {
                if (arr_name[i].contains(keywordName)) {
                    arr_filter_address.add(arr_address[i]);
                    arr_filter_name.add(arr_name[i]);
                }
            }

            // Save the previously existing callback
            mBluetoothConnectionListenerSecondary = mBluetoothConnectionListener;

            mBluetoothConnectionListener = new BluetoothConnectionListener() {
                public void onDeviceConnected(String name, String address) {
                    isAutoConnecting = false;
                    // Run the secondary callback
                    if (mBluetoothConnectionListenerSecondary != null) {
                        mBluetoothConnectionListenerSecondary.onDeviceConnected(name, address);
                    }
                }

                public void onDeviceDisconnected() {
                    // Run the secondary callback
                    if (mBluetoothConnectionListenerSecondary != null) {
                        mBluetoothConnectionListenerSecondary.onDeviceDisconnected();
                    }

                }

                public void onDeviceConnectionFailed() {
                    Log.e("CHeck", "Failed");
                    if (isServiceRunning) {
                        if (isAutoConnectionEnabled) {
                            c++;
                            if (c >= arr_filter_address.size())
                                c = 0;
                            isAutoConnecting = true;
                            connect(arr_filter_address.get(c));
                            Log.e("CHeck", "Connect");
                            if (mAutoConnectionListener != null)
                                mAutoConnectionListener.onNewConnection(arr_filter_name.get(c)
                                        , arr_filter_address.get(c));
                        } else {
                            isAutoConnecting = false;
                        }

                        // Run the secondary callback (can't fail is AutoConnection is disabled)
                        if (mBluetoothConnectionListenerSecondary != null) {
                            mBluetoothConnectionListenerSecondary.onDeviceConnectionFailed();
                        }

                    }
                }
            };

            c = 0;
            if (arr_filter_address.size() > 0) {
                if (!isConnected) {
                    // Connect() breaks when it's already connected
                    if (mAutoConnectionListener != null)
                        mAutoConnectionListener.onNewConnection(arr_name[c], arr_address[c]);
                    isAutoConnecting = true;
                    connect(arr_filter_address.get(c));
                }
            } else {
                Toast.makeText(mContext, "Device not found", Toast.LENGTH_SHORT).show();
            }
        }
    }
}