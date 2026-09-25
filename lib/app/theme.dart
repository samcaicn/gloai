/// theme.dart — 全局主题与文案常量。
library;

import 'package:flutter/material.dart';

class AppTheme {
  static const primary = Color(0xFF1F6FEB); // 品牌蓝
  static const bg = Color(0xFFF5F6F8);
  static const danger = Color(0xFFE5484D);
  static const ok = Color(0xFF2DA44E);

  static ThemeData get light => ThemeData(
        useMaterial3: true,
        colorSchemeSeed: primary,
        scaffoldBackgroundColor: bg,
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.white,
          foregroundColor: Colors.black87,
          elevation: 0,
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: primary,
            foregroundColor: Colors.white,
            minimumSize: const Size(double.infinity, 48),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(10),
            ),
          ),
        ),
      );

  /// 自查项中文标签（与后端 code 对齐）。
  static const Map<String, String> inspectLabels = {
    'mask': '口罩',
    'chef_hat': '厨帽',
    'smoking': '抽烟',
    'rodent': '鼠患',
    'leave_post': '离岗',
  };

  /// 自查项整改建议。
  static const Map<String, String> inspectSuggestions = {
    'mask': '后厨人员必须规范佩戴口罩，立即整改并复核。',
    'chef_hat': '后厨人员必须佩戴厨师帽，防止头发污染食品。',
    'smoking': '后厨严禁吸烟，立即制止并记录责任人。',
    'rodent': '发现鼠患痕迹，立即报修消杀并封堵孔洞。',
    'leave_post': '岗位出现长时间离岗，请确认当班人员在岗。',
  };
}
