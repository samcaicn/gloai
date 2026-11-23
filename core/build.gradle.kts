import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // 沙箱路径契约测试放在 testFixtures,app 的 AndroidSandbox 也跑同一套
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

val traceSchemas by configurations.creating { isTransitive = false }
val extractTraceSchemas by tasks.registering(Sync::class) {
    from({ zipTree(traceSchemas.singleFile) }) {
        include("opentelemetry/proto/common/v1/common.proto", "opentelemetry/proto/resource/v1/resource.proto",
            "opentelemetry/proto/trace/v1/trace.proto", "opentelemetry/proto/collector/trace/v1/trace_service.proto")
    }
    into(layout.buildDirectory.dir("trace-schemas"))
}
sourceSets.main { proto.srcDir(extractTraceSchemas) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().configureEach { builtins { named("java") { option("lite") } } }
    }
}

dependencies {
    implementation(libs.protobuf.lite)
    traceSchemas(libs.otel.proto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // 测试里的 TLS 1.3 与导出密钥:用和 Android 平台同源的 TLS 实现,只进测试类路径
    testImplementation(libs.conscrypt.openjdk.uber)
    testFixturesImplementation(libs.junit)
}

// 运行时列不了资源目录:构建时扫 knowledge/apps、knowledge/sites、skills 的头部,校验后生成索引资源;校验不过构建失败
val knowledgeIndex by tasks.registering(JavaExec::class) {
    val resources = file("src/main/resources")
    val out = layout.buildDirectory.dir("generated/knowledge-index")
    inputs.dir(resources.resolve("knowledge")).withPropertyName("knowledge")
    inputs.dir(resources.resolve("skills")).withPropertyName("skills")
    outputs.dir(out)
    classpath = sourceSets.main.get().output.classesDirs + configurations.runtimeClasspath.get()
    mainClass.set("com.wangzan101.mika.core.learn.knowledge.KnowledgeIndex")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(resources.absolutePath, out.get().file("knowledge/index.json").asFile.absolutePath)
    })
}
sourceSets.main { resources.srcDir(knowledgeIndex) }

// README 里会变的清单(内置知识、服务商预设)从源头重新生成,见 testFixtures 里的 ReadmeSections;ReadmeSectionsTest 守着两边一致
val updateReadme by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "重新生成 README.md、docs/readme/README.zh-CN.md 里 generated 标记之间的段落"
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.docs.ReadmeSections")
    args(rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
}

// 桌面上对着 OpenAI 真实服务走一遍「用 ChatGPT 登录」(登录、列模型、Responses、工具调用、换新令牌),见 docs/dev/chatgpt-signin-check.md。
// 单独的源码集:不进 core 的产物,也不进 app
val chatgptCheck by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[chatgptCheck.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[chatgptCheck.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val chatgptSignInCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "用 App 的登录代码对着 OpenAI 走一遍 ChatGPT 登录;-Ptimeout=<秒> -Pmodel=<slug> -Pkeep"
    classpath = chatgptCheck.runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.model.chatgpt.check.ChatGptSignInCheckKt")
    args(listOfNotNull(
        providers.gradleProperty("timeout").orNull?.let { "--timeout=$it" },
        providers.gradleProperty("model").orNull?.let { "--model=$it" },
        if (providers.gradleProperty("keep").isPresent) "--keep" else null,
    ))
    outputs.upToDateWhen { false }
}

tasks.test {
    // 跨语言 CLI 契约由 cliTest 显式运行;纯 JVM 测试不要求电脑安装 Python。
    exclude("**/RemoteCliContractTest*")
    // 真网关跨语言测试显式运行,普通 JVM 测试不要求安装 Go。
    exclude("**/RelayGatewayIntegrationTest*")
    // testFixtures 会把 core 的 jar 放到测试类路径前面;让目录排在前面,按目录读资源的测试才找得到文件
    classpath = sourceSets.main.get().output + classpath
    // TaskSetTest 读仓库根目录的评测任务集
    inputs.dir(rootProject.file("eval")).withPropertyName("evalTasks")
    // DocsConsistencyTest、ReadmeTranslationsTest 读仓库的文档(根目录 README.md,docs/readme/ 下各语言的译文在 docs 里),并对照 app 的源码;文档或 app 源码变了要重跑
    inputs.files(rootProject.file("README.md"), rootProject.file("tools/sandbox/README.md")).withPropertyName("docsFiles")
    inputs.dir(rootProject.file("docs")).withPropertyName("docsDir")
    inputs.dir(rootProject.file("app/src")).withPropertyName("appSources")
    // updateReadme 先改写 README,同一次构建里测试要读改写后的
    mustRunAfter(updateReadme)
    // 循环场景的消息序列金样;-PupdateSnapshots 时重新生成(见 LoopSnapshot)
    systemProperty("mika.snapshotDir", file("src/test/resources/loop-snapshots").absolutePath)
    // 提示词表面(系统提示词、工具定义、反馈文字)的金样;同样用 -PupdateSnapshots 重新生成(见 PromptSurface)
    systemProperty("mika.promptSurfaceDir", file("src/test/resources/prompt-surface").absolutePath)
    val updateSnapshots = project.hasProperty("updateSnapshots")
    inputs.property("updateSnapshots", updateSnapshots)
    if (updateSnapshots) {
        systemProperty("mika.updateSnapshots", "true")
        outputs.upToDateWhen { false }
    }
}

val relayGatewayBinary = tasks.register<Exec>("relayGatewayBinary") {
    group = "verification"
    description = "构建自托管远程 MCP 网关,供跨语言集成测试使用"
    val output = layout.buildDirectory.file("relay-check/mika-relay")
    workingDir(rootProject.file("companions/relay"))
    inputs.files(fileTree(rootProject.file("companions/relay")) { include("*.go", "go.mod", "go.sum", "web/**") })
    outputs.file(output)
    environment("GOCACHE", rootProject.layout.buildDirectory.dir("go-cache").get().asFile.absolutePath)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine("go", "build", "-trimpath", "-o", output.get().asFile.absolutePath, ".")
}

tasks.register<Test>("relayTest") {
    group = "verification"
    description = "真实 Go 网关连接 Kotlin 手机隧道,验证远程 CLI / MCP 全链路"
    dependsOn(relayGatewayBinary)
    inputs.file(layout.buildDirectory.file("relay-check/mika-relay")).withPropertyName("relayGatewayBinary")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/RelayGatewayIntegrationTest*")
    systemProperty("mika.relayBinary", layout.buildDirectory.file("relay-check/mika-relay").get().asFile.absolutePath)
}

val cliContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "真实 Python CLI 对接 Kotlin 任务 API,核对只走 /api/v1"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.main.get().output + sourceSets.test.get().runtimeClasspath
    include("**/RemoteCliContractTest*")
    inputs.dir(rootProject.file("companions/mika/scripts"))
    systemProperty("mika.python", providers.gradleProperty("mikaPython").getOrElse("python3"))
}

// 要 Python 3.10+,不挂在 check 上;需要时显式运行 ./gradlew :core:cliTest
val cliTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "CLI 进程、假 ADB、独立 Skill 与真实 HTTP 契约测试 (Python 3.10+)"
    dependsOn(cliContractTest)
    workingDir(rootProject.projectDir)
    commandLine(providers.gradleProperty("mikaPython").getOrElse("python3"), "-m", "unittest", "discover", "-s", "companions/mika/tests", "-v")
}
