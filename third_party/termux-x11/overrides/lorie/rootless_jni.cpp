#define EGL_EGLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>
#include "lorie.h"
#include "rootless.h"
// Xserver's C helpers must not expand qualified C++ algorithms.
#undef min
#undef max

namespace {
struct Layer { RootlessLayer value{}; std::string title; bool primary = false; };
struct Output {
    uint64_t window, generation, lease;
    ANativeWindow *pending = nullptr, *native = nullptr;
    EGLSurface surface = EGL_NO_SURFACE;
    int width = 0, height = 0;
    bool readOnly = true, changed = false, drawPending = true;
};
struct Rootless {
    uint64_t handle;
    JavaVM *vm = nullptr;
    jobject callback = nullptr;
    jmethodID onWindow, onError, onConnected, onClipboardText, onClipboardRequest, onImeReset;
    uint64_t focusedWindow = 0, focusedGeneration = 0, clipboardLease = 0;
    bool clipboardEnabled = false;
    Renderer renderer;
    volatile int fd = -1;
    std::thread transport;
    std::mutex connectionLock; // Serializes ownership changes and transport-thread joins.
    std::atomic<bool> active{true}, dirty{true};
    std::vector<Layer> layers;
    std::vector<Output> outputs;
};
std::mutex mutex;
std::shared_ptr<Rootless> current;
uint64_t nextHandle = 1;
std::mutex transportMutex;
std::condition_variable surfaceChanged;
struct Env {
    JavaVM *vm;
    JNIEnv *env = nullptr;
    bool attached = false;
    explicit Env(JavaVM *v) : vm(v) {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};
std::shared_ptr<Rootless> get(uint64_t handle) {
    std::lock_guard<std::mutex> lock(mutex);
    return current && current->active && current->handle == handle ? current : nullptr;
}
void wake(Rootless &r) {
    r.dirty = true;
    if (r.renderer.stateCond) pthread_cond_signal(r.renderer.stateCond);
}
bool dirtyOwner(Rootless &r, uint64_t window, uint64_t generation) {
    bool marked = false;
    for (auto &o : r.outputs) if (o.window == window && o.generation == generation) {
        o.drawPending = true;
        marked = marked || o.pending || (!o.changed && o.native);
    }
    return marked;
}
void dirtyBuffer(Rootless &r, uint64_t buffer) {
    for (const auto &layer : r.layers) if (layer.value.buffer == buffer && layer.value.present) {
        if (layer.value.owner) dirtyOwner(r,layer.value.owner,layer.value.ownerGeneration);
        dirtyOwner(r,0,0);
    }
}
void error(Rootless &r, const char *message) {
    Env e(r.vm);
    if (!e.env || !r.callback) return;
    jstring text = e.env->NewStringUTF(message);
    e.env->CallVoidMethod(r.callback, r.onError, text);
    e.env->DeleteLocalRef(text);
    if (e.env->ExceptionCheck()) e.env->ExceptionClear();
}
jstring titleString(JNIEnv *env, const std::string &text) {
    // X11 _NET_WM_NAME is UTF-8, not JNI's modified UTF-8.
    std::vector<jchar> out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size();) {
        uint32_t c = static_cast<unsigned char>(text[i++]);
        unsigned continuation = c < 0x80 ? 0 : c >= 0xf0 && c <= 0xf4 ? 3 : c >= 0xe0 && c < 0xf0 ? 2 : c >= 0xc2 && c < 0xe0 ? 1 : 4;
        if (continuation == 4) c = 0xfffd;
        else if (continuation) {
            uint32_t minimum = continuation == 1 ? 0x80 : continuation == 2 ? 0x800 : 0x10000;
            c &= (1u << (6-continuation))-1;
            bool valid = true;
            for (unsigned n = 0; n < continuation; n++) {
                if (i == text.size() || (static_cast<unsigned char>(text[i]) & 0xc0) != 0x80) { valid = false; break; }
                c = (c << 6) | (text[i++] & 0x3f);
            }
            if (!valid || c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) c = 0xfffd;
        }
        if (c > 0xffff) { c -= 0x10000; out.push_back(0xd800 + (c >> 10)); out.push_back(0xdc00 + (c & 1023)); }
        else out.push_back(c);
    }
    return env->NewString(out.data(), out.size());
}
void announce(Rootless &r, const Layer &layer, bool present) {
    Env e(r.vm);
    if (!e.env || !r.callback) return;
    jstring title = titleString(e.env, layer.title);
    e.env->CallVoidMethod(r.callback, r.onWindow, static_cast<jlong>(layer.value.window),
        static_cast<jlong>(layer.value.generation), layer.value.width, layer.value.height, title, static_cast<jboolean>(present));
    e.env->DeleteLocalRef(title);
    if (e.env->ExceptionCheck()) e.env->ExceptionClear();
}
void connected(Rootless &r, bool value) {
    Env e(r.vm);
    if (e.env && r.callback) e.env->CallVoidMethod(r.callback, r.onConnected, static_cast<jboolean>(value));
}
bool readAll(int fd, void *data, size_t length) {
    char *p = static_cast<char*>(data);
    while (length) {
        ssize_t n = read(fd, p, length);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n; length -= n;
    }
    return true;
}
void clipboardCallback(Rootless &r, const std::string *text) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!r.active || !r.clipboardEnabled) return;
    bool authorized = false;
    for (auto &o : r.outputs)
        if (o.window == r.focusedWindow && o.generation == r.focusedGeneration &&
            o.lease == r.clipboardLease && !o.readOnly && (o.pending || (!o.changed && o.native))) authorized = true;
    if (!authorized) return;
    Env e(r.vm);
    if (!e.env) return;
    if (text) {
        jstring value = titleString(e.env, *text);
        e.env->CallVoidMethod(r.callback, r.onClipboardText, static_cast<jlong>(r.focusedWindow), static_cast<jlong>(r.focusedGeneration), value);
        e.env->DeleteLocalRef(value);
    } else e.env->CallVoidMethod(r.callback, r.onClipboardRequest, static_cast<jlong>(r.focusedWindow), static_cast<jlong>(r.focusedGeneration));
}
void receive(std::shared_ptr<Rootless> r, int fd) {
    connected(*r, true);
    lorieEvent event{};
    while (r->active && readAll(fd, &event, sizeof(event))) {
        if (event.type == EVENT_SHARED_SERVER_STATE) {
            int stateFd = ancil_recv_fd(fd);
            if (stateFd < 0) { error(*r, "Renderer transport did not receive shared server state"); break; }
            auto *state = static_cast<lorie_shared_server_state*>(mmap(nullptr, sizeof(lorie_shared_server_state), PROT_READ|PROT_WRITE, MAP_SHARED, stateFd, 0));
            close(stateFd);
            if (state == MAP_FAILED) { error(*r, "Cannot map X server renderer state"); break; }
            r->renderer.setSharedState(state);
        } else if (event.type == EVENT_ADD_BUFFER) {
            LorieBuffer *buffer = nullptr;
            LorieBuffer_recvHandleFromUnixSocket(fd, &buffer);
            if (!buffer) { error(*r, "Cannot import Linux window GPU buffer"); break; }
            r->renderer.addBuffer(buffer);
            // Do not take the scene lock here: the renderer may be waiting for
            // another buffer carried by this same transport.
            wake(*r);
        } else if (event.type == EVENT_REMOVE_BUFFER) r->renderer.removeBuffer(event.removeBuffer.id);
        else if (event.type == EVENT_CLIPBOARD_SEND) {
            if (event.clipboardSend.count > 16*1024*1024) { error(*r, "X clipboard exceeds 16 MiB safety limit"); break; }
            std::string text(event.clipboardSend.count, '\0');
            if (!readAll(fd, text.data(), text.size())) break;
            clipboardCallback(*r, &text);
        } else if (event.type == EVENT_CLIPBOARD_REQUEST) clipboardCallback(*r, nullptr);
        else if (event.type == EVENT_WINDOW_FOCUS_CHANGED) rootlessRequestImeReset(r->handle);
    }
    r->renderer.setSharedState(nullptr);
    r->renderer.removeAllBuffers();
    {
        std::lock_guard<std::mutex> writeLock(transportMutex);
        close(fd);
        if (r->fd == fd) r->fd = -1;
    }
    connected(*r, false);
}
Layer *findLayer(Rootless &r, uint64_t window, uint64_t generation) {
    for (auto &layer : r.layers)
        if (layer.value.window == window && layer.value.generation == generation) return &layer;
    return nullptr;
}
void releaseOutput(Renderer &renderer, Output &output) {
    eglMakeCurrent(renderer.egl_display, renderer.defaultSfc, renderer.defaultSfc, renderer.ctx);
    if (output.surface != EGL_NO_SURFACE) eglDestroySurface(renderer.egl_display, output.surface);
    output.surface = EGL_NO_SURFACE;
    if (output.native) ANativeWindow_release(output.native);
    output.native = nullptr;
}
void bounds(Rootless &r, const Layer &main, int &x, int &y, int &width, int &height) {
    x = main.value.x; y = main.value.y;
    int right = x + main.value.width, bottom = y + main.value.height;
    for (auto &layer : r.layers) if (layer.value.present && layer.value.owner == main.value.window &&
        layer.value.ownerGeneration == main.value.generation) {
        x = std::min(x, layer.value.x); y = std::min(y, layer.value.y);
        right = std::max(right, layer.value.x + layer.value.width);
        bottom = std::max(bottom, layer.value.y + layer.value.height);
    }
    width = right-x; height = bottom-y;
}
}

extern "C" bool rootlessEnabled() {
    std::lock_guard<std::mutex> lock(mutex);
    return current && current->active;
}
extern "C" uint64_t rootlessSession() {
    std::lock_guard<std::mutex> lock(mutex);
    return current && current->active ? current->handle : 0;
}
extern "C" void rootlessDamage(uint64_t window, uint64_t generation) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current || !current->active) return;
    Layer *layer = findLayer(*current,window,generation);
    if (!layer || !layer->value.present) return;
    bool dirty = dirtyOwner(*current,0,0);
    if (layer->value.owner)
        dirty = dirtyOwner(*current,layer->value.owner,layer->value.ownerGeneration) || dirty;
    if (dirty) wake(*current);
}
extern "C" void rootlessImeReset(uint64_t handle) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current || !current->active || current->handle != handle) return;
    Rootless &r = *current;
    Layer *layer = findLayer(r,r.focusedWindow,r.focusedGeneration);
    if (!layer || !layer->primary || !layer->value.present) return;
    for (const auto &o : r.outputs) if (o.window == r.focusedWindow && o.generation == r.focusedGeneration &&
        !o.readOnly && (o.pending || (!o.changed && o.native))) {
        Env e(r.vm);
        if (e.env) {
            e.env->CallVoidMethod(r.callback,r.onImeReset,static_cast<jlong>(o.window),static_cast<jlong>(o.generation));
            if (e.env->ExceptionCheck()) e.env->ExceptionClear();
        }
        break;
    }
}
extern "C" void rootlessError(const char *message) {
    std::lock_guard<std::mutex> lock(mutex);
    if (current && current->active) error(*current, message);
}
extern "C" void rootlessPublish(const RootlessLayer *value, const char *title, bool primary) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current || !current->active) return;
    Rootless &r = *current;
    Layer *layer = findLayer(r, value->window, value->generation);
    bool orderChanged = !layer || layer->value.order != value->order;
    if (!layer) { r.layers.emplace_back(); layer = &r.layers.back(); }
    bool wasPresent = layer->primary && layer->value.present;
    bool metadata = layer->value.width != value->width || layer->value.height != value->height || layer->title != title;
    bool present = primary && value->present;
    if (layer->value.owner) dirtyOwner(r,layer->value.owner,layer->value.ownerGeneration);
    if (value->owner) dirtyOwner(r,value->owner,value->ownerGeneration);
    dirtyOwner(r,0,0);
    layer->value = *value; layer->primary = primary;
    if (layer->title != title) layer->title = title;
    if (wasPresent != present || (present && metadata)) announce(r, *layer, present);
    if (orderChanged)
        std::sort(r.layers.begin(), r.layers.end(), [](const Layer &a, const Layer &b) { return a.value.order < b.value.order; });
    wake(r);
}
extern "C" void rootlessForget(uint64_t window, uint64_t generation) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current) return;
    Rootless &r = *current;
    dirtyOwner(r,0,0);
    for (auto it = r.layers.begin(); it != r.layers.end(); ++it) if (it->value.window == window && it->value.generation == generation) {
        if (it->primary && it->value.present && r.active) announce(r, *it, false);
        if (it->value.owner) dirtyOwner(r,it->value.owner,it->value.ownerGeneration);
        r.layers.erase(it); break;
    }
    for (auto &layer : r.layers) if (layer.value.owner == window && layer.value.ownerGeneration == generation) {
        layer.value.owner = 0; layer.value.ownerGeneration = 0;
    }
    for (auto &o : r.outputs) if (o.window == window && o.generation == generation) {
        if (o.pending) ANativeWindow_release(o.pending);
        o.pending = nullptr; o.changed = true;
    }
    wake(r);
}
extern "C" bool rootlessAuthorize(RootlessCommand *command, int *width, int *height) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current || !current->active || current->handle != command->handle) return false;
    Rootless &r = *current;
    Layer *layer = findLayer(r, command->window, command->generation);
    if (!layer || !layer->primary || !layer->value.present) return false;
    for (auto &o : r.outputs) if (o.window == command->window && o.generation == command->generation &&
        o.lease == command->lease && !o.readOnly && (o.pending || (!o.changed && o.native))) {
        *width = o.width; *height = o.height;
        bounds(r, *layer, command->sourceX, command->sourceY, command->sourceWidth, command->sourceHeight);
        return true;
    }
    return false;
}
static void disableClipboardLocked(Rootless &r);
extern "C" void rootlessFocused(uint64_t window, uint64_t generation) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current || !current->active) return;
    if (current->focusedWindow != window || current->focusedGeneration != generation) {
        disableClipboardLocked(*current);
        dirtyOwner(*current,current->focusedWindow,current->focusedGeneration);
        dirtyOwner(*current,window,generation);
        wake(*current);
    }
    current->focusedWindow = window; current->focusedGeneration = generation;
}
static bool sendAll(int fd, const void *data, size_t size) {
    const char *p = static_cast<const char*>(data);
    while (size) {
        ssize_t count = send(fd, p, size, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        p += count; size -= count;
    }
    return true;
}
static void disableClipboardLocked(Rootless &r) {
    if (!r.clipboardEnabled) return;
    r.clipboardEnabled = false; r.clipboardLease = 0;
    lorieEvent event{.clipboardEnable = {.t = EVENT_CLIPBOARD_ENABLE, .enable = false}};
    std::lock_guard<std::mutex> writeLock(transportMutex);
    if (r.fd >= 0) sendAll(r.fd, &event, sizeof(event));
}
extern "C" void rootlessClipboardCommand(const RootlessCommand *c) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current || !current->active || current->handle != c->handle ||
        current->focusedWindow != c->window || current->focusedGeneration != c->generation || current->fd < 0) return;
    Rootless &r = *current;
    lorieEvent event{};
    if (c->kind == ROOTLESS_CLIPBOARD_ENABLE) {
        r.clipboardEnabled = c->down; r.clipboardLease = c->lease;
        event.clipboardEnable.t = EVENT_CLIPBOARD_ENABLE; event.clipboardEnable.enable = c->down;
    } else {
        if (!r.clipboardEnabled || r.clipboardLease != c->lease) return;
        if (c->kind == ROOTLESS_CLIPBOARD_ANNOUNCE) event.type = EVENT_CLIPBOARD_ANNOUNCE;
        else { event.clipboardSend.t = EVENT_CLIPBOARD_SEND; event.clipboardSend.count = c->text ? strlen(c->text) : 0; }
    }
    std::lock_guard<std::mutex> writeLock(transportMutex);
    if (!sendAll(r.fd, &event, sizeof(event)) ||
        (event.type == EVENT_CLIPBOARD_SEND && !sendAll(r.fd,c->text,event.clipboardSend.count)))
        error(r,"Cannot send clipboard through the X server transport");
}
void rootlessNotifyGpuCopyDone(Renderer *renderer) {
    std::lock_guard<std::mutex> writeLock(transportMutex);
    lorieEvent event{.type = EVENT_GPU_COPY_DONE};
    if (renderer->connFdPtr && *renderer->connFdPtr >= 0)
        sendAll(*renderer->connFdPtr, &event, sizeof(event));
}
void rootlessRendererBufferDamage(Renderer *renderer, uint64_t buffer) {
    // Renderer-thread callers hold the rootless scene lock.
    if (!current || &current->renderer != renderer) return;
    dirtyBuffer(*current,buffer);
    if (renderer->state && buffer == renderer->state->rootWindowTextureID) dirtyOwner(*current,0,0);
}
void rootlessRendererBufferReady(Renderer *renderer, uint64_t buffer) {
    std::lock_guard<std::mutex> lock(mutex);
    rootlessRendererBufferDamage(renderer,buffer);
}
bool rootlessRendererPending(Renderer *renderer) {
    std::lock_guard<std::mutex> lock(mutex);
    return current && &current->renderer == renderer && current->dirty;
}
void rootlessRendererDraw(Renderer *renderer) {
    auto *state = renderer->state;
    if (state) lorie_mutex_lock(&state->lock, &state->lockingPid);
    std::unique_lock<std::mutex> lock(mutex);
    if (!current || &current->renderer != renderer) {
        lock.unlock();
        if (state) lorie_mutex_unlock(&state->lock, &state->lockingPid);
        return;
    }
    Rootless &r = *current;
    r.dirty = false;
    for (auto &o : r.outputs) if (o.changed) {
        releaseOutput(*renderer, o);
        o.native = o.pending; o.pending = nullptr; o.changed = false;
        o.drawPending = true;
        if (o.native) {
            o.surface = eglCreateWindowSurface(renderer->egl_display, renderer->cfg, o.native, nullptr);
            if (o.surface == EGL_NO_SURFACE) { error(r, "Cannot create EGL output for Android window Surface"); ANativeWindow_release(o.native); o.native = nullptr; }
        }
    }
    r.outputs.erase(std::remove_if(r.outputs.begin(), r.outputs.end(), [&](const Output &o) {
        return o.window && !o.native && !o.pending && !findLayer(r,o.window,o.generation);
    }), r.outputs.end());
    surfaceChanged.notify_all();
    if (!state) return;
    if (!eglMakeCurrent(renderer->egl_display, renderer->defaultSfc, renderer->defaultSfc, renderer->ctx)) {
        error(r,"The central GPU context was lost; reconnect the X11 renderer");
        state->surfaceAvailable = false;
        {
            std::lock_guard<std::mutex> writeLock(transportMutex);
            if (r.fd >= 0) shutdown(r.fd,SHUT_RDWR);
        }
        lock.unlock();
        lorie_mutex_unlock(&state->lock, &state->lockingPid);
        return;
    }
    if (state->drawRequested) dirtyOwner(r,0,0);
    if (state->cursor.moved || state->cursor.updated) {
        if (r.focusedWindow) dirtyOwner(r,r.focusedWindow,r.focusedGeneration);
        dirtyOwner(r,0,0);
    }
    uint64_t serial = renderer->applyPendingGpuCopiesLocked();
    if (state->cursor.updated) {
        lorie_mutex_lock(&state->cursor.lock, &state->cursor.lockingPid);
        renderer->bindTexture(renderer->cursor.id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, state->cursor.width, state->cursor.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, state->cursor.bits);
        state->cursor.updated = false;
        lorie_mutex_unlock(&state->cursor.lock, &state->cursor.lockingPid);
    }
    bool gpuFinished = false;
    for (auto &o : r.outputs) {
        if (o.surface == EGL_NO_SURFACE || !o.drawPending) continue;
        o.drawPending = false;
        Layer *main = o.window ? findLayer(r, o.window, o.generation) : nullptr;
        if (o.window && (!main || !main->value.present)) continue;
        if (!eglMakeCurrent(renderer->egl_display, o.surface, o.surface, renderer->ctx)) {
            error(r, "Android window EGL Surface was lost; reopen the window"); releaseOutput(*renderer, o); continue;
        }
        eglSwapInterval(renderer->egl_display, 0);
        int sw = ANativeWindow_getWidth(o.native), sh = ANativeWindow_getHeight(o.native);
        int x = 0, y = 0, width = 0, height = 0;
        LorieBuffer *desktop = nullptr;
        if (main) bounds(r, *main, x, y, width, height);
        else {
            desktop = renderer->findBufferWithRetry(state->rootWindowTextureID);
            if (desktop) { const auto *d = LorieBuffer_description(desktop); width = d->width; height = d->height; }
        }
        if (width <= 0 || height <= 0 || sw <= 0 || sh <= 0) continue;
        float scale = std::min(static_cast<float>(sw)/width, static_cast<float>(sh)/height);
        int vw = std::max(1, static_cast<int>(width*scale)), vh = std::max(1, static_cast<int>(height*scale));
        glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND);
        glViewport(0, 0, sw, sh); glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
        glViewport((sw-vw)/2, (sh-vh)/2, vw, vh);
        if (desktop) {
            LorieBuffer_bindTexture(desktop);
            renderer->drawRegion(0,-1,-1,1,1,0,0,1,1,LorieBuffer_isRgba(desktop));
        }
        for (auto &layer : r.layers) {
            const auto &v = layer.value;
            if (!v.present || !v.buffer ||
                (o.window && (v.owner != o.window || v.ownerGeneration != o.generation))) continue;
            LorieBuffer *buffer = renderer->findBufferWithRetry(v.buffer);
            if (!buffer) continue; // Socket registration arrival schedules the next frame.
            LorieBuffer_bindTexture(buffer);
            if (v.alpha) { glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); }
            else glDisable(GL_BLEND);
            renderer->drawRegion(0, 2.f*(v.x-x)/width-1, 2.f*(v.y-y)/height-1,
                2.f*(v.x-x+v.width)/width-1, 2.f*(v.y-y+v.height)/height-1,
                0,0,1,1,LorieBuffer_isRgba(buffer));
        }
        glDisable(GL_BLEND);
        if (!o.window || (r.focusedWindow == o.window && r.focusedGeneration == o.generation))
            renderer->drawCursor(width, height, x, y, state->cursor.x, state->cursor.y);
        // Finish GPU reads before releasing the server's buffer write lock. Never read back pixels.
        glFinish();
        gpuFinished = true;
        if (!eglSwapBuffers(renderer->egl_display, o.surface)) {
            error(r, "Android window swap failed; reopen the window"); releaseOutput(*renderer, o);
        }
        state->renderedFrames++;
    }
    if (serial && !gpuFinished) glFinish();
    if (serial) {
        __atomic_store_n(&state->gpuCopyQueue.completedSerial, serial, __ATOMIC_RELEASE);
        renderer->notifyGpuCopyDone();
    }
    state->drawRequested = false; state->cursor.moved = false; state->waitForNextFrame = true;
    lock.unlock();
    lorie_mutex_unlock(&state->lock, &state->lockingPid);
}
void rootlessRendererRelease(Renderer *renderer) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!current || &current->renderer != renderer) return;
    for (auto &o : current->outputs) {
        releaseOutput(*renderer, o);
        if (o.pending) ANativeWindow_release(o.pending);
        o.pending = nullptr;
    }
    current->outputs.clear();
    surfaceChanged.notify_all();
}

#define JNI(name) extern "C" JNIEXPORT
JNI(create) jlong JNICALL Java_com_termux_x11_RootlessNative_create(JNIEnv *env, jobject self) {
    std::unique_lock<std::mutex> lock(mutex);
    if (current) { error(*current, "Only one rootless renderer may own this X server"); return 0; }
    auto r = std::make_shared<Rootless>();
    r->handle = nextHandle++;
    env->GetJavaVM(&r->vm);
    r->callback = env->NewGlobalRef(self);
    jclass clazz = env->GetObjectClass(self);
    r->onWindow = env->GetMethodID(clazz,"onWindow","(JJIILjava/lang/String;Z)V");
    r->onError = env->GetMethodID(clazz,"onError","(Ljava/lang/String;)V");
    r->onConnected = env->GetMethodID(clazz,"onConnected","(Z)V");
    r->onClipboardText = env->GetMethodID(clazz,"onClipboardText","(JJLjava/lang/String;)V");
    r->onClipboardRequest = env->GetMethodID(clazz,"onClipboardRequest","(JJ)V");
    r->onImeReset = env->GetMethodID(clazz,"onImeReset","(JJ)V");
    env->DeleteLocalRef(clazz);
    if (!r->onWindow || !r->onError || !r->onConnected || !r->onClipboardText || !r->onClipboardRequest || !r->onImeReset) { env->DeleteGlobalRef(r->callback); return 0; }
    current = r;
    r->renderer.rootlessMode = true;
    r->renderer.connFdPtr = &r->fd;
    lock.unlock();
    r->renderer.init(env,self);
    if (!r->renderer.initialized) {
        error(*r,"EGL initialization failed; independent GPU window rendering is unavailable");
        r->renderer.destroy();
        std::lock_guard<std::mutex> failedLock(mutex);
        r->active = false; env->DeleteGlobalRef(r->callback); r->callback = nullptr; current.reset();
        return 0;
    }
    return r->handle;
}
JNI(configureDisplay) void JNICALL Java_com_termux_x11_RootlessNative_configureDisplay(JNIEnv *env, jobject, jlong handle, jint width, jint height, jint refreshRate) {
    std::lock_guard<std::mutex> lock(mutex);
    const char *failure = nullptr;
    if (!current || !current->active || current->handle != static_cast<uint64_t>(handle))
        failure = "Initial display configuration requires the active rootless renderer";
    else if (width < 64 || width > 8192 || height < 64 || height > 8192 || refreshRate < 1 || refreshRate > 240)
        failure = "Initial X display requires dimensions 64..8192 and refresh rate 1..240 Hz";
    else if (!lorieConfigureInitialDisplay(width,height,refreshRate))
        failure = "Configure the X display exactly once, before starting the X server";
    if (failure) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception) { env->ThrowNew(exception,failure); env->DeleteLocalRef(exception); }
    }
}
JNI(connect) void JNICALL Java_com_termux_x11_RootlessNative_connect(JNIEnv *, jobject, jlong handle, jint fd) {
    auto r = get(handle);
    if (!r) { if (fd >= 0) close(fd); return; }
    std::lock_guard<std::mutex> connectionLock(r->connectionLock);
    if (!r->active) { if (fd >= 0) close(fd); return; }
    {
        std::lock_guard<std::mutex> writeLock(transportMutex);
        if (r->fd != -1) {
            error(*r,"The rootless renderer already owns a transport connection");
            if (fd >= 0) close(fd);
            connected(*r,true); // Reject this attempt without disconnecting the live owner.
            return;
        }
    }
    if (r->transport.joinable()) r->transport.join();
    if (fd < 0) {
        error(*r,"No renderer transport file descriptor");
        connected(*r,false);
        return;
    }
    std::unique_lock<std::mutex> writeLock(transportMutex);
    r->fd = fd;
    lorieEvent event = {.type = EVENT_RENDERER_WAKEUP_COND};
    if (write(fd,&event,sizeof(event)) != sizeof(event) || ancil_send_fd(fd,r->renderer.getWakeupCondFd()) < 0) {
        close(fd); r->fd = -1;
        writeLock.unlock();
        error(*r,"Cannot establish the sole rootless renderer transport");
        connected(*r,false); // No receive thread exists to report this failed attempt.
        return;
    }
    r->transport = std::thread(receive,r,fd);
}
JNI(attach) void JNICALL Java_com_termux_x11_RootlessNative_attach(JNIEnv *env, jobject, jlong handle, jlong window, jlong generation, jlong lease, jobject surface, jint width, jint height, jboolean readOnly) {
    std::unique_lock<std::mutex> lock(mutex);
    if (!current || !current->active || current->handle != static_cast<uint64_t>(handle)) return;
    Rootless &r = *current;
    Layer *layer = findLayer(r,window,generation);
    if ((window && (!layer || !layer->primary || !layer->value.present)) || (!window && generation) ||
        !surface || width < 1 || height < 1 || lease <= 0) return;
    Output *output = nullptr;
    for (auto &o : r.outputs) if (o.window == static_cast<uint64_t>(window) && o.generation == static_cast<uint64_t>(generation)) { output = &o; break; }
    if (output && output->lease >= static_cast<uint64_t>(lease)) return;
    uint64_t previousLease = output ? output->lease : 0;
    ANativeWindow *native = ANativeWindow_fromSurface(env,surface);
    if (!native) { error(r,"Android supplied an invalid window Surface"); return; }
    if (!output) { r.outputs.push_back({static_cast<uint64_t>(window),static_cast<uint64_t>(generation),static_cast<uint64_t>(lease)}); output = &r.outputs.back(); }
    if (output->pending) ANativeWindow_release(output->pending);
    output->pending = native; output->lease = lease; output->width = width; output->height = height;
    output->readOnly = readOnly || !window; output->changed = true;
    if (output->readOnly && r.focusedWindow == static_cast<uint64_t>(window) && r.focusedGeneration == static_cast<uint64_t>(generation))
        disableClipboardLocked(r);
    wake(r);
    surfaceChanged.wait(lock, [&] {
        if (!current || !current->active) return true;
        for (auto &o : current->outputs)
            if (o.window == static_cast<uint64_t>(window) && o.generation == static_cast<uint64_t>(generation) && o.lease == static_cast<uint64_t>(lease)) return !o.changed;
        return true;
    });
    lock.unlock();
    if (previousLease) {
        RootlessCommand release{}; release.handle=handle; release.window=window; release.generation=generation;
        release.lease=previousLease; release.kind=ROOTLESS_RELEASE; rootlessQueueCommand(&release);
    }
}
JNI(detach) void JNICALL Java_com_termux_x11_RootlessNative_detach(JNIEnv *, jobject, jlong handle, jlong window, jlong generation, jlong lease) {
    std::unique_lock<std::mutex> lock(mutex);
    if (!current || current->handle != static_cast<uint64_t>(handle)) return;
    bool matched = false;
    for (auto &o : current->outputs) if (o.window == static_cast<uint64_t>(window) && o.generation == static_cast<uint64_t>(generation) && o.lease == static_cast<uint64_t>(lease)) {
        matched = true;
        if (o.pending) ANativeWindow_release(o.pending);
        o.pending = nullptr; o.changed = true; o.readOnly = true;
        if (current->focusedWindow == o.window && current->focusedGeneration == o.generation)
            disableClipboardLocked(*current);
        wake(*current);
    }
    if (!matched) return;
    surfaceChanged.wait(lock, [&] {
        if (!current || !current->active) return true;
        for (auto &o : current->outputs)
            if (o.window == static_cast<uint64_t>(window) && o.generation == static_cast<uint64_t>(generation) && o.lease == static_cast<uint64_t>(lease)) return !o.changed;
        return true;
    });
    lock.unlock();
    RootlessCommand release{}; release.handle=handle; release.window=window; release.generation=generation;
    release.lease=lease; release.kind=ROOTLESS_RELEASE; rootlessQueueCommand(&release);
}
static void command(jlong handle, jlong window, jlong generation, int kind, int width=0, int height=0, float x=0, float y=0, int button=0, bool down=false, bool relative=false, int key=0, uint32_t cp=0, const char *text=nullptr) {
    if (!get(handle) || !window) return;
    RootlessCommand c{};
    c.handle=handle; c.window=window; c.generation=generation; c.kind=kind;
    c.width=width; c.height=height; c.x=x; c.y=y; c.button=button; c.down=down; c.relative=relative; c.key=key; c.codepoint=cp;
    c.text = text;
    {
        std::lock_guard<std::mutex> lock(mutex);
        if (!current || current->handle != static_cast<uint64_t>(handle)) return;
        for (auto &o : current->outputs)
            if (o.window == static_cast<uint64_t>(window) && o.generation == static_cast<uint64_t>(generation) && !o.readOnly)
                c.lease = o.lease;
    }
    if (!c.lease) return;
    rootlessQueueCommand(&c);
}
JNI(resize) void JNICALL Java_com_termux_x11_RootlessNative_resize(JNIEnv *, jobject, jlong h, jlong w, jlong g, jint width, jint height) { command(h,w,g,ROOTLESS_RESIZE,width,height); }
JNI(focus) void JNICALL Java_com_termux_x11_RootlessNative_focus(JNIEnv *, jobject, jlong h, jlong w, jlong g) { command(h,w,g,ROOTLESS_FOCUS); }
JNI(pointer) void JNICALL Java_com_termux_x11_RootlessNative_pointer(JNIEnv *, jobject, jlong h, jlong w, jlong g, jfloat x, jfloat y, jint button, jboolean down, jboolean relative) { command(h,w,g,ROOTLESS_POINTER,0,0,x,y,button,down,relative); }
JNI(key) void JNICALL Java_com_termux_x11_RootlessNative_key(JNIEnv *, jobject, jlong h, jlong w, jlong g, jint scanCode, jint keyCode, jboolean down) {
    int key = scanCode > 0 ? scanCode : keyCode >= 0 && keyCode < static_cast<int>(sizeof(android_to_linux_keycode)/sizeof(int)) ? android_to_linux_keycode[keyCode] : 0;
    if (key > 0 && key <= 247) command(h,w,g,ROOTLESS_KEY,0,0,0,0,0,down,false,key+8);
}
JNI(text) void JNICALL Java_com_termux_x11_RootlessNative_text(JNIEnv *env, jobject, jlong h, jlong w, jlong g, jstring text) {
    if (!text || !get(h)) return;
    jsize length = env->GetStringLength(text);
    const jchar *chars = env->GetStringChars(text,nullptr);
    if (!chars) return;
    for (jsize i=0;i<length;i++) {
        uint32_t cp=chars[i];
        if (cp>=0xd800 && cp<=0xdbff && i+1<length && chars[i+1]>=0xdc00 && chars[i+1]<=0xdfff) { cp=0x10000+((cp-0xd800)<<10)+(chars[++i]-0xdc00); }
        else if (cp>=0xd800 && cp<=0xdfff) cp=0xfffd;
        command(h,w,g,ROOTLESS_TEXT,0,0,0,0,0,false,false,0,cp);
    }
    env->ReleaseStringChars(text,chars);
}
JNI(closeWindow) void JNICALL Java_com_termux_x11_RootlessNative_closeWindow(JNIEnv *, jobject, jlong h, jlong w, jlong g) { command(h,w,g,ROOTLESS_CLOSE); }
JNI(clipboardEnabled) void JNICALL Java_com_termux_x11_RootlessNative_clipboardEnabled(JNIEnv *, jobject, jlong h, jlong w, jlong g, jboolean enabled) {
    command(h,w,g,ROOTLESS_CLIPBOARD_ENABLE,0,0,0,0,0,enabled);
}
JNI(clipboardAnnounce) void JNICALL Java_com_termux_x11_RootlessNative_clipboardAnnounce(JNIEnv *, jobject, jlong h, jlong w, jlong g) {
    command(h,w,g,ROOTLESS_CLIPBOARD_ANNOUNCE);
}
JNI(clipboardText) void JNICALL Java_com_termux_x11_RootlessNative_clipboardText(JNIEnv *env, jobject, jlong h, jlong w, jlong g, jstring text) {
    if (!text || !get(h)) return;
    const jchar *chars = env->GetStringChars(text,nullptr);
    if (!chars) return;
    jsize length = env->GetStringLength(text);
    std::string utf8;
    utf8.reserve(length);
    for (jsize i=0; i<length; i++) {
        uint32_t c=chars[i];
        if (c>=0xd800 && c<=0xdbff && i+1<length && chars[i+1]>=0xdc00 && chars[i+1]<=0xdfff)
            c=0x10000+((c-0xd800)<<10)+(chars[++i]-0xdc00);
        else if (c>=0xd800 && c<=0xdfff) c=0xfffd;
        if (c<0x80) utf8.push_back(c);
        else {
            if (c<0x800) utf8.push_back(0xc0|(c>>6));
            else {
                if (c<0x10000) utf8.push_back(0xe0|(c>>12));
                else { utf8.push_back(0xf0|(c>>18)); utf8.push_back(0x80|((c>>12)&63)); }
                utf8.push_back(0x80|((c>>6)&63));
            }
            utf8.push_back(0x80|(c&63));
        }
    }
    env->ReleaseStringChars(text,chars);
    command(h,w,g,ROOTLESS_CLIPBOARD_TEXT,0,0,0,0,0,false,false,0,0,utf8.c_str());
}
JNI(destroy) void JNICALL Java_com_termux_x11_RootlessNative_destroy(JNIEnv *env, jobject, jlong handle) {
    auto r=get(handle);
    if (!r) return;
    std::lock_guard<std::mutex> connectionLock(r->connectionLock);
    if (!r->active) return;
    r->active=false;
    {
        std::lock_guard<std::mutex> writeLock(transportMutex);
        if (r->fd!=-1) shutdown(r->fd,SHUT_RDWR);
    }
    if (r->transport.joinable()) r->transport.join();
    r->renderer.destroy();
    std::lock_guard<std::mutex> lock(mutex);
    env->DeleteGlobalRef(r->callback); r->callback=nullptr;
    if (current==r) current.reset();
    lorieWakeServer();
}
