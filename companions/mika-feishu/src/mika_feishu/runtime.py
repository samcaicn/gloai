"""Official Feishu SDK transport; SDK event thread never waits on a phone task."""
import json
import logging
import threading
import uuid


class FeishuSender:
    def __init__(self, config):
        import lark_oapi as lark
        self.client = (lark.Client.builder().app_id(config["feishu_app_id"])
                       .app_secret(config["feishu_app_secret"]).timeout(15)
                       .log_level(lark.LogLevel.ERROR).build())
        self.lock = threading.Lock()

    def __call__(self, chat, text, key):
        from lark_oapi.api.im.v1 import CreateMessageRequest, CreateMessageRequestBody
        # Limit UTF-8 bytes per message; deterministic UUID avoids duplicate notifications
        # when the phone read is retried. Never use an outgoing message as an incoming task.
        chunks, current = [], ""
        for char in text:
            if len((current + char).encode()) > 6000:
                chunks.append(current)
                current = ""
            current += char
        chunks.append(current)
        sent = {}
        for index, chunk in enumerate(chunks):
            body = (CreateMessageRequestBody.builder().receive_id(chat).msg_type("text")
                    .content(json.dumps({"text": chunk}, ensure_ascii=False))
                    .uuid(str(uuid.uuid5(uuid.NAMESPACE_URL, key + ":" + str(index)))).build())
            request = (CreateMessageRequest.builder().receive_id_type("chat_id")
                       .request_body(body).build())
            with self.lock:
                response = self.client.im.v1.message.create(request)
            if not response.success():
                raise RuntimeError("feishu_send_failed:" + str(response.code))
            if response.data and response.data.message_id:
                sent[response.data.message_id] = int(response.data.create_time or 0)
        return sent


def check_feishu(config):
    """Authentication only: never send a message or expose the returned tenant token."""
    import requests
    try:
        response = requests.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                                 json={"app_id": config["feishu_app_id"], "app_secret": config["feishu_app_secret"]},
                                 timeout=15, allow_redirects=False)
        if response.status_code != 200:
            return {"ok": False, "code": "http_" + str(response.status_code)}
        data = response.json()
        return {"ok": data.get("code") == 0 and bool(data.get("tenant_access_token")),
                "code": data.get("code", "invalid_response")}
    except (requests.RequestException, ValueError):
        return {"ok": False, "code": "connection_or_response_error"}


def run(config, api, store):
    import lark_oapi as lark
    from .bridge import Bridge
    sender = FeishuSender(config)
    bridge = Bridge(api, store, sender, config.get("allowed_open_ids", []))
    pair_code = bridge.enable_pairing()
    if pair_code:
        print("First pairing: within 10 minutes, send this in a private chat with your bot:", flush=True)
        print("/pair " + pair_code, flush=True)
        print("Only the paired user may control the phone. Do not share this code.", flush=True)
    else:
        print("Loaded allowed users; accepting their private text messages only.", flush=True)
    stop = threading.Event()

    def work():
        try:
            bridge.recover()
        except Exception as exc:
            logging.error("Recovery notification failed (%s)", type(exc).__name__)
        while not stop.is_set():
            try:
                if not bridge.work_once():
                    stop.wait(0.2)
            except Exception as exc:
                logging.error("Message processing failed (%s); no automatic write retry", type(exc).__name__)
                stop.wait(1)

    def poll():
        while not stop.wait(5):
            try:
                bridge.poll_once()
            except Exception as exc:
                logging.error("Status/notification check failed (%s)", type(exc).__name__)

    def incoming(data):
        try:
            bridge.receive(json.loads(lark.JSON.marshal(data)))
        except Exception as exc:
            logging.error("Event could not be persisted (%s)", type(exc).__name__)
            raise  # Do not acknowledge an event we failed to store.

    handler = (lark.EventDispatcherHandler.builder("", "")
               .register_p2_im_message_receive_v1(incoming).build())
    worker = threading.Thread(target=work, daemon=True)
    monitor = threading.Thread(target=poll, daemon=True)
    worker.start()
    monitor.start()
    print("Starting Feishu WebSocket; keep this process and computer online. Ctrl+C stops the connector, not phone tasks.", flush=True)
    try:
        client = lark.ws.Client(config["feishu_app_id"], config["feishu_app_secret"],
                                event_handler=handler, log_level=lark.LogLevel.ERROR)
        client.start()
    finally:
        stop.set()
        worker.join(timeout=15)
        monitor.join(timeout=15)
