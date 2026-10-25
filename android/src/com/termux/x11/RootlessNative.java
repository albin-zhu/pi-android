package com.termux.x11;

import android.view.Surface;

/** Sole native renderer, owned by the X11 service, never by an Activity. */
public final class RootlessNative {
    interface Listener {
        void window(long window, long generation, int width, int height, String title, boolean present);
        void error(String message);
        void connected(boolean connected);
        void clipboardText(long window, long generation, String text);
        void clipboardRequest(long window, long generation);
        void imeReset(long window, long generation);
    }
    private final Listener listener;
    RootlessNative(Listener listener) {
        this.listener = listener;
        System.loadLibrary("Xlorie");
    }
    public native long create();
    public native void configureDisplay(long handle, int width, int height, int refreshRate);
    public native void connect(long handle, int fd);
    public native void attach(long handle, long window, long generation, long lease, Surface surface, int width, int height, boolean readOnly);
    public native void detach(long handle, long window, long generation, long lease);
    public native void resize(long handle, long window, long generation, int width, int height);
    public native void focus(long handle, long window, long generation);
    public native void pointer(long handle, long window, long generation, float x, float y, int button, boolean down, boolean relative);
    public native void key(long handle, long window, long generation, int scanCode, int keyCode, boolean down);
    public native void text(long handle, long window, long generation, String text);
    public native void closeWindow(long handle, long window, long generation);
    public native void clipboardEnabled(long handle, long window, long generation, boolean enabled);
    public native void clipboardAnnounce(long handle, long window, long generation);
    public native void clipboardText(long handle, long window, long generation, String text);
    public native void destroy(long handle);
    public void onWindow(long window, long generation, int width, int height, String title, boolean present) {
        listener.window(window, generation, width, height, title, present);
    }
    public void onError(String message) { listener.error(message); }
    public void onConnected(boolean connected) { listener.connected(connected); }
    public void onClipboardText(long window, long generation, String text) { listener.clipboardText(window, generation, text); }
    public void onClipboardRequest(long window, long generation) { listener.clipboardRequest(window, generation); }
    public void onImeReset(long window, long generation) { listener.imeReset(window, generation); }
}
