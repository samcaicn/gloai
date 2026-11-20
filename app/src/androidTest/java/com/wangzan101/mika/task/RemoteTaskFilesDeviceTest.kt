package com.wangzan101.mika.task

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.remote.PendingUploads
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.mcp.McpServerState
import com.wangzan101.mika.ui.MainActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

/** Uses the production authenticated listener, task service, inbox and MediaStore with a local scripted model. */
@RunWith(AndroidJUnit4::class)
class RemoteTaskFilesDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun computerCanDownloadTheExactFileExportedByItsTurn() {
        val app = ApplicationProvider.getApplicationContext<MikaApp>()
        val usb = app.mcp.usb.enabled.value
        val conversation = "mcp-" + UUID.randomUUID()
        val model = ScriptedModel().apply { intent = "ACT" }
        val client = OkHttpClient()
        val csv = "SKU,数量\n".repeat(40_000).toByteArray()
        val args = InstrumentationRegistry.getArguments()
        val driverId = args.getString("browserDriver")?.let(UUID::fromString)
        val receipt = File(app.cacheDir, "desktop-file-test-$driverId")
        var usedConversation = conversation
        var exported: Uri? = null
        try {
            testTasks.modelFactory = { _, _ -> model }
            testTasks.lightModelFactory = { _, _ -> null }
            testTasks.visionModelFactory = { _, _ -> null }
            receipt.delete()
            if (!usb) ui.runOnIdle { app.mcp.setUsbEnabled(true) }
            ui.waitUntil(20_000) { app.mcp.state.value is McpServerState.Listening }
            val port = (app.mcp.state.value as McpServerState.Listening).port
            fun request(path: String, body: JSONObject? = null, status: Int? = null): JSONObject {
                val builder = Request.Builder().url("http://127.0.0.1:$port/api/v1$path")
                    .header("Authorization", "Bearer " + app.mcp.usb.token()!!)
                if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
                return client.newCall(builder.build()).execute().use { response ->
                    val json = JSONObject(response.body!!.string())
                    if (status == null) assertTrue("$path -> $json", response.isSuccessful)
                    else assertEquals("$path -> $json", status, response.code)
                    json
                }
            }
            val task = if (driverId != null) {
                var submitted: RemoteTask? = null
                ui.waitUntil(120_000) {
                    submitted = app.remoteTasks.list(50).find { AttachmentLines.words(it.goal).endsWith(driverId.toString()) }
                    submitted != null
                }
                checkNotNull(submitted).also { usedConversation = it.conversation }.id
            } else {
                val id = request("/uploads", JSONObject().put("size", csv.size).put("name", "清单.csv")).getString("upload_id")
                for (pos in csv.indices step PendingUploads.CHUNK_BYTES) {
                    val part = csv.copyOfRange(pos, minOf(csv.size, pos + PendingUploads.CHUNK_BYTES))
                    request("/uploads/$id", JSONObject().put("offset", pos).put("data", Base64.getEncoder().encodeToString(part)))
                }
                val requestId = UUID.randomUUID().toString()
                val body = JSONObject().put("goal", "请导出附件").put("conversation", conversation)
                    .put("request_id", requestId).put("attachments", JSONArray().put(id))
                val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                try {
                    val results = pool.invokeAll(List(2) {
                        java.util.concurrent.Callable { request("/tasks", body).getString("task_id") }
                    })
                    assertEquals(listOf(requestId, requestId), results.map { it.get() })
                } finally { pool.shutdownNow() }
                request("/tasks", JSONObject(body.toString()).put("attachments", JSONArray()), 409)
                request("/tasks", JSONObject(body.toString()).put("attachments", JSONArray().put(UUID.randomUUID().toString())), 409)
                requestId
            }
            val inputs = AttachmentLines.parse(app.remoteTasks.get(task)!!.goal).files
            val input = inputs.first().first
            val workspace = SandboxDirs(app).workspace(WorkspaceKey.Conversation(usedConversation))
            assertArrayEquals(csv, File(workspace, input.removePrefix("/workspace/")).readBytes())
            if (driverId != null) {
                assertEquals(2, inputs.size)
                val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNwyzvxHwAE9gJ8c7jPEgAAAABJRU5ErkJggg==")
                val pasted = File(workspace, inputs[1].first.removePrefix("/workspace/")).readBytes()
                assertArrayEquals(png, pasted)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(pasted, 0, pasted.size)
                assertNotNull("Pasted attachment must decode as an image", bitmap)
                assertEquals(1, bitmap.width)
                bitmap.recycle()
            }
            model.awaitCall()
            model.feed("share_file", JSONObject().put("path", input).put("name", "核对结果.csv").toString())
            val reply = model.awaitCall()
            assertTrue("share_file did not export: " + reply.messages.last(), app.remoteTasks.get(task)!!.files.isNotEmpty())
            model.answer("文件已经导出")
            ui.waitUntil(30_000) { request("/tasks/$task").getString("status") == "done" }
            val file = request("/tasks/$task").getJSONArray("files").getJSONObject(0)
            val result = ByteArrayOutputStream()
            while (result.size() < file.getLong("size")) {
                val part = request("/tasks/$task/files/" + file.getString("id") + "?offset=" + result.size())
                val bytes = Base64.getDecoder().decode(part.getString("data"))
                assertTrue(bytes.isNotEmpty())
                result.write(bytes)
            }
            assertArrayEquals(csv, result.toByteArray())
            ui.waitUntil(20_000) { app.conversations.records(usedConversation).any { it.second.isNotEmpty() } }
            exported = Uri.parse(app.conversations.records(usedConversation).last().second.first().uri)
            if (driverId != null) ui.waitUntil(120_000) { receipt.exists() }
        } finally {
            if (!usb) ui.runOnIdle { app.mcp.setUsbEnabled(false) }
            testTasks.modelFactory = TaskEnvironment.defaultModelFactory
            testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
            testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
            receipt.delete()
            exported?.let { app.contentResolver.delete(it, null, null) }
            app.conversations.delete(usedConversation)
            SandboxDirs(app).workspace(WorkspaceKey.Conversation(usedConversation)).deleteRecursively()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
