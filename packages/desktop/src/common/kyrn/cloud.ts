/**
 * The AiBuddy cloud: the one backend the product connects to. The model gateway (the `auto` sentinel is routed
 * per task) and the small configuration document the app reads at startup live on the same host, so a switch the
 * cloud throws is picked up by the next start without anything being installed.
 */

/** The model gateway the first-run guide connects to. */
export const AIAPI_HOST = 'https://aiapi.tuptup.top';

/**
 * The configuration document: `{"judge":"auto"}` or `{"judge":"off"}`. `auto` is the cloud choosing per task,
 * which is also what an app that never reached the document runs with; `off` turns the judgment layer off until
 * the document says otherwise. Unknown fields are ignored, so the server can grow the document without this app
 * having to know it, and a value that is not one of the two is no answer at all.
 */
export const JUDGE_CONFIG_PATH = '/v1/config';

/** What the cloud says about the judgment layer. */
export type JudgeMode = 'auto' | 'off';
