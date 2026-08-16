async function handler(params, complete) {
  const { action } = params

  if (action === 'open') {
    return { _kuaiju: true, url: 'https://kuaiju2c.tuptup.top' }
  }

  return {
    error: '未知动作: ' + action,
    supported: ['open - 打开快剧（快捷键视频）']
  }
}
