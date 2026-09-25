/// main.dart — 应用入口。
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'data/api_client.dart';
import 'data/local_store.dart';
import 'data/store_repository.dart';
import 'state/session.dart';
import 'app/app.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final local = LocalStore();
  await local.init();
  final api = ApiClient();
  final repo = StoreRepository(api: api, local: local);
  final session = Session(repo);
  await session.bootstrap();
  runApp(MultiProvider(
    providers: [
      Provider<StoreRepository>.value(value: repo),
      ChangeNotifierProvider<Session>.value(value: session),
    ],
    child: const AppRoot(),
  ));
}
