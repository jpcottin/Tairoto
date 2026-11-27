#include "render/renderer.h"

#define VK_USE_PLATFORM_ANDROID_KHR
#include <android/native_window.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#include "core/log.h"

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

namespace dt {

namespace {
/** True on the Android emulator, whatever GPU name it reports. */
bool runningOnEmulator() {
#if defined(__ANDROID__)
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.boot.qemu", v) > 0 && v[0] == '1') return true;
    if (__system_property_get("ro.hardware", v) > 0 && (std::string(v) == "ranchu" || std::string(v) == "goldfish")) return true;
#endif
    return false;
}
}  // namespace
namespace {

// SPIR-V compiled at build time by glslc (-mfmt=c) from shaders/.
const uint32_t kFullscreenVert[] =
#include "fullscreen_vert.spv.h"
    ;
const uint32_t kFloorFrag[] =
#include "floor_frag.spv.h"
    ;
const uint32_t kShadowVert[] =
#include "shadow_vert.spv.h"
    ;
const uint32_t kShadowFrag[] =
#include "shadow_frag.spv.h"
    ;
const uint32_t kCreatureVert[] =
#include "creature_vert.spv.h"
    ;
const uint32_t kCreatureFrag[] =
#include "creature_frag.spv.h"
    ;
const uint32_t kMotesVert[] =
#include "motes_vert.spv.h"
    ;
const uint32_t kMotesFrag[] =
#include "motes_frag.spv.h"
    ;
const uint32_t kCompositeFrag[] =
#include "composite_frag.spv.h"
    ;

/** The frame uniform block, laid out as std140 (shaders/common.glsl). */
struct FrameUniforms {
    float cam[4];
    float outSize[4];
    float origin[4];
    float tank[4];
    float sun[4];
    float misc[4];
    float view[4];
    float ripples[Renderer::kMaxRipples][4];
};

struct MotesPush {
    float layer;
};

enum class Blend { Opaque, Alpha, Shadow, Additive };

#define VK_CHECK(x)                                                         \
    do {                                                                    \
        VkResult err_ = (x);                                                \
        if (err_ != VK_SUCCESS) {                                           \
            DT_LOGE("Vulkan error %d at %s:%d", err_, __FILE__, __LINE__); \
            return false;                                                   \
        }                                                                   \
    } while (0)

VkShaderModule makeModule(VkDevice dev, const uint32_t* code, size_t bytes) {
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = bytes;
    ci.pCode = code;
    VkShaderModule m = VK_NULL_HANDLE;
    if (vkCreateShaderModule(dev, &ci, nullptr, &m) != VK_SUCCESS) return VK_NULL_HANDLE;
    return m;
}

}  // namespace

// ================================================================ instance & device

bool Renderer::initInstance() {
    if (instance_) return true;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Tairoto";
    app.pEngineName = "tairoto";
    app.apiVersion = VK_API_VERSION_1_0;
    const char* exts[] = {"VK_KHR_surface", "VK_KHR_android_surface"};
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = 2;
    ci.ppEnabledExtensionNames = exts;
    VkResult r = vkCreateInstance(&ci, nullptr, &instance_);
    if (r != VK_SUCCESS) {
        DT_LOGE("vkCreateInstance failed: %d", r);
        unsupported_ = true;
        return false;
    }
    return true;
}

uint32_t Renderer::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(phys_, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((typeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props) return i;
    return UINT32_MAX;
}

VkFormat Renderer::pickSceneFormat() {
    // HDR so caustics and glints can go past 1.0 before tone mapping; 8-bit as a last resort.
    const VkFormat candidates[] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                                   VK_FORMAT_R8G8B8A8_UNORM};
    for (VkFormat f : candidates) {
        VkFormatProperties p;
        vkGetPhysicalDeviceFormatProperties(phys_, f, &p);
        VkFormatFeatureFlags need = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT |
                                    VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT |
                                    VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                    VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
        if ((p.optimalTilingFeatures & need) == need) return f;
    }
    return VK_FORMAT_R8G8B8A8_UNORM;
}

bool Renderer::ensureDevice() {
    if (deviceReady_) return true;
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (count == 0) {
        DT_LOGE("no Vulkan device");
        unsupported_ = true;
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());
    for (auto d : devices) {
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, qf.data());
        for (uint32_t i = 0; i < qn; i++) {
            VkBool32 present = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(d, i, surface_, &present);
            if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                phys_ = d;
                queueFamily_ = i;
                break;
            }
        }
        if (phys_) break;
    }
    if (!phys_) {
        DT_LOGE("no graphics queue that can present");
        unsupported_ = true;
        return false;
    }
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(phys_, &props);
    gpuName_ = props.deviceName;
    // The emulator's virtualised GPU, under its own name or passing the host's through.
    inlineUpload_ = gpuName_.find("GFXStream") != std::string::npos || runningOnEmulator();
    if (inlineUpload_) DT_LOGI("virtualised GPU: mesh uploads go through the command stream");
    DT_LOGI("GPU: %s (Vulkan %d.%d.%d)", props.deviceName, VK_VERSION_MAJOR(props.apiVersion),
            VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion));

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = queueFamily_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    const char* exts[] = {"VK_KHR_swapchain"};
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = 1;
    dci.ppEnabledExtensionNames = exts;
    VK_CHECK(vkCreateDevice(phys_, &dci, nullptr, &device_));
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);

    sceneFormat_ = pickSceneFormat();
    DT_LOGI("scene format %d", sceneFormat_);

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = queueFamily_;
    VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.maxLod = 0;
    if (vkCreateCommandPool(device_, &pci, nullptr, &cmdPool_) != VK_SUCCESS ||
        vkCreateSampler(device_, &sci, nullptr, &sampler_) != VK_SUCCESS || !createDescriptors() || !createSkins() ||
        !createFrameBuffers(65536, 65536 * 3)) {
        destroyDevice();
        return false;
    }
    deviceReady_ = true;
    return true;
}

bool Renderer::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkBuffer& buf, VkDeviceMemory& mem,
                            void** map) {
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = size;
    bci.usage = usage;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_CHECK(vkCreateBuffer(device_, &bci, nullptr, &buf));
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device_, buf, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits,
                                         VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) return false;
    VK_CHECK(vkAllocateMemory(device_, &mai, nullptr, &mem));
    VK_CHECK(vkBindBufferMemory(device_, buf, mem, 0));
    VK_CHECK(vkMapMemory(device_, mem, 0, size, 0, map));
    return true;
}

bool Renderer::createDeviceBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkBuffer& buf, VkDeviceMemory& mem) {
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = size;
    bci.usage = usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_CHECK(vkCreateBuffer(device_, &bci, nullptr, &buf));
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device_, buf, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, 0);
    VK_CHECK(vkAllocateMemory(device_, &mai, nullptr, &mem));
    VK_CHECK(vkBindBufferMemory(device_, buf, mem, 0));
    return true;
}

void Renderer::recordInlineUpload(VkCommandBuffer cb, PerFrame& f, const Mesh& mesh) {
    auto upload = [&](VkBuffer dst, const void* data, size_t bytes) {
        // vkCmdUpdateBuffer takes at most 65536 bytes, in multiples of 4.
        const auto* p = static_cast<const uint8_t*>(data);
        for (size_t off = 0; off < bytes; off += 65536) {
            size_t n = std::min<size_t>(65536, bytes - off);
            vkCmdUpdateBuffer(cb, dst, off, n, p + off);
        }
    };
    upload(f.vbo, mesh.vertices.data(), mesh.vertices.size() * sizeof(Vertex));
    upload(f.ibo, mesh.indices.data(), mesh.indices.size() * sizeof(uint32_t));
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT;
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, 0, 1, &mb, 0, nullptr,
                         0, nullptr);
}

bool Renderer::createDescriptors() {
    // Set 0: the frame's uniforms, and the drawings drawn fish wear.
    VkDescriptorSetLayoutBinding fb[2]{};
    fb[0].binding = 0;
    fb[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    fb[0].descriptorCount = 1;
    fb[0].stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    fb[1].binding = 1;
    fb[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    fb[1].descriptorCount = 1;
    fb[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    lci.bindingCount = 2;
    lci.pBindings = fb;
    VK_CHECK(vkCreateDescriptorSetLayout(device_, &lci, nullptr, &frameLayout_));
    lci.bindingCount = 1;

    VkDescriptorSetLayoutBinding tb{};
    tb.binding = 0;
    tb.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    tb.descriptorCount = 1;
    tb.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    lci.pBindings = &tb;
    VK_CHECK(vkCreateDescriptorSetLayout(device_, &lci, nullptr, &textureLayout_));

    VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, (uint32_t)kFramesInFlight},
                                     {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1 + (uint32_t)kFramesInFlight}};
    VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pci.maxSets = kFramesInFlight + 1;
    pci.poolSizeCount = 2;
    pci.pPoolSizes = sizes;
    VK_CHECK(vkCreateDescriptorPool(device_, &pci, nullptr, &descriptorPool_));

    VkDescriptorSetLayout layouts[2] = {frameLayout_, textureLayout_};
    VkPushConstantRange pcr{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(MotesPush)};
    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 2;
    plci.pSetLayouts = layouts;
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr;
    VK_CHECK(vkCreatePipelineLayout(device_, &plci, nullptr, &pipelineLayout_));

    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = descriptorPool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &textureLayout_;
    VK_CHECK(vkAllocateDescriptorSets(device_, &ai, &textureSet_));
    return true;
}

void Renderer::setSkin(int layer, std::vector<uint8_t> rgba) {
    if (layer < 0 || layer >= kMaxSkins || rgba.size() != kSkinBytes) return;
    skinPixels_[layer] = std::move(rgba);
    skinDirty_[layer] = true;
}

bool Renderer::createSkins() {
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_R8G8B8A8_SRGB;
    ici.extent = {(uint32_t)kSkinW, (uint32_t)kSkinH, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = kMaxSkins;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_CHECK(vkCreateImage(device_, &ici, nullptr, &skinImage_));
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, skinImage_, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, 0);
    VK_CHECK(vkAllocateMemory(device_, &mai, nullptr, &skinMem_));
    VK_CHECK(vkBindImageMemory(device_, skinImage_, skinMem_, 0));
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = skinImage_;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    vci.format = ici.format;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, (uint32_t)kMaxSkins};
    VK_CHECK(vkCreateImageView(device_, &vci, nullptr, &skinView_));
    if (!createDeviceBuffer(kSkinBytes * kMaxSkins, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, skinStage_, skinStageMem_)) return false;
    skinsCleared_ = false;
    // A new device has none of them yet.
    for (int i = 0; i < kMaxSkins; i++) skinDirty_[i] = !skinPixels_[i].empty();
    return true;
}

void Renderer::destroySkins() {
    if (skinView_) vkDestroyImageView(device_, skinView_, nullptr);
    if (skinImage_) vkDestroyImage(device_, skinImage_, nullptr);
    if (skinMem_) vkFreeMemory(device_, skinMem_, nullptr);
    if (skinStage_) vkDestroyBuffer(device_, skinStage_, nullptr);
    if (skinStageMem_) vkFreeMemory(device_, skinStageMem_, nullptr);
    skinView_ = VK_NULL_HANDLE;
    skinImage_ = VK_NULL_HANDLE;
    skinMem_ = VK_NULL_HANDLE;
    skinStage_ = VK_NULL_HANDLE;
    skinStageMem_ = VK_NULL_HANDLE;
    skinsCleared_ = false;
}

void Renderer::recordSkinUploads(VkCommandBuffer cb) {
    bool any = !skinsCleared_;
    for (int i = 0; i < kMaxSkins; i++) any = any || skinDirty_[i];
    if (!any) return;
    const VkImageSubresourceRange all{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, (uint32_t)kMaxSkins};
    // Whatever earlier frames still read or copy finishes first.
    VkBufferMemoryBarrier sb{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    sb.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    sb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    sb.srcQueueFamilyIndex = sb.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sb.buffer = skinStage_;
    sb.size = VK_WHOLE_SIZE;
    VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    ib.srcAccessMask = skinsCleared_ ? VK_ACCESS_SHADER_READ_BIT : 0;
    ib.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    ib.oldLayout = skinsCleared_ ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
    ib.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    ib.srcQueueFamilyIndex = ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    ib.image = skinImage_;
    ib.subresourceRange = all;
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1, &sb, 1, &ib);
    if (!skinsCleared_) {
        VkClearColorValue clear{};
        vkCmdClearColorImage(cb, skinImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &all);
    }
    std::vector<VkBufferImageCopy> copies;
    for (int i = 0; i < kMaxSkins; i++) {
        if (!skinDirty_[i]) continue;
        const VkDeviceSize off = (VkDeviceSize)i * kSkinBytes;
        for (size_t o = 0; o < kSkinBytes; o += 65536)
            vkCmdUpdateBuffer(cb, skinStage_, off + o, std::min<size_t>(65536, kSkinBytes - o), skinPixels_[i].data() + o);
        VkBufferImageCopy c{};
        c.bufferOffset = off;
        c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, (uint32_t)i, 1};
        c.imageExtent = {(uint32_t)kSkinW, (uint32_t)kSkinH, 1};
        copies.push_back(c);
        skinDirty_[i] = false;
    }
    if (!copies.empty()) {
        VkBufferMemoryBarrier wb = sb;
        wb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        wb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1, &wb, 0, nullptr);
        vkCmdCopyBufferToImage(cb, skinStage_, skinImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, (uint32_t)copies.size(), copies.data());
    }
    ib.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    ib.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    ib.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    ib.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
                         1, &ib);
    skinsCleared_ = true;
}

bool Renderer::createFrameBuffers(uint32_t vertexCap, uint32_t indexCap) {
    vertexCap_ = vertexCap;
    indexCap_ = indexCap;
    for (auto& f : frames_) {
        if (!createBuffer(sizeof(FrameUniforms), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, f.ubo, f.uboMem, &f.uboMap)) return false;
        if (inlineUpload_) {
            if (!createDeviceBuffer((VkDeviceSize)vertexCap * sizeof(Vertex), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, f.vbo, f.vboMem)) return false;
            if (!createDeviceBuffer((VkDeviceSize)indexCap * sizeof(uint32_t), VK_BUFFER_USAGE_INDEX_BUFFER_BIT, f.ibo, f.iboMem)) return false;
        } else {
            if (!createBuffer((VkDeviceSize)vertexCap * sizeof(Vertex), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, f.vbo, f.vboMem, &f.vboMap)) return false;
            if (!createBuffer((VkDeviceSize)indexCap * sizeof(uint32_t), VK_BUFFER_USAGE_INDEX_BUFFER_BIT, f.ibo, f.iboMem, &f.iboMap)) return false;
        }

        if (!f.set) {
            VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            ai.descriptorPool = descriptorPool_;
            ai.descriptorSetCount = 1;
            ai.pSetLayouts = &frameLayout_;
            VK_CHECK(vkAllocateDescriptorSets(device_, &ai, &f.set));
        }
        VkDescriptorBufferInfo bi{f.ubo, 0, sizeof(FrameUniforms)};
        VkDescriptorImageInfo si{sampler_, skinView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet w[2]{};
        w[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[0].dstSet = f.set;
        w[0].dstBinding = 0;
        w[0].descriptorCount = 1;
        w[0].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        w[0].pBufferInfo = &bi;
        w[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[1].dstSet = f.set;
        w[1].dstBinding = 1;
        w[1].descriptorCount = 1;
        w[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w[1].pImageInfo = &si;
        vkUpdateDescriptorSets(device_, 2, w, 0, nullptr);

        if (!f.cmd) {
            VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            cbai.commandPool = cmdPool_;
            cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            cbai.commandBufferCount = 1;
            VK_CHECK(vkAllocateCommandBuffers(device_, &cbai, &f.cmd));
            VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            VK_CHECK(vkCreateSemaphore(device_, &sci, nullptr, &f.imageAvailable));
            VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            VK_CHECK(vkCreateFence(device_, &fci, nullptr, &f.inFlight));
        }
    }
    return true;
}

void Renderer::destroyFrameBuffers() {
    for (auto& f : frames_) {
        auto drop = [&](VkBuffer& b, VkDeviceMemory& m, void*& map) {
            if (b) vkDestroyBuffer(device_, b, nullptr);
            if (m) vkFreeMemory(device_, m, nullptr);  // unmaps too
            b = VK_NULL_HANDLE;
            m = VK_NULL_HANDLE;
            map = nullptr;
        };
        drop(f.ubo, f.uboMem, f.uboMap);
        drop(f.vbo, f.vboMem, f.vboMap);
        drop(f.ibo, f.iboMem, f.iboMap);
    }
}

// ================================================================ passes & pipelines

bool Renderer::createRenderPasses() {
    auto makePass = [&](VkFormat format, VkImageLayout finalLayout, VkAttachmentLoadOp load, VkRenderPass& out,
                        bool sampledAfter) -> bool {
        VkAttachmentDescription color{};
        color.format = format;
        color.samples = VK_SAMPLE_COUNT_1_BIT;
        color.loadOp = load;
        color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        color.finalLayout = finalLayout;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &ref;
        VkSubpassDependency deps[2]{};
        // In: wait for earlier writes, and for the previous frame's composite to finish reading.
        deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        deps[0].dstSubpass = 0;
        deps[0].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[0].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
        // Out: the composite samples what the scene pass wrote.
        deps[1].srcSubpass = 0;
        deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        deps[1].dstStageMask = sampledAfter ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
        deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        deps[1].dstAccessMask = sampledAfter ? VK_ACCESS_SHADER_READ_BIT : 0;
        VkRenderPassCreateInfo rpci{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
        rpci.attachmentCount = 1;
        rpci.pAttachments = &color;
        rpci.subpassCount = 1;
        rpci.pSubpasses = &sub;
        rpci.dependencyCount = 2;
        rpci.pDependencies = deps;
        VK_CHECK(vkCreateRenderPass(device_, &rpci, nullptr, &out));
        return true;
    };
    if (!scenePass_ && !makePass(sceneFormat_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ATTACHMENT_LOAD_OP_CLEAR,
                                 scenePass_, true))
        return false;
    if (!compositePass_ && !makePass(swapFormat_, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                                     compositePass_, false))
        return false;
    return true;
}

bool Renderer::createPipelines() {
    if (floorPipe_) return true;
    struct Spec {
        const uint32_t* vs;
        size_t vsBytes;
        const uint32_t* fs;
        size_t fsBytes;
        bool vertices;
        Blend blend;
        VkRenderPass pass;
        VkPipeline* out;
    };
    const Spec specs[] = {
        {kFullscreenVert, sizeof(kFullscreenVert), kFloorFrag, sizeof(kFloorFrag), false, Blend::Opaque, scenePass_, &floorPipe_},
        {kShadowVert, sizeof(kShadowVert), kShadowFrag, sizeof(kShadowFrag), true, Blend::Shadow, scenePass_, &shadowPipe_},
        {kCreatureVert, sizeof(kCreatureVert), kCreatureFrag, sizeof(kCreatureFrag), true, Blend::Alpha, scenePass_, &creaturePipe_},
        {kMotesVert, sizeof(kMotesVert), kMotesFrag, sizeof(kMotesFrag), false, Blend::Additive, scenePass_, &motesPipe_},
        {kFullscreenVert, sizeof(kFullscreenVert), kCompositeFrag, sizeof(kCompositeFrag), false, Blend::Opaque, compositePass_, &compositePipe_},
    };

    VkVertexInputBindingDescription bind{0, sizeof(Vertex), VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription attrs[7] = {
        {0, 0, VK_FORMAT_R32G32_SFLOAT, offsetof(Vertex, x)},
        {1, 0, VK_FORMAT_R32G32_SFLOAT, offsetof(Vertex, u)},
        {2, 0, VK_FORMAT_R32_SFLOAT, offsetof(Vertex, z)},
        {3, 0, VK_FORMAT_R32_SFLOAT, offsetof(Vertex, kind)},
        {4, 0, VK_FORMAT_R32_SFLOAT, offsetof(Vertex, seed)},
        {5, 0, VK_FORMAT_R32_SFLOAT, offsetof(Vertex, frame)},
        {6, 0, VK_FORMAT_R32G32B32A32_SFLOAT, offsetof(Vertex, aux)},
    };

    for (const Spec& s : specs) {
        VkShaderModule vs = makeModule(device_, s.vs, s.vsBytes);
        VkShaderModule fs = makeModule(device_, s.fs, s.fsBytes);
        if (!vs || !fs) {
            DT_LOGE("shader module failed");
            return false;
        }
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vs;
        stages[0].pName = "main";
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = fs;
        stages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        if (s.vertices) {
            vi.vertexBindingDescriptionCount = 1;
            vi.pVertexBindingDescriptions = &bind;
            vi.vertexAttributeDescriptionCount = 7;
            vi.pVertexAttributeDescriptions = attrs;
        }
        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        vp.viewportCount = 1;
        vp.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = VK_CULL_MODE_NONE;
        rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rs.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState cba{};
        cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT |
                             VK_COLOR_COMPONENT_A_BIT;
        cba.colorBlendOp = cba.alphaBlendOp = VK_BLEND_OP_ADD;
        cba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        cba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        switch (s.blend) {
            case Blend::Opaque:
                cba.blendEnable = VK_FALSE;
                break;
            case Blend::Alpha:
                cba.blendEnable = VK_TRUE;
                cba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
                cba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                break;
            case Blend::Shadow:  // dst * (1 - a)
                cba.blendEnable = VK_TRUE;
                cba.srcColorBlendFactor = VK_BLEND_FACTOR_ZERO;
                cba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                cba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
                cba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
                break;
            case Blend::Additive:
                cba.blendEnable = VK_TRUE;
                cba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
                cba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE;
                cba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
                cba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
                break;
        }
        VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        cb.attachmentCount = 1;
        cb.pAttachments = &cba;
        VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        ds.dynamicStateCount = 2;
        ds.pDynamicStates = dyn;

        VkGraphicsPipelineCreateInfo pci{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        pci.stageCount = 2;
        pci.pStages = stages;
        pci.pVertexInputState = &vi;
        pci.pInputAssemblyState = &ia;
        pci.pViewportState = &vp;
        pci.pRasterizationState = &rs;
        pci.pMultisampleState = &ms;
        pci.pColorBlendState = &cb;
        pci.pDynamicState = &ds;
        pci.layout = pipelineLayout_;
        pci.renderPass = s.pass;
        VkResult r = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pci, nullptr, s.out);
        vkDestroyShaderModule(device_, vs, nullptr);
        vkDestroyShaderModule(device_, fs, nullptr);
        if (r != VK_SUCCESS) {
            DT_LOGE("pipeline creation failed %d", r);
            return false;
        }
    }
    return true;
}

// ================================================================ offscreen scene target

bool Renderer::createOffscreen() {
    destroyOffscreen();
    sceneExtent_.width = std::max(1u, (uint32_t)std::lround(extent_.width * renderScale_));
    sceneExtent_.height = std::max(1u, (uint32_t)std::lround(extent_.height * renderScale_));

    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = sceneFormat_;
    ici.extent = {sceneExtent_.width, sceneExtent_.height, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_CHECK(vkCreateImage(device_, &ici, nullptr, &sceneImage_));
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, sceneImage_, &req);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mai.memoryTypeIndex == UINT32_MAX) mai.memoryTypeIndex = findMemoryType(req.memoryTypeBits, 0);
    VK_CHECK(vkAllocateMemory(device_, &mai, nullptr, &sceneMem_));
    VK_CHECK(vkBindImageMemory(device_, sceneImage_, sceneMem_, 0));

    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = sceneImage_;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = sceneFormat_;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_CHECK(vkCreateImageView(device_, &vci, nullptr, &sceneView_));

    VkFramebufferCreateInfo fci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fci.renderPass = scenePass_;
    fci.attachmentCount = 1;
    fci.pAttachments = &sceneView_;
    fci.width = sceneExtent_.width;
    fci.height = sceneExtent_.height;
    fci.layers = 1;
    VK_CHECK(vkCreateFramebuffer(device_, &fci, nullptr, &sceneFramebuffer_));

    VkDescriptorImageInfo ii{sampler_, sceneView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = textureSet_;
    w.dstBinding = 0;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &ii;
    vkUpdateDescriptorSets(device_, 1, &w, 0, nullptr);
    DT_LOGI("scene target %ux%u (scale %.2f)", sceneExtent_.width, sceneExtent_.height, renderScale_);
    return true;
}

void Renderer::destroyOffscreen() {
    if (!device_) return;
    if (sceneFramebuffer_) vkDestroyFramebuffer(device_, sceneFramebuffer_, nullptr);
    if (sceneView_) vkDestroyImageView(device_, sceneView_, nullptr);
    if (sceneImage_) vkDestroyImage(device_, sceneImage_, nullptr);
    if (sceneMem_) vkFreeMemory(device_, sceneMem_, nullptr);
    sceneFramebuffer_ = VK_NULL_HANDLE;
    sceneView_ = VK_NULL_HANDLE;
    sceneImage_ = VK_NULL_HANDLE;
    sceneMem_ = VK_NULL_HANDLE;
}

// ================================================================ swapchain & surface

bool Renderer::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps{};
    VK_CHECK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(phys_, surface_, &caps));
    extent_ = caps.currentExtent;
    if (extent_.width == 0xFFFFFFFF) {
        extent_.width = (uint32_t)ANativeWindow_getWidth(window_);
        extent_.height = (uint32_t)ANativeWindow_getHeight(window_);
    }
    if (extent_.width == 0 || extent_.height == 0) return false;

    uint32_t fmtCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &fmtCount, nullptr);
    if (fmtCount == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(fmtCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &fmtCount, formats.data());
    // A UNORM target: the composite shader writes gamma-encoded colour itself.
    VkSurfaceFormatKHR chosen = formats[0];
    for (auto& f : formats) {
        if ((f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) &&
            f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
            break;
        }
    }
    if (compositePass_ && chosen.format != swapFormat_) {
        // A different format would need new composite pipelines; keep the old one if offered.
        for (auto& f : formats)
            if (f.format == swapFormat_) chosen = f;
    }
    swapFormat_ = chosen.format;

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) imageCount = caps.maxImageCount;
    VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = surface_;
    sci.minImageCount = imageCount;
    sci.imageFormat = chosen.format;
    sci.imageColorSpace = chosen.colorSpace;
    sci.imageExtent = extent_;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // Content is not pre-rotated: the compositor rotates on landscape-native panels.
    sci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                           ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                           : caps.currentTransform;
    sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                             ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                             : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;  // vsync: paced by the display
    sci.clipped = VK_TRUE;
    VK_CHECK(vkCreateSwapchainKHR(device_, &sci, nullptr, &swapchain_));

    uint32_t n = 0;
    VK_CHECK(vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr));
    images_.resize(n);
    VK_CHECK(vkGetSwapchainImagesKHR(device_, swapchain_, &n, images_.data()));
    renderFinished_.assign(n, VK_NULL_HANDLE);
    VkSemaphoreCreateInfo semci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < n; i++) VK_CHECK(vkCreateSemaphore(device_, &semci, nullptr, &renderFinished_[i]));

    if (!createRenderPasses()) return false;
    views_.resize(n);
    swapFramebuffers_.resize(n);
    for (uint32_t i = 0; i < n; i++) {
        VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vci.image = images_[i];
        vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vci.format = swapFormat_;
        vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VK_CHECK(vkCreateImageView(device_, &vci, nullptr, &views_[i]));
        VkFramebufferCreateInfo fci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fci.renderPass = compositePass_;
        fci.attachmentCount = 1;
        fci.pAttachments = &views_[i];
        fci.width = extent_.width;
        fci.height = extent_.height;
        fci.layers = 1;
        VK_CHECK(vkCreateFramebuffer(device_, &fci, nullptr, &swapFramebuffers_[i]));
    }
    if (renderScale_ <= 0) renderScale_ = settingsFor(Quality::High).renderScale;
    return createOffscreen();
}

void Renderer::destroySwapchain() {
    if (!device_) return;
    vkDeviceWaitIdle(device_);
    destroyOffscreen();
    for (auto fb : swapFramebuffers_) if (fb) vkDestroyFramebuffer(device_, fb, nullptr);
    for (auto v : views_) if (v) vkDestroyImageView(device_, v, nullptr);
    for (auto s : renderFinished_) if (s) vkDestroySemaphore(device_, s, nullptr);
    swapFramebuffers_.clear();
    views_.clear();
    images_.clear();
    renderFinished_.clear();
    if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    swapchain_ = VK_NULL_HANDLE;
}

bool Renderer::createSurface() {
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = window_;
    VK_CHECK(vkCreateAndroidSurfaceKHR(instance_, &sci, nullptr, &surface_));
    return true;
}

void Renderer::destroySurface() {
    if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    surface_ = VK_NULL_HANDLE;
}

bool Renderer::setupForWindow() {
    swapchainReady_ = false;
    if (deviceBroken_) destroyDevice();
    if (!ensureDevice()) return false;
    if (!createSwapchain()) return false;
    if (!createPipelines()) return false;
    swapchainReady_ = true;
    DT_LOGI("swapchain ready %ux%u", extent_.width, extent_.height);
    return true;
}

bool Renderer::attach(ANativeWindow* window) {
    if (!initInstance()) return false;
    window_ = window;
    if (!createSurface()) return false;
    return setupForWindow();
}

void Renderer::detach() {
    swapchainReady_ = false;
    destroySwapchain();
    destroySurface();
    window_ = nullptr;
}

void Renderer::recreateSwapchain(bool surfaceToo) {
    destroySwapchain();
    swapchainReady_ = false;
    if (surfaceToo) {
        destroySurface();
        if (!window_ || !createSurface()) return;
    }
    swapchainReady_ = createSwapchain();
    if (!swapchainReady_) DT_LOGW("swapchain rebuild failed; will retry");
}

void Renderer::tryRecover() {
    if (!window_ || swapchainReady_) return;
    if (deviceBroken_ || !deviceReady_) {
        destroySwapchain();
        destroySurface();
        if (!createSurface()) return;
        setupForWindow();
    } else {
        recreateSwapchain(surface_ == VK_NULL_HANDLE);
    }
}

void Renderer::stopRendering(const char* what, VkResult r) {
    DT_LOGE("%s failed %d; rebuilding the device", what, r);
    swapchainReady_ = false;
    deviceBroken_ = true;
}

void Renderer::checkResize() {
    VkSurfaceCapabilitiesKHR caps{};
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(phys_, surface_, &caps) != VK_SUCCESS) return;
    VkExtent2D cur = caps.currentExtent;
    if (cur.width == 0xFFFFFFFF) {
        cur.width = (uint32_t)ANativeWindow_getWidth(window_);
        cur.height = (uint32_t)ANativeWindow_getHeight(window_);
    }
    if (cur.width && cur.height && (cur.width != extent_.width || cur.height != extent_.height)) {
        DT_LOGI("surface resized %ux%u -> %ux%u", extent_.width, extent_.height, cur.width, cur.height);
        recreateSwapchain(false);
    }
}

// ================================================================ drawing

void Renderer::record(PerFrame& f, uint32_t imageIndex, const Mesh& mesh, int motes) {
    VkCommandBuffer cb = f.cmd;
    const uint32_t indexCount = (uint32_t)mesh.indices.size();
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cb, &bi);
    if (inlineUpload_ && indexCount > 0) recordInlineUpload(cb, f, mesh);
    recordSkinUploads(cb);

    // ---- scene
    VkClearValue clear{};
    clear.color = {{0.0f, 0.05f, 0.07f, 1.0f}};
    VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rp.renderPass = scenePass_;
    rp.framebuffer = sceneFramebuffer_;
    rp.renderArea.extent = sceneExtent_;
    rp.clearValueCount = 1;
    rp.pClearValues = &clear;
    vkCmdBeginRenderPass(cb, &rp, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0, 0, (float)sceneExtent_.width, (float)sceneExtent_.height, 0, 1};
    VkRect2D sc{{0, 0}, sceneExtent_};
    vkCmdSetViewport(cb, 0, 1, &vp);
    vkCmdSetScissor(cb, 0, 1, &sc);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout_, 0, 1, &f.set, 0, nullptr);

    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, floorPipe_);
    vkCmdDraw(cb, 3, 1, 0, 0);

    MotesPush push{0};
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, motesPipe_);
    vkCmdPushConstants(cb, pipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push), &push);
    vkCmdDraw(cb, (uint32_t)motes * 6, 1, 0, 0);

    if (indexCount > 0) {
        VkDeviceSize off = 0;
        vkCmdBindVertexBuffers(cb, 0, 1, &f.vbo, &off);
        vkCmdBindIndexBuffer(cb, f.ibo, 0, VK_INDEX_TYPE_UINT32);
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, shadowPipe_);
        vkCmdDrawIndexed(cb, indexCount, 1, 0, 0, 0);
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, creaturePipe_);
        vkCmdDrawIndexed(cb, indexCount, 1, 0, 0, 0);
    }

    push.layer = 1;
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, motesPipe_);
    vkCmdPushConstants(cb, pipelineLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push), &push);
    vkCmdDraw(cb, (uint32_t)motes * 6, 1, 0, 0);
    vkCmdEndRenderPass(cb);

    // ---- composite
    rp.renderPass = compositePass_;
    rp.framebuffer = swapFramebuffers_[imageIndex];
    rp.renderArea.extent = extent_;
    rp.clearValueCount = 0;
    rp.pClearValues = nullptr;
    vkCmdBeginRenderPass(cb, &rp, VK_SUBPASS_CONTENTS_INLINE);
    vp = {0, 0, (float)extent_.width, (float)extent_.height, 0, 1};
    sc = {{0, 0}, extent_};
    vkCmdSetViewport(cb, 0, 1, &vp);
    vkCmdSetScissor(cb, 0, 1, &sc);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipe_);
    VkDescriptorSet sets[2] = {f.set, textureSet_};
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout_, 0, 2, sets, 0, nullptr);
    vkCmdDraw(cb, 3, 1, 0, 0);
    vkCmdEndRenderPass(cb);
    vkEndCommandBuffer(cb);
}

bool Renderer::draw(const FrameInput& in) {
    if (!swapchainReady_) return false;
    if (framesDrawn_++ % 15 == 0) {
        checkResize();
        if (!swapchainReady_) return false;
    }
    QualitySettings qs = settingsFor(in.quality);
    if (std::fabs(qs.renderScale - renderScale_) > 1e-3f) {
        vkDeviceWaitIdle(device_);
        renderScale_ = qs.renderScale;
        if (!createOffscreen()) {
            stopRendering("offscreen target", VK_ERROR_OUT_OF_DEVICE_MEMORY);
            return false;
        }
    }

    // Grow the per-frame buffers if the tank got busier than they hold.
    const Mesh& mesh = *in.mesh;
    if (mesh.vertices.size() > vertexCap_ || mesh.indices.size() > indexCap_) {
        vkDeviceWaitIdle(device_);
        destroyFrameBuffers();
        uint32_t v = std::max<uint32_t>(vertexCap_ * 2, (uint32_t)mesh.vertices.size());
        uint32_t i = std::max<uint32_t>(indexCap_ * 2, (uint32_t)mesh.indices.size());
        if (!createFrameBuffers(v, i)) {
            stopRendering("growing buffers", VK_ERROR_OUT_OF_DEVICE_MEMORY);
            return false;
        }
    }

    PerFrame& f = frames_[frame_];
    VkResult r = vkWaitForFences(device_, 1, &f.inFlight, VK_TRUE, UINT64_MAX);
    if (r != VK_SUCCESS) {
        stopRendering("vkWaitForFences", r);
        return false;
    }
    uint32_t imageIndex = 0;
    r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, f.imageAvailable, VK_NULL_HANDLE, &imageIndex);
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) {
        recreateSwapchain(r == VK_ERROR_SURFACE_LOST_KHR);
        return false;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
        stopRendering("vkAcquireNextImageKHR", r);
        return false;
    }

    // Frame uniforms.
    FrameUniforms u{};
    const CameraParams& c = in.camera;
    float sx = (float)extent_.width / std::max(c.outSize.x, 1.0f);  // the camera may lag a resize by a frame
    u.cam[0] = c.center.x;
    u.cam[1] = c.center.y;
    u.cam[2] = c.pxPerMm * sx;
    u.cam[3] = c.camHeight;
    u.outSize[0] = (float)extent_.width;
    u.outSize[1] = (float)extent_.height;
    u.outSize[2] = (float)sceneExtent_.width;
    u.outSize[3] = (float)sceneExtent_.height;
    u.origin[0] = c.origin.x;
    u.origin[1] = c.origin.y;
    u.origin[2] = in.time;
    u.origin[3] = (float)(int)in.quality;
    u.tank[0] = in.tank.width;
    u.tank[1] = in.tank.height;
    u.tank[2] = in.tank.depth;
    u.tank[3] = (float)qs.motesPerLayer;
    u.sun[0] = Sun::kDirX;
    u.sun[1] = Sun::kDirY;
    u.sun[2] = Sun::kDirZ;
    u.sun[3] = 1.0f;
    u.misc[0] = in.tilt.x;
    u.misc[1] = in.tilt.y;
    u.misc[2] = qs.refraction ? 2.8f : 0.0f;
    u.misc[3] = 1.08f;
    u.view[0] = c.turnCos();
    u.view[1] = c.turnSin();
    for (int i = 0; i < kMaxRipples; i++) {
        const Ripple& rp = in.ripples[i];
        u.ripples[i][0] = rp.at.x;
        u.ripples[i][1] = rp.at.y;
        u.ripples[i][2] = rp.start;
        u.ripples[i][3] = rp.amplitude;
    }
    std::memcpy(f.uboMap, &u, sizeof(u));
    VkMappedMemoryRange ranges[3]{};
    uint32_t nRanges = 0;
    auto flush = [&](VkDeviceMemory mem) {
        VkMappedMemoryRange& r = ranges[nRanges++];
        r.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        r.memory = mem;
        r.offset = 0;
        r.size = VK_WHOLE_SIZE;
    };
    flush(f.uboMem);
    if (!mesh.vertices.empty() && !inlineUpload_) {
        std::memcpy(f.vboMap, mesh.vertices.data(), mesh.vertices.size() * sizeof(Vertex));
        std::memcpy(f.iboMap, mesh.indices.data(), mesh.indices.size() * sizeof(uint32_t));
        flush(f.vboMem);
        flush(f.iboMem);
    }
    // Host-coherent memory needs no flush by the spec, but a virtualised GPU
    // (gfxstream on the emulator) was seen drawing with last frame's vertices
    // without one: a flush is cheap and always valid.
    vkFlushMappedMemoryRanges(device_, nRanges, ranges);

    vkResetFences(device_, 1, &f.inFlight);
    vkResetCommandBuffer(f.cmd, 0);
    record(f, imageIndex, mesh, qs.motesPerLayer);

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &f.imageAvailable;
    si.pWaitDstStageMask = &waitStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &f.cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &renderFinished_[imageIndex];
    r = vkQueueSubmit(queue_, 1, &si, f.inFlight);
    if (r != VK_SUCCESS) {
        stopRendering("vkQueueSubmit", r);
        return false;
    }
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &renderFinished_[imageIndex];
    pi.swapchainCount = 1;
    pi.pSwapchains = &swapchain_;
    pi.pImageIndices = &imageIndex;
    r = vkQueuePresentKHR(queue_, &pi);
    frame_ = (frame_ + 1) % kFramesInFlight;
    if (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR) {
        if (framesPresented_++ == 0) DT_LOGI("First frame presented");
        return true;
    }
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) recreateSwapchain(r == VK_ERROR_SURFACE_LOST_KHR);
    else stopRendering("vkQueuePresentKHR", r);
    return false;
}

// ================================================================ teardown

void Renderer::destroyDevice() {
    if (!device_) return;
    vkDeviceWaitIdle(device_);
    destroySwapchain();
    for (VkPipeline* p : {&floorPipe_, &shadowPipe_, &creaturePipe_, &motesPipe_, &compositePipe_}) {
        if (*p) vkDestroyPipeline(device_, *p, nullptr);
        *p = VK_NULL_HANDLE;
    }
    if (scenePass_) vkDestroyRenderPass(device_, scenePass_, nullptr);
    if (compositePass_) vkDestroyRenderPass(device_, compositePass_, nullptr);
    scenePass_ = compositePass_ = VK_NULL_HANDLE;
    destroyFrameBuffers();
    destroySkins();
    for (auto& f : frames_) {
        if (f.imageAvailable) vkDestroySemaphore(device_, f.imageAvailable, nullptr);
        if (f.inFlight) vkDestroyFence(device_, f.inFlight, nullptr);
        f = PerFrame{};
    }
    if (sampler_) vkDestroySampler(device_, sampler_, nullptr);
    if (pipelineLayout_) vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    if (descriptorPool_) vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
    if (frameLayout_) vkDestroyDescriptorSetLayout(device_, frameLayout_, nullptr);
    if (textureLayout_) vkDestroyDescriptorSetLayout(device_, textureLayout_, nullptr);
    if (cmdPool_) vkDestroyCommandPool(device_, cmdPool_, nullptr);
    vkDestroyDevice(device_, nullptr);
    sampler_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    textureSet_ = VK_NULL_HANDLE;
    frameLayout_ = textureLayout_ = VK_NULL_HANDLE;
    cmdPool_ = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
    queue_ = VK_NULL_HANDLE;
    phys_ = VK_NULL_HANDLE;
    vertexCap_ = indexCap_ = 0;
    frame_ = 0;
    deviceReady_ = false;
    deviceBroken_ = false;
}

void Renderer::cleanup() {
    destroyDevice();
    destroySurface();
    if (instance_) vkDestroyInstance(instance_, nullptr);
    instance_ = VK_NULL_HANDLE;
    window_ = nullptr;
}

}  // namespace dt
