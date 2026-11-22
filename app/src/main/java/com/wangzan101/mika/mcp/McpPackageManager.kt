package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths

/**
 * stdio 包的安装与缓存:安装到工作区受控缓存,返回安装后可执行文件所在目录的 argv 前缀,
 * 不执行 MCP 服务本身。调用方把前缀与预设声明的 argv 拼成最终命令。
 *
 * 安全:未确认或未缓存时不下载;安装只在 [confirmed] 时进行;成功后原子写入成功标记,中断/失败不留标记、
 * 可重试;秘密只经 Task 5 的环境变量进进程,绝不进入这里生成的 argv。
 */
interface McpPackageProvisioner {
    /**
     * 确保 [spec] 已装进共享包缓存的 [workspace] 视图,返回 argv 前缀(单元素:可执行文件目录,如
     * `/opt/mika/mcp-cache/npm-<名>-<版本>/node_modules/.bin`)。未缓存且 [confirmed] 为 false 时抛
     * `mcp_package_consent`;下载/安装失败抛可重试的 `mcp_package_install_failed`。
     */
    suspend fun prepare(workspace: String, spec: McpPackageSpec, confirmed: Boolean): List<String>
}

class McpPackageManager(
    private val port: SandboxPort,
    private val networkAllowed: () -> Boolean = { port.networkAllowed() },
) : McpPackageProvisioner {

    override suspend fun prepare(workspace: String, spec: McpPackageSpec, confirmed: Boolean): List<String> {
        val cache = cacheDir(spec)
        val marker = "$cache/.ok"
        if (probe(workspace, listOf("test", "-f", marker))) return prefix(spec, cache)
        if (!confirmed) throw consentError(spec)
        requireSupported(spec)
        if (!networkAllowed()) {
            throw SandboxError("mcp_package_network", "The sandbox has no network access, so this package cannot be installed. Turn on network access and try again.", recoverable = true)
        }
        val result = port.run(workspace, installArgv(spec, cache), null, INSTALL_TIMEOUT_MS) { }
        if (result.exitCode != 0) {
            runCatching { port.run(workspace, listOf("rm", "-rf", cache), null, CLEANUP_TIMEOUT_MS) {} }
            throw SandboxError(
                "mcp_package_install_failed",
                "Installing ${spec.name} ${spec.version} failed: " + result.output.trim().takeLast(400),
                recoverable = true,
            )
        }
        port.run(workspace, listOf("mkdir", "-p", cache), null, CLEANUP_TIMEOUT_MS) {}
        port.run(workspace, listOf("touch", marker), null, CLEANUP_TIMEOUT_MS) {}
        return prefix(spec, cache)
    }

    private suspend fun probe(workspace: String, argv: List<String>): Boolean =
        port.run(workspace, argv, null, PROBE_TIMEOUT_MS) {}.exitCode == 0

    private fun prefix(spec: McpPackageSpec, cache: String): List<String> = when (spec.ecosystem) {
        "npm" -> listOf("$cache/node_modules/.bin")
        "pypi" -> listOf("$cache/bin")
        else -> emptyList()
    }

    private fun installArgv(spec: McpPackageSpec, cache: String): List<String> = when (spec.ecosystem) {
        "npm" -> listOf("npm", "install", "${spec.name}@${spec.version}", "--ignore-scripts", "--no-audit", "--no-fund", "--no-save", "--prefix", cache)
        "pypi" -> listOf("python3", "-m", "pip", "install", "${spec.name}==${spec.version}", "--only-binary=:all:", "--prefix", cache)
        else -> emptyList()
    }

    private fun requireSupported(spec: McpPackageSpec) {
        if (spec.ecosystem !in setOf("npm", "pypi")) {
            throw SandboxError("mcp_package_unsupported", "Packages from ${spec.ecosystem} are not supported for stdio servers.", recoverable = false)
        }
    }

    private fun consentError(spec: McpPackageSpec): SandboxError = SandboxError(
        "mcp_package_consent",
        "This stdio server needs to install ${spec.name} ${spec.version} from ${spec.source}. Confirm to download and install it.",
        recoverable = true,
    )

    private fun cacheDir(spec: McpPackageSpec): String {
        val safe = spec.name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("")
        return "${SandboxPaths.MCP_CACHE}/${spec.ecosystem}-$safe-${spec.version}"
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 15_000L
        const val CLEANUP_TIMEOUT_MS = 30_000L
        const val INSTALL_TIMEOUT_MS = 5 * 60_000L
    }
}
