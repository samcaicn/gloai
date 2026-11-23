package com.wangzan101.mika.core.cap.wechat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class WechatApiTest {
    private fun server(block: (MockWebServer, WechatApi) -> Unit) {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val api = WechatApi(OkHttpClient(), "0.1.35", server.url("/").toString())
            block(server, api)
        }
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun qrLoginCarriesBotTypeAndRestoresAccount() = server { server, api ->
        server.enqueue(response("""{"qrcode":"qr-key","qrcode_img_content":"https://example.test/qr"}"""))
        val start = api.startLogin()
        assertEquals("qr-key", start.qrcode)
        assertEquals("3", server.takeRequest(1, TimeUnit.SECONDS)?.requestUrl?.queryParameter("bot_type"))

        server.enqueue(response("""{"status":"confirmed","bot_token":"secret","ilink_bot_id":"bot","baseurl":"https://example.test","ilink_user_id":"owner"}"""))
        val account = api.pollLogin("qr-key").account()
        assertNotNull(account)
        assertEquals("owner", account?.ownerId)
        assertEquals("secret", account?.token)
    }

    @Test fun updatesAcceptNumericMessageIdAndIgnoreUnsupportedItems() = server { server, api ->
        server.enqueue(response("""{
            "get_updates_buf":"next",
            "msgs":[{"message_id":123456,"from_user_id":"owner","message_type":1,
              "context_token":"context","item_list":[{"type":1,"text_item":{"text":"打开浏览器"}}]},
              {"message_id":"other","from_user_id":"owner","message_type":1,"item_list":[{"type":3}]}]
        }"""))
        val updates = api.updates(WechatAccount("token", server.url("/").toString(), "bot", "owner"), "old")
        assertEquals("next", updates.cursor)
        assertEquals("123456", updates.msgs[0].messageId)
        assertEquals("打开浏览器", updates.msgs[0].userText())
        assertEquals(null, updates.msgs[1].userText())
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("Bearer token", request?.getHeader("Authorization"))
        assertEquals("ilink_bot_token", request?.getHeader("AuthorizationType"))
    }

    @Test fun sendTextUsesDeliveryIdAndContextToken() = server { server, api ->
        server.enqueue(response("""{"ret":0}"""))
        api.sendText(WechatAccount("token", server.url("/").toString(), "bot", "owner"),
            "owner", "result:run-1", "完成了", "context")
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        val body = Json.parseToJsonElement(request?.body?.readUtf8().orEmpty()).jsonObject
        val message = body.getValue("msg").jsonObject
        assertEquals("result:run-1", message.getValue("client_id").jsonPrimitive.content)
        assertEquals("context", message.getValue("context_token").jsonPrimitive.content)
        assertEquals("owner", message.getValue("to_user_id").jsonPrimitive.content)
        assertEquals("/ilink/bot/sendmessage", request?.path)
    }
}
