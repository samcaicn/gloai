fn main() {
    // 防破解（weauto 移植）：这两个编译期变量由 CI 构建步注入。
    // 声明 rerun-if-env-changed，保证注入值变化时触发重编译。
    println!("cargo:rerun-if-env-changed=BBT_RELEASE");
    println!("cargo:rerun-if-env-changed=BBT_BUILD_SECRETS");
    tauri_build::build()
}
