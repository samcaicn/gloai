/// app.dart — MaterialApp 壳与路由。
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../state/session.dart';
import '../ui/login_page.dart';
import '../ui/home_page.dart';
import '../ui/camera/camera_manage_page.dart';
import '../ui/inspect/inspect_page.dart';
import '../ui/report/report_page.dart';

class AppRoot extends StatelessWidget {
  const AppRoot({super.key});

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    return MaterialApp(
      title: '店长自查',
      theme: AppTheme.light,
      home: session.authed ? const HomePage() : const LoginPage(),
      routes: {
        '/home': (_) => const HomePage(),
        '/login': (_) => const LoginPage(),
        '/camera': (_) => const CameraManagePage(),
        '/inspect': (_) => const InspectPage(),
        '/report': (_) => const ReportPage(),
      },
      debugShowCheckedModeBanner: false,
    );
  }
}
