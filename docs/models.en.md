# Models and sign-in

[简体中文](models.md) | English

Mika doesn't come with a model; it uses a model provider you choose. Enter an API key, or sign in with an account. The main model must support tool calling.

## Add a model

Open "Models" (模型) in Settings (the app also walks you through this on first launch). Presets for many common providers are built in; the list is under "Supported model providers" in the [README](../README.md). For any other provider pick Custom (自定义), which takes any OpenAI-compatible, Anthropic, Gemini or OpenAI Responses endpoint.

1. Add a provider: pick a preset, or pick Custom and enter the endpoint URL; enter your API key. For servers you run yourself, such as Ollama or LM Studio, the address is prefilled with the default port; change it to that machine’s address, and the key is optional.
2. Tap "Test and save" (测试并保存). When you connect your first provider, a main model is picked automatically. If the provider has no model list, the list lacks the model you want, or listing fails, tap "Enter a model name yourself", type the model ID from the provider's docs and tap "Test and save" again: only that model gets a test call, and the provider is saved only if it works. When picking a role you can also type a model name under any provider; typed models are tagged "Typed in". If the provider just will not connect and you want it saved anyway, tap "Don’t test, save anyway": it saves only the address and key, without listing models or making a test call. Such a provider is marked "not tested"; from its page you can test it again or refresh to list its models. An untested model never becomes the main model on its own: picking one as the main model on that provider tests it first, and if a task fails on a model error while the main model is still untested, the message points you to “Test again” on that provider.
3. The main model must support tool calling. Optional: a vision model (understands the UI and finds buttons in screenshots), a light model (anything cheap and fast; handles small jobs like judging task outcomes and extracting memories) and speech to text (see below).

The first three roles: the **main model** (does the work; must support tool calling), the **vision model** (describes the UI and locates things in screenshots; off by default on a fresh install, or choose "use main model"), and the **light model** (small jobs like judging outcomes, extracting memories and understanding intent; defaults to the main model). For exactly what "Test and save" tries and where the capability tags on the model list come from, see [model configuration details](../.github/CONTRIBUTING.md#model-configuration-details) in the contributing guide.

Whether a model can see images: if the provider's model list says, Mika goes by that; if it doesn't (many relays and self-hosted services don't), the preset's initial value applies, and a custom provider is assumed not to see. Tap a model on the provider's page and the sheet has a "Can see images" switch: what you set wins, even over the list. When the main model can see, screenshots go straight to it instead of costing a separate vision call. Picking the main model itself as the vision model (or choosing "use main model") also counts as saying the main model can see; switching away undoes it.

## Check task cost

When using the official DeepSeek API, turn on **Show task cost** under **Settings › Task running**. After a new task ends, the estimate appears in Chinese yuan next to the token count. Mika uses the official price table and the usage recorded for the task. It shows a range during possible weekday peak hours and marks estimates as partial when some requests cannot be priced. Check your DeepSeek bill for the final charge.

## Jev decision model (optional)

Configure it separately in **Settings › Models › Decision model · Jev**. It is off by default and does not change your main, vision or light model selections.

| Provider | Complete request URL | Default model ID |
|---|---|---|
| TypeSafe | `https://api.typesafe.ai/v1/systemone` | `jev-1.14.0` |
| OpenRouter | `https://openrouter.ai/api/v1/systemone` | `typesafe/jev-1.14` |
| Custom | A complete System One-compatible endpoint | As specified by the provider |

Enter that provider's API key, enable the switch and choose **Save**. Changes apply to the next task. Selecting a different provider preset clears the previous key. **Test connection** classifies a fixed greeting without sending conversations or screenshots, saving settings, or enabling the service. The provider may charge for this test. Custom endpoints require HTTPS, except for local or LAN services; a chat `/chat/completions` endpoint is not compatible.

When enabled, Jev classifies intent at the chat entry point (discussion, reading or action), and selects saved routes when experience replay is enabled. Intent classification sends the current request and a small amount of recent conversation. Route selection sends the request, candidate templates and parameters extracted from the user's exact words. The API key and settings are encrypted on the phone. The main model still plans, generates text and calls tools; Jev never operates the phone directly or replaces confirmation gates.

Missing candidates, ambiguous parameters, low probabilities, errors and timeouts fall back to the existing model flow. A confident choice not to reuse a route skips replay and lets the main model plan. Disable and save to stop Jev calls in new tasks; tasks already running keep their starting configuration. Usage is recorded by model name, and `jev_intent` / `jev_route` run events identify selection, skipped replay or fallback. This version does not use images, Box grounding, per-step UI action selection or memory generation.

## Speech to text

The fourth role, used when you hold to talk; pick it under Settings › Models › Speech to text. By default it uses the phone's built-in speech recognition, which shows words as you speak and costs nothing. If the phone has none, or it is not accurate enough, pick a provider's speech model: it transcribes after you finish speaking and uses your quota. The list only shows models whose names look like speech models; for anything else, type the model name in by hand under that provider. Picking runs a short test transcription of silence and only selects the model if it works; otherwise you see why right away. If the phone's recognizer never responds when you hold to talk, Mika tells you and points you here; it never switches to a paid model by itself.

## Sign in with an account (no key needed)

At the top of "Add provider" is the group "Sign in with an account · no key needed". Tap one to read what happens, sign in in the browser, and come back to Mika to see the account and its models; you can set one as the main model right there.

- **ChatGPT** (Plus or Pro, uses your plan's usage): tap "Continue with ChatGPT" and sign in in the browser.
- **xAI / Grok** (SuperGrok or X Premium+ subscription): the browser opens xAI's confirmation page; check that it shows the same short code as your phone, then approve. This sign-in borrows the public sign-in entry of xAI's official command-line tool; xAI did not set it up for Mika and may restrict this use. Some subscription tiers still can't make calls after signing in (403); use an xAI API key in that case.
- **OpenRouter**: OpenRouter's official authorization for third-party apps. Once you approve, OpenRouter gives Mika a key of your own (named "Mika"), paid from your OpenRouter balance; to stop, delete it on OpenRouter's Keys page.

Signing out only clears the sign-in on this phone.

## Check current allowance

Open **Settings › Models › Token usage**. The top section shows the current allowance for each configured provider. Use the date filters below to review the token usage recorded on this device.

DeepSeek, Kimi, and SiliconFlow show the account balance. OpenRouter shows how much the current key can still spend. Tap the row to see the account balance on OpenRouter. For providers such as ChatGPT, **Manage usage** opens the official site. Tap **Refresh** to check current allowances again.

## Reasoning effort

Models on ChatGPT sign-in, xAI sign-in, and the Responses API can adjust **Reasoning effort** below each role card; OpenAI-compatible, Anthropic Messages, and Gemini models can too when the provider's model list states the levels for that model. The default leaves the choice to the provider. "Use main model" inherits its effort; explicitly picking the same model lets each role use a different effort. Options come from the provider's model list when available (the Anthropic model list states each model's levels; models that only take a thinking budget get none). Without listed levels, only Responses models get generic manual options, subject to server support; OpenAI-compatible, Anthropic, and Gemini models are not guessed. Higher effort usually takes longer and uses more tokens.

## Context window and compaction

Open **Context and compaction** under a role in **Settings › Models**. Leave the window blank to use the selected model’s window. Leave the trigger blank for automatic compaction, or enter an absolute token count. The trigger counts the whole request, including the system prompt, tools, and messages. A window of 1,000,000 tokens has an automatic trigger of 967,000. Enter 600,000 to compact earlier. Use digits only in the fields. The dialog shows the allowed range and effective values. After compaction, the request can grow by a margin before it triggers again.

The settings apply on the next task and are shared by roles using the same provider and model. Choose **Reset** and save to restore both defaults. If reselecting a model gives it a smaller window, the effective trigger stays within its new bounds. A window override sets the local budget. The provider still enforces its own context limit.

## Models on your own computer or LAN

When adding a provider choose "Custom" (自定义); the base URL may start with `http://`, e.g. `http://192.168.1.10:11434/v1` (with the phone on the same Wi-Fi). Local and LAN addresses show no warning; an unencrypted address outside your LAN shows a short warning, since your API key and chats could be seen by others on the network. Use `https://` when you can.

## Network proxy

If you need a proxy to reach a model service but don't want a system-wide VPN, enter an address in **Settings › Network proxy**: `http://host:port` or `socks5://host:port` (`socks5h` works too; host names are resolved by the proxy). For a proxy that needs a login, write `http://user:password@host:port`. The address is stored encrypted on the phone; the screen only shows the scheme, host and port.

- Goes through the proxy: model requests (chat, speech to text, connection tests), model lists, provider allowance checks, token exchange when you sign in with an account, in-app update downloads, the in-app browser, and commands in Files & computing environment (while network access is on there).
- Always direct: addresses on this phone or your LAN (localhost, 127.x, 10.x, 172.16–31.x, 192.168.x, 100.64–127.x, names ending in `.local` or `.lan`, and single-word machine names), so local models such as Ollama or LM Studio keep working.
- If the proxy can't be reached or rejects the user name or password, Mika says so instead of blaming your key or model.
- `https://` proxies (an encrypted connection to the proxy itself) are only used by commands in Files & computing environment; Mika's own connections stay direct. Use `http://` or `socks5://` to cover everything.
- The in-app browser uses the proxy only if the phone's WebView supports a per-app proxy, and it can't use a SOCKS proxy that needs a login. In those cases the settings row says the browser connects directly.

## API key pool and auto-rotation

You can add multiple API keys for any provider by separating them with newlines, commas, or semicolons in the API key field, or using the "Add API Key" button under the provider's details page.

- **Round-robin load balancing**: When multiple healthy keys are active, Mika rotates requests evenly across the pool, multiplying your rate limit and token throughput.
- **Sub-50ms failover on rate limits**: If a key hits an `HTTP 429` (rate limit) or `401`/`403` (quota exhausted or invalid), Mika traps the failure, puts the affected key on a temporary cooldown (honoring the server's `Retry-After` header or a default 60s cooldown), and seamlessly swaps to the next key in `<50ms` without interrupting your task.
- **Auto-unfreeze**: Once a key's cooldown period ends, it automatically returns to active rotation.

## Multi-model waterfall and fallback

To prevent tasks from failing when a provider encounters capacity outages, service degradations, or prompt token limits (e.g. `HTTP 413` or `5xx` errors):

- Mika supports configuring secondary and tertiary fallback models under each role.
- If all keys for your primary model fail or the provider is temporarily unavailable, execution cascades down your waterfall chain automatically.
- Once primary keys recover from their cooldown, subsequent tasks automatically ascend back to your primary model.

## ChatGPT reports a workspace access error after switching accounts

If the browser says the app is only available to members of its workspace and shows `3p_login_workspace_scope_denied`, first check that you selected the ChatGPT account and workspace originally used to authorize it. The error means the current sign-in does not meet the app registration's workspace scope; it does not by itself establish a wrong password or exhausted plan usage.

ChatGPT **Sign out** in Mika keeps the registered `client_id` so the same account can sign in again. If you intend to switch to a different account or workspace, repeatedly signing out and back in may keep using the old binding. To create a new connection:

1. Return to Mika and cancel any pending sign-in.
2. Note your main, vision and light model selections.
3. Open **Settings › Models › ChatGPT** and confirm **Delete this provider** at the bottom. This removes that provider's local credentials, model configurations and registration ID; roles using it need to be selected again. Conversations stay on the phone. You do not need to uninstall Mika or clear all app data.
4. Add ChatGPT again, tap **Continue with ChatGPT**, choose the intended account and workspace in the browser, complete authorization, and return to Mika.
5. Select the models for your roles again and send a short request to verify inference. A model list confirms sign-in and model listing, not a successful inference request.

This is for switching accounts or workspaces, not bypassing workspace access controls. If a fresh connection is still denied, check that the account has access to the workspace or contact its administrator. Include the error code and Mika version when reporting it, and redact email addresses, authorization codes and tokens.
