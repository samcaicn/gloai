package com.wangzan101.mika.shell;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Surface;

// 运行在 shell 身份(uid 2000)进程里的常驻服务:经无线调试拉起的内置助手。
interface IMikaShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=已启动 1=用户正在主屏使用该 App(不启动,不打扰主屏) 2=没有可启动的入口 3=出错
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean setAudioMuted(String packageName, boolean muted) = 8;
    // 冷启动:先结束该 App 的进程再启动;返回值同 startApp,用户正在主屏使用时不结束、返回 1;
    // App 在 Mika 的屏以外还有任务时不结束,改为普通启动并返回 4
    int restartApp(int displayId, String packageName) = 9;
    // 该包当前的 PLAY_AUDIO 模式(allow/ignore/default…);读不到返回 null
    String getAudioMode(String packageName) = 10;
    // 把 PLAY_AUDIO 设回指定模式;用于 Mika 异常退出后按持久化记录恢复
    boolean setAudioMode(String packageName, String mode) = 11;
    // 任务所在的屏;任务已不存在返回 -1
    int displayOfTask(int taskId) = 12;
    // 任务最上面页面所属的包;任务不存在返回 null
    String packageOfTask(int taskId) = 13;
    // 以 shell 身份把剪贴板设成这段纯文字(不弹系统的「已复制」浮层),供粘贴到只认真实输入的编辑区。
    // 内容标为敏感(API 33+)。第一次设置前记下原剪贴板;读不到原剪贴板时不动它。返回 0=已设置 1=读不到原剪贴板(未改动) 2=出错
    int setClipText(String text) = 14;
    // 还原 setClipText 之前的剪贴板(原先为空则清空);已被用户换掉时不动并视为完成,此刻读不到则保留记录、返回 false 待下次再还原
    boolean restoreClip() = 15;
    // 打开链接或安卓设置页(action 为空时按 VIEW 打开 uri;packageName 限定由哪个 App 打开)。
    // 返回值同 startApp,另有 5=没有 App 能打开 6=链接不允许;解析出的 App 在主屏有任务时不启动、返回 1
    int startView(int displayId, String action, String uri, String packageName) = 17;
    // 该包的任务所在的 Mika 虚拟屏(并行任务查冲突用);不在 Mika 的任何一块屏上返回 -1
    int displayOfPackage(String packageName) = 18;
    // setClipText 之前用户剪贴板的文字(原先为空或不是文字时为空串);没有待还原的返回 null。供 Mika 加密留底,进程被杀后还原
    String savedClipText() = 19;
    // 剪贴板仍是 Mika 放进去的 ours 时,换回用户原来的文字 original(空串则清空);已被用户换掉时不动
    // 已还原、用户已换掉或已不需要还原返回 true;此刻读不到或写入失败返回 false,调用方保留加密留底
    boolean restoreClipText(String original, String ours) = 20;
    // 服务所在进程的 [uid, pid];Mika 收下内置助手的 Binder 前据此确认它就在来交接的那个进程里
    int[] identity() = 21;
    // 撤掉 Mika 这次绑定留下的改动(还原剪贴板、解除静音、释放所有虚拟屏),进程不退出;内置助手解绑时用
    void releaseAll() = 22;
    // 以 shell 身份连 Mika 自己 WebView 的调试套接字(抽象名 webview_devtools_remote_<pid>,pid 须是 Mika 的进程),
    // 把连好的套接字交回;名字不合规矩或连不上返回 null。Mika 自己直连被系统拦下时用
    ParcelFileDescriptor openDevtoolsSocket(String name) = 23;
    // 在本任务已建好的虚拟屏上下文中，以 shell 身份执行命令；返回 exitCode 和 output。
    Bundle runSystemCommand(int displayId, String command) = 24;
    // 当前虚拟屏最上面 Activity 的完整类名；没有任务时返回 null。
    String topActivity(int displayId) = 25;
    // 往这块屏发 Ctrl+键(只放行 A 全选、V 粘贴):0=按下未投递 1=完整按键已投递 2=可能只投递了一部分
    int injectCtrlKey(int displayId, int keyCode) = 26;
    // `pm list users` 的原样输出;出错为 null
    String listUsers() = 27;
    // `pm list packages --user <userId>` 的原样输出;出错为 null
    String listPackages(int userId) = 28;
    // 该包是系统自带的(含更新过的系统 App);查不到返回 false
    boolean isSystemPackage(String packageName) = 29;
    // 把一段文字分享给该包(ACTION_SEND text/plain,EXTRA_TEXT),启动到这块屏;返回值同 startApp,另有 5=该包没有收文字分享的页面;
    // 该包在主屏有任务时不启动、返回 1
    int startShare(int displayId, String packageName, String text) = 30;
    // 从现在起盯着主屏 windowMs:这段时间里主屏最上面换了的任务都记下(见 mainArrivals);返回此刻主屏最上面的任务,没有为 -1。
    // Agent 每个动作之前调:App 不从自己在 Mika 屏上的页面、而是从后台打开另一个 App 时,系统把它放到主屏,Mika 据此认出、挪回来
    int watchMainScreen(int windowMs) = 31;
    // sinceUptimeMs(SystemClock.uptimeMillis)之后主屏冒到最上面的任务,先后排;字段见 MainScreenWatch.toBundle。出错为 null
    Bundle mainArrivals(long sinceUptimeMs) = 32;
    // 任务所属的用户(分身、工作资料是别的用户);任务不存在返回 -1
    int userOfTask(int taskId) = 33;
    // 在 Mika 自己的屏上按一根手指的一笔触摸:times[i] 毫秒(相对按下)时手指在 (xs[i], ys[i]);第一个点按下,最后一个点抬起。
    // 按时间逐个注入,注完才返回(至多约 10 秒);不是 Mika 的屏、参数不对或按下没注入成功返回 false
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    // 系统动作(设闹钟、计时器、打开闹钟列表、打开预填的新日程,见 SystemIntents;别的 intent 一律不启动),启动到这块屏。
    // 先以 shell 身份启动;接手的页面要求 Mika 有的权限(如设闹钟)而 shell 没有时,改为发出 Mika 做好的 asMika(以 Mika 的身份启动、
    // 屏仍是这块)。返回值同 startView:5=没有页面接,6=不在放行之列;接手的 App 在主屏有任务时不启动、返回 1
    int startSystemAction(int displayId, in Intent intent, in PendingIntent asMika) = 35;
    // 以 shell 身份跑一条设置用的命令(授权、无障碍列表等;和经已配对的无线调试跑一样),不限于某块屏;返回 exitCode 和 output。
    // 助手是电脑经 USB 起的、没配对过无线调试时,Mika 的这类命令只能经这里
    Bundle runCommand(String command) = 36;
    // Mika 的进程被杀后助手要不要把它拉起来(RestoreWish.flags:1=拉起来 2=拉起来前写回无障碍服务)。
    // 助手读不到 Mika 的设置,只记最后交来的这一份;Mika 每次交接后和心愿变了时都交一次
    void setRestoreWish(int flags) = 37;
    // 能打开里面 App 的那几份别的用户(分身、工作资料一类;规则见 AppCopies):users=int[] 编号,summary=给诊断信息的一行
    // (编号、标记、类型、资料组,不含名字和装了什么);读不到用户为 null
    Bundle appCopyUsers() = 38;
    // 在另一份用户 userId 里打开该包,启动到这块屏;只认 appCopyUsers 列出的那几份,别的返回 8。返回值同 startApp / restartApp。
    // 主屏、Mika 另一块屏上的占用只看这一份(包名 + 用户):用户在主屏用着本来那份时不挡这一份,反过来也一样。
    // cold 时只结束这份用户里的进程,另一份不动;userId 是 Mika 自己的用户时等同 startApp / restartApp
    int startAppInUser(int displayId, String packageName, int userId, boolean cold) = 39;
    // 该包在 userId 那份用户里的任务所在的 Mika 虚拟屏;不在 Mika 的任何一块屏上返回 -1
    int displayOfPackageInUser(String packageName, int userId) = 40;

    // 主屏设备准备,与虚拟屏按键白名单分开;凭据只经 Binder 传入,不经 shell 命令行。
    boolean wakeDevice() = 41;
    boolean dismissDeviceKeyguard() = 42;
    // clear=输入前要回删的已有字符数(空框为 0,避免在部分系统上空框 DEL 关掉密码页)
    boolean enterDeviceCredential(int kind, in byte[] credential, int clear) = 43;

    // 同 getAudioMode / setAudioMuted / setAudioMode,作用在 userId 那份用户里的那份(分身、工作资料);
    // userId 是 Mika 自己的用户时等同原来的几个。PLAY_AUDIO 按包 + 用户分开设,两份互不影响
    String getAudioModeInUser(String packageName, int userId) = 44;
    boolean setAudioMutedInUser(String packageName, int userId, boolean muted) = 45;
    boolean setAudioModeInUser(String packageName, int userId, String mode) = 46;
    // 该包(userId 那份)在主屏上有看得见的任务,即用户正在主屏用它;看不清主屏时返回 true
    boolean visibleOnMainScreen(String packageName, int userId) = 47;

    // 任务结束:按规则处理这块屏上的 App(见 AppsAfterTask),再释放这块屏。留下的挪到主屏、排在最后面(不到用户眼前),
    // 正在出声的和 close 里的随屏关掉。keep / close 是模型标过的键(包名,另一份为 包名@用户)。
    // 返回 AppsAfterTask.KEY_* :kept=留下的键,closed=关掉的「键:原因」,note=挪不了时的一句原因(可为空)。不是 Mika 的屏为 null
    Bundle finishDisplay(int displayId, in String[] keep, in String[] close) = 48;
    // 安全锁屏还停在通知/指纹页时,做一次定向给 SystemUI 的通用上滑;不含凭据。
    boolean revealDeviceCredential() = 49;

    // 只在 Mika 的虚拟屏、指定的最前面任务里,把可打印 ASCII 作为键盘事件输入;不发送控制字符
    // 0=未发送 1=全部事件已投递 2=投递不确定(可能只输入了前缀)
    int injectAsciiText(int displayId, int taskId, String packageName, String text) = 50;

    // 对 SystemUI 做一次定向点击,用于生物识别优先锁屏页上「用密码解锁」这类回退按钮;不含凭据。
    boolean revealDeviceCredentialTap(int x, int y) = 51;

    // 通话音频独立于通话控制。0=自动,1=直连运营商音频,2=Android PSTN 重定向。
    Bundle probeCallAudio(int mode) = 52;
    // 仅 ACTIVE 运营商通话；owner 每通独立，死亡时停止。下行可读/上行可写的匿名管道均为单声道 PCM16LE。
    Bundle openCallAudio(int mode, IBinder owner) = 53;
    void stopCallAudio(IBinder owner) = 54;
    boolean flushCallAudio(IBinder owner) = 55;
    Bundle callAudioStats(IBinder owner) = 56;

    // 当前用户的 /sdcard 或 /storage/emulated/<user> 绝对路径,按名称排序;首次 afterName=null,limit 为 1..128。
    // status=ok/not_found/denied/not_directory/invalid/io_error;成功时 names、types
    // (0=其他或链接 1=普通文件 2=目录)等长,next 非空时传给下一页;目录变化时不保证快照。
    Bundle listFiles(String path, String afterName, int limit) = 57;
    // 只读打开普通文件;结果 status=ok/not_found/denied/not_file/invalid/io_error,
    // 成功时 fd 为 ParcelFileDescriptor,调用方负责关闭。路径按 shell 的权限访问。
    Bundle openFile(String path) = 58;
    // 只读、有界搜索；返回 status、paths、visited、truncated 和可选 reason。
    Bundle searchFiles(String path, String name, String extension, long modifiedAfter, long modifiedBefore, int maxDepth, int maxEntries, int timeoutMs) = 59;
    // Replace the device-file bridge policy. Empty roots deny all access; protocol and revision are separate.
    Bundle setDeviceFilePolicy(int protocol, String session, long revision, in String[] roots, in boolean[] recursive) = 60;

    // 把工作区里的一个文件分享给一个包:ACTION_SEND + EXTRA_STREAM,content URI 已授权可读;只打开分享页,不发出。
    int startShareFile(int displayId, String packageName, String uri, String mime, String text) = 61;

    // 原地改变 Mika 虚拟屏尺寸并替换接收画面的 Surface，保留屏上的任务。
    boolean resizeDisplay(int displayId, in Surface surface, int width, int height, int dpi) = 62;

    // 沿用早先定下的销毁事务号;AIDL 要求要么全部方法编号要么都不编号
    void destroy() = 16777114;
}
