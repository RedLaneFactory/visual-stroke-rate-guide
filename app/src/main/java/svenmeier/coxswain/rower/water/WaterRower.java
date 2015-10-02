/*
 * Copyright 2015 Sven Meier
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package svenmeier.coxswain.rower.water;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;

import svenmeier.coxswain.MainActivity;
import svenmeier.coxswain.view.ProgramsFragment;
import svenmeier.coxswain.gym.Snapshot;
import svenmeier.coxswain.rower.Rower;

/**
 * https://github.com/jamesnesfield/node-waterrower/blob/develop/Waterrower/index.js
 */
public class WaterRower implements Rower {

    private static final String ACTION_USB_PERMISSION = "svenmeier.coxswain.USB_PERMISSION";

    private static final int TIMEOUT = 0; // milliseconds

    private final Context context;

    private final Snapshot memory;

    private final UsbDevice device;

    private UsbDeviceConnection connection;

    private Input input;
    private Output output;

    private Mapper mapper = new Mapper();

    private BroadcastReceiver receiver;

    private Queue<String> requests = new LinkedList<>();

    public WaterRower(Context context, Snapshot memory, UsbDevice device) {
        this.context = context;
        this.memory = memory;

        this.device = device;
    }

    @Override
    public synchronized void open() {
        if (isOpen()) {
            return;
        }

        if (this.device == null) {
            onFailed("No device");
            return;
        }

        receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (isOpen()) {
                    if (ACTION_USB_PERMISSION.equals(action)) {
                        synchronized (this) {
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                connect();
                            } else {
                                onFailed("No permission granted");
                            }
                        }
                    } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (device != null) {
                            onEnd();
                        }
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(receiver, filter);

        UsbManager manager = (UsbManager) context.getSystemService(context.USB_SERVICE);

        manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0));
    }

    private void connect() {
        UsbManager manager = (UsbManager) context.getSystemService(context.USB_SERVICE);

        connection = manager.openDevice(device);
        if (connection == null) {
            onFailed("No open");
            return;
        }

        if (initEndpoints() == false) {
            onFailed("No endpoints found");
            return;
        }

        // set data request, baud rate, 115200,
        connection.controlTransfer(0x40, 0x03, 0x001A, 0, null, 0, 0);

        requests.add(Mapper.INIT);
        requests.add(Mapper.RESET);
        requests.add(Mapper.VERSION);

        onStart();
    }

    @Override
    public synchronized boolean isOpen() {
        return this.receiver != null;
    }

    @Override
    public synchronized void close() {
        if (isOpen() == false) {
            return;
        }

        this.requests.clear();

        context.unregisterReceiver(receiver);
        this.receiver = null;

        this.input = null;
        this.output = null;

        if (this.connection != null) {
            this.connection.close();
            this.connection = null;
        }

        Log.d(MainActivity.TAG, "closed");
    }

    @Override
    public void reset() {
        requests.add(Mapper.RESET);
    }

    @Override
    public synchronized boolean row() {
        Log.d(MainActivity.TAG, "processing");

        if (isOpen() == false) {
            return false;
        }

        if (requests.isEmpty()) {
            output.write(mapper.cycle().request);
        } else {
            output.write(requests.remove());
        }

        // could be closed while #write() was waiting
        if (isOpen() == false) {
            return false;
        }

        while (true) {
            String read = input.read();
            if (read == null) {
                break;
            }

            mapper.map(read, memory);
        }

        return true;
    }

    private boolean initEndpoints() {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);

            Log.d(MainActivity.TAG, String.format("interface %s", i));

            UsbEndpoint out = null;
            UsbEndpoint in = null;

            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint endpoint = intf.getEndpoint(e);

                Log.d(MainActivity.TAG, String.format("endpoint %s: type=%s direction=%s", e, endpoint.getType(), endpoint.getDirection()));

                if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                        out = endpoint;
                    } else if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                        in = endpoint;
                    }
                }
            }

            if (out != null && in != null) {
                if (connection.claimInterface(intf, true)) {
                    input = new Input(connection, in);
                    output = new Output(connection, out, this);
                    return true;
                } else {
                    Log.d(MainActivity.TAG, "cannot claim");
                }
            }
        }
        return false;
    }

    protected void onFailed(String message) {
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onEnd() {
    }
}