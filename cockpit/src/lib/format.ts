export function formatWan(value: number): string {
  return (value / 10000).toFixed(1) + '万'
}

export function formatPercent(value: number): string {
  return value.toFixed(1) + '%'
}

export function formatRatio(value: number): string {
  return (value * 100).toFixed(0) + '%'
}

export function formatNumber(value: number): string {
  return value.toLocaleString('zh-CN')
}

export function formatProductivity(value: number): string {
  return value.toFixed(2)
}
