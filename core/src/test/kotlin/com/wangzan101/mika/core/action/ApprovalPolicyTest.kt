package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyTest {
    @Test fun onlyPaymentAsks() {
        for (level in ActionRisk.entries) {
            assertEquals("$level", Approval.Allow, ApprovalPolicy.decide(Risk(level)))
            assertEquals("$level payment", Approval.Ask, ApprovalPolicy.decide(Risk(level, payment = true)))
        }
    }

    @Test fun everyOriginAndAttendance() {
        for (origin in RunOrigin.entries) for (attendance in Attendance.entries) for (level in ActionRisk.entries) {
            val run = RunProvenance(origin, attendance)
            // 不花钱的:不可逆的在无人值守/不在手机上不做,其余(只读、改动、对外但可撤销)谁发起都做
            val unattended = origin != RunOrigin.Ui || attendance == Attendance.Unattended
            val noMoney = if (level == ActionRisk.Irreversible && unattended) Approval.Deny else Approval.Allow
            assertEquals("$origin $attendance $level", noMoney, ApprovalPolicy.decide(Risk(level), run))
            // 花钱的:只有手机上发起、人在场时问;问不到的(渠道、定时、MCP、子任务、无人值守)不做
            val expected = if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
            assertEquals("$origin $attendance $level payment", expected, ApprovalPolicy.decide(Risk(level, payment = true), run))
        }
    }
}
