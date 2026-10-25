#pragma once
#include <stdint.h>
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
/* X pointers never cross this interface. All IDs are paired with a lifetime generation. */
typedef struct {
    uint64_t window, generation, owner, ownerGeneration, buffer;
    int x, y, width, height, order;
    bool alpha, present;
} RootlessLayer;
typedef struct {
    uint64_t handle, window, generation, lease;
    int kind, width, height, button, key;
    float x, y;
    bool down, relative;
    uint32_t codepoint;
    int sourceX, sourceY, sourceWidth, sourceHeight;
    const char *text;
} RootlessCommand;
bool rootlessEnabled(void);
uint64_t rootlessSession(void);
void rootlessScreenReady(void);
void rootlessScreenClose(void);
enum { ROOTLESS_RESIZE, ROOTLESS_FOCUS, ROOTLESS_POINTER, ROOTLESS_KEY, ROOTLESS_TEXT, ROOTLESS_CLOSE,
       ROOTLESS_CLIPBOARD_ENABLE, ROOTLESS_CLIPBOARD_ANNOUNCE, ROOTLESS_CLIPBOARD_TEXT, ROOTLESS_RELEASE };
void rootlessPoll(void);
void rootlessInvalidate(void);
void rootlessQueueCommand(const RootlessCommand *command);
void rootlessPublish(const RootlessLayer *layer, const char *title, bool primary);
void rootlessDamage(uint64_t window, uint64_t generation);
void rootlessForget(uint64_t window, uint64_t generation);
bool rootlessAuthorize(RootlessCommand *command, int *width, int *height);
void rootlessFocused(uint64_t window, uint64_t generation);
void rootlessRequestImeReset(uint64_t handle);
void rootlessImeReset(uint64_t handle);
void rootlessClipboardCommand(const RootlessCommand *command);
void rootlessError(const char *message);
uint64_t lorieRootlessBuffer(void *pixmap);
bool lorieConfigureInitialDisplay(int width, int height, int refreshRate);
#ifdef __cplusplus
}
struct Renderer;
bool rootlessRendererPending(Renderer *renderer);
void rootlessRendererDraw(Renderer *renderer);
void rootlessRendererRelease(Renderer *renderer);
/* Renderer thread only, while its rootless scene lock is held. */
void rootlessRendererBufferDamage(Renderer *renderer, uint64_t buffer);
/* Renderer thread only, outside the rootless scene lock. */
void rootlessRendererBufferReady(Renderer *renderer, uint64_t buffer);
void rootlessNotifyGpuCopyDone(Renderer *renderer);
#endif