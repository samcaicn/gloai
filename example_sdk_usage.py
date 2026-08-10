"""
使用示例：如何使用 Shijiback Python SDK
"""

import asyncio
from server.sdk import ClawClient, generate_device_fingerprint


async def main():
    # 1. 生成设备指纹 (本地生成，无需联网)
    fingerprint = generate_device_fingerprint(
        platform="linux",
        arch="x86_64",
        language="zh-CN",
        timezone="Asia/Shanghai",
        hardware_serial="ABC123",
    )
    print(f"Device Fingerprint: {fingerprint}")

    # 2. 创建客户端并注册设备
    async with ClawClient("https://api.example.com") as client:
        # 注册指纹获取 device_token
        resp = await client.register_fingerprint(fingerprint)
        print(f"Register Response: {resp}")

        if resp.get("success") and resp.get("device_token"):
            device_token = resp["device_token"]
            print(f"Device Token: {device_token}")

            # 3. 通过分享码绑定到租户
            bind_resp = await client.bind(join_code="12345678")
            print(f"Bind Response: {bind_resp}")

            # 4. 轮询绑定状态
            if bind_resp.get("request_id"):
                status_resp = await client.bind_status(bind_resp["request_id"])
                print(f"Bind Status: {status_resp}")

            # 5. 调用 MCP action
            result = await client.call_mcp("skill.list")
            print(f"Skill List: {result}")


if __name__ == "__main__":
    asyncio.run(main())