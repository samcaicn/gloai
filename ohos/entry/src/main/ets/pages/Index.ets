// Index.ets —— Flutter 承载页（OpenHarmony 标准结构）。
import { FlutterPage } from '@ohos/flutter_ohos';

@Entry
@Component
struct Index {
  private flutterPage: FlutterPage | null = null;

  aboutToAppear(): void {
    this.flutterPage = FlutterPage.getInstance();
    this.flutterPage?.attachFlutterView(); // 由 FlutterAbility 自动管理，这里留空即可
  }

  build() {
    Stack() {
      // Flutter 渲染由引擎接管，页面容器内无需额外 UI
    }
    .width('100%')
    .height('100%')
  }
}
