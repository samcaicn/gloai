// 加密混淆的 API 凭证
const getApiCredentials = () => {
  const keyParts = ['gk_', '981279d245764a1cb53738da'];
  const secretParts = ['gs_', 'jMlgnMLL10ELKYUUzuMP8Ahf3ddrBbfE'];
  return {
    apiKey: keyParts.join(''),
    apiSecret: secretParts.join('')
  };
};

const UPDATE_CHECK_URL = 'https://ggai.tuptup.top/api';
const FALLBACK_DOWNLOAD_URL = 'https://ggai.tuptup.top';

export const UPDATE_POLL_INTERVAL_MS = 12 * 60 * 60 * 1000;



export type ChangeLogEntry = { title: string; content: string[] };

export interface AppUpdateDownloadProgress {
  received: number;
  total: number | undefined;
  percent: number | undefined;
  speed: number | undefined;
}

export interface AppUpdateInfo {
  latestVersion: string;
  date: string;
  changeLog: { zh: ChangeLogEntry; en: ChangeLogEntry };
  url: string;
}

const toVersionParts = (version: string): number[] => (
  version
    .split('.')
    .map((part) => {
      const match = part.trim().match(/^\d+/);
      return match ? Number.parseInt(match[0], 10) : 0;
    })
);

const compareVersions = (a: string, b: string): number => {
  const aParts = toVersionParts(a);
  const bParts = toVersionParts(b);
  const maxLength = Math.max(aParts.length, bParts.length);

  for (let i = 0; i < maxLength; i += 1) {
    const left = aParts[i] ?? 0;
    const right = bParts[i] ?? 0;
    if (left > right) return 1;
    if (left < right) return -1;
  }

  return 0;
};

const isNewerVersion = (latestVersion: string, currentVersion: string): boolean => (
  compareVersions(latestVersion, currentVersion) > 0
);

export const checkForAppUpdate = async (currentVersion: string): Promise<AppUpdateInfo | null> => {
  const { apiKey, apiSecret } = getApiCredentials();
  
  const url = new URL(UPDATE_CHECK_URL);
  url.searchParams.append('version', currentVersion);
  url.searchParams.append('platform', window.electron.platform);
  
  const response = await window.electron.api.fetch({
    url: url.toString(),
    method: 'GET',
    headers: {
      'X-API-Key': apiKey,
      'X-API-Secret': apiSecret,
    },
  });

  if (!response.ok || typeof response.data !== 'object' || response.data === null) {
    return null;
  }

  const payload = response.data as any;
  if (payload.code !== 200) {
    return null;
  }

  const data = payload.data;
  const latestVersion = data?.version?.trim();
  if (!latestVersion || !isNewerVersion(latestVersion, currentVersion)) {
    return null;
  }

  const toEntry = (log?: any): ChangeLogEntry => ({
    title: typeof log?.title === 'string' ? log.title : '',
    content: Array.isArray(log?.content) ? log.content : [],
  });

  return {
    latestVersion,
    date: data?.date?.trim() || '',
    changeLog: {
      zh: toEntry(data?.changeLog?.zh),
      en: toEntry(data?.changeLog?.en),
    },
    url: data?.downloadUrl || FALLBACK_DOWNLOAD_URL,
  };
};
