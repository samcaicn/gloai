package com.wangzan101.mika.core.gate

/** 等待期间发生过锁屏周期:准备好的写屏动作按旧屏幕排的,先重读现在的屏幕再决定怎么做。 */
class ScreenRecoveryRequired : Exception()
