// WeAuto 运行时完整性模块（L2 反篡改 / 反马甲包核心）。
//
// 全部关键判定都在 native 层做，目的：让"打开 MT 管理器改两下就重签分发"的
// 低端面甲包流水线失效，并让内核级重打包也能被一道绊线拦一下。
//
// 设计要点：
//  - 官方签名证书指纹（SHA-256）以「异或混淆 + 分段常量」形式只存在于本 .so，
//    不在 Java 层出现，smali 搜不到明文哈希。
//  - 比对在 native 完成：重打包必然换证书 → 运行时指纹≠官方 → nativeSignatureOfficial 返回 false。
//  - 注入扫描读 /proc/self/maps 与 /proc/self/status，识别 frida / Xposed / LSPatch /
//    APatch / Substrate / Magisk 与调试器，正是免重打包注入（LSPatch / NPatch / APatch）
//    的标准绕过手法。
//  - HMAC 密钥同样混淆存储，仅在签名时短暂还原到内存，不落盘。
//
// 注意：纯客户端校验可被内核级 root 绕过（APatch 即为一例）。本模块是「绊线」，
// 真正的「墙」在服务端（L3：weauto-license Worker 校验 X-Sig-Fp）。

#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>

#define TAG "JEVASSIST_INTEGRITY"

// ---- 官方证书指纹（混淆存储，运行时还原）----
// 还原：EXPECTED[i] = EXPECTED_XOR[i] ^ FP_MASK[i]
static const uint8_t EXPECTED_XOR[32] = { 170, 82, 110, 120, 80, 45, 65, 73, 55, 89, 154, 7, 2, 8, 140, 80, 14, 93, 18, 90, 209, 64, 117, 95, 97, 199, 29, 243, 234, 229, 24, 179 };
static const uint8_t FP_MASK[32]      = { 119, 69, 65, 85, 84, 48, 95, 49, 110, 116, 51, 103, 114, 49, 116, 121, 95, 77, 52, 115, 107, 95, 118, 51, 114, 121, 95, 108, 48, 110, 103, 95 };

// ---- HMAC 密钥（混淆存储，运行时还原）----
static const uint8_t HMACKEY_XOR[32] = { 193, 75, 75, 53, 105, 75, 70, 170, 167, 186, 235, 107, 133, 26, 155, 204, 124, 253, 3, 240, 100, 113, 233, 162, 242, 177, 176, 209, 54, 134, 24, 138 };
static const uint8_t HMAC_MASK[32]   = { 104, 77, 52, 99, 95, 75, 51, 121, 95, 48, 98, 102, 117, 53, 99, 56, 95, 109, 52, 115, 107, 95, 100, 49, 102, 102, 51, 114, 51, 110, 116, 95 };

// ============================== SHA-256 ==============================

static const uint32_t SHA_K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

static uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

static void sha256(const uint8_t* data, size_t len, uint8_t out[32]) {
    uint32_t h[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
                     0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    // 预处理：补位 + 长度（big-endian 位长）
    size_t newlen = ((len + 8) / 64 + 1) * 64;
    uint8_t* msg = (uint8_t*)__builtin_alloca(newlen);
    memcpy(msg, data, len);
    memset(msg + len, 0, newlen - len);
    msg[len] = 0x80;
    uint64_t bitlen = (uint64_t)len * 8;
    for (int i = 0; i < 8; i++)
        msg[newlen - 1 - i] = (uint8_t)(bitlen >> (8 * i));

    uint32_t w[64];
    for (size_t off = 0; off < newlen; off += 64) {
        for (int i = 0; i < 16; i++) {
            w[i] = ((uint32_t)msg[off + i*4] << 24) | ((uint32_t)msg[off + i*4+1] << 16) |
                   ((uint32_t)msg[off + i*4+2] << 8)  | ((uint32_t)msg[off + i*4+3]);
        }
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr(w[i-15],7) ^ rotr(w[i-15],18) ^ (w[i-15] >> 3);
            uint32_t s1 = rotr(w[i-2],17) ^ rotr(w[i-2],19) ^ (w[i-2] >> 10);
            w[i] = w[i-16] + s0 + w[i-7] + s1;
        }
        uint32_t a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rotr(e,6) ^ rotr(e,11) ^ rotr(e,25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t t1 = hh + S1 + ch + SHA_K[i] + w[i];
            uint32_t S0 = rotr(a,2) ^ rotr(a,13) ^ rotr(a,22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + maj;
            hh=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
        }
        h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hh;
    }
    for (int i = 0; i < 8; i++) {
        out[i*4]   = (uint8_t)(h[i] >> 24);
        out[i*4+1] = (uint8_t)(h[i] >> 16);
        out[i*4+2] = (uint8_t)(h[i] >> 8);
        out[i*4+3] = (uint8_t)(h[i]);
    }
}

// 把 32 字节哈希转成小写十六进制 jstring
static jstring to_hex(JNIEnv* env, const uint8_t digest[32]) {
    static const char* hexd = "0123456789abcdef";
    char buf[65];
    for (int i = 0; i < 32; i++) {
        buf[i*2]   = hexd[(digest[i] >> 4) & 0xf];
        buf[i*2+1] = hexd[digest[i] & 0xf];
    }
    buf[64] = '\0';
    return env->NewStringUTF(buf);
}

// ============================== 注入扫描 ==============================

// 逐行读 /proc/self/maps，命中已知框架子串即置位。
static int scan_maps() {
    int mask = 0;
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return mask;
    char buf[65536];
    ssize_t total = 0, n;
    while (total < (ssize_t)sizeof(buf) - 1 &&
           (n = read(fd, buf + total, sizeof(buf) - 1 - total)) > 0) {
        total += n;
    }
    close(fd);
    if (total <= 0) return mask;
    buf[total] = '\0';
    char* p = buf;
    for (char* nl = strchr(p, '\n'); nl != nullptr; nl = strchr(p, '\n')) {
        *nl = '\0';
        if (strstr(p, "frida")     != nullptr) mask |= (1 << 0);
        if (strstr(p, "xposed")    != nullptr) mask |= (1 << 1);
        if (strstr(p, "lspatch")   != nullptr) mask |= (1 << 2);
        if (strstr(p, "apatch")    != nullptr) mask |= (1 << 3);
        if (strstr(p, "substrate") != nullptr) mask |= (1 << 4);
        if (strstr(p, "magisk")    != nullptr) mask |= (1 << 5);
        p = nl + 1;
    }
    return mask;
}

// 读 /proc/self/status 的 TracerPid，非 0 表示被调试。
static int scan_debugger() {
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return 0;
    char buf[1024];
    ssize_t n = read(fd, buf, sizeof(buf)-1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    const char* key = strstr(buf, "TracerPid:");
    if (key == nullptr) return 0;
    int pid = 0;
    // "TracerPid:\t123"
    const char* v = key + strlen("TracerPid:");
    while (*v == ' ' || *v == '\t') v++;
    while (*v >= '0' && *v <= '9') { pid = pid * 10 + (*v - '0'); v++; }
    return pid != 0 ? (1 << 6) : 0;
}

// ============================== JNI ==============================

extern "C" {

// 返回签名证书（DER 字节）的 SHA-256 十六进制串，供 L3 客户端头 X-Sig-Fp 使用。
JNIEXPORT jstring JNICALL
Java_com_jev_probe_core_Integrity_nativeCertFingerprint(JNIEnv* env, jobject, jbyteArray cert) {
    if (cert == nullptr) return env->NewStringUTF("");
    jsize len = env->GetArrayLength(cert);
    if (len <= 0) return env->NewStringUTF("");
    jbyte* bytes = env->GetByteArrayElements(cert, nullptr);
    uint8_t digest[32];
    sha256((const uint8_t*)bytes, (size_t)len, digest);
    env->ReleaseByteArrayElements(cert, bytes, JNI_ABORT);
    return to_hex(env, digest);
}

// 运行时证书指纹是否等于官方（混淆比对）。true=官方签名，false=被重签/马甲包。
JNIEXPORT jboolean JNICALL
Java_com_jev_probe_core_Integrity_nativeSignatureOfficial(JNIEnv* env, jobject, jbyteArray cert) {
    if (cert == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(cert);
    if (len <= 0) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(cert, nullptr);
    uint8_t digest[32];
    sha256((const uint8_t*)bytes, (size_t)len, digest);
    env->ReleaseByteArrayElements(cert, bytes, JNI_ABORT);

    uint8_t expected[32];
    for (int i = 0; i < 32; i++) expected[i] = EXPECTED_XOR[i] ^ FP_MASK[i];

    // 常量时间比较，避免时序侧信道
    uint8_t diff = 0;
    for (int i = 0; i < 32; i++) diff |= (digest[i] ^ expected[i]);
    return diff == 0 ? JNI_TRUE : JNI_FALSE;
}

// 注入/调试位掩码：bit0 frida,1 xposed,2 lspatch,3 apatch,4 substrate,5 magisk,6 debugger
JNIEXPORT jint JNICALL
Java_com_jev_probe_core_Integrity_nativeInjectionMask(JNIEnv*, jobject) {
    return (jint)(scan_maps() | scan_debugger());
}

// 还原 HMAC 密钥（32 字节），仅在签名时短暂使用，不落盘。
JNIEXPORT jbyteArray JNICALL
Java_com_jev_probe_core_Integrity_nativeHmacKey(JNIEnv* env, jobject) {
    uint8_t key[32];
    for (int i = 0; i < 32; i++) key[i] = HMACKEY_XOR[i] ^ HMAC_MASK[i];
    jbyteArray out = env->NewByteArray(32);
    env->SetByteArrayRegion(out, 0, 32, (const jbyte*)key);
    return out;
}

}
