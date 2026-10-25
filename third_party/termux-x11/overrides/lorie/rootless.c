#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <X11/Xatom.h>
#include <X11/extensions/composite.h>
#include "dix.h"
#include "dixstruct.h"
#include "windowstr.h"
#include "propertyst.h"
#include "inputstr.h"
#include "inpututils.h"
#include "exevents.h"
#include "mipointer.h"
#include "mi.h"
#include "damage.h"
#include "compint.h"
#include "lorie.h"
#include "rootless.h"

extern ScreenPtr pScreenPtr;
extern DeviceIntPtr lorieMouse, lorieKeyboard;
extern int ucs2keysym(long code);
extern void lorieKeysymKeyboardEvent(KeySym keysym, int down);

typedef struct RootlessWindow {
    struct RootlessWindow *next;
    WindowPtr top, client;
    uint64_t generation;
    bool redirected, announced, seen, damaged, storageError, modal;
    DamagePtr damage;
    RootlessLayer layer;
    char title[4096];
    uint64_t inputLease;
    uint8_t keys[32], buttons;
    XID transientID;
    uint64_t ownerWindow, ownerGeneration;
} RootlessWindow;
static RootlessWindow *windows;
static uint64_t nextGeneration = 1, focused, focusedGeneration, trackerSession;
static ScreenPtr screen;
static DestroyWindowProcPtr destroyWindow;
static bool polling;
static bool sceneDirty = true, damageDirty;
static CreateWindowProcPtr createWindow;
static RealizeWindowProcPtr realizeWindow;
static UnrealizeWindowProcPtr unrealizeWindow;
static PositionWindowProcPtr positionWindow;
static ResizeWindowProcPtr resizeWindow;
static RestackWindowProcPtr restackWindow;
static ReparentWindowProcPtr reparentWindow;
static Atom wmState, transientFor, netName, wmProtocols, wmDelete, windowType;
static Atom desktopType, dockType, normalType, utf8String, clientLeader, netPid, netState, modalState;

static PropertyPtr property(WindowPtr window, Atom atom) {
    PropertyPtr result = NULL;
    return dixLookupProperty(&result, window, atom, serverClient, DixReadAccess) == Success ? result : NULL;
}
static XID windowProperty(WindowPtr window, Atom atom) {
    PropertyPtr p = property(window, atom);
    return p && p->format == 32 && p->size ? ((CARD32*)p->data)[0] : 0;
}
static bool atomListContains(WindowPtr window, Atom propertyName, Atom value) {
    PropertyPtr p = property(window,propertyName);
    if (!p || p->type != XA_ATOM || p->format != 32) return false;
    for (unsigned long i = 0; i < p->size; i++)
        if (((CARD32*)p->data)[i] == value) return true;
    return false;
}
static WindowPtr clientWindow(WindowPtr window) {
    if (property(window, wmState)) return window;
    for (WindowPtr child = window->firstChild; child; child = child->nextSib) {
        WindowPtr found = clientWindow(child);
        if (found) return found;
    }
    return NULL;
}
static RootlessWindow *findWindow(uint64_t id) {
    for (RootlessWindow *r = windows; r; r = r->next)
        if (r->client->drawable.id == id || r->top->drawable.id == id) return r;
    return NULL;
}
static XID typedWindowProperty(WindowPtr window, Atom atom, Atom type) {
    PropertyPtr p = property(window,atom);
    return p && p->type == type && p->format == 32 && p->size == 1 ? ((CARD32*)p->data)[0] : 0;
}
static bool sameApplication(RootlessWindow *a, RootlessWindow *b) {
    if (CLIENT_ID(a->client->drawable.id) == CLIENT_ID(b->client->drawable.id)) return true;
    XID leader = typedWindowProperty(a->client,clientLeader,XA_WINDOW);
    if (leader && leader == typedWindowProperty(b->client,clientLeader,XA_WINDOW)) {
        WindowPtr window;
        if (dixLookupWindow(&window,leader,serverClient,DixReadAccess) == Success) return true;
    }
    XID pid = typedWindowProperty(a->client,netPid,XA_CARDINAL);
    if (!pid || pid != typedWindowProperty(b->client,netPid,XA_CARDINAL)) return false;
    PropertyPtr am = property(a->client,XA_WM_CLIENT_MACHINE);
    PropertyPtr bm = property(b->client,XA_WM_CLIENT_MACHINE);
    // Equal process numbers on explicitly different hosts are not an app relationship.
    return !am || !bm || (am->type == XA_STRING && bm->type == XA_STRING &&
        am->format == 8 && bm->format == 8 && am->size == bm->size &&
        (!am->size || !memcmp(am->data,bm->data,am->size)));
}
static bool primaryEligible(RootlessWindow *r) {
    XID type = windowProperty(r->client,windowType);
    XID transient = windowProperty(r->client,transientFor);
    // Root denotes a group transient, not an actual owning window. A standalone
    // managed dialog/utility remains independently usable when no owner resolves.
    // A previously bound orphan is not promoted or rebound after owner XID reuse.
    return !r->top->overrideRedirect && !r->ownerGeneration &&
        type != desktopType && type != dockType &&
        (!transient || transient == screen->root->drawable.id);
}
static RootlessWindow *directOwner(RootlessWindow *r) {
    XID id = windowProperty(r->client, transientFor);
    if (id != r->transientID) {
        r->transientID = id;
        r->ownerWindow = r->ownerGeneration = 0;
    }
    /* An unchanged relationship never binds to a replacement incarnation. */
    if (r->ownerGeneration) {
        RootlessWindow *owner = findWindow(r->ownerWindow);
        return owner && owner->generation == r->ownerGeneration ? owner : NULL;
    }
    RootlessWindow *owner = id ? findWindow(id) : NULL;
    if (owner == r) return NULL;
    if (id && id != screen->root->drawable.id && !owner) return NULL;
    XID type = windowProperty(r->client, windowType);
    if (!owner && (r->top->overrideRedirect || id || (type && type != normalType))) {
        RootlessWindow *focus = findWindow(focused);
        if (focus && focus->generation != focusedGeneration) focus = NULL;
        if (focus && focus != r && focus->top->mapped && primaryEligible(focus) && sameApplication(r,focus))
            owner = focus;
        if (!owner) for (RootlessWindow *candidate = windows; candidate; candidate = candidate->next) {
            XID candidateType = windowProperty(candidate->client,windowType);
            if (candidate != r && candidate->top->mapped && primaryEligible(candidate) &&
                (!windowProperty(candidate->client,transientFor) || candidate->announced) &&
                (!candidateType || candidateType == normalType || candidate->announced) &&
                sameApplication(r,candidate)) { owner = candidate; break; }
        }
    }
    if (owner) {
        r->ownerWindow = owner->client->drawable.id;
        r->ownerGeneration = owner->generation;
    }
    return owner;
}
static RootlessWindow *ownerOf(RootlessWindow *r) {
    RootlessWindow *owner = directOwner(r);
    for (unsigned depth = 0; owner && depth < 32; depth++) {
        if (owner == r) return NULL;
        RootlessWindow *next = directOwner(owner);
        if (!next) return primaryEligible(owner) ? owner : NULL;
        owner = next;
    }
    return NULL;
}
static void releaseInput(RootlessWindow *r) {
    if (!r->inputLease) return;
    for (int key = 8; key < 256; key++)
        if (r->keys[key/8] & (1u << (key%8))) QueueKeyboardEvents(lorieKeyboard, KeyRelease, key);
    for (int button = 1; button <= 3; button++)
        if (r->buttons & (1u << button)) QueuePointerEvents(lorieMouse, ButtonRelease, button, POINTER_RELATIVE, NULL);
    memset(r->keys,0,sizeof(r->keys)); r->buttons = 0; r->inputLease = 0;
    mieqProcessInputEvents();
}
static void removeRecord(RootlessWindow **link, bool unredirect) {
    RootlessWindow *r = *link;
    releaseInput(r);
    rootlessForget(r->client->drawable.id, r->generation);
    if (r->damage) DamageDestroy(r->damage);
    if (unredirect && r->redirected) compUnredirectWindow(serverClient, r->top, CompositeRedirectManual);
    *link = r->next;
    free(r);
}
static Bool destroy(WindowPtr window) {
    sceneDirty = true;
    RootlessWindow **link = &windows;
    while (*link) {
        if ((*link)->top == window || (*link)->client == window) removeRecord(link, (*link)->top != window);
        else link = &(*link)->next;
    }
    screen->DestroyWindow = destroyWindow;
    Bool result = screen->DestroyWindow(window);
    destroyWindow = screen->DestroyWindow;
    screen->DestroyWindow = destroy;
    return result;
}
static void block(void *data, void *timeout) { (void)data; (void)timeout; rootlessPoll(); }
static void wake(void *data, int result) { (void)data; (void)result; }
void rootlessInvalidate(void) { sceneDirty = true; }
static void propertyChanged(CallbackListPtr *list, void *closure, void *data) {
    (void)list; (void)closure;
    PropertyStateRec *state = data;
    Atom atom = state->prop->propertyName;
    if (atom == transientFor || atom == windowType || atom == clientLeader || atom == netPid || atom == XA_WM_CLIENT_MACHINE) {
        XID transient = windowProperty(state->win,transientFor);
        if (atom == transientFor || !transient || transient == screen->root->drawable.id) {
            RootlessWindow *r = findWindow(state->win->drawable.id);
            if (r) r->ownerWindow = r->ownerGeneration = 0;
        }
    }
    if (atom == wmState || atom == transientFor || atom == netName || atom == XA_WM_NAME || atom == windowType ||
        atom == clientLeader || atom == netPid || atom == XA_WM_CLIENT_MACHINE || atom == netState)
        sceneDirty = true;
}
static void damaged(DamagePtr damage, RegionPtr region, void *closure) {
    (void)damage; (void)region;
    ((RootlessWindow*)closure)->damaged = true;
    damageDirty = true;
}
static Bool created(WindowPtr w) {
    screen->CreateWindow = createWindow;
    Bool result = screen->CreateWindow(w);
    createWindow = screen->CreateWindow; screen->CreateWindow = created;
    sceneDirty = true; return result;
}
static Bool realized(WindowPtr w) {
    screen->RealizeWindow = realizeWindow;
    Bool result = screen->RealizeWindow(w);
    realizeWindow = screen->RealizeWindow; screen->RealizeWindow = realized;
    sceneDirty = true; return result;
}
static Bool unrealized(WindowPtr w) {
    screen->UnrealizeWindow = unrealizeWindow;
    Bool result = screen->UnrealizeWindow(w);
    unrealizeWindow = screen->UnrealizeWindow; screen->UnrealizeWindow = unrealized;
    sceneDirty = true; return result;
}
static Bool positioned(WindowPtr w, int x, int y) {
    screen->PositionWindow = positionWindow;
    Bool result = screen->PositionWindow(w,x,y);
    positionWindow = screen->PositionWindow; screen->PositionWindow = positioned;
    sceneDirty = true; return result;
}
static void resized(WindowPtr w, int x, int y, unsigned width, unsigned height, WindowPtr sibling) {
    screen->ResizeWindow = resizeWindow;
    screen->ResizeWindow(w,x,y,width,height,sibling);
    resizeWindow = screen->ResizeWindow; screen->ResizeWindow = resized; sceneDirty = true;
}
static void restacked(WindowPtr w, WindowPtr sibling) {
    screen->RestackWindow = restackWindow;
    if (screen->RestackWindow) screen->RestackWindow(w,sibling);
    restackWindow = screen->RestackWindow; screen->RestackWindow = restacked; sceneDirty = true;
}
static void reparented(WindowPtr w, WindowPtr parent) {
    screen->ReparentWindow = reparentWindow;
    if (screen->ReparentWindow) screen->ReparentWindow(w,parent);
    reparentWindow = screen->ReparentWindow; screen->ReparentWindow = reparented; sceneDirty = true;
}

void rootlessScreenReady(void) {
    if (screen || !pScreenPtr || !pScreenPtr->root || !rootlessEnabled()) return;
    screen = pScreenPtr;
    wmState = MakeAtom("WM_STATE", 8, TRUE);
    transientFor = MakeAtom("WM_TRANSIENT_FOR", 16, TRUE);
    netName = MakeAtom("_NET_WM_NAME", 12, TRUE);
    wmProtocols = MakeAtom("WM_PROTOCOLS", 12, TRUE);
    wmDelete = MakeAtom("WM_DELETE_WINDOW", 16, TRUE);
    windowType = MakeAtom("_NET_WM_WINDOW_TYPE", 19, TRUE);
    desktopType = MakeAtom("_NET_WM_WINDOW_TYPE_DESKTOP", sizeof("_NET_WM_WINDOW_TYPE_DESKTOP")-1, TRUE);
    dockType = MakeAtom("_NET_WM_WINDOW_TYPE_DOCK", sizeof("_NET_WM_WINDOW_TYPE_DOCK")-1, TRUE);
    normalType = MakeAtom("_NET_WM_WINDOW_TYPE_NORMAL", sizeof("_NET_WM_WINDOW_TYPE_NORMAL")-1, TRUE);
    utf8String = MakeAtom("UTF8_STRING", sizeof("UTF8_STRING")-1, TRUE);
    clientLeader = MakeAtom("WM_CLIENT_LEADER", sizeof("WM_CLIENT_LEADER")-1, TRUE);
    netPid = MakeAtom("_NET_WM_PID", sizeof("_NET_WM_PID")-1, TRUE);
    netState = MakeAtom("_NET_WM_STATE", sizeof("_NET_WM_STATE")-1, TRUE);
    modalState = MakeAtom("_NET_WM_STATE_MODAL", sizeof("_NET_WM_STATE_MODAL")-1, TRUE);
    destroyWindow = screen->DestroyWindow;
    screen->DestroyWindow = destroy;
    createWindow = screen->CreateWindow; screen->CreateWindow = created;
    realizeWindow = screen->RealizeWindow; screen->RealizeWindow = realized;
    unrealizeWindow = screen->UnrealizeWindow; screen->UnrealizeWindow = unrealized;
    positionWindow = screen->PositionWindow; screen->PositionWindow = positioned;
    resizeWindow = screen->ResizeWindow; screen->ResizeWindow = resized;
    restackWindow = screen->RestackWindow; screen->RestackWindow = restacked;
    reparentWindow = screen->ReparentWindow; screen->ReparentWindow = reparented;
    AddCallback(&PropertyStateCallback, propertyChanged, NULL);
    sceneDirty = true;
    RegisterBlockAndWakeupHandlers(block, wake, NULL);
    rootlessPoll();
}
void rootlessScreenClose(void) {
    if (!screen) return;
    RemoveBlockAndWakeupHandlers(block, wake, NULL);
    DeleteCallback(&PropertyStateCallback, propertyChanged, NULL);
    while (windows) removeRecord(&windows, true);
    screen->DestroyWindow = destroyWindow;
    screen->CreateWindow = createWindow;
    screen->RealizeWindow = realizeWindow;
    screen->UnrealizeWindow = unrealizeWindow;
    screen->PositionWindow = positionWindow;
    screen->ResizeWindow = resizeWindow;
    screen->RestackWindow = restackWindow;
    screen->ReparentWindow = reparentWindow;
    screen = NULL;
    focused = focusedGeneration = 0;
    trackerSession = 0;
}
void rootlessPoll(void) {
    if (!screen || polling) return;
    polling = true;
    uint64_t session = rootlessSession();
    if (!session) {
        rootlessScreenClose();
        polling = false;
        return;
    }
    if (trackerSession != session) {
        while (windows) removeRecord(&windows, true);
        trackerSession = session;
        sceneDirty = true;
    }
    if (!sceneDirty) {
        if (damageDirty) for (RootlessWindow *r = windows; r; r = r->next) if (r->damaged) {
            DamageEmpty(r->damage); r->damaged = false;
            rootlessDamage(r->client->drawable.id,r->generation);
        }
        damageDirty = false; polling = false; return;
    }
    sceneDirty = false;
    for (RootlessWindow *r = windows; r; r = r->next) r->seen = false;
    for (WindowPtr top = screen->root->lastChild; top; top = top->prevSib) {
        if (top->drawable.class == InputOnly) continue;
        WindowPtr client = clientWindow(top);
        if (!client) client = top;
        if (CLIENT_ID(client->drawable.id) == 0) continue;
        RootlessWindow *r = findWindow(client->drawable.id);
        if (r && r->top != top) {
            RootlessWindow **link = &windows;
            while (*link != r) link = &(*link)->next;
            removeRecord(link, true);
            r = NULL;
        }
        if (!r) {
            r = calloc(1, sizeof(*r));
            if (!r) { rootlessError("Cannot allocate native window tracking record"); break; }
            r->top = top; r->client = client; r->generation = nextGeneration++;
            r->next = windows; windows = r;
        }
        r->seen = true;
        r->modal = atomListContains(r->client,netState,modalState);
        if (!r->redirected && top->mapped) {
            if (compRedirectWindow(serverClient, top, CompositeRedirectManual) != Success) {
                rootlessError("Composite could not redirect a Linux window to independent GPU storage");
                continue;
            }
            r->redirected = true;
            r->damage = DamageCreate(damaged, NULL, DamageReportNonEmpty, TRUE, screen, r);
            if (r->damage) DamageRegister(&top->drawable, r->damage);
        }
    }
    RootlessWindow **link = &windows;
    while (*link) {
        if (!(*link)->seen) removeRecord(link, true);
        else link = &(*link)->next;
    }
    int order = 0;
    for (WindowPtr top = screen->root->lastChild; top; top = top->prevSib, order++) {
        RootlessWindow *r = findWindow(top->drawable.id);
        if (!r) continue;
        XID type = windowProperty(r->client, windowType);
        RootlessWindow *owner = type == desktopType || type == dockType ? NULL : ownerOf(r);
        bool primary = !owner && primaryEligible(r);
        PixmapPtr pixmap = r->redirected && top->mapped ? screen->GetWindowPixmap(top) : NULL;
        RootlessLayer layer = { .window = r->client->drawable.id, .generation = r->generation,
            .owner = owner ? owner->client->drawable.id : (primary ? r->client->drawable.id : 0),
            .ownerGeneration = owner ? owner->generation : (primary ? r->generation : 0),
            .buffer = pixmap ? lorieRootlessBuffer(pixmap) : 0,
            .x = top->drawable.x - top->borderWidth, .y = top->drawable.y - top->borderWidth,
            .width = top->drawable.width + 2 * top->borderWidth,
            .height = top->drawable.height + 2 * top->borderWidth, .order = order,
            .alpha = top->drawable.depth == 32, .present = top->viewable && r->client->viewable && r->redirected };
        if (layer.present && !layer.buffer && !r->storageError) {
            rootlessError("A Linux window has no independent AHardwareBuffer backing; GPU rendering is unavailable for it");
            r->storageError = true;
        } else if (layer.buffer) r->storageError = false;
        char title[sizeof(r->title)];
        PropertyPtr name = property(r->client, netName);
        if (!name || name->format != 8 || name->type != utf8String) name = property(r->client, XA_WM_NAME);
        size_t length = 0;
        if (name && name->format == 8 && name->type == XA_STRING) {
            const unsigned char *bytes = name->data;
            for (size_t i = 0; i < name->size && length + 2 < sizeof(title); i++) {
                unsigned char c = bytes[i];
                if (c >= 0x80) title[length++] = 0xc0 | (c >> 6);
                title[length++] = c < 0x80 ? c : 0x80 | (c & 0x3f);
            }
        } else if (name && name->format == 8 && name->type == utf8String) {
            length = name->size < sizeof(title) ? name->size : sizeof(title)-1;
            if (length) memcpy(title,name->data,length);
        }
        title[length] = 0;
        bool damaged = r->damage && RegionNotEmpty(DamageRegion(r->damage));
        if (damaged || memcmp(&layer, &r->layer, sizeof(layer)) || strcmp(title, r->title) || primary != r->announced) {
            rootlessPublish(&layer, title, primary);
            r->layer = layer; memcpy(r->title, title, length + 1); r->announced = primary;
        }
        if (damaged) DamageEmpty(r->damage);
        r->damaged = false;
    }
    damageDirty = false;
    polling = false;
}

static bool grabBelongsTo(DeviceIntPtr device, RootlessWindow *target) {
    GrabPtr grab = device ? device->deviceGrab.grab : NULL;
    if (!grab) return true;
    for (WindowPtr w = grab->window; w && w != screen->root; w = w->parent) {
        RootlessWindow *record = findWindow(w->drawable.id);
        if (record && (record == target || ownerOf(record) == target)) return true;
    }
    return grab->window == screen->root && focused == target->client->drawable.id && focusedGeneration == target->generation &&
        CLIENT_ID(grab->resource) == CLIENT_ID(target->client->drawable.id);
}

static bool inGroup(RootlessWindow *record, RootlessWindow *target) {
    return record && (record == target || ownerOf(record) == target);
}
static RootlessWindow *recordForWindow(WindowPtr window) {
    if (window == NoneWin || window == PointerRootWin || window == FollowKeyboardWin) return NULL;
    for (WindowPtr w = window; w && w != screen->root; w = w->parent) {
        RootlessWindow *record = findWindow(w->drawable.id);
        if (record) return record;
    }
    return NULL;
}
static RootlessWindow *modalTarget(RootlessWindow *r) {
    for (WindowPtr top = screen->root->firstChild; top; top = top->nextSib) {
        RootlessWindow *dialog = findWindow(top->drawable.id);
        if (dialog && dialog->modal && dialog->client->viewable && !top->overrideRedirect &&
            ownerOf(dialog) == r) return dialog;
    }
    return NULL;
}
static bool withinModal(RootlessWindow *record, RootlessWindow *modal) {
    if (!modal) return true;
    for (unsigned depth = 0; record && depth < 32; depth++) {
        if (record == modal) return true;
        record = directOwner(record);
    }
    return false;
}
static RootlessWindow *pointRecord(double x, double y) {
    BoxRec box;
    for (WindowPtr top = screen->root->firstChild; top; top = top->nextSib) {
        if (!top->mapped || top->unhittable || !RegionContainsPoint(&top->borderSize,x,y,&box)) continue;
        if (wInputShape(top) && !RegionContainsPoint(wInputShape(top),x-top->drawable.x,y-top->drawable.y,&box)) continue;
        return findWindow(top->drawable.id);
    }
    return NULL;
}
static bool pointBelongsTo(RootlessWindow *target, double x, double y) {
    RootlessWindow *record = pointRecord(x,y);
    return inGroup(record,target) && withinModal(record,modalTarget(target));
}
static WindowPtr focusTarget(RootlessWindow *r, RootlessWindow *clicked) {
    RootlessWindow *modal = modalTarget(r);
    WindowPtr focus = inputInfo.keyboard->focus->win;
    RootlessWindow *record = recordForWindow(focus);
    bool retained = record && focus->viewable && inGroup(record,r);
    // Preserve legitimate app-selected focus during its pointer grab.
    if (retained && (inputInfo.pointer->deviceGrab.grab || lorieMouse->deviceGrab.grab)) return focus;
    if (clicked && !clicked->top->overrideRedirect && clicked->client->viewable &&
        inGroup(clicked,r) && withinModal(clicked,modal))
        return retained && record == clicked ? focus : clicked->client;
    if (retained && withinModal(record,modal)) return focus;
    if (!clicked && !modal && focus == PointerRootWin) {
        int x, y;
        miPointerGetPosition(lorieMouse,&x,&y);
        if (inGroup(pointRecord(x,y),r)) return focus;
    }
    return modal ? modal->client : r->client;
}

static bool focusWindow(RootlessWindow *r, RootlessWindow *clicked) {
    WindowPtr target = focusTarget(r,clicked);
    bool covered = focused != r->client->drawable.id || focusedGeneration != r->generation;
    for (WindowPtr top = screen->root->firstChild; top; top = top->nextSib) {
        RootlessWindow *front = findWindow(top->drawable.id);
        if (front && top->mapped) { covered = front != r && ownerOf(front) != r; break; }
    }
    if (!inputInfo.pointer->deviceGrab.grab && covered) {
        XID above = Above;
        ConfigureWindow(r->top, CWStackMode, &above, wClient(r->top));
        /* Snapshot order lives in records; never traverse a sibling chain while restacking it. */
        int previous = -1;
        for (;;) {
            RootlessWindow *next = NULL;
            for (RootlessWindow *child = windows; child; child = child->next)
                if (child != r && child->top->mapped && child->layer.order > previous &&
                    ownerOf(child) == r && (!next || child->layer.order < next->layer.order)) next = child;
            if (!next) break;
            previous = next->layer.order;
            ConfigureWindow(next->top, CWStackMode, &above, wClient(next->top));
        }
    }
    if (focused != r->client->drawable.id || focusedGeneration != r->generation)
        for (RootlessWindow *other = windows; other; other = other->next) if (other != r) releaseInput(other);
    if (!inputInfo.keyboard->deviceGrab.grab && !lorieKeyboard->deviceGrab.grab && inputInfo.keyboard->focus->win != target &&
        SetInputFocus(serverClient, inputInfo.keyboard, target == PointerRootWin ? PointerRoot : target->drawable.id,
            RevertToParent, CurrentTime, FALSE) != Success) {
        rootlessError("The Linux window could not receive keyboard focus; input was not injected");
        return false;
    }
    if (focused != r->client->drawable.id || focusedGeneration != r->generation) sceneDirty = true;
    focused = r->client->drawable.id;
    focusedGeneration = r->generation;
    rootlessFocused(r->client->drawable.id, r->generation);
    return true;
}
static Bool commandWork(ClientPtr client, void *data) {
    (void)client;
    RootlessCommand *c = data;
    rootlessPoll();
    RootlessWindow *r = screen ? findWindow(c->window) : NULL;
    if (c->kind == ROOTLESS_RELEASE) {
        if (r && r->generation == c->generation && r->inputLease == c->lease) releaseInput(r);
        free((void*)c->text); free(c); return TRUE;
    }
    int surfaceWidth = 0, surfaceHeight = 0;
    if (!r || r->generation != c->generation || !r->client->viewable ||
        !rootlessAuthorize(c, &surfaceWidth, &surfaceHeight)) { free((void*)c->text); free(c); return TRUE; }
    if ((c->kind == ROOTLESS_FOCUS || c->kind == ROOTLESS_POINTER || c->kind == ROOTLESS_KEY || c->kind == ROOTLESS_TEXT) &&
        (!grabBelongsTo(inputInfo.pointer, r) || !grabBelongsTo(inputInfo.keyboard, r) ||
         !grabBelongsTo(lorieMouse, r) || !grabBelongsTo(lorieKeyboard, r))) {
        static uint64_t lastRejected;
        if (lastRejected != r->generation) {
            rootlessError("Another Linux window owns an input grab; release its menu or pointer capture before switching windows");
            lastRejected = r->generation;
        }
        free((void*)c->text); free(c); return TRUE;
    }
    if (c->kind >= ROOTLESS_CLIPBOARD_ENABLE) {
        if (c->kind == ROOTLESS_CLIPBOARD_ENABLE && !c->down) releaseInput(r);
        rootlessClipboardCommand(c);
    } else if (c->kind == ROOTLESS_RESIZE) {
        unsigned width = c->width < 1 ? 1 : c->width > screen->width ? screen->width : c->width;
        unsigned height = c->height < 1 ? 1 : c->height > screen->height ? screen->height : c->height;
        if (r->top == r->client) {
            int x = r->client->drawable.x, y = r->client->drawable.y;
            x = x < 0 ? 0 : x > screen->width-(int)width ? screen->width-width : x;
            y = y < 0 ? 0 : y > screen->height-(int)height ? screen->height-height : y;
            XID values[] = {(XID)x,(XID)y,width,height};
            ConfigureWindow(r->client,CWX|CWY|CWWidth|CWHeight,values,wClient(r->client));
        } else {
            XID values[] = {width,height};
            ConfigureWindow(r->client,CWWidth|CWHeight,values,wClient(r->client));
        }
    } else if (c->kind == ROOTLESS_FOCUS) focusWindow(r,NULL);
    else if (c->kind == ROOTLESS_CLOSE) {
        PropertyPtr protocols = property(r->client, wmProtocols);
        bool supported = false;
        if (protocols && protocols->format == 32)
            for (unsigned i = 0; i < protocols->size; i++) if (((CARD32*)protocols->data)[i] == wmDelete) supported = true;
        if (!supported) rootlessError("This Linux application does not support WM_DELETE_WINDOW; it was not killed");
        else {
            xEvent event = {0};
            event.u.u.type = ClientMessage | 0x80;
            event.u.u.detail = 32;
            event.u.clientMessage.window = r->client->drawable.id;
            event.u.clientMessage.u.l.type = wmProtocols;
            event.u.clientMessage.u.l.longs0 = wmDelete;
            event.u.clientMessage.u.l.longs1 = CurrentTime;
            WriteEventsToClient(wClient(r->client), 1, &event);
        }
    } else {
        if (!focusWindow(r,NULL)) { free((void*)c->text); free(c); return TRUE; }
        if (c->kind == ROOTLESS_KEY) {
            if (c->key >= 8 && c->key <= 255) {
                r->inputLease = c->lease;
                if (c->down) r->keys[c->key/8] |= 1u << (c->key%8);
                else r->keys[c->key/8] &= ~(1u << (c->key%8));
                QueueKeyboardEvents(lorieKeyboard, c->down ? KeyPress : KeyRelease, c->key);
            }
        } else if (c->kind == ROOTLESS_TEXT) {
            int keysym = ucs2keysym(c->codepoint);
            lorieKeysymKeyboardEvent(keysym, TRUE); lorieKeysymKeyboardEvent(keysym, FALSE);
        } else if (c->kind == ROOTLESS_POINTER && isfinite(c->x) && isfinite(c->y)) {
            ValuatorMask mask;
            valuator_mask_zero(&mask);
            bool grabbed = inputInfo.pointer->deviceGrab.grab || lorieMouse->deviceGrab.grab;
            int cursorX, cursorY;
            miPointerGetPosition(lorieMouse, &cursorX, &cursorY);
            if ((c->relative || c->button == 4) && !grabbed && !pointBelongsTo(r,cursorX,cursorY)) {
                RootlessWindow *anchor = modalTarget(r);
                if (!anchor) anchor = r;
                cursorX = anchor->client->drawable.x + anchor->client->drawable.width/2;
                cursorY = anchor->client->drawable.y + anchor->client->drawable.height/2;
                if (!pointBelongsTo(r,cursorX,cursorY)) { free((void*)c->text); free(c); return TRUE; }
                valuator_mask_set_double(&mask,0,cursorX); valuator_mask_set_double(&mask,1,cursorY);
                QueuePointerEvents(lorieMouse,MotionNotify,0,POINTER_ABSOLUTE|POINTER_DESKTOP|POINTER_NORAW,&mask);
                mieqProcessInputEvents();
                valuator_mask_zero(&mask);
            }
            if (c->button == 4) {
                valuator_mask_set_double(&mask, 2, c->x / 120.0);
                valuator_mask_set_double(&mask, 3, c->y / 120.0);
                QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
            } else {
                double scale = fmin((double)surfaceWidth/c->sourceWidth, (double)surfaceHeight/c->sourceHeight);
                if (scale > 0) {
                    double x = c->x/scale, y = c->y/scale;
                    if (!c->relative) {
                        x -= (surfaceWidth/scale-c->sourceWidth)/2;
                        y -= (surfaceHeight/scale-c->sourceHeight)/2;
                        x = fmax(0, fmin(c->sourceWidth-1, x)) + c->sourceX;
                        y = fmax(0, fmin(c->sourceHeight-1, y)) + c->sourceY;
                    }
                    if (!grabbed) {
                        if (c->relative) {
                            if (!pointBelongsTo(r,cursorX+x,cursorY+y)) {
                                x = fmax(r->layer.x, fmin(r->layer.x+r->layer.width-1,cursorX+x))-cursorX;
                                y = fmax(r->layer.y, fmin(r->layer.y+r->layer.height-1,cursorY+y))-cursorY;
                                if (!pointBelongsTo(r,cursorX+x,cursorY+y)) { free((void*)c->text); free(c); return TRUE; }
                            }
                        } else if (!pointBelongsTo(r,x,y)) { free((void*)c->text); free(c); return TRUE; }
                    }
                    if (!grabbed && c->down && c->button >= 1 && c->button <= 3 &&
                        !focusWindow(r,pointRecord(c->relative ? cursorX+x : x,c->relative ? cursorY+y : y))) {
                        free((void*)c->text); free(c); return TRUE;
                    }
                    valuator_mask_set_double(&mask, 0, x); valuator_mask_set_double(&mask, 1, y);
                    QueuePointerEvents(lorieMouse, MotionNotify, 0, c->relative ? POINTER_RELATIVE : POINTER_ABSOLUTE | POINTER_DESKTOP | POINTER_NORAW, &mask);
                    if (c->button >= 1 && c->button <= 3) {
                        r->inputLease = c->lease;
                        if (c->down) r->buttons |= 1u << c->button;
                        else r->buttons &= ~(1u << c->button);
                        QueuePointerEvents(lorieMouse, c->down ? ButtonPress : ButtonRelease, c->button, POINTER_RELATIVE, NULL);
                    }
                }
            }
        }
        mieqProcessInputEvents();
    }
    free((void*)c->text);
    free(c);
    return TRUE;
}
void rootlessQueueCommand(const RootlessCommand *command) {
    RootlessCommand *copy = malloc(sizeof(*copy));
    if (!copy) { rootlessError("Cannot allocate native input command"); return; }
    *copy = *command;
    copy->text = command->text ? strdup(command->text) : NULL;
    if (command->text && !copy->text) { free(copy); rootlessError("Cannot allocate clipboard text"); return; }
    if (!QueueWorkProc(commandWork, NULL, copy)) { free((void*)copy->text); free(copy); rootlessError("Cannot queue native input command"); return; }
    lorieWakeServer();
}

static Bool imeResetWork(ClientPtr client, void *closure) {
    (void)client;
    rootlessImeReset((uint64_t)(uintptr_t)closure);
    return TRUE;
}
void rootlessRequestImeReset(uint64_t handle) {
    /* Focus notifications can arrive before the initiating focus work item finishes. */
    if (!QueueWorkProc(imeResetWork,NULL,(void*)(uintptr_t)handle)) {
        rootlessError("Cannot queue IME reset after Linux focus change");
        return;
    }
    lorieWakeServer();
}
