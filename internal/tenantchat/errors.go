package tenantchat

import "errors"

var (
	errNoAI            = errors.New("全局 AI 未配置：请先在「系统管理 → AI 设置」中填写 API Key")
	errNoStore         = errors.New("存储未初始化")
	errNoUser          = errors.New("未登录")
	errNotFound        = errors.New("对聊会话不存在")
	errNotParticipant  = errors.New("你不是该会话的参与者")
	errNotPaired       = errors.New("会话尚未配对：需甲、乙双方都加入后才能开始")
	errSeatTaken       = errors.New("乙席位已被认领")
	errBadCode         = errors.New("邀请码不正确")
	errSameUser        = errors.New("不能与自己配对")
	errNoParticipant   = errors.New("租户不存在")
	errRunning         = errors.New("对话正在自动进行中，请先暂停")
	errPassiveDisabled = errors.New("对方未开启被动会话（不允许被找）")
	errBadBounds       = errors.New("参数超出范围")
	errHandleTaken     = errors.New("该名称已被占用，请换一个")
	errBadHandle       = errors.New("名称只能包含小写字母、数字、- 和 _，长度 2~32")
	errPassiveNotFound = errors.New("找不到该用户")
)
