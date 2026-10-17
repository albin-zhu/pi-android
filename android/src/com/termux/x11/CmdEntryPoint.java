package com.termux.x11;

import android.os.ParcelFileDescriptor;
import dalvik.annotation.optimization.CriticalNative;

/** JNI contract for Termux:X11 fa3a8b430e2896a19f44c99a9cb056254615ae06. */
public final class CmdEntryPoint {
    private final X11Service service;

    CmdEntryPoint(X11Service service) {
        this.service = service;
        System.loadLibrary("Xlorie");
    }

    public native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    public native void reportFatalError(String message);
    @CriticalNative static native boolean connected();

    // Native invokes these on its X server thread. Never create a renderer connection here:
    // OsVendorInit runs before the screen is ready, and network knocks are not trusted IPC.
    public void sendBroadcast() { service.onNativeAnnouncement(false); }
    public void sendBroadcastDelayed() { service.onNativeAnnouncement(true); }
    public void setDocumentTag(String tag) { service.onNativeDocumentTag(tag); }
}
