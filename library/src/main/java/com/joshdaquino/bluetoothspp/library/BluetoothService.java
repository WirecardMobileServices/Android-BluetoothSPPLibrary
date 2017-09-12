/*
 * Copyright (C) 2009 The Android Open Source Project
 *
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

package com.joshdaquino.bluetoothspp.library;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;

@SuppressLint("NewApi")
public class BluetoothService {
    // Debugging
    private static final String TAG = "Bluetooth Service";

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "Bluetooth Secure";

    // Unique UUID for this application
    private static final UUID UUID_ANDROID_DEVICE =
            UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    private static final UUID UUID_OTHER_DEVICE =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private final HandReader handReader;
    private AcceptThread mSecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    private boolean isAndroid = BluetoothState.DEVICE_ANDROID;

    // Constructor. Prepares a new BluetoothChat session
    // context : The UI Activity Context
    // handler : A Handler to send messages back to the UI Activity
    /*public BluetoothService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = BluetoothState.STATE_NONE;
        mHandler = handler;
    }*/

    public BluetoothService(Context context, Handler handler, HandReader handReader) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = BluetoothState.STATE_NONE;
        mHandler = handler;
        this.handReader = handReader;
    }

    private String getNameOfState(int state) {
        switch (state) {
            case BluetoothState.STATE_NONE:
                return "STATE_NONE";
            case BluetoothState.STATE_LISTEN:
                return "STATE_LISTEN";
            case BluetoothState.STATE_CONNECTING:
                return "STATE_CONNECTING";
            case BluetoothState.STATE_CONNECTED:
                return "STATE_CONNECTED";
            case BluetoothState.STATE_NULL:
                return "STATE_NULL";
            default:
                return "UNKNOWN";
        }
    }

    // Return the current connection state.
    public synchronized int getState() {
        return mState;
    }

    // Set the current state of the chat connection
    // state : An integer defining the current connection state
    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + getNameOfState(mState) + " -> " + getNameOfState(state));
        mState = state;

        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(BluetoothState.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    // Start the chat service. Specifically start AcceptThread to begin a
    // session in listening (server) mode. Called by the Activity onResume()
    public synchronized void start(boolean isAndroid) {
        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(BluetoothState.STATE_LISTEN);

        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(isAndroid);
            mSecureAcceptThread.start();
            BluetoothService.this.isAndroid = isAndroid;
        }
    }

    // Start the ConnectThread to initiate a connection to a remote device
    // device : The BluetoothDevice to connect
    // secure : Socket Security type - Secure (true) , Insecure (false)
    public synchronized void connect(BluetoothDevice device) {
        // Cancel any thread attempting to make a connection
        if (mState == BluetoothState.STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(BluetoothState.STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device, final String socketType) {
        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(BluetoothState.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothState.DEVICE_NAME, device.getName());
        bundle.putString(BluetoothState.DEVICE_ADDRESS, device.getAddress());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(BluetoothState.STATE_CONNECTED);
    }

    // Stop all threads
    public synchronized void stop() {
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread.kill();
            mSecureAcceptThread = null;
        }
        setState(BluetoothState.STATE_NONE);
    }

    // Write to the ConnectedThread in an unsynchronized manner
    // out : The bytes to write
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != BluetoothState.STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    // Indicate that the connection attempt failed and notify the UI Activity
    private void connectionFailed() {
        // Start the service over to restart listening mode
        BluetoothService.this.start(BluetoothService.this.isAndroid);
    }

    // Indicate that the connection was lost and notify the UI Activity
    private void connectionLost() {
        // Start the service over to restart listening mode
        BluetoothService.this.start(BluetoothService.this.isAndroid);
    }

    // This thread runs while listening for incoming connections. It behaves
    // like a server-side client. It runs until a connection is accepted
    // (or until cancelled)
    private class AcceptThread extends Thread {
        boolean isRunning = true;
        // The local server socket
        private BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean isAndroid) {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try {
                if (isAndroid)
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, UUID_ANDROID_DEVICE);
                else
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, UUID_OTHER_DEVICE);
            } catch (IOException e) {
            }
            mmServerSocket = tmp;
        }

        public void run() {
            setName("AcceptThread" + mSocketType);
            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (mState != BluetoothState.STATE_CONNECTED && isRunning) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    if (socket == null) {
                        socket = mmServerSocket.accept();
                    }
                } catch (IOException e) {
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothService.this) {
                        switch (mState) {
                            case BluetoothState.STATE_LISTEN:
                            case BluetoothState.STATE_CONNECTING:
                                // Situation normal. Start the connected thread.
                                connected(socket, socket.getRemoteDevice(),
                                        mSocketType);
                                break;
                            case BluetoothState.STATE_NONE:
                            case BluetoothState.STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                if (mmServerSocket != null) {
                    mmServerSocket.close();
                    mmServerSocket = null;
                }
            } catch (IOException e) {
            }
        }

        public void kill() {
            isRunning = false;
        }
    }


    // This thread runs while attempting to make an outgoing connection
    // with a device. It runs straight through
    // the connection either succeeds or fails
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                if (BluetoothService.this.isAndroid)
                    tmp = device.createRfcommSocketToServiceRecord(UUID_ANDROID_DEVICE);
                else
                    tmp = device.createRfcommSocketToServiceRecord(UUID_OTHER_DEVICE);
            } catch (IOException e) {
            }
            mmSocket = tmp;
        }

        public void run() {
            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
            }
        }
    }

    // This thread runs during a connection with a remote device.
    // It handles all incoming and outgoing transmissions.
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[2048];  // buffer store for the stream
            int bytes; // bytes returned from read()
            ArrayList<Integer> arr_byte = new ArrayList<Integer>();
            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream and prevent stream from cutting off.
                    if (!handReader.equals(HandReader.BLUEBERRY)) {
                        byte[] buffer1 = new byte[2048];  // buffer store for the stream
                        int bytes1; // bytes returned from read()
                        // Read from the InputStream
                        bytes1 = mmInStream.read(buffer1);

                        // Send the obtained bytes to the UI Activity
                        mHandler.obtainMessage(BluetoothState.MESSAGE_READ, bytes1, -1, buffer1).sendToTarget();
                    } else {
                        bytes = mmInStream.read();
                        // If the data is a "/n" (0x0A) then skip it, else if it's a "/r" (0x0D) then add it to the array,
                        // if it's any other character then keep adding to the array until it reaches "/r" then send it to the handler.
                        if (bytes == 0x0A) {
                        } else if (bytes == 0x0D) {
                            buffer = new byte[arr_byte.size()];
                            for (int i = 0; i < arr_byte.size(); i++) {
                                buffer[i] = arr_byte.get(i).byteValue();
                            }
                            // Send the obtained bytes to the UI Activity
                            mHandler.obtainMessage(BluetoothState.MESSAGE_READ, buffer.length, -1, buffer).sendToTarget();
                            arr_byte = new ArrayList<Integer>();
                        } else {
                            arr_byte.add(bytes);
                        }
                    }

                } catch (IOException e) {
                    connectionLost();
                    // Start the service over to restart listening mode
                    BluetoothService.this.start(BluetoothService.this.isAndroid);
                    break;
                }
            }
        }

        // Write to the connected OutStream.
        // @param buffer  The bytes to write
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(BluetoothState.MESSAGE_WRITE, -1, -1, buffer).sendToTarget();
            } catch (IOException e) {
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
            }
        }
    }
}