#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <string>
#include <vector>

namespace {

constexpr int kMaxSlots = 16;
constexpr int kMaxDeadZones = 8;
constexpr int kDefaultScreenW = 1080;
constexpr int kDefaultScreenH = 2400;
constexpr const char* kDefaultStatePath = "/data/local/tmp/deadzone_state.json";
constexpr const char* kVirtualDeviceName = "DeadZone Virtual Touchscreen";
constexpr const char* kBackendVersion = "deadzone_daemon file-control-v6";
constexpr int kPendingEnableTimeoutMs = 5000;

volatile sig_atomic_t g_running = 1;
bool g_uinputWriteFailed = false;
int g_uinputWriteErrno = 0;

struct RectZone {
    bool enabled = true;
    float centerX = 540.0f;
    float centerY = 960.0f;
    float width = 360.0f;
    float height = 78.0f;
    float rotation = 0.0f;
};

struct RectConfig {
    bool enabled = true;
    float displayWidth = kDefaultScreenW;
    float displayHeight = kDefaultScreenH;
    float centerX = 540.0f;
    float centerY = 960.0f;
    float width = 360.0f;
    float height = 78.0f;
    float rotation = 0.0f;
    int zoneCount = 1;
    std::array<RectZone, kMaxDeadZones> zones {};
};

struct AbsRange {
    int min = 0;
    int max = 0;
    int resolution = 0;
};

struct SlotState {
    bool active = false;
    bool hasX = false;
    bool hasY = false;
    bool hasPosition = false;
    bool hasPrevious = false;
    bool splitThisGesture = false;
    bool passthroughUntilOutside = false;
    bool blockedByDeadzone = false;
    bool endedWhileBlocked = false;
    bool virtualActive = false;
    int virtualTrackingId = -1;
    int trackingId = -1;
    int x = 0;
    int y = 0;
    int prevX = 0;
    int prevY = 0;
    int pressure = 50;
    int touchMajor = 8;
};

struct Options {
    std::string device;
    std::string configPath;
    std::string statePath = kDefaultStatePath;
    std::string commandPath;
    int screenW = kDefaultScreenW;
    int screenH = kDefaultScreenH;
    bool grab = false;
    bool emit = false;
    bool verbose = false;
    bool logState = false;
    bool swapXY = false;
    bool invertX = false;
    bool invertY = false;
    int rotation = -1;
    float offsetX = 0.0f;
    float offsetY = 0.0f;
};

void onSignal(int) {
    g_running = 0;
}

void usage() {
    fprintf(stderr,
            "Usage: deadzone_daemon --device /dev/input/eventX --config /path/deadzone_config.json [--screen 1080x2400] [--emit] [--grab]\n"
            "\n"
            "Default mode is dry-run: it reads touch events and logs split points only.\n"
            "--emit creates a uinput touchscreen and forwards events.\n"
            "--grab enables EVIOCGRAB on the real touchscreen. Use only after dry-run looks correct.\n"
            "--log-state logs enter/cross/leave deadzone state changes.\n"
            "--verbose logs active slot positions on every input frame for calibration.\n"
            "--swap-xy swaps mapped X/Y display coordinates.\n"
            "--invert-x flips mapped X around the display width.\n"
            "--invert-y flips mapped Y around the display height.\n"
            "--rotate 0|90|180|270 overrides automatic display rotation mapping.\n"
            "--offset-x N adds N pixels to mapped display X.\n"
            "--offset-y N adds N pixels to mapped display Y.\n"
            "--command PATH polls a JSON command file every 20ms.\n"
            "--state PATH writes startup/runtime state JSON.\n");
}

bool parseScreen(const char* text, int* w, int* h) {
    return sscanf(text, "%dx%d", w, h) == 2 && *w > 0 && *h > 0;
}

Options parseArgs(int argc, char** argv) {
    Options opt;
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--version")) {
            fprintf(stdout, "%s\n", kBackendVersion);
            exit(0);
        } else if (!strcmp(argv[i], "--device") && i + 1 < argc) {
            opt.device = argv[++i];
        } else if (!strcmp(argv[i], "--config") && i + 1 < argc) {
            opt.configPath = argv[++i];
        } else if (!strcmp(argv[i], "--state") && i + 1 < argc) {
            opt.statePath = argv[++i];
        } else if (!strcmp(argv[i], "--command") && i + 1 < argc) {
            opt.commandPath = argv[++i];
        } else if (!strcmp(argv[i], "--screen") && i + 1 < argc) {
            if (!parseScreen(argv[++i], &opt.screenW, &opt.screenH)) {
                fprintf(stderr, "Invalid --screen value\n");
                opt.device.clear();
            }
        } else if (!strcmp(argv[i], "--grab")) {
            opt.grab = true;
        } else if (!strcmp(argv[i], "--emit")) {
            opt.emit = true;
        } else if (!strcmp(argv[i], "--verbose")) {
            opt.verbose = true;
        } else if (!strcmp(argv[i], "--log-state")) {
            opt.logState = true;
        } else if (!strcmp(argv[i], "--swap-xy")) {
            opt.swapXY = true;
        } else if (!strcmp(argv[i], "--invert-x")) {
            opt.invertX = true;
        } else if (!strcmp(argv[i], "--invert-y")) {
            opt.invertY = true;
        } else if (!strcmp(argv[i], "--rotate") && i + 1 < argc) {
            opt.rotation = atoi(argv[++i]);
        } else if (!strcmp(argv[i], "--offset-x") && i + 1 < argc) {
            opt.offsetX = strtof(argv[++i], nullptr);
        } else if (!strcmp(argv[i], "--offset-y") && i + 1 < argc) {
            opt.offsetY = strtof(argv[++i], nullptr);
        } else {
            usage();
            exit(2);
        }
    }
    return opt;
}

bool readFile(const std::string& path, std::string* out) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[4096];
    out->clear();
    ssize_t n = 0;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        out->append(buf, static_cast<size_t>(n));
    }
    close(fd);
    return !out->empty();
}

std::string parentDirectory(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return ".";
    if (slash == 0) return "/";
    return path.substr(0, slash);
}

int createCommandWatcher(const std::string& commandPath) {
    if (commandPath.empty()) return -1;
    const int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (fd < 0) return -1;
    const uint32_t mask = IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_ATTRIB;
    if (inotify_add_watch(fd, parentDirectory(commandPath).c_str(), mask) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

void drainCommandWatcher(int fd) {
    if (fd < 0) return;
    alignas(inotify_event) char buffer[4096];
    while (true) {
        const ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n > 0) continue;
        if (n < 0 && errno == EINTR) continue;
        break;
    }
}

std::string jsonEscape(const std::string& text) {
    std::string out;
    out.reserve(text.size());
    for (char ch : text) {
        switch (ch) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += ch; break;
        }
    }
    return out;
}

long long nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

bool writeAll(int fd, const void* data, size_t size) {
    const char* cursor = static_cast<const char*>(data);
    size_t remaining = size;
    while (remaining > 0) {
        const ssize_t written = write(fd, cursor, remaining);
        if (written > 0) {
            cursor += written;
            remaining -= static_cast<size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) continue;
        g_uinputWriteFailed = true;
        g_uinputWriteErrno = written < 0 ? errno : EIO;
        return false;
    }
    return true;
}

void writeState(const Options& opt, const char* state, const std::string& message = "") {
    if (opt.statePath.empty()) return;
    char buffer[2048];
    const int n = snprintf(
        buffer,
        sizeof(buffer),
        "{\n"
        "  \"state\": \"%s\",\n"
        "  \"message\": \"%s\",\n"
        "  \"device\": \"%s\",\n"
        "  \"screen\": \"%dx%d\",\n"
        "  \"emit\": %s,\n"
        "  \"grab\": %s,\n"
        "  \"pid\": %d,\n"
        "  \"timestampMs\": %lld\n"
        "}\n",
        state,
        jsonEscape(message).c_str(),
        jsonEscape(opt.device).c_str(),
        opt.screenW,
        opt.screenH,
        opt.emit ? "true" : "false",
        opt.grab ? "true" : "false",
        static_cast<int>(getpid()),
        nowMs());
    if (n <= 0) return;
    int fd = open(opt.statePath.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    write(fd, buffer, std::min(static_cast<int>(sizeof(buffer) - 1), n));
    close(fd);
}

bool virtualDeviceRegistered() {
    std::string devices;
    return readFile("/proc/bus/input/devices", &devices) &&
           devices.find(kVirtualDeviceName) != std::string::npos;
}

bool waitForVirtualDeviceRegistered(int timeoutMs) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    do {
        if (virtualDeviceRegistered()) return true;
        usleep(50000);
    } while (std::chrono::steady_clock::now() < deadline);
    return virtualDeviceRegistered();
}

bool validateConfig(const RectConfig& rect, std::string* reason) {
    if (rect.displayWidth <= 0.0f || rect.displayHeight <= 0.0f) {
        *reason = "invalid display size";
        return false;
    }
    if (rect.zoneCount < 0 || rect.zoneCount > kMaxDeadZones) {
        *reason = "invalid deadzone count";
        return false;
    }
    for (int i = 0; i < rect.zoneCount; ++i) {
        const RectZone& zone = rect.zones[i];
        if (!std::isfinite(zone.centerX) || !std::isfinite(zone.centerY) ||
            !std::isfinite(zone.width) || !std::isfinite(zone.height) ||
            !std::isfinite(zone.rotation) || zone.width <= 0.0f || zone.height <= 0.0f) {
            *reason = "invalid deadzone rectangle";
            return false;
        }
    }
    return true;
}

bool parseBoolField(const std::string& json, const char* key, bool fallback) {
    std::string needle = std::string("\"") + key + "\"";
    size_t p = json.find(needle);
    if (p == std::string::npos) return fallback;
    p = json.find(':', p);
    if (p == std::string::npos) return fallback;
    size_t t = json.find_first_not_of(" \t\r\n", p + 1);
    if (t == std::string::npos) return fallback;
    if (json.compare(t, 4, "true") == 0) return true;
    if (json.compare(t, 5, "false") == 0) return false;
    return fallback;
}

float parseFloatField(const std::string& json, const char* key, float fallback) {
    std::string needle = std::string("\"") + key + "\"";
    size_t p = json.find(needle);
    if (p == std::string::npos) return fallback;
    p = json.find(':', p);
    if (p == std::string::npos) return fallback;
    char* end = nullptr;
    float value = strtof(json.c_str() + p + 1, &end);
    return end == json.c_str() + p + 1 ? fallback : value;
}

void loadRectZonesFromJson(const std::string& json, RectConfig* config) {
    const float requestedCount = parseFloatField(json, "zoneCount", -1.0f);
    if (requestedCount < 0.0f) {
        config->zoneCount = 1;
        config->zones[0] = RectZone {
            true,
            config->centerX,
            config->centerY,
            config->width,
            config->height,
            config->rotation
        };
        return;
    }

    config->zoneCount = std::clamp(static_cast<int>(lroundf(requestedCount)), 0, kMaxDeadZones);
    for (int i = 0; i < config->zoneCount; ++i) {
        char key[48];
        RectZone zone {};
        snprintf(key, sizeof(key), "z%dEnabled", i);
        zone.enabled = parseBoolField(json, key, true);
        snprintf(key, sizeof(key), "z%dCenterX", i);
        zone.centerX = parseFloatField(json, key, config->centerX);
        snprintf(key, sizeof(key), "z%dCenterY", i);
        zone.centerY = parseFloatField(json, key, config->centerY);
        snprintf(key, sizeof(key), "z%dWidth", i);
        zone.width = parseFloatField(json, key, config->width);
        snprintf(key, sizeof(key), "z%dHeight", i);
        zone.height = parseFloatField(json, key, config->height);
        snprintf(key, sizeof(key), "z%dRotation", i);
        zone.rotation = parseFloatField(json, key, config->rotation);
        config->zones[i] = zone;
    }
}

long long fileVersion(const struct stat& st) {
    return static_cast<long long>(st.st_mtime) * 1000000000LL + static_cast<long long>(st.st_mtim.tv_nsec);
}

bool loadConfigIfChanged(const std::string& path, RectConfig* config, long long* lastVersion, bool logConfig) {
    if (path.empty()) return false;

    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return false;
    const long long version = fileVersion(st);
    if (version == *lastVersion) return false;

    std::string json;
    if (!readFile(path, &json)) return false;

    config->enabled = parseBoolField(json, "enabled", config->enabled);
    config->displayWidth = parseFloatField(json, "displayWidth", config->displayWidth);
    config->displayHeight = parseFloatField(json, "displayHeight", config->displayHeight);
    config->centerX = parseFloatField(json, "centerX", config->centerX);
    config->centerY = parseFloatField(json, "centerY", config->centerY);
    config->width = parseFloatField(json, "width", config->width);
    config->height = parseFloatField(json, "height", config->height);
    config->rotation = parseFloatField(json, "rotation", config->rotation);
    loadRectZonesFromJson(json, config);
    *lastVersion = version;

    if (logConfig) {
        fprintf(stderr, "config: display=(%.0f,%.0f) center=(%.1f,%.1f) size=(%.1f,%.1f) rot=%.1f zones=%d enabled=%d\n",
                config->displayWidth, config->displayHeight, config->centerX, config->centerY, config->width, config->height,
                config->rotation, config->zoneCount, config->enabled ? 1 : 0);
        fflush(stderr);
    }
    return true;
}

void loadConfigFromJson(const std::string& json, RectConfig* config) {
    config->enabled = parseBoolField(json, "enabled", config->enabled);
    config->displayWidth = parseFloatField(json, "displayWidth", config->displayWidth);
    config->displayHeight = parseFloatField(json, "displayHeight", config->displayHeight);
    config->centerX = parseFloatField(json, "centerX", config->centerX);
    config->centerY = parseFloatField(json, "centerY", config->centerY);
    config->width = parseFloatField(json, "width", config->width);
    config->height = parseFloatField(json, "height", config->height);
    config->rotation = parseFloatField(json, "rotation", config->rotation);
    loadRectZonesFromJson(json, config);
}

std::string parseStringField(const std::string& json, const char* key) {
    std::string needle = std::string("\"") + key + "\"";
    size_t p = json.find(needle);
    if (p == std::string::npos) return "";
    p = json.find(':', p);
    if (p == std::string::npos) return "";
    p = json.find('"', p + 1);
    if (p == std::string::npos) return "";
    std::string out;
    bool escape = false;
    for (size_t i = p + 1; i < json.size(); ++i) {
        char ch = json[i];
        if (escape) {
            out += ch;
            escape = false;
        } else if (ch == '\\') {
            escape = true;
        } else if (ch == '"') {
            return out;
        } else {
            out += ch;
        }
    }
    return "";
}

int parseIntField(const std::string& json, const char* key, int fallback) {
    float value = parseFloatField(json, key, static_cast<float>(fallback));
    return static_cast<int>(value);
}

const char* runtimeState(bool enabled) {
    return enabled ? "RUNNING_ENABLED" : "RUNNING_DISABLED";
}

std::string configSummary(const RectConfig& rect) {
    char buffer[512];
    snprintf(buffer, sizeof(buffer),
             "display=%.0fx%.0f center=(%.1f,%.1f) size=(%.1f,%.1f) rotation=%.1f zones=%d",
             rect.displayWidth, rect.displayHeight,
             rect.centerX, rect.centerY,
             rect.width, rect.height,
             rect.rotation,
             rect.zoneCount);
    return std::string(buffer);
}

bool hasAnyVirtualActiveSlot(const std::array<SlotState, kMaxSlots>& slots);
void emitSlotUpFrame(int uinputFd, int slot, bool anyFingerStillDown);
void emitSlotDownFrame(int uinputFd, int slot, const SlotState& state);
bool isInsideDeadzone(const SlotState& slot, const RectConfig& rect, const AbsRange& xRange,
                      const AbsRange& yRange, const Options& opt);

void resetSlots(std::array<SlotState, kMaxSlots>* slots) {
    for (SlotState& slot : *slots) slot = SlotState {};
}

void clearBlockedSlots(std::array<SlotState, kMaxSlots>* slots, int uinputFd, int* nextTrackingId) {
    for (int i = 0; i < kMaxSlots; ++i) {
        SlotState& slot = (*slots)[i];
        if (slot.active && slot.hasPosition && slot.blockedByDeadzone && uinputFd >= 0) {
            slot.virtualActive = true;
            slot.virtualTrackingId = (*nextTrackingId)++;
            emitSlotDownFrame(uinputFd, i, slot);
        }
        slot.blockedByDeadzone = false;
        if (slot.active && slot.hasPosition) slot.virtualActive = true;
    }
}

void releaseGrabIfNeeded(int inputFd, bool* grabbed) {
    if (*grabbed) {
        ioctl(inputFd, EVIOCGRAB, 0);
        *grabbed = false;
    }
}

bool grabInputIfNeeded(int inputFd, bool* grabbed, std::string* reason) {
    if (*grabbed) return true;
    if (ioctl(inputFd, EVIOCGRAB, 1) != 0) {
        *reason = std::string("EVIOCGRAB failed: ") + strerror(errno);
        return false;
    }
    *grabbed = true;
    return true;
}

bool hasActiveSlots(const std::array<SlotState, kMaxSlots>& slots) {
    for (const SlotState& slot : slots) {
        if (slot.active) return true;
    }
    return false;
}

bool activateDeadzone(RectConfig* rect,
                      std::array<SlotState, kMaxSlots>* slots,
                      int inputFd, bool* grabbed,
                      int uinputFd, int* nextTrackingId,
                      const AbsRange& xRange, const AbsRange& yRange,
                      const Options& opt,
                      const std::string& idText) {
    std::string reason;
    if (!validateConfig(*rect, &reason)) {
        writeState(opt, "FAILED", reason + " " + idText);
        return false;
    }
    if (opt.grab) {
        if (!grabInputIfNeeded(inputFd, grabbed, &reason)) {
            writeState(opt, "FAILED", reason + " " + idText);
            return false;
        }
        writeState(opt, "GRAB_DONE", "real input device grabbed " + idText);
    }
    rect->enabled = true;
    if (opt.emit && uinputFd >= 0) {
        for (int i = 0; i < kMaxSlots; ++i) {
            SlotState& slot = (*slots)[i];
            if (slot.active && slot.hasPosition && !slot.virtualActive &&
                !isInsideDeadzone(slot, *rect, xRange, yRange, opt)) {
                slot.virtualActive = true;
                slot.virtualTrackingId = (*nextTrackingId)++;
                emitSlotDownFrame(uinputFd, i, slot);
            }
        }
    }
    writeState(opt, "RUNNING_ENABLED", "deadzone enabled by app " + idText + "; " + configSummary(*rect));
    return true;
}

void handleControlCommand(const std::string& line, RectConfig* rect,
                          std::array<SlotState, kMaxSlots>* slots,
                          int inputFd, int uinputFd, bool* grabbed,
                          int* nextTrackingId, bool* pendingEnable,
                          std::string* pendingEnableId,
                          long long* pendingEnableSinceMs,
                          const AbsRange& xRange, const AbsRange& yRange,
                          const Options& opt) {
    const int id = parseIntField(line, "id", 0);
    char idBuffer[48];
    snprintf(idBuffer, sizeof(idBuffer), "id=%d", id);
    const std::string idText(idBuffer);
    const std::string cmd = parseStringField(line, "cmd");
    if (cmd == "PING" || cmd == "GET_STATUS") {
        writeState(opt, runtimeState(rect->enabled), "file command pong " + idText);
    } else if (cmd == "DISABLE") {
        rect->enabled = false;
        clearBlockedSlots(slots, uinputFd, nextTrackingId);
        resetSlots(slots);
        releaseGrabIfNeeded(inputFd, grabbed);
        *pendingEnable = false;
        pendingEnableId->clear();
        *pendingEnableSinceMs = 0;
        writeState(opt, "RUNNING_DISABLED", "deadzone disabled by app " + idText);
    } else if (cmd == "ENABLE") {
        std::string reason;
        if (!validateConfig(*rect, &reason)) {
            writeState(opt, "FAILED", reason + " " + idText);
            return;
        }
        if (hasActiveSlots(*slots)) {
            rect->enabled = false;
            *pendingEnable = true;
            *pendingEnableId = idText;
            *pendingEnableSinceMs = nowMs();
            writeState(opt, "RUNNING_PENDING_ENABLE", "waiting for fingers up before grabbing " + idText);
            return;
        }
        activateDeadzone(rect, slots, inputFd, grabbed, uinputFd, nextTrackingId, xRange, yRange, opt, idText);
    } else if (cmd == "SET_CONFIG") {
        RectConfig next = *rect;
        loadConfigFromJson(line, &next);
        next.enabled = false;
        std::string reason;
        if (!validateConfig(next, &reason)) {
            writeState(opt, "FAILED", reason + " " + idText);
            return;
        }
        *rect = next;
        clearBlockedSlots(slots, uinputFd, nextTrackingId);
        resetSlots(slots);
        *pendingEnable = false;
        pendingEnableId->clear();
        *pendingEnableSinceMs = 0;
        writeState(opt, "RUNNING_DISABLED", "config updated by app " + idText + "; " + configSummary(*rect));
    } else if (cmd == "STOP") {
        *pendingEnable = false;
        pendingEnableId->clear();
        *pendingEnableSinceMs = 0;
        writeState(opt, "STOPPING", "backend stopping by app " + idText);
        g_running = 0;
    } else {
        writeState(opt, runtimeState(rect->enabled), "unknown command " + idText);
    }
}

float clamp01(float v) {
    return std::max(0.0f, std::min(1.0f, v));
}

float mapAxis01(int value, const AbsRange& range) {
    if (range.max <= range.min) return 0.0f;
    const float t = static_cast<float>(value - range.min) / static_cast<float>(range.max - range.min);
    return clamp01(t);
}

int chooseRotation(const Options& opt, const RectConfig& rect) {
    if (opt.rotation == 0 || opt.rotation == 90 || opt.rotation == 180 || opt.rotation == 270) return opt.rotation;

    const bool same = fabsf(rect.displayWidth - opt.screenW) < fabsf(rect.displayWidth - opt.screenH);
    if (same) return 0;

    // Most Android tablets report touch in natural portrait coordinates. In landscape,
    // display X usually comes from touch Y and display Y from natural width - touch X.
    return 90;
}

void mapTouchToScreen(const SlotState& slot, const AbsRange& xRange, const AbsRange& yRange,
                      const Options& opt, const RectConfig& rect, bool previous, float* outX, float* outY) {
    const float nx = mapAxis01(previous ? slot.prevX : slot.x, xRange);
    const float ny = mapAxis01(previous ? slot.prevY : slot.y, yRange);
    const float displayW = std::max(1.0f, rect.displayWidth);
    const float displayH = std::max(1.0f, rect.displayHeight);

    float x = nx * displayW;
    float y = ny * displayH;

    switch (chooseRotation(opt, rect)) {
        case 90:
            x = ny * displayW;
            y = (1.0f - nx) * displayH;
            break;
        case 180:
            x = (1.0f - nx) * displayW;
            y = (1.0f - ny) * displayH;
            break;
        case 270:
            x = (1.0f - ny) * displayW;
            y = nx * displayH;
            break;
        default:
            break;
    }

    if (opt.swapXY) {
        std::swap(x, y);
    }
    if (opt.invertX) {
        x = rect.displayWidth - x;
    }
    if (opt.invertY) {
        y = rect.displayHeight - y;
    }

    x += opt.offsetX;
    y += opt.offsetY;

    *outX = x;
    *outY = y;
}

void unrotatePointForZone(float x, float y, const RectZone& zone, float* outX, float* outY) {
    const float radians = -zone.rotation * static_cast<float>(M_PI) / 180.0f;
    const float dx = x - zone.centerX;
    const float dy = y - zone.centerY;
    *outX = dx * cosf(radians) - dy * sinf(radians);
    *outY = dx * sinf(radians) + dy * cosf(radians);
}

bool pointInsideZone(float x, float y, const RectZone& zone) {
    if (!zone.enabled) return false;
    float localX = 0.0f;
    float localY = 0.0f;
    unrotatePointForZone(x, y, zone, &localX, &localY);
    return fabsf(localX) <= zone.width * 0.5f && fabsf(localY) <= zone.height * 0.5f;
}

bool pointInsideDeadzoneShape(float x, float y, const RectConfig& rect) {
    if (!rect.enabled) return false;
    for (int i = 0; i < rect.zoneCount; ++i) {
        if (pointInsideZone(x, y, rect.zones[i])) return true;
    }
    return false;
}

bool segmentIntersectsZone(float ax, float ay, float bx, float by, const RectZone& zone) {
    if (!zone.enabled) return false;
    if (pointInsideZone(ax, ay, zone) || pointInsideZone(bx, by, zone)) return true;

    float lax = 0.0f;
    float lay = 0.0f;
    float lbx = 0.0f;
    float lby = 0.0f;
    unrotatePointForZone(ax, ay, zone, &lax, &lay);
    unrotatePointForZone(bx, by, zone, &lbx, &lby);

    const float halfW = zone.width * 0.5f;
    const float halfH = zone.height * 0.5f;
    float t0 = 0.0f;
    float t1 = 1.0f;
    const float dx = lbx - lax;
    const float dy = lby - lay;

    auto clip = [&](float p, float q) -> bool {
        if (fabsf(p) < 0.0001f) return q >= 0.0f;
        float r = q / p;
        if (p < 0.0f) {
            if (r > t1) return false;
            if (r > t0) t0 = r;
        } else {
            if (r < t0) return false;
            if (r < t1) t1 = r;
        }
        return true;
    };

    return clip(-dx, lax + halfW) &&
           clip(dx, halfW - lax) &&
           clip(-dy, lay + halfH) &&
           clip(dy, halfH - lay);
}

bool segmentIntersectsRect(float ax, float ay, float bx, float by, const RectConfig& rect) {
    if (!rect.enabled) return false;
    for (int i = 0; i < rect.zoneCount; ++i) {
        if (segmentIntersectsZone(ax, ay, bx, by, rect.zones[i])) return true;
    }
    return false;
}

int getAbsRange(int fd, int code, AbsRange* range) {
    input_absinfo info {};
    if (ioctl(fd, EVIOCGABS(code), &info) != 0) return -1;
    range->min = info.minimum;
    range->max = info.maximum;
    range->resolution = info.resolution;
    return 0;
}

int absRangeSize(const AbsRange& range) {
    return std::max(1, range.max - range.min + 1);
}

int normalizedAbsRangeSize(const AbsRange& range) {
    const int rawSize = absRangeSize(range);
    if (range.resolution > 1 && rawSize >= 10000) {
        return std::max(1, static_cast<int>(roundf(static_cast<float>(rawSize) / static_cast<float>(range.resolution))));
    }
    return rawSize;
}

void normalizeScreenOption(Options* opt, const AbsRange& xRange, const AbsRange& yRange,
                           const RectConfig& rect, bool logState) {
    const int originalW = opt->screenW;
    const int originalH = opt->screenH;
    const int rawW = absRangeSize(xRange);
    const int rawH = absRangeSize(yRange);
    const int naturalW = normalizedAbsRangeSize(xRange);
    const int naturalH = normalizedAbsRangeSize(yRange);

    const bool looksLikeRawAbs =
        xRange.resolution > 1 && yRange.resolution > 1 &&
        abs(opt->screenW - rawW) <= std::max(2, rawW / 100) &&
        abs(opt->screenH - rawH) <= std::max(2, rawH / 100);

    if (looksLikeRawAbs) {
        opt->screenW = naturalW;
        opt->screenH = naturalH;
    }

    while (opt->screenW >= 10000 || opt->screenH >= 10000) {
        opt->screenW = std::max(1, static_cast<int>(roundf(static_cast<float>(opt->screenW) / 10.0f)));
        opt->screenH = std::max(1, static_cast<int>(roundf(static_cast<float>(opt->screenH) / 10.0f)));
    }

    const float displayMax = std::max(rect.displayWidth, rect.displayHeight);
    if (displayMax > 0.0f && (opt->screenW > displayMax * 3.0f || opt->screenH > displayMax * 3.0f)) {
        opt->screenW = std::max(1, static_cast<int>(roundf(static_cast<float>(opt->screenW) / 10.0f)));
        opt->screenH = std::max(1, static_cast<int>(roundf(static_cast<float>(opt->screenH) / 10.0f)));
    }

    if (logState && (originalW != opt->screenW || originalH != opt->screenH)) {
        fprintf(stderr, "normalized --screen from %dx%d to %dx%d using abs=(%d,%d) resolution=(%d,%d)\n",
                originalW, originalH, opt->screenW, opt->screenH, rawW, rawH, xRange.resolution, yRange.resolution);
    }
}

void emitEvent(int fd, uint16_t type, uint16_t code, int32_t value) {
    input_event ev {};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    writeAll(fd, &ev, sizeof(ev));
}

void emitSyn(int fd) {
    emitEvent(fd, EV_SYN, SYN_REPORT, 0);
}

int createUinputDevice(const AbsRange& xRange, const AbsRange& yRange) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        perror("open /dev/uinput");
        return -1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);

    uinput_user_dev dev {};
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "%s", kVirtualDeviceName);
    dev.id.bustype = BUS_VIRTUAL;
    dev.id.vendor = 0x445a;
    dev.id.product = 0x0001;
    dev.id.version = 1;

    dev.absmin[ABS_MT_SLOT] = 0;
    dev.absmax[ABS_MT_SLOT] = kMaxSlots - 1;
    dev.absmin[ABS_MT_TRACKING_ID] = 0;
    dev.absmax[ABS_MT_TRACKING_ID] = 65535;
    dev.absmin[ABS_MT_POSITION_X] = xRange.min;
    dev.absmax[ABS_MT_POSITION_X] = xRange.max;
    dev.absmin[ABS_MT_POSITION_Y] = yRange.min;
    dev.absmax[ABS_MT_POSITION_Y] = yRange.max;
    dev.absmin[ABS_MT_PRESSURE] = 0;
    dev.absmax[ABS_MT_PRESSURE] = 255;
    dev.absmin[ABS_MT_TOUCH_MAJOR] = 0;
    dev.absmax[ABS_MT_TOUCH_MAJOR] = 255;

    if (write(fd, &dev, sizeof(dev)) < 0) {
        perror("write uinput_user_dev");
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) != 0) {
        perror("UI_DEV_CREATE");
        close(fd);
        return -1;
    }

    return fd;
}

void destroyUinputDevice(int fd) {
    if (fd >= 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
    }
}

bool hasAnyVirtualActiveSlot(const std::array<SlotState, kMaxSlots>& slots) {
    for (const SlotState& slot : slots) {
        if (slot.virtualActive) return true;
    }
    return false;
}

void emitSlotUpFrame(int uinputFd, int slot, bool anyFingerStillDown) {
    emitEvent(uinputFd, EV_ABS, ABS_MT_SLOT, slot);
    emitEvent(uinputFd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emitEvent(uinputFd, EV_KEY, BTN_TOUCH, anyFingerStillDown ? 1 : 0);
    emitEvent(uinputFd, EV_KEY, BTN_TOOL_FINGER, anyFingerStillDown ? 1 : 0);
    emitSyn(uinputFd);
}

void emitSlotDownFrame(int uinputFd, int slot, const SlotState& state) {
    emitEvent(uinputFd, EV_ABS, ABS_MT_SLOT, slot);
    emitEvent(uinputFd, EV_ABS, ABS_MT_TRACKING_ID, state.virtualTrackingId);
    emitEvent(uinputFd, EV_ABS, ABS_MT_POSITION_X, state.x);
    emitEvent(uinputFd, EV_ABS, ABS_MT_POSITION_Y, state.y);
    emitEvent(uinputFd, EV_ABS, ABS_MT_PRESSURE, state.pressure);
    emitEvent(uinputFd, EV_ABS, ABS_MT_TOUCH_MAJOR, state.touchMajor);
    emitEvent(uinputFd, EV_KEY, BTN_TOUCH, 1);
    emitEvent(uinputFd, EV_KEY, BTN_TOOL_FINGER, 1);
    emitSyn(uinputFd);
}

void writeFilteredFrame(int fd, const std::vector<input_event>& events,
                        const std::array<bool, kMaxSlots>& suppressSlots,
                        bool hasVisibleTouch,
                        bool* virtualTouchDown,
                        int* realFrameSlot,
                        int* virtualCurrentSlot) {
    std::vector<input_event> output;
    output.reserve(events.size() + 2);
    input_event lastRealSlotEvent {};
    bool hasRealSlotEvent = false;

    auto selectVirtualSlot = [&](const input_event& sourceEvent) {
        if (*virtualCurrentSlot == *realFrameSlot) return;
        input_event slotEvent = sourceEvent;
        slotEvent.type = EV_ABS;
        slotEvent.code = ABS_MT_SLOT;
        slotEvent.value = *realFrameSlot;
        output.push_back(slotEvent);
        *virtualCurrentSlot = *realFrameSlot;
    };

    for (const input_event& event : events) {
        if (event.type == EV_ABS && event.code == ABS_MT_SLOT) {
            if (event.value >= 0 && event.value < kMaxSlots) {
                *realFrameSlot = event.value;
                lastRealSlotEvent = event;
                hasRealSlotEvent = true;
            }
            continue;
        }

        if (event.type == EV_ABS) {
            if (suppressSlots[*realFrameSlot]) continue;
            selectVirtualSlot(hasRealSlotEvent ? lastRealSlotEvent : event);
            output.push_back(event);
            continue;
        }

        if (event.type == EV_KEY && (event.code == BTN_TOUCH || event.code == BTN_TOOL_FINGER)) {
            // The real device keeps BTN_TOUCH=1 while a physically held finger is
            // suppressed. A second visible finger therefore may not produce another
            // BTN_TOUCH transition. Rebuild the global key state from visible slots
            // at SYN_REPORT instead of trusting the physical device's global keys.
            continue;
        }

        if (event.type == EV_SYN && event.code == SYN_REPORT) {
            if (*virtualTouchDown != hasVisibleTouch) {
                input_event btnTouch {};
                btnTouch.type = EV_KEY;
                btnTouch.code = BTN_TOUCH;
                btnTouch.value = hasVisibleTouch ? 1 : 0;
                output.push_back(btnTouch);

                input_event btnTool {};
                btnTool.type = EV_KEY;
                btnTool.code = BTN_TOOL_FINGER;
                btnTool.value = hasVisibleTouch ? 1 : 0;
                output.push_back(btnTool);
                *virtualTouchDown = hasVisibleTouch;
            }
            output.push_back(event);
            continue;
        }

        output.push_back(event);
    }

    if (!output.empty()) {
        writeAll(fd, output.data(), output.size() * sizeof(input_event));
    }
}

void updateSlotState(const input_event& ev, int* currentSlot, std::array<SlotState, kMaxSlots>* slots) {
    if (ev.type != EV_ABS) return;

    if (ev.code == ABS_MT_SLOT) {
        if (ev.value >= 0 && ev.value < kMaxSlots) *currentSlot = ev.value;
        return;
    }

    SlotState& slot = (*slots)[*currentSlot];
    switch (ev.code) {
        case ABS_MT_TRACKING_ID:
            if (ev.value < 0) {
                slot.endedWhileBlocked = slot.blockedByDeadzone;
                slot.active = false;
                slot.trackingId = -1;
                slot.hasX = false;
                slot.hasY = false;
                slot.hasPosition = false;
                slot.hasPrevious = false;
                slot.splitThisGesture = false;
                slot.passthroughUntilOutside = false;
            } else {
                slot.active = true;
                slot.trackingId = ev.value;
                slot.hasX = false;
                slot.hasY = false;
                slot.hasPosition = false;
                slot.hasPrevious = false;
                slot.splitThisGesture = false;
                slot.passthroughUntilOutside = false;
                slot.endedWhileBlocked = false;
            }
            break;
        case ABS_MT_POSITION_X:
            slot.x = ev.value;
            slot.hasX = true;
            slot.hasPosition = slot.hasX && slot.hasY;
            break;
        case ABS_MT_POSITION_Y:
            slot.y = ev.value;
            slot.hasY = true;
            slot.hasPosition = slot.hasX && slot.hasY;
            break;
        case ABS_MT_PRESSURE:
            slot.pressure = ev.value;
            break;
        case ABS_MT_TOUCH_MAJOR:
            slot.touchMajor = ev.value;
            break;
        default:
            break;
    }
}

bool isInsideDeadzone(const SlotState& slot, const RectConfig& rect, const AbsRange& xRange,
                      const AbsRange& yRange, const Options& opt) {
    if (!slot.active || !slot.hasPosition || !rect.enabled) return false;

    float screenX = 0.0f;
    float screenY = 0.0f;
    mapTouchToScreen(slot, xRange, yRange, opt, rect, false, &screenX, &screenY);
    return pointInsideDeadzoneShape(screenX, screenY, rect);
}

bool crossesDeadzone(const SlotState& slot, const RectConfig& rect, const AbsRange& xRange,
                     const AbsRange& yRange, const Options& opt) {
    if (!slot.active || !slot.hasPosition || !slot.hasPrevious || !rect.enabled) return false;

    float prevScreenX = 0.0f;
    float prevScreenY = 0.0f;
    float screenX = 0.0f;
    float screenY = 0.0f;
    mapTouchToScreen(slot, xRange, yRange, opt, rect, true, &prevScreenX, &prevScreenY);
    mapTouchToScreen(slot, xRange, yRange, opt, rect, false, &screenX, &screenY);
    return segmentIntersectsRect(prevScreenX, prevScreenY, screenX, screenY, rect);
}

void commitFramePositions(std::array<SlotState, kMaxSlots>* slots) {
    for (SlotState& slot : *slots) {
        if (!slot.active || !slot.hasPosition) continue;
        slot.prevX = slot.x;
        slot.prevY = slot.y;
        slot.hasPrevious = true;
    }
}

}  // namespace

int main(int argc, char** argv) {
    setvbuf(stderr, nullptr, _IONBF, 0);

    signal(SIGINT, onSignal);
    signal(SIGTERM, onSignal);

    Options opt = parseArgs(argc, argv);
    writeState(opt, "STARTING", "backend process started");
    if (opt.device.empty()) {
        writeState(opt, "FAILED", "missing input device");
        usage();
        return 2;
    }

    int inputFd = open(opt.device.c_str(), O_RDONLY | O_CLOEXEC);
    if (inputFd < 0) {
        perror("open input device");
        writeState(opt, "FAILED", std::string("open input device failed: ") + strerror(errno));
        return 1;
    }
    writeState(opt, "INPUT_OPENED", "real input device opened");

    AbsRange xRange {};
    AbsRange yRange {};
    if (getAbsRange(inputFd, ABS_MT_POSITION_X, &xRange) != 0 ||
        getAbsRange(inputFd, ABS_MT_POSITION_Y, &yRange) != 0) {
        fprintf(stderr, "Selected device does not look like a multitouch touchscreen\n");
        writeState(opt, "FAILED", "selected device is not a multitouch touchscreen");
        close(inputFd);
        return 1;
    }

    RectConfig rect;
    rect.enabled = false;
    long long configVersion = 0;
    if (!opt.configPath.empty()) {
        loadConfigIfChanged(opt.configPath, &rect, &configVersion, opt.logState || opt.verbose);
        rect.enabled = false;
    }
    std::string configError;
    if (!validateConfig(rect, &configError)) {
        writeState(opt, "FAILED", configError);
        close(inputFd);
        return 1;
    }
    normalizeScreenOption(&opt, xRange, yRange, rect, opt.logState || opt.verbose);
    writeState(opt, "CONFIG_LOADED", "deadzone config loaded; " + configSummary(rect));

    int uinputFd = -1;
    if (opt.emit) {
        uinputFd = createUinputDevice(xRange, yRange);
        if (uinputFd < 0) {
            writeState(opt, "FAILED", "create uinput device failed");
            close(inputFd);
            return 1;
        }
        writeState(opt, "UINPUT_CREATED", "virtual touch device created");
        if (!waitForVirtualDeviceRegistered(3000)) {
            writeState(opt, "FAILED", "virtual touch device was not registered by Android");
            destroyUinputDevice(uinputFd);
            close(inputFd);
            return 1;
        }
        writeState(opt, "UINPUT_READY", "virtual touch device registered");
    }

    writeState(opt, "RUNNING_DISABLED", "backend running disabled");

    if (opt.logState || opt.verbose) {
        fprintf(stderr, "deadzone_daemon started: device=%s naturalScreen=%dx%d emit=%d grab=%d logState=%d verbose=%d swapXY=%d invertX=%d invertY=%d rotation=%d offset=(%.1f,%.1f)\n",
                opt.device.c_str(), opt.screenW, opt.screenH, opt.emit ? 1 : 0, opt.grab ? 1 : 0,
                opt.logState ? 1 : 0, opt.verbose ? 1 : 0, opt.swapXY ? 1 : 0, opt.invertX ? 1 : 0, opt.invertY ? 1 : 0,
                opt.rotation, opt.offsetX, opt.offsetY);
    }

    std::array<SlotState, kMaxSlots> slots {};
    int currentSlot = 0;
    int nextTrackingId = 40000;
    bool grabbed = false;
    bool pendingEnable = false;
    std::string pendingEnableId;
    long long pendingEnableSinceMs = 0;
    std::string lastFileCommand;
    const int commandWatcherFd = createCommandWatcher(opt.commandPath);
    long long lastFallbackCommandCheckMs = 0;
    std::vector<input_event> pendingFrame;
    bool forwardingSynced = false;
    bool previousGrabbed = false;
    bool virtualTouchDown = false;
    int filteredRealCurrentSlot = 0;
    int virtualCurrentSlot = -1;
    int trackingScanSlot = 0;

    while (g_running) {
        pollfd pfds[2] {};
        nfds_t nfds = 0;
        const nfds_t inputIndex = nfds;
        pfds[nfds++] = pollfd {inputFd, POLLIN, 0};
        const nfds_t commandIndex = nfds;
        if (commandWatcherFd >= 0) {
            pfds[nfds++] = pollfd {commandWatcherFd, POLLIN, 0};
        }

        int timeoutMs = -1;
        if (pendingEnable) {
            const long long elapsed = nowMs() - pendingEnableSinceMs;
            timeoutMs = static_cast<int>(std::max(0LL, static_cast<long long>(kPendingEnableTimeoutMs) - elapsed));
        } else if (commandWatcherFd < 0 && !opt.commandPath.empty()) {
            timeoutMs = 250;
        }

        int pollResult = poll(pfds, nfds, timeoutMs);
        if (pollResult < 0) {
            if (errno == EINTR) continue;
            perror("poll");
            break;
        }

        const long long commandNowMs = nowMs();

        if (grabbed != previousGrabbed) {
            pendingFrame.clear();
            forwardingSynced = false;
            virtualTouchDown = false;
            filteredRealCurrentSlot = currentSlot;
            virtualCurrentSlot = -1;
            trackingScanSlot = currentSlot;
            previousGrabbed = grabbed;
        }

        const bool inputReady = pollResult > 0 && (pfds[inputIndex].revents & POLLIN);
        const bool commandReady = commandWatcherFd >= 0 && pollResult > 0 &&
                                  (pfds[commandIndex].revents & POLLIN);

        if (inputReady) {
            input_event ev {};
            ssize_t n = read(inputFd, &ev, sizeof(ev));
            if (n != sizeof(ev)) continue;

            if (ev.type == EV_SYN && ev.code == SYN_DROPPED) {
                g_uinputWriteFailed = true;
                g_uinputWriteErrno = EOVERFLOW;
                break;
            }

            updateSlotState(ev, &currentSlot, &slots);

            if (grabbed && opt.emit && uinputFd >= 0) {
            if (!forwardingSynced) {
                if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    forwardingSynced = true;
                }
            } else {
                pendingFrame.push_back(ev);
            }
        }

            if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            std::array<bool, kMaxSlots> suppressSlots {};
            bool hasVisibleTouch = false;

            for (int slotIndex = 0; slotIndex < kMaxSlots; ++slotIndex) {
                SlotState& slot = slots[slotIndex];
                const bool geometricInsideDeadzone = isInsideDeadzone(slot, rect, xRange, yRange, opt);

                // A contact that starts inside a deadzone must behave like a normal
                // touch. Keep it in passthrough mode until it has moved outside once;
                // a later outside-to-inside entry in the same gesture is blockable.
                if (slot.active && slot.hasPosition && !slot.hasPrevious && geometricInsideDeadzone) {
                    slot.passthroughUntilOutside = true;
                }
                const bool passthroughThisFrame = slot.passthroughUntilOutside;
                const bool insideDeadzone = geometricInsideDeadzone && !passthroughThisFrame;
                const bool crossedDeadzone = !passthroughThisFrame && !insideDeadzone &&
                                             !slot.blockedByDeadzone &&
                                             crossesDeadzone(slot, rect, xRange, yRange, opt);

                if (passthroughThisFrame && !geometricInsideDeadzone) {
                    slot.passthroughUntilOutside = false;
                }

                if (opt.verbose && slot.active && slot.hasPosition) {
                    float sx = 0.0f;
                    float sy = 0.0f;
                    mapTouchToScreen(slot, xRange, yRange, opt, rect, false, &sx, &sy);
                    fprintf(stderr,
                            "slot=%d raw=(%d,%d) screen=(%.0f,%.0f) inside=%d passthrough=%d blocked=%d virtual=%d crossed=%d\n",
                            slotIndex, slot.x, slot.y, sx, sy,
                            geometricInsideDeadzone ? 1 : 0,
                            passthroughThisFrame ? 1 : 0,
                            slot.blockedByDeadzone ? 1 : 0,
                            slot.virtualActive ? 1 : 0,
                            crossedDeadzone ? 1 : 0);
                }

                if (grabbed && opt.emit && uinputFd >= 0) {
                    if (crossedDeadzone && slot.virtualActive) {
                        if (opt.logState || opt.verbose) {
                            fprintf(stderr, "cross deadzone slot=%d raw=(%d,%d)\n", slotIndex, slot.x, slot.y);
                        }
                        slot.virtualActive = false;
                        slot.virtualTrackingId = -1;
                        emitSlotUpFrame(uinputFd, slotIndex, hasAnyVirtualActiveSlot(slots));
                        virtualCurrentSlot = slotIndex;
                        slot.virtualActive = true;
                        slot.virtualTrackingId = nextTrackingId++;
                        emitSlotDownFrame(uinputFd, slotIndex, slot);
                        virtualCurrentSlot = slotIndex;
                        suppressSlots[slotIndex] = true;
                    } else if (insideDeadzone && !slot.blockedByDeadzone && slot.virtualActive) {
                        slot.virtualActive = false;
                        slot.virtualTrackingId = -1;
                        emitSlotUpFrame(uinputFd, slotIndex, hasAnyVirtualActiveSlot(slots));
                        virtualCurrentSlot = slotIndex;
                    } else if (!insideDeadzone && slot.blockedByDeadzone && slot.active) {
                        slot.virtualActive = true;
                        slot.virtualTrackingId = nextTrackingId++;
                        emitSlotDownFrame(uinputFd, slotIndex, slot);
                        virtualCurrentSlot = slotIndex;
                        suppressSlots[slotIndex] = true;
                    }
                }

                const bool endedWhileBlocked = !slot.active && slot.endedWhileBlocked;
                if (endedWhileBlocked) {
                    slot.blockedByDeadzone = false;
                    slot.endedWhileBlocked = false;
                } else if (insideDeadzone && !slot.blockedByDeadzone) {
                    if (opt.logState || opt.verbose) {
                        fprintf(stderr, "enter deadzone slot=%d raw=(%d,%d)\n", slotIndex, slot.x, slot.y);
                    }
                    slot.blockedByDeadzone = true;
                    slot.splitThisGesture = true;
                } else if (crossedDeadzone) {
                    if (opt.logState || opt.verbose) {
                        fprintf(stderr, "cross deadzone slot=%d raw=(%d,%d)\n", slotIndex, slot.x, slot.y);
                    }
                } else if (!insideDeadzone && slot.blockedByDeadzone && slot.active) {
                    if (opt.logState || opt.verbose) {
                        fprintf(stderr, "leave deadzone slot=%d raw=(%d,%d)\n", slotIndex, slot.x, slot.y);
                    }
                    slot.blockedByDeadzone = false;
                } else if (!slot.active && slot.blockedByDeadzone) {
                    slot.blockedByDeadzone = false;
                }

                if (slot.blockedByDeadzone || endedWhileBlocked) suppressSlots[slotIndex] = true;
                if (slot.active && !slot.blockedByDeadzone) hasVisibleTouch = true;
            }

            if (pendingEnable && !hasActiveSlots(slots)) {
                pendingEnable = false;
                activateDeadzone(&rect, &slots, inputFd, &grabbed, uinputFd, &nextTrackingId, xRange, yRange, opt, pendingEnableId);
                pendingEnableId.clear();
                pendingEnableSinceMs = 0;
                commitFramePositions(&slots);
                continue;
            }

            if (grabbed && opt.emit && uinputFd >= 0 && forwardingSynced && pendingFrame.size() > 0) {
                writeFilteredFrame(
                    uinputFd,
                    pendingFrame,
                    suppressSlots,
                    hasVisibleTouch,
                    &virtualTouchDown,
                    &filteredRealCurrentSlot,
                    &virtualCurrentSlot
                );

                for (const input_event& pending : pendingFrame) {
                    if (pending.type != EV_ABS) continue;
                    if (pending.code == ABS_MT_SLOT) {
                        if (pending.value >= 0 && pending.value < kMaxSlots) trackingScanSlot = pending.value;
                        continue;
                    }
                    if (pending.code != ABS_MT_TRACKING_ID || suppressSlots[trackingScanSlot]) continue;

                    SlotState& slot = slots[trackingScanSlot];
                    slot.virtualActive = pending.value >= 0;
                    slot.virtualTrackingId = pending.value;
                    if (pending.value < 0) {
                        slot.blockedByDeadzone = false;
                    }
                }
                pendingFrame.clear();
            } else if (grabbed && opt.emit && uinputFd >= 0) {
                virtualTouchDown = hasVisibleTouch;
            }
                commitFramePositions(&slots);
            }
        }

        if (commandReady) drainCommandWatcher(commandWatcherFd);
        const bool fallbackCommandReady = commandWatcherFd < 0 && !opt.commandPath.empty() &&
                                          commandNowMs - lastFallbackCommandCheckMs >= 250;
        if ((commandReady || fallbackCommandReady) && !opt.commandPath.empty()) {
            lastFallbackCommandCheckMs = commandNowMs;

            RectConfig diskConfig = rect;
            const bool runtimeEnabled = rect.enabled;
            if (loadConfigIfChanged(opt.configPath, &diskConfig, &configVersion, false)) {
                diskConfig.enabled = runtimeEnabled;
                std::string configReason;
                if (validateConfig(diskConfig, &configReason)) {
                    rect = diskConfig;
                } else if (opt.logState || opt.verbose) {
                    fprintf(stderr, "ignored invalid live config: %s\n", configReason.c_str());
                }
            }

            std::string fileCommand;
            if (readFile(opt.commandPath, &fileCommand) && fileCommand != lastFileCommand) {
                lastFileCommand = fileCommand;
                handleControlCommand(fileCommand, &rect, &slots, inputFd, uinputFd, &grabbed,
                                     &nextTrackingId, &pendingEnable, &pendingEnableId,
                                     &pendingEnableSinceMs, xRange, yRange, opt);
            }
        }

        if (pendingEnable && commandNowMs - pendingEnableSinceMs >= kPendingEnableTimeoutMs) {
            pendingEnable = false;
            rect.enabled = false;
            writeState(opt, "FAILED", "enable timed out waiting for all fingers up " + pendingEnableId);
            pendingEnableId.clear();
            pendingEnableSinceMs = 0;
        }

        if (g_uinputWriteFailed) break;
    }

    if (opt.logState || opt.verbose) {
        fprintf(stderr, "deadzone_daemon stopping\n");
    }
    releaseGrabIfNeeded(inputFd, &grabbed);
    destroyUinputDevice(uinputFd);
    if (commandWatcherFd >= 0) close(commandWatcherFd);
    close(inputFd);
    if (g_uinputWriteFailed) {
        writeState(opt, "FAILED", std::string("input proxy failed: ") + strerror(g_uinputWriteErrno));
        return 1;
    }
    writeState(opt, "STOPPED", "backend stopped");
    return 0;
}
