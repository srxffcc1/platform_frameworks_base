/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import static com.android.internal.util.BitUtils.uint16;

import android.hardware.tetheroffload.control.V1_0.IOffloadControl;
import android.hardware.tetheroffload.control.V1_0.ITetheringOffloadCallback;
import android.hardware.tetheroffload.control.V1_0.NatTimeoutUpdate;
import android.os.Handler;
import android.os.RemoteException;
import android.net.util.SharedLog;

import java.util.ArrayList;


/**
 * Capture tethering dependencies, for injection.
 *
 * @hide
 */
public class OffloadHardwareInterface {
    private static final String TAG = OffloadHardwareInterface.class.getSimpleName();
    private static final String NO_INTERFACE_NAME = "";
    private static final String NO_IPV4_ADDRESS = "";
    private static final String NO_IPV4_GATEWAY = "";

    private static native boolean configOffload();

    private final Handler mHandler;
    private final SharedLog mLog;
    private IOffloadControl mOffloadControl;
    private TetheringOffloadCallback mTetheringOffloadCallback;
    private ControlCallback mControlCallback;

    public static class ControlCallback {
        public void onOffloadEvent(int event) {}

        public void onNatTimeoutUpdate(int proto,
                                       String srcAddr, int srcPort,
                                       String dstAddr, int dstPort) {}
    }

    public OffloadHardwareInterface(Handler h, SharedLog log) {
        mHandler = h;
        mLog = log.forSubComponent(TAG);
    }

    public boolean initOffloadConfig() {
        return configOffload();
    }

    public boolean initOffloadControl(ControlCallback controlCb) {
        mControlCallback = controlCb;

        if (mOffloadControl == null) {
            try {
                mOffloadControl = IOffloadControl.getService();
            } catch (RemoteException e) {
                mLog.e("tethering offload control not supported: " + e);
                return false;
            }
        }

        final String logmsg = String.format("initOffloadControl(%s)",
                (controlCb == null) ? "null"
                        : "0x" + Integer.toHexString(System.identityHashCode(controlCb)));

        mTetheringOffloadCallback = new TetheringOffloadCallback(mHandler, mControlCallback);
        final CbResults results = new CbResults();
        try {
            mOffloadControl.initOffload(
                    mTetheringOffloadCallback,
                    (boolean success, String errMsg) -> {
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

    public void stopOffloadControl() {
        if (mOffloadControl != null) {
            try {
                mOffloadControl.stopOffload(
                        (boolean success, String errMsg) -> {
                            if (!success) mLog.e("stopOffload failed: " + errMsg);
                        });
            } catch (RemoteException e) {
                mLog.e("failed to stopOffload: " + e);
            }
        }
        mOffloadControl = null;
        mTetheringOffloadCallback = null;
        mControlCallback = null;
        mLog.log("stopOffloadControl()");
    }

    public boolean setUpstreamParameters(
            String iface, String v4addr, String v4gateway, ArrayList<String> v6gws) {
        iface = (iface != null) ? iface : NO_INTERFACE_NAME;
        v4addr = (v4addr != null) ? v4addr : NO_IPV4_ADDRESS;
        v4gateway = (v4gateway != null) ? v4gateway : NO_IPV4_GATEWAY;
        v6gws = (v6gws != null) ? v6gws : new ArrayList<>();

        final String logmsg = String.format("setUpstreamParameters(%s, %s, %s, [%s])",
                iface, v4addr, v4gateway, String.join(",", v6gws));

        final CbResults results = new CbResults();
        try {
            mOffloadControl.setUpstreamParameters(
                    iface, v4addr, v4gateway, v6gws,
                    (boolean success, String errMsg) -> {
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            record(logmsg, e);
            return false;
        }

        record(logmsg, results);
        return results.success;
    }

    private void record(String msg, Throwable t) {
        mLog.e(msg + " -> exception: " + t);
    }

    private void record(String msg, CbResults results) {
        final String logmsg = msg + " -> " + results;
        if (!results.success) {
            mLog.e(logmsg);
        } else {
            mLog.log(logmsg);
        }
    }

    private static class TetheringOffloadCallback extends ITetheringOffloadCallback.Stub {
        public final Handler handler;
        public final ControlCallback controlCb;

        public TetheringOffloadCallback(Handler h, ControlCallback cb) {
            handler = h;
            controlCb = cb;
        }

        @Override
        public void onEvent(int event) {
            handler.post(() -> { controlCb.onOffloadEvent(event); });
        }

        @Override
        public void updateTimeout(NatTimeoutUpdate params) {
            handler.post(() -> {
                    controlCb.onNatTimeoutUpdate(
                        params.proto,
                        params.src.addr, uint16(params.src.port),
                        params.dst.addr, uint16(params.dst.port));
            });
        }
    }

    private static class CbResults {
        boolean success;
        String errMsg;

        public String toString() {
            if (success) {
                return "ok";
            } else {
                return "fail: " + errMsg;
            }
        }
    }
}
