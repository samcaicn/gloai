/**
 * Signing in to a model provider's subscription (ChatGPT, Claude, Grok) with pi's own OAuth flows, from the app.
 * The main process runs the flow in a child process and opens the browser; the screen polls the state and answers
 * what a flow asks (a code to paste, a choice). No credential ever comes back to the screen.
 *
 * Google-account sign-ins (Gemini CLI, Antigravity) were removed: they are Google OAuth flows blocked behind the
 * GFW and have no place in this build.
 */

/** Every subscription the app knows how to sign in to, by pi's provider id, in the order they are shown. */
export const SUBSCRIPTION_PROVIDERS = [
  'openai-codex',
  'anthropic',
  'xai',
] as const;
export type SubscriptionProvider = (typeof SUBSCRIPTION_PROVIDERS)[number];

export const isSubscriptionProvider = (value: unknown): value is SubscriptionProvider =>
  SUBSCRIPTION_PROVIDERS.includes(value as SubscriptionProvider);

/**
 * The Google sign-ins (Gemini CLI, Antigravity) are Google-account OAuth flows that are blocked behind the GFW and
 * have been removed from this build. The helpers stay so the rest of the app (the risk-consent check, the login
 * waiting screen) still compiles; they simply never match now.
 */
export const GOOGLE_PROVIDERS: readonly SubscriptionProvider[] = [];
export const isGoogleProvider = (provider: SubscriptionProvider | undefined) =>
  provider !== undefined && GOOGLE_PROVIDERS.includes(provider);

/** What every harness can sign in to: pi's own flows. The rest is only offered when the runner says so. */
export const BUILT_IN_PROVIDERS: readonly SubscriptionProvider[] = ['openai-codex', 'anthropic', 'xai'];
/**
 * Subscriptions whose provider id also takes an API key (pi reads ANTHROPIC_API_KEY and XAI_API_KEY): mu can report
 * them as usable while nobody is signed in. The others are usable only signed in.
 */
export const KEY_PROVIDERS: readonly SubscriptionProvider[] = ['anthropic', 'xai'];

export type LoginModel = { id: string; name: string };

/** What a flow asks the person: a code to paste (raced with the browser callback), a line of text, a choice. */
export type LoginPrompt = {
  type: 'manual_code' | 'text' | 'secret' | 'select';
  message: string;
  placeholder?: string;
  options?: { id: string; label: string; description?: string }[];
};

export type LoginPhase = 'idle' | 'running' | 'done' | 'failed' | 'cancelled';

export type LoginState = {
  /** Grows with every start, so a screen can tell an old answer from the current one. */
  id: number;
  provider?: SubscriptionProvider;
  phase: LoginPhase;
  /** The page the flow opened in the browser: shown so it can be opened again. */
  url?: string;
  /** For flows that show a code on one device and take it on another. */
  device?: { userCode: string; verificationUri: string };
  /** The flow's latest progress or info line. */
  message?: string;
  /** The credential is stored: the account is signed in, and what it can use is still being read. */
  stored?: boolean;
  prompt?: LoginPrompt;
  /** After success: what the account can use, from pi's catalogue. */
  models?: LoginModel[];
  error?: string;
};

/**
 * Who is signed in already, by provider, with what they can use, and what this harness can sign in to (`offered`,
 * in the order they are shown; absent when the runner did not say).
 */
export type LoginStatus = {
  signedIn: { provider: SubscriptionProvider; models: LoginModel[] }[];
  offered?: SubscriptionProvider[];
};

/** The providers to show: what the runner offers, or pi's own ones until it has said. Always in the app's order. */
export const offeredProviders = (status: LoginStatus): SubscriptionProvider[] =>
  SUBSCRIPTION_PROVIDERS.filter((provider) => (status.offered ?? BUILT_IN_PROVIDERS).includes(provider));

/** The risk question of a Google sign-in: a choice to go on or not, asked before any browser or port is opened. */
export const isRiskConsent = (provider: SubscriptionProvider | undefined, prompt: LoginPrompt | undefined) =>
  isGoogleProvider(provider) &&
  prompt?.type === 'select' &&
  ['continue', 'cancel'].every((id) => prompt.options?.some((option) => option.id === id));
