// safeopc 技能自动测试器 — 自动搜索所有内置技能，逐一执行安全测试动作，监测执行效果
// 动作: run / status / report
// run: 自动发现所有内置技能 → 逐一用安全 action（status）执行 → 收集结果
// status: 查看上次测试结果
// report: 生成测试报告
async function handler(params, complete) {
  const { action } = params
  cap.llm.setComplete(complete)

  const REPORT_KEY = 'safeopc_skill_test_report'

  function loadReport() { return cap.storage.get(REPORT_KEY, null) }
  function saveReport(r) { cap.storage.set(REPORT_KEY, r) }

  // 安全测试 action 映射：每个技能用不会产生副作用的 action 来测试
  // 优先使用 get_flowchart（纯读取，无副作用），其次 status（查状态），
  // 对于不支持这两者的技能用 help/guide（LLM prompt 引导类技能）。
  const SAFE_ACTIONS = {
    // 有 status 动作的自动化/发布类技能
    'builtin-wechat-publisher': 'status',
    'builtin-xiaohongshu-publisher': 'status',
    'builtin-auto-product-comm': 'status',
    'builtin-trace-auto': 'status',
    // 支持 get_flowchart 的跨境电商技能（纯读取流程图，无副作用）
    'builtin-amazon-product-research': 'get_flowchart',
    'builtin-alibaba-1688-sourcing': 'get_flowchart',
    'builtin-cross-border-competitor': 'get_flowchart',
    'builtin-cross-border-expansion': 'get_flowchart',
    'builtin-global-tax-guide': 'get_flowchart',
    'builtin-listing-optimizer': 'get_flowchart',
    'builtin-listing-translator': 'get_flowchart',
    'builtin-profit-calculator': 'get_flowchart',
    'builtin-shopify-operator': 'get_flowchart',
    'builtin-tiktok-trend-tracker': 'get_flowchart',
    'builtin-hot-content-monitor': 'get_flowchart',
    'builtin-script-rewriter': 'get_flowchart',
    'builtin-content-to-video': 'get_flowchart',
    // LLM prompt 引导类技能 — 任意 action 都返回 ok
    'builtin-seedance': 'help',
    'builtin-seedance-ad-creative': 'help',
    // 自身跳过
    'builtin-safeopc-skill-tester': 'skip',
  }

  // ── action: status ──
  if (action === 'status' || !action) {
    const report = loadReport()
    if (!report) {
      return {
        action: 'status',
        message: '尚未执行过测试。请运行 { "action": "run" } 开始自动测试。',
        hasReport: false,
      }
    }
    return {
      action: 'status',
      hasReport: true,
      lastRunAt: report.runAt,
      totalSkills: report.results.length,
      passed: report.results.filter(function(r) { return r.success }).length,
      failed: report.results.filter(function(r) { return !r.success }).length,
      results: report.results.map(function(r) {
        return {
          skillId: r.skillId,
          skillName: r.skillName,
          action: r.action,
          success: r.success,
          duration: r.duration,
          error: r.error,
          resultSummary: r.resultSummary,
        }
      }),
    }
  }

  // ── action: report ──
  if (action === 'report') {
    const report = loadReport()
    if (!report) {
      return { action: 'report', message: '尚未执行过测试。请先运行 { "action": "run" }。' }
    }

    // 用 LLM 生成可读报告
    const summary = report.results.map(function(r) {
      return '- ' + r.skillName + ' (' + r.skillId + '): action=' + r.action +
        ', ' + (r.success ? '✅ 通过' : '❌ 失败') +
        ', 耗时 ' + r.duration + 'ms' +
        (r.error ? ', 错误: ' + r.error : '') +
        (r.resultSummary ? ', 结果: ' + r.resultSummary : '')
    }).join('\n')

    const prompt = '你是一个 QA 工程师。以下是 safeopc 内置技能自动测试的结果，请生成一份简洁的中文测试报告：\n\n' +
      '测试时间: ' + report.runAt + '\n' +
      '总技能数: ' + report.results.length + '\n' +
      '通过: ' + report.results.filter(function(r) { return r.success }).length + '\n' +
      '失败: ' + report.results.filter(function(r) { return !r.success }).length + '\n\n' +
      '详细结果:\n' + summary + '\n\n' +
      '请生成测试报告，包含：1.总体评价 2.各技能状态 3.失败原因分析 4.改进建议'

    const llmReport = await cap.llm.complete(
      [{ role: 'user', content: prompt }],
      { max_tokens: 1500, temperature: 0.4 }
    )

    return {
      action: 'report',
      report: llmReport || summary,
      rawResults: report.results,
      runAt: report.runAt,
    }
  }

  // ── action: run — 自动测试所有内置技能 ──
  if (action === 'run' || action === 'execute') {
    cap.runtime.log('skill_tester', '开始自动测试所有内置技能')

    // 1. 通过 Tauri invoke 获取所有内置技能列表
    let builtinSkills = []
    try {
      // 尝试通过 Tauri invoke 获取
      if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke) {
        const invoke = window.__TAURI_INTERNALS__.invoke
        builtinSkills = await invoke('get_builtin_skills_command') || []
      }
    } catch (e) {
      cap.runtime.log('skill_tester', '获取技能列表失败: ' + e.message)
    }

    // 降级：如果无法获取列表，使用已知技能 ID
    if (!builtinSkills || builtinSkills.length === 0) {
      builtinSkills = [
        { id: 'builtin-wechat-publisher', name: '公众号文章技能' },
        { id: 'builtin-xiaohongshu-publisher', name: '小红书文案技能' },
        { id: 'builtin-auto-product-comm', name: '自动选品智能沟通' },
        { id: 'builtin-trace-auto', name: 'AIMarketing' },
      ]
    }

    cap.runtime.log('skill_tester', '发现 ' + builtinSkills.length + ' 个技能')

    // 2. 逐一执行安全测试
    const results = []
    for (var i = 0; i < builtinSkills.length; i++) {
      var skill = builtinSkills[i]
      var skillId = skill.id || skill.skill_id || ''
      var skillName = skill.name || skill.skill_name || skillId
      var testAction = SAFE_ACTIONS[skillId] || 'get_flowchart'

      // 跳过自身或显式标记为 skip 的技能
      if (testAction === 'skip' || skillId === 'builtin-safeopc-skill-tester') {
        results.push({
          skillId: skillId,
          skillName: skillName,
          action: 'skip',
          success: true,
          duration: 0,
          error: null,
          resultSummary: '自身 — 跳过',
        })
        continue
      }

      cap.runtime.log('skill_tester', '测试 ' + skillName + ' (' + skillId + ') action=' + testAction)

      var t0 = Date.now()
      var success = false
      var errorMsg = null
      var resultSummary = ''

      try {
        // 获取技能代码并执行
        var skillCode = skill.code
        if (!skillCode) {
          // 重新拉取完整技能列表（含 code）
          try {
            if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke) {
              var fullList = await window.__TAURI_INTERNALS__.invoke('get_builtin_skills_command') || []
              var full = fullList.find(function(s) { return s.id === skillId })
              if (full) skillCode = full.code
            }
          } catch (e2) {
            throw new Error('无法获取技能代码: ' + e2.message)
          }
        }

        if (!skillCode) {
          throw new Error('技能代码为空')
        }

        // 清理 ES module 语法
        var code = skillCode
          .replace(/export\s+default\s+/g, 'var __default_export__ = ')
          .replace(/export\s+const\s+/g, 'const ')
          .replace(/export\s+\{/g, '{')
          .replace(/^\s*import\s.+$/gm, '')
        code += '\nreturn typeof handler === "function" ? handler : (typeof execute === "function" ? execute : null);'

        // 执行技能
        var fn = new Function(code)
        var handlerFn = fn()
        if (typeof handlerFn !== 'function') {
          throw new Error('技能未暴露 handler/execute 函数')
        }

        // 用安全 action 执行
        var testParams = { action: testAction }
        var execResult = await Promise.race([
          handlerFn(testParams, null),
          new Promise(function(_, reject) {
            setTimeout(function() { reject(new Error('执行超时 30s')) }, 30000)
          }),
        ])

        // 检查结果的 ok 字段：技能返回 { ok: false, error: '...' } 时应判为失败
        if (execResult && execResult.ok === false) {
          success = false
          errorMsg = execResult.error || '技能返回 ok=false'
          resultSummary = JSON.stringify(execResult).slice(0, 200)
          cap.runtime.log('skill_tester', skillName + ' 测试失败(ok=false): ' + errorMsg)
        } else {
          success = true
          resultSummary = JSON.stringify(execResult).slice(0, 200)
          cap.runtime.log('skill_tester', skillName + ' 测试通过 (' + (Date.now() - t0) + 'ms)')
        }
      } catch (e) {
        success = false
        errorMsg = (e && e.message) || String(e)
        cap.runtime.log('skill_tester', skillName + ' 测试失败: ' + errorMsg)
      }

      results.push({
        skillId: skillId,
        skillName: skillName,
        action: testAction,
        success: success,
        duration: Date.now() - t0,
        error: errorMsg,
        resultSummary: resultSummary,
      })

      // 上报 coverage
      try {
        if (typeof window !== 'undefined' && window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke) {
          await window.__TAURI_INTERNALS__.invoke('record_builtin_skill_run_command', {
            skillId: skillId,
            action: testAction,
            status: success ? 'ok' : 'error',
          })
        }
      } catch {}
    }

    // 3. 保存报告
    var report = {
      runAt: new Date().toISOString(),
      totalSkills: results.length,
      passed: results.filter(function(r) { return r.success }).length,
      failed: results.filter(function(r) { return !r.success }).length,
      results: results,
    }
    saveReport(report)

    cap.runtime.log('skill_tester', '测试完成: ' + report.passed + '/' + report.totalSkills + ' 通过')

    return {
      action: 'run',
      summary: '自动测试完成：' + report.passed + '/' + report.totalSkills + ' 通过，' + report.failed + ' 失败',
      totalSkills: report.totalSkills,
      passed: report.passed,
      failed: report.failed,
      results: results.map(function(r) {
        return {
          skillId: r.skillId,
          skillName: r.skillName,
          action: r.action,
          success: r.success,
          duration: r.duration,
          error: r.error,
          resultSummary: r.resultSummary,
        }
      }),
    }
  }

  // ── 默认 ──
  return {
    message: 'Safeopc 技能自动测试器 — 自动发现所有内置技能并逐一执行安全测试',
    actions: ['run', 'status', 'report'],
    hint: '示例: { "action": "run" } 开始自动测试所有技能',
  }
}
