/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.car.trust;

import static android.bluetooth.BluetoothProfile.GATT_SERVER;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.car.Utils;

/**
 * A generic class that manages BLE operations like start/stop advertising, notifying connects/
 * disconnects and reading/writing values to GATT characteristics.
 *
 * TODO(b/123248433) This could move to a separate comms library.
 */
public abstract class BleManager {
    private static final String TAG = BleManager.class.getSimpleName();

    private static final int BLE_RETRY_LIMIT = 5;
    private static final int BLE_RETRY_INTERVAL_MS = 1000;

    private final Handler mHandler = new Handler();

    private final Context mContext;
    private BluetoothManager mBluetoothManager;
    private BluetoothLeAdvertiser mAdvertiser;
    private BluetoothGattServer mGattServer;
    private int mAdvertiserStartCount;

    BleManager(Context context) {
        mContext = context;
    }

    /**
     * Starts the GATT server with the given {@link BluetoothGattService} and begins
     * advertising.
     *
     * <p>It is possible that BLE service is still in TURNING_ON state when this method is invoked.
     * Therefore, several retries will be made to ensure advertising is started.
     *
     * @param service {@link BluetoothGattService} that will be discovered by clients
     */
    protected void startAdvertising(BluetoothGattService service,
            AdvertiseCallback advertiseCallback) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startAdvertising: " + service.getUuid().toString());
        }
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "System does not support BLE");
            return;
        }

        // Only open one Gatt server.
        if (mGattServer == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Opening a new GATT Server");
            }
            mBluetoothManager = (BluetoothManager) mContext.getSystemService(
                    Context.BLUETOOTH_SERVICE);
            mGattServer = mBluetoothManager.openGattServer(mContext, mGattServerCallback);

            if (mGattServer == null) {
                Log.e(TAG, "Gatt Server not created");
                return;
            }
        }

        mGattServer.clearServices();
        mGattServer.addService(service);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(service.getUuid()))
                .build();

        mAdvertiserStartCount = 0;
        startAdvertisingInternally(settings, data, advertiseCallback);
    }

    private void startAdvertisingInternally(AdvertiseSettings settings, AdvertiseData data,
            AdvertiseCallback advertiseCallback) {
        if (BluetoothAdapter.getDefaultAdapter() != null) {
            mAdvertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        }

        if (mAdvertiser != null) {
            mAdvertiser.startAdvertising(settings, data, advertiseCallback);
            mAdvertiserStartCount = 0;
        } else if (mAdvertiserStartCount < BLE_RETRY_LIMIT) {
            mHandler.postDelayed(
                    () -> startAdvertisingInternally(settings, data, advertiseCallback),
                    BLE_RETRY_INTERVAL_MS);
            mAdvertiserStartCount += 1;
        } else {
            Log.e(TAG, "Cannot start BLE Advertisement.  BT Adapter: "
                    + BluetoothAdapter.getDefaultAdapter() + " Advertise Retry count: "
                    + mAdvertiserStartCount);
        }
    }

    protected void stopAdvertising(AdvertiseCallback advertiseCallback) {
        if (mAdvertiser != null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "stopAdvertising: ");
            }
            mAdvertiser.stopAdvertising(advertiseCallback);
        }
    }

    /**
     * Notifies the characteristic change via {@link BluetoothGattServer}
     */
    protected void notifyCharacteristicChanged(BluetoothDevice device,
            BluetoothGattCharacteristic characteristic, boolean confirm) {
        if (mGattServer != null) {
            mGattServer.notifyCharacteristicChanged(device, characteristic, confirm);
        }
    }

    protected Context getContext() {
        return mContext;
    }

    /**
     * Cleans up the BLE GATT server state.
     */
    void cleanup() {
        // Stops the advertiser and GATT server. This needs to be done to avoid leaks
        if (mAdvertiser != null) {
            mAdvertiser.cleanup();
        }

        if (mGattServer != null) {
            mGattServer.clearServices();
            try {
                for (BluetoothDevice d : mBluetoothManager.getConnectedDevices(GATT_SERVER)) {
                    mGattServer.cancelConnection(d);
                }
            } catch (UnsupportedOperationException e) {
                Log.e(TAG, "Error getting connected devices", e);
            } finally {
                stopGattServer();
            }
        }
    }

    /**
     * Close the GATT Server
     */
    void stopGattServer() {
        if (mGattServer == null) {
            return;
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "stopGattServer");
        }
        mGattServer.close();
        mGattServer = null;
    }

    /**
     * Triggered when a device (GATT client) connected.
     *
     * @param device Remote device that connected on BLE.
     */
    protected void onRemoteDeviceConnected(BluetoothDevice device) {
    }

    /**
     * Triggered when a device (GATT client) disconnected.
     *
     * @param device Remote device that disconnected on BLE.
     */
    protected void onRemoteDeviceDisconnected(BluetoothDevice device) {
    }

    /**
     * Triggered when this BleManager receives a write request from a remote
     * device. Sub-classes should implement how to handle requests.
     * <p>
     *
     * @see BluetoothGattServerCallback#onCharacteristicWriteRequest(BluetoothDevice, int,
     * BluetoothGattCharacteristic, boolean, boolean, int, byte[])
     */
    protected abstract void onCharacteristicWrite(BluetoothDevice device, int requestId,
            BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean
            responseNeeded, int offset, byte[] value);

    /**
     * Triggered when this BleManager receives a read request from a remote device.
     * <p>
     *
     * @see BluetoothGattServerCallback#onCharacteristicReadRequest(BluetoothDevice, int, int,
     * BluetoothGattCharacteristic)
     */
    protected abstract void onCharacteristicRead(BluetoothDevice device,
            int requestId, int offset, BluetoothGattCharacteristic characteristic);

    private final BluetoothGattServerCallback mGattServerCallback =
            new BluetoothGattServerCallback() {
                @Override
                public void onConnectionStateChange(BluetoothDevice device, int status,
                        int newState) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "BLE Connection State Change: " + newState);
                    }
                    switch (newState) {
                        case BluetoothProfile.STATE_CONNECTED:
                            onRemoteDeviceConnected(device);
                            break;
                        case BluetoothProfile.STATE_DISCONNECTED:
                            onRemoteDeviceDisconnected(device);
                            break;
                        default:
                            Log.w(TAG,
                                    "Connection state not connecting or disconnecting; ignoring: "
                                            + newState);
                    }
                }

                @Override
                public void onServiceAdded(int status, BluetoothGattService service) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG,
                                "Service added status: " + status + " uuid: " + service.getUuid());
                    }
                }

                @Override
                public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                        int offset, BluetoothGattCharacteristic characteristic) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Read request for characteristic: " + characteristic.getUuid());
                    }

                    mGattServer.sendResponse(device, requestId,
                            BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());
                    onCharacteristicRead(device, requestId, offset, characteristic);
                }

                @Override
                public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                        BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                        boolean responseNeeded, int offset, byte[] value) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Write request for characteristic: " + characteristic.getUuid()
                                + "value: " + Utils.byteArrayToHexString(value));
                    }

                    mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                            offset, value);
                    onCharacteristicWrite(device, requestId, characteristic,
                            preparedWrite, responseNeeded, offset, value);
                }
            };
}
