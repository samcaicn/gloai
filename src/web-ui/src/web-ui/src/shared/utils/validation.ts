import { i18nService } from '@/infrastructure/i18n';

export function isValidEmail(email: string): boolean {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailRegex.test(email);
}


export function isValidUrl(url: string): boolean {
  try {
    new URL(url);
    return true;
  } catch {
    return false;
  }
}

/**
 * 校验 URL 是否为"安全可外开"的 http(s)/mailto 链接。
 *
 * 与 [`isValidUrl`] 的关键区别：`isValidUrl` 用 `new URL()`，会接受
 * `javascript:` / `data:` 等可执行协议（`new URL('javascript:alert(1)')` 合法），
 * 不能用于决定是否经 open_external / window.open 打开。
 *
 * 本函数只允许 http / https / mailto 三个协议，拒绝一切可执行 / 本地协议，
 * 防止从服务器配置（tenant/brand website）注入的恶意 URL 被打开执行。
 *
 * 实现用正则提取 scheme（而非 new URL），避免大小写 / 前导空白 /
 * 混淆字符绕过；scheme-less（相对 URL）也拒绝（外链必须是绝对地址）。
 */
export function isSafeHttpUrl(url: string): boolean {
  if (!url || typeof url !== 'string') return false;
  const trimmed = url.trim();
  if (!trimmed) return false;
  const schemeMatch = trimmed.match(/^([a-zA-Z][a-zA-Z0-9+.\-]*):/);
  if (!schemeMatch) return false;
  const scheme = schemeMatch[1].toLowerCase();
  return scheme === 'http' || scheme === 'https' || scheme === 'mailto';
}

 
export function isValidFilePath(path: string): boolean {
  
  if (!path || path.trim().length === 0) {
    return false;
  }
  
  
  const illegalChars = /[<>:"|?*]/;
  return !illegalChars.test(path);
}

 
export function hasValidExtension(filename: string, allowedExtensions: string[]): boolean {
  const extension = filename.split('.').pop()?.toLowerCase();
  return extension ? allowedExtensions.includes(extension) : false;
}

 
export function isValidJson(str: string): boolean {
  try {
    JSON.parse(str);
    return true;
  } catch {
    return false;
  }
}

 
export function isInRange(value: number, min: number, max: number): boolean {
  return value >= min && value <= max;
}

 
export function isValidLength(str: string, minLength = 0, maxLength = Infinity): boolean {
  return str.length >= minLength && str.length <= maxLength;
}

 
export function isRequired(value: any): boolean {
  if (value === null || value === undefined) {
    return false;
  }
  
  if (typeof value === 'string') {
    return value.trim().length > 0;
  }
  
  if (Array.isArray(value)) {
    return value.length > 0;
  }
  
  return true;
}

 
export function matchesPattern(value: string, pattern: RegExp): boolean {
  return pattern.test(value);
}

 
export function isValidFileSize(file: File, maxSizeInMB: number): boolean {
  const maxSizeInBytes = maxSizeInMB * 1024 * 1024;
  return file.size <= maxSizeInBytes;
}

 
export function isValidPort(port: number): boolean {
  return Number.isInteger(port) && port >= 1 && port <= 65535;
}

 
export function isValidIPAddress(ip: string): boolean {
  const ipRegex = /^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;
  return ipRegex.test(ip);
}

 
export function validatePasswordStrength(password: string): {
  isValid: boolean;
  score: number;
  issues: string[];
} {
  const issues: string[] = [];
  let score = 0;
  
  if (password.length < 8) {
    issues.push(i18nService.t('common:validation.password.minLength', { min: 8 }));
  } else {
    score += 25;
  }
  
  if (!/[a-z]/.test(password)) {
    issues.push(i18nService.t('common:validation.password.lowercase'));
  } else {
    score += 25;
  }
  
  if (!/[A-Z]/.test(password)) {
    issues.push(i18nService.t('common:validation.password.uppercase'));
  } else {
    score += 25;
  }
  
  if (!/[0-9]/.test(password)) {
    issues.push(i18nService.t('common:validation.password.number'));
  } else {
    score += 25;
  }
  
  if (!/[^a-zA-Z0-9]/.test(password)) {
    issues.push(i18nService.t('common:validation.password.specialCharSuggested'));
  } else {
    score += 10;
  }
  
  return {
    isValid: issues.length === 0,
    score: Math.min(score, 100),
    issues
  };
}

