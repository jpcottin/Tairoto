#pragma once
// The Vulkan renderer. Two passes a frame:
//   1. scene, into an offscreen HDR image (smaller than the screen on cheaper
//      tiers): the sand, deep marine snow, shadows, creatures, shallow snow;
//   2. composite, into the swapchain: the water surface (refraction, glints,
//      tap rings), tone mapping, grade, vignette and dither.
// Every shader shares one frame uniform block (shaders/common.glsl).
#include <vulkan/vulkan.h>

#include <string>
#include <vector>

#include "render/camera.h"
#include "render/mesh.h"
#include "render/quality.h"
#include "sim/world.h"

struct ANativeWindow;

namespace dt {

struct Ripple {
    Vec2 at;
    float start = -100;
    float amplitude = 0;
};

struct FrameInput {
    CameraParams camera;
    Tank tank;
    float time = 0;
    Quality quality = Quality::High;
    const Mesh* mesh = nullptr;
    const Ripple* ripples = nullptr;  // kMaxRipples of them
    Vec2 tilt;
    float light = 1;   // 0 under the moon .. 1 full day
    float warmth = 0;  // the gold of a low sun, 0 .. 1
};

class Renderer {
public:
    static constexpr int kMaxRipples = 8;
    /** The drawings drawn fish wear: layers of one texture array, sampled in the creature and shadow shaders. */
    static constexpr int kSkinW = 128, kSkinH = 64, kMaxSkins = 16;
    static constexpr size_t kSkinBytes = (size_t)kSkinW * kSkinH * 4;

    /** Keeps a drawing for [layer] (RGBA, sRGB), sent to the GPU with the next frame, and again if the device is lost. */
    void setSkin(int layer, std::vector<uint8_t> rgba);

    bool initInstance();
    bool attach(ANativeWindow* window);
    void detach();
    void cleanup();

    bool ready() const { return swapchainReady_; }
    bool needsRecovery() const { return window_ != nullptr && !swapchainReady_; }
    bool unsupported() const { return unsupported_; }
    void tryRecover();

    /** Draws and presents a frame; false when nothing could be shown. */
    bool draw(const FrameInput& in);

    int width() const { return (int)extent_.width; }
    int height() const { return (int)extent_.height; }
    int sceneWidth() const { return (int)sceneExtent_.width; }
    int sceneHeight() const { return (int)sceneExtent_.height; }
    const std::string& gpuName() const { return gpuName_; }
    uint64_t framesPresented() const { return framesPresented_; }

private:
    struct PerFrame {
        VkBuffer ubo = VK_NULL_HANDLE;
        VkDeviceMemory uboMem = VK_NULL_HANDLE;
        void* uboMap = nullptr;
        VkBuffer vbo = VK_NULL_HANDLE;
        VkDeviceMemory vboMem = VK_NULL_HANDLE;
        void* vboMap = nullptr;
        VkBuffer ibo = VK_NULL_HANDLE;
        VkDeviceMemory iboMem = VK_NULL_HANDLE;
        void* iboMap = nullptr;
        VkDescriptorSet set = VK_NULL_HANDLE;
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkSemaphore imageAvailable = VK_NULL_HANDLE;
        VkFence inFlight = VK_NULL_HANDLE;
    };

    bool ensureDevice();
    void destroyDevice();
    bool createSurface();
    void destroySurface();
    bool setupForWindow();
    bool createSwapchain();
    void destroySwapchain();
    void recreateSwapchain(bool surfaceToo);
    void stopRendering(const char* what, VkResult r);
    void checkResize();

    bool createRenderPasses();
    bool createDescriptors();
    bool createPipelines();
    bool createFrameBuffers(uint32_t vertexCap, uint32_t indexCap);
    void destroyFrameBuffers();
    bool createOffscreen();
    void destroyOffscreen();
    bool createSkins();
    void destroySkins();
    /** Copies the drawings that changed into their layers (before the scene pass). */
    void recordSkinUploads(VkCommandBuffer cb);

    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkBuffer& buf, VkDeviceMemory& mem, void** map);
    bool createDeviceBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkBuffer& buf, VkDeviceMemory& mem);
    /** Records the mesh into the frame's buffers through the command stream (see inlineUpload_). */
    void recordInlineUpload(VkCommandBuffer cb, PerFrame& f, const Mesh& mesh);
    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props);
    VkFormat pickSceneFormat();
    void record(PerFrame& f, uint32_t imageIndex, const Mesh& mesh, int motes);

    ANativeWindow* window_ = nullptr;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkQueue queue_ = VK_NULL_HANDLE;
    std::string gpuName_;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{0, 0};
    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    std::vector<VkFramebuffer> swapFramebuffers_;
    std::vector<VkSemaphore> renderFinished_;

    VkFormat sceneFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D sceneExtent_{0, 0};
    float renderScale_ = 0;
    VkImage sceneImage_ = VK_NULL_HANDLE;
    VkDeviceMemory sceneMem_ = VK_NULL_HANDLE;
    VkImageView sceneView_ = VK_NULL_HANDLE;
    VkFramebuffer sceneFramebuffer_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;

    std::vector<uint8_t> skinPixels_[kMaxSkins];
    bool skinDirty_[kMaxSkins] = {};
    VkImage skinImage_ = VK_NULL_HANDLE;
    VkDeviceMemory skinMem_ = VK_NULL_HANDLE;
    VkImageView skinView_ = VK_NULL_HANDLE;
    // Filled through the command stream (vkCmdUpdateBuffer), like the meshes on a virtualised GPU.
    VkBuffer skinStage_ = VK_NULL_HANDLE;
    VkDeviceMemory skinStageMem_ = VK_NULL_HANDLE;
    bool skinsCleared_ = false;  // the image has been cleared and is ready to sample

    VkRenderPass scenePass_ = VK_NULL_HANDLE;
    VkRenderPass compositePass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout frameLayout_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout textureLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet textureSet_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline floorPipe_ = VK_NULL_HANDLE;
    VkPipeline shadowPipe_ = VK_NULL_HANDLE;
    VkPipeline creaturePipe_ = VK_NULL_HANDLE;
    VkPipeline motesPipe_ = VK_NULL_HANDLE;
    VkPipeline compositePipe_ = VK_NULL_HANDLE;

    VkCommandPool cmdPool_ = VK_NULL_HANDLE;
    static constexpr int kFramesInFlight = 2;
    PerFrame frames_[kFramesInFlight];
    uint32_t vertexCap_ = 0, indexCap_ = 0;
    uint32_t frame_ = 0;
    uint64_t framesDrawn_ = 0;
    uint64_t framesPresented_ = 0;

    bool deviceReady_ = false;
    bool deviceBroken_ = false;
    bool swapchainReady_ = false;
    bool unsupported_ = false;
    // On the emulator's virtualised GPU (GFXStream), vertex data written
    // through mapped memory was sometimes drawn torn, even with the queue idle
    // between frames: the meshes were checked correct on the CPU side. There
    // the per-frame vertices and indices travel inside the command buffer
    // (vkCmdUpdateBuffer into GPU-local buffers) instead.
    bool inlineUpload_ = false;
};

}  // namespace dt
