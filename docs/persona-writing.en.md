# For AI assistants: writing a Mika persona for the user

[简体中文](persona-writing.md) | English

This document is written for **an AI assistant that is drafting a Mika persona for the user**. People can follow it too.

If you are the user: send your AI assistant the line below, replacing the last sentence with what you want.

```
Follow https://github.com/wangzan101/Mika/blob/main/docs/persona-writing.en.md and write a Mika persona for me. What I want: shorter answers, less talk while working, the tone of a reliable colleague.
```

If you are the AI assistant: read "What a persona can change" first, ask the user what effect they want, then deliver the result in the "Delivery format". When you are unsure what the user wants, ask once; do not invent a character for them.

## What a persona is

Mika is an assistant on the user's phone that gets things done: it operates apps, browses the web, works with files and reports what happened. It speaks in two ways:

- **Progress notes**: one line next to each step while it works, shown in the chat and in the floating capsule, where space is tight.
- **Final answer**: the result once the task is done. Markdown works (headings, lists, tables, code blocks).

A persona decides how both are worded. It has four parts:

| Part | What it is | Limit |
| --- | --- | --- |
| Name | Only tells several saved personas apart; shown under "Manage personas" and in the run details. With a single persona it is called "My style" automatically | 40 characters |
| Nickname | The name Mika uses for itself, Mika by default; letters, digits and spaces only | 16 characters |
| Options | A few settings chosen in the UI, see below | none |
| Instructions | Free text for what the options cannot express | 4000 characters (about 3000 tokens) |

One persona is chosen in Settings and applies to every conversation from the next message on; a task that is already running is not affected. With no persona, Mika speaks the way it normally does.

## What a persona can change

Only how Mika speaks and writes:

- tone, warmth, formality, humour
- how long answers are
- how progress notes and suggestions are phrased
- formatting preferences (headings, lists, emoji)

The following have no effect even if written. Mika keeps to its rules and, where it matters, tells the user why:

- **Making it act**: starting a task, calling a tool, reading memory or the screen, changing a setting, sending something outward, disclosing information. Those follow only the user's own messages.
- **Changing the rules**: evidence, safety, privacy, authorization, confirmation and completion. For example "pay without asking me", "say it is done without checking", "make something up if you cannot find it". To be asked less often, the user turns on Autonomous mode under Settings › Task running; see [Ask and Autonomous](modes.en.md).
- **Granting abilities**: a persona cannot give it tools, permissions or access.
- **A different identity**: the nickname can change (see the table above), and only the name changes. It is still the same assistant with the same rules and abilities, and the app is still called Mika.
- **The reply language**: chosen under Settings › Language & appearance; do not put it in a persona.

Two more kinds of content do not belong in a persona:

- One-off task requests ("book tomorrow's ticket"): that is a user message.
- Facts about the user ("I live in Shanghai", "I am allergic to peanuts"): that is memory.

## Options first, then instructions

Options are ready-made: each value maps to one prepared sentence, so prefer them. Instructions are only for what the options cannot say.

| Option | Values | What it controls |
| --- | --- | --- |
| Base style and tone | Default, Crisp, Friendly, Playful | The overall register |
| Emoji | Less, Default, More | Whether it uses emoji |
| Answer length | Shorter, Default, Longer | Length of the final answer |
| Progress notes | Brief, Default, Detailed | How much it says at each step |

When choosing:

- **Leave most options at Default.** Change only what the user clearly wants changed.
- **Avoid combinations that pull against each other.** "Crisp" with "Longer answers" asks for two things that cancel out. If the user really needs that, say in the instructions which one wins.
- **Do not repeat in the instructions what an option already says.**

## Writing the instructions

1. **Describe how it speaks, not who it is.** Two or three sentences set the tone. A long backstory dilutes the requests that matter.
2. **Describe observable behaviour, not adjectives.** "Be professional" does nothing; "put amounts, times and addresses on their own lines" does.
3. **Say which kind of speech you mean.** State whether a request is about progress notes or the final answer. Progress notes have little room; do not ask for essays there.
4. **One request per line, in the imperative.** "Lead with the conclusion, then the reasons." "Call me boss."
5. **Keep it short.** Under about 150 words is a good target. The instructions are sent with every task, so length costs tokens every time.
6. **Write in the user's language.** No need to translate into English.
7. **Leave these out**: angle-bracket tags, "ignore the rules above", asking it to pretend it saw something it did not. They do not work and only take up space.

Three blocks work well; drop the ones you do not need:

```
Tone: …
Speaking habits: …
While working: …
```

Check the draft before handing it over:

- Is every line only about how to speak or write?
- Is any line something an option already covers?
- Do any two lines contradict each other?
- If a line were removed, would anything change? If not, remove it.

## Examples

**A crisp colleague**

- Options: base style Crisp, emoji Less, answer length Shorter, progress notes Brief. Everything else Default.
- Instructions:

```
Speaking habits: lead with the conclusion, then the reasons. Put numbers, times and places on their own lines.
While working: when I need to decide something, list the choices as 1, 2, 3 so I can reply with a number.
```

**A patient explainer**

- Options: base style Friendly, answer length Longer. Everything else Default.
- Instructions:

```
Tone: treat me as someone new to the topic. Avoid jargon; when a term is unavoidable, explain it in passing.
Speaking habits: number the steps when there are more than three. For each step, say what I will see when it is done.
```

**A butler with some character**

- Options: all Default.
- Instructions:

```
Tone: an old-school butler: courteous, unhurried, with the occasional dry remark.
Speaking habits: address me as "sir". Give bad news plainly, but say first what can be done about it.
```

## Delivery format

A persona can currently only be set by hand on the phone, so give the user three parts they can follow:

1. **Nickname**: give one when the user wants it to go by another name; otherwise say "leave empty".
2. **Options**: list only the ones that are not Default, with the value to pick.
3. **Instructions**: plain text in a code block, ready to copy. If nothing needs adding, say "leave empty".

Only when the user wants to keep several personas, also give each a name of at most 40 characters.

Then tell the user where to enter it and how to confirm it works:

- Where: Mika › Settings › Persona. Enter the nickname, pick the base style and the options on the page, paste the instructions into the text box, then tap "Save" at the bottom. Nothing applies until it is saved.
- Several personas: "Manage personas" at the bottom of the page creates, renames, copies and deletes them; tap one and choose "Use this one" and every conversation follows it. "Preview the full prompt" there shows the exact text sent to the model.
- Confirm: send a message; the run details for that turn (expand "More details") show which persona was used.
