# What Mika can do, in detail

[简体中文](features.md) | English

Tell Mika what you want in one sentence, and it gets it done on your phone. This page has the details behind each item under "What it can do" in the [README](../README.md).

## Saved tasks

- Long-press one of your text messages and choose “Save as task”, then edit its title and instructions. You can also add, edit, or delete tasks using the star beside the composer or “Saved tasks” in Settings.
- Choosing a task on a new chat or through the star fills the current composer for editing before sending. Existing text requires confirmation before replacement.
- While editing, place the cursor where the text should vary and choose “Add a fill-in field”, then name it (for example, “Contact” or “Time range”). Selected text can also be replaced by a field. Choosing this task asks for those values before filling the composer. Repeated names share one value, with up to 12 distinct fields.
- You can still edit before sending. Cancelling the form or draft replacement leaves the original input untouched. Entered values do not change the saved task and are requested afresh next time.
- Tasks are stored locally, preserving the previous recommended-task list. Existing tasks remain literal until “Add a fill-in field” is used. Saving, filling and selecting tasks do not call a model or execute a task.

## Checklist updates and reuse

- Long-press a text reply from Mika and choose “Save to checklist”. Create a list or select an existing one, review the complete reply, and save it as a new version. Earlier versions remain available. “Saved checklists” is also accessible from the star beside the composer.
- Choose “Add information” to put the current list and your additions into a draft update request. Send it through the existing chat flow, then long-press the complete updated reply and save it to the original checklist. There is no background overwrite or separate model call.
- “Changes and history” keeps the latest 10 versions, showing added/rewritten and removed/replaced nonempty text lines plus the saved source reply. These are text differences, not judgments about which business facts are correct. Manual edits also create versions.
- “Use next” combines your next instruction with the latest checklist in the composer. Existing drafts can be kept or replaced. The latest content can also be copied. Failed writes retain edits, and stale editors cannot overwrite newer versions.
- Up to 30 lists are stored locally with encryption, independently of conversations; titles allow 100 characters and content allows 12000. Deleting a conversation does not delete its lists. Deleting a checklist removes its history as well.

## Reusable multi-step tasks

- Open the star beside the composer → “Multi-step tasks” to create a task with 2–8 named steps. Edit, reorder, duplicate, or delete templates and supply requirements for each run. A running task uses an immutable template snapshot.
- Steps use the existing phone task service in sequence, each in its own conversation. Each receives the run requirements and the previous step’s text result, with instructions to inspect the current screen before acting. Previous clicks are never replayed. Results should retain information needed downstream; attachments and workspace files are not automatically passed between steps in this first version.
- Progress includes each step’s result and conversation, where you can answer questions, take over, or stop. The star remains available during execution. “Pause after this step” finishes the current step before pausing; continuing retains completed work.
- Only a successful existing outcome judgment advances automatically. Failure, cancellation, an unknown judgment, or process interruption requires review. Inspect the conversation, supply an existing result, or explicitly retry that step. Retries keep prior attempts and use new execution IDs. Potentially completed messages are never automatically resent; model-based judgments cannot guarantee exactly-once actions in external apps.
- Final text results are saved in run history and can be copied or inserted into the composer, protecting existing drafts. Run again with different requirements. Templates and history are encrypted locally; deleting a template retains its run history.
- Limits: 30 templates, 50 runs, 10 attempts per step; titles 100 characters, step instructions 2000, run requirements 4000, and results 12000. At capacity, only the oldest completed run is pruned. Oversized results require editing before continuing. One multi-step task runs at a time, using existing queueing, phone permissions, and model settings.

## Operates any app without taking over your screen

Any app installed on your phone works, with no per-app integration: Mika looks at the screen, finds buttons, taps and types like a person would. If it can see it, it can operate it.

- Opens apps on a separate background screen and reads, taps, types and scrolls there while you keep using your phone. A small live view in the chat shows what it is doing; you can close it for the current task, leaving a small button at the bottom right that turns orange when Mika needs you.
- When several tasks run at once, “Background screens” next to “Running” in the side menu lists which app each task is using and how far it got; open one to watch its live screen, tap to enlarge it and pinch to zoom.
- When two tasks need the same app, the later one can wait until the other is done with it (up to a few minutes; it shows “Waiting for another task to finish with …”). Two tasks waiting on each other are caught at once instead of hanging.
- Reads long lists and chat histories screen by screen, e.g. "summarize what my group chat talked about today".
- Works even with apps whose UI cannot be read directly (custom-drawn UI, unlabeled controls): Mika reads on-screen text with the text recognition bundled in the APK, and with a vision-capable model it can also find buttons and input fields from screenshots.
- Can share a piece of text straight into an app (to send a message or save a note); Mika still picks the recipient and taps send on that page itself.
- Recognizes the system asking "open with which app", a dual-installed app asking "which one", and one app opening another (e.g. jumping to a payment page). It carries on when it can, and stops to let you act on screen when you need to choose or handle it yourself.
- Things only you should do (logging in, verification codes, payment) trigger a notification asking you to take over: the app moves to your main screen, you finish and hand it back, and Mika re-checks before continuing.
- When a task is done, the apps Mika opened stay in the background on the page where it finished: you find them in recent apps, and the next time Mika opens one it continues from there. They never jump in front of what you are doing. An app that is playing sound or video at that moment is closed; say "keep the music playing" or "close it when you're done" and Mika does that instead.

## Built-in browser

- Opens any website in the background and operates the page with real clicks and keyboard input. Pages open in desktop mode by default.
- Reads the page text as well as the data the page itself receives; supports downloads and uploads.
- Logins persist. You can browse yourself from "Browser" (浏览器) in the side drawer, and sites you log into there are available to Mika too.
- Paces itself: consecutive page loads on the same site are spaced out, longer on sites that limit browsing speed. It recognizes anti-bot block pages and hands them to you instead of forcing through.

## Mika's own pages

- With Settings › During work › "Let Mika operate its own screens" turned on (off by default), Mika can open its Settings, Browser, Memory, Scheduled tasks and other pages for you. It can also open a report, image or PDF from the workspace in Mika's built-in viewer, post a notification, or put a piece of text on the clipboard.
- After you approve it, it can also put a link or search results in your visible browser; for a site that needs a sign-in, it can open the "sign in for Mika" page so you can sign in yourself and then carry on.
- Mika never taps its own UI and cannot change its permissions, model or budget; it only opens the page, and you still do the operating.

## The screen you are looking at

- While you are in another app, a floating Mika capsule at the screen edge shows background task progress, things that need you, and results. Tap it to add a message, answer, approve or stop. If you don't want it, open it and tap "Turn off capsule" at the bottom; you can turn it back on in Settings › Floating capsule.
- After dragging the capsule to an edge, it fades into a half circle while idle; tap to restore it. Add the Floating capsule tile in Android Quick Settings to switch it on or off. Settings › Floating capsule has optional fullscreen hiding. The expanded chat preview supports pinch resizing and size/reset buttons. See [capsule controls](dev/capsule-controls.md).
- **Double-tap the capsule** to send the screen you are looking at to Mika, e.g. "how should I reply to this" or "note this down". You can also take a screenshot from the card and add a line before sending.

## Scheduled and conditional tasks

- "Remind me at 9 tomorrow morning", "check the weather every morning and tell me only if it will rain", "do X every Friday".
- Conditions can compare with last time: "tell me when it drops by more than 50" compares with the price from the last time the condition started the task; "tell me every time it rains" speaks up on each rainy day; you can also say "at most once a day".
- Runs at the scheduled time in the original conversation and sends the result back. The scheduled tasks page lets you create, edit, pause, run now or delete, with a preview of the next three occurrences. A daily or weekly plan can use several local times (e.g. `08:00, 12:30, 20:00`) under one identity and pause switch; add, change or remove them with the pickers next to the times, or type them directly. For the first run, pick a date in the calendar and then a time on the wheels. In its original conversation, you can also ask to edit its content, condition, reminder or recurrence.
- Open a scheduled task's menu and select **Run history** to see the times and statuses of its last 10 recorded runs.
- Choose **Copy as new plan** from a plan's menu to reuse its content, condition, and recurrence. The copy starts paused by default and sends results to the same conversation.
- Under **Settings › Notifications**, turn on scheduled task quiet hours and choose start and end times. The window repeats daily in the device's local time and can span midnight. Tasks still run, results go back to the conversation, and scheduled results and reminders appear silently in the notification shade.

## Talk while it works: interrupt or stop anytime

- In the chat and in voice calls, just talk to Mika. What it can answer right away, it answers right away; anything that needs doing (opening an app, looking something up online, operating the phone, sending a message), Mika says so and gets on with it, and you can keep talking about something else meanwhile.
- What Mika is working on shows in the progress bar above the composer: one line when collapsed; expand it to see each step and how long it took, stop it, or open the full details.
- While Mika works, just say it: add to or correct your request, ask how it is going, answer a question Mika asked, or say to stop, and Mika does that. Progress answers come from what is really happening, never guessed. Mika works from your whole sentence as you said it, never reworded, and never starts, answers or stops anything on its own without you asking.
- When Mika needs you, a card shows in the conversation and you handle it right there: tap an option when it asks you to choose, "Allow this once" or "Refuse" when it needs your OK once, or "I’ll handle it" and then "Done" when it needs you on the phone.
- When something is done, the result is written in full in the conversation; when it did not work out, Mika says why and what you can do next.
- One message can start several things; anything beyond the parallel limit waits in line and starts when the earlier ones finish.
- When you leave the chat, results and the reasons things did not work out are still written back to the conversation; finishing, failing and needing you also send a notification.
- Choose the model Mika replies with under "Settings › Models › Voice › Call mode", in "Think": the light model or the main model, for both chat and calls. Without a light model it uses the main model.
- Typing in the full details of one thing adds to that thing directly: Mika reads it before its next step.
- Every step in the details can be opened to see exactly what it tapped or typed and how that turned out.
- "View process" works while Mika is still at it; new steps keep arriving as it goes.
- Long-press a message sent while Mika is working to withdraw or edit it before the model picks it up. Editing restores its quote too and is available when the composer has no draft; a message that carries a screen or text selected on a web page cannot be edited.
- When you press stop, Mika adds a short wrap-up: what it did, what it saw, how many times it sent something, and what is left. Say "continue" next time and it knows where to pick up.

## Hold to talk

- When you are not typing, hold the chat input to speak. When you let go the words land in the input for you to edit; nothing is sent by itself. While a task runs, sending it adds to the task. To send right away, slide up into the "Listening" card; when it turns green and says "Let go to send", let go and the words are sent together with anything already in the input. Slide further up past the card until it says "Let go to cancel" and let go to throw that take away.
- In other apps, long-press the floating capsule to speak and let go to send it straight to Mika (it adds to a running task, or answers when Mika is asking you). Let go on "Edit" to open the card with the words in its input.
- By default it uses the phone's built-in speech recognition, which shows words as you speak and costs nothing. If the phone has none, or it is not accurate enough, pick a provider's speech model under Settings › Models › Speech to text; see [models and sign-in](models.en.md#speech-to-text).

## Ask / Autonomous

- The default is **Ask**: only spending money (placing orders, paying) is confirmed with you first; sending messages, submitting and deleting are done directly. You can switch to **Autonomous**, which does not ask even before paying. See [Ask and Autonomous](modes.en.md).

## Memory

- Remembers facts, preferences and ways of doing things that you state explicitly (ways of doing things need your approval). You can view, edit and delete them. It only learns from what you yourself say, and never stores passwords, verification codes, card numbers and the like. Memory is stored encrypted on the phone.

## Faster and steadier on familiar apps and websites

Even without any instructions, Mika can get things done by looking at the screen. For some apps and websites there is extra usage knowledge (where things are, common ways to do things, what to watch out for), which Mika looks up automatically when it needs it, so it takes fewer wrong turns. For example, in a chat app it already knows how to find a contact and scroll through chat history; on a job site it knows how to filter jobs and greet recruiters. There are also general how-to skills for a few kinds of tasks.

The apps and websites that already have usage knowledge are listed in the collapsed section under "What it can do" in the [README](../README.md) (anything not listed still works). The knowledge keeps growing and anyone can add to it: it is plain text under [`core/src/main/resources/knowledge/`](../core/src/main/resources/knowledge/) and [`core/src/main/resources/skills/`](../core/src/main/resources/skills/). [Contributions welcome](../.github/CONTRIBUTING.md).

## Batch jobs

- Batch work (e.g. greeting or applying one by one) starts right away without asking you to confirm the list first. It waits a random interval between items, and stops and tells you when the site itself says you have hit a limit.

## Everything is traceable

- Answers list their sources (pages read, screenshots), and every task has a step-by-step record you can review.
- Each task ends with a one-line summary: total time, model round trips, tokens used, and time to first feedback. "Token usage" (Token 用量) in Settings breaks usage down by day, provider and model.

## Linux sandbox

- Each conversation gets a workspace that can run Python, Node and shell commands, process files you send, and save results to your phone's Download/Mika folder.
- To share PDFs, documents, audio clips, images or other files, select Mika in the Android share sheet. The files appear in a chat draft where you can add a message before sending. You can share up to 9 files at a time, up to 200 MiB each.
- Open **Files** in the sidebar to browse attachments and exported files. Combine name, conversation, source, and file type filters. File types are determined by extension and include images, PDFs, audio, video, and other files.
- Long-press a conversation and choose "Terminal", or open "Files & computing environment" in Settings and tap "Open terminal" on a workspace row, to run commands yourself in that conversation's workspace. It uses the same workspace and network setting as Mika's scripts; Mika cannot see what is in the terminal, and terminal commands do not go into the conversation or run records.
- Online by default; turn off "Allow scripts to go online" (允许脚本联网) under "Files & computing environment" (文件与计算环境) in Settings if you don't need it. arm64 devices only; on other devices the app installs normally and the sandbox shows "not supported on this device".
- When it is online these commands also use **Settings › Network proxy** (the same proxy Mika uses for model calls, updates and the in-app browser; see [Models and sign-in](models.en.md#network-proxy)).
- The same page shows how much space the sandbox uses and lets you clear a conversation's workspace files; conversations with a running task are skipped.

## AI tools on your computer can use it (CLI / MCP)

Computer AI tools can use **CLI + Skill or MCP** to hand Mika tasks, check progress and get results. Normally choose one; you don't need both, though you can use both to connect different clients. Both require the phone APK and use the same phone task service. For how to choose and connect, see [Use Mika from a computer](computer.en.md).

- **CLI + Skill** suits computer agents that support Skills and can run commands. The Skill includes its own CLI script, so you do not need a separate global CLI installation or MCP configuration. For terminal or script use alone, install just the CLI. See the [computer CLI / Skill guide](computer-install.en.md) for installation, updates and removal.
- **MCP** offers a quick USB connection (ADB forwarding with token authentication) and direct Wi-Fi / private-network access, which must be enabled separately on the phone; see the [computer access guide](mcp.en.md). Confirmations such as payments still have to be handled on the phone.

### Let an AI agent on your computer install Mika

Connect the phone to your computer with a USB cable, turn on USB debugging on the phone (don't change other switches in Developer options unless the agent asks you to: some can erase the phone), and send this sentence to an AI agent on the computer that can run commands:

```
Follow https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.en.md to install Mika on the Android phone connected over USB.
```

It checks the phone, downloads and installs Mika, turns on the permissions it needs, and stops to tell you whenever something needs a tap on the phone. You enter your API key in Mika yourself; it never goes through the agent. If you also ask for computer access, the agent configures the access method you choose. The steps are in [Installing Mika with an AI agent](install-with-agent.en.md).

## Requirements

| Item | Requirement |
|---|---|
| Install, chat, web and sandbox tasks | Android 11 or later (sandbox requires arm64) |
| Phone app control | Android 12 or later; background work may affect main-screen focus on Android 12–13 |
| Recommended | Android 14 or later: the background screen has its own focus, so it does not interfere with your typing on the main screen; running tasks from different conversations at the same time also needs it |
| Linux sandbox | arm64 devices only; on other devices the app installs normally and the sandbox shows "not supported on this device" |
| Model | A model that supports tool calling, configured through a provider API, a local service or account sign-in; see [Models and sign-in](models.en.md) |

Phone app control needs wireless debugging paired once in the "Set up phone control" (设置手机操作) wizard (or the phone helper started from a computer over USB); every step is in the [phone control setup guide](setup-guide.en.md). You don't need to turn on accessibility by hand: once pairing succeeds, Mika turns it on by itself. For apps installed from GitHub rather than an app store, Android often blocks turning accessibility on manually ("Restricted setting"), and letting Mika do it avoids that.

## Try it

An empty conversation shows a few examples you can tap, edit and send. Or try something like:

- "Summarize what my WeChat group talked about today"
- "Open Xiaohongshu and summarize what this creator posted recently"
- "Set alarms for 9:40, 9:50 and 10:00 tomorrow morning"
- "Check the weather at 8 every morning and tell me only if it will rain"

## FAQ

The FAQ now has its own page: [FAQ](faq.en.md). For phone control, see [Troubleshooting](setup-guide.en.md#troubleshooting) in the setup guide.

## Use OpenAI Realtime for phone calls

In Settings › AI phone calls › Settings › Realtime voice service, add a configuration, select OpenAI Realtime, and enter an OpenAI API key with Realtime access. The default model is `gpt-realtime` and the voice is `marin`. Save the configuration, test the connection, and select **Use as default** for incoming and outgoing calls. Call audio goes to OpenAI and may incur API usage charges.

Before enabling auto-answer, manually answer a test call with Mika and check that both parties can hear each other.

## Follow up after an AI call

Enable call summaries, tasks and message drafts in AI calling settings. After an AI call, the selected main model summarizes the available transcript and drafts a short WeChat follow-up in the same request. Calls without a transcript do not trigger generation; small talk, a refusal to be contacted or no useful follow-up may produce no draft.

Open the call record to review, edit, save and copy the draft. It stays encrypted with the call history. This does not find contacts, open WeChat or send messages. Failed summaries can be retried from the call record; existing summaries without a draft offer “Write follow-up message” to compose one locally, without another model request. Review facts and commitments before using a draft.
