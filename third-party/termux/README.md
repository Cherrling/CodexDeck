# Termux 组件来源与适配

来源：https://github.com/termux/termux-app/tree/8629e632fcb95da272221be327db653fb24befe9

仅导入 terminal-emulator / terminal-view 的 Java 源码、资源、manifest 与 emulator 测试；未导入 Termux app、userspace、JNI 或本地进程实现。根许可证见 UPSTREAM-LICENSE.md，保留源文件原有许可头。项目采用 GPL-3.0-only；部分上游文件另有 Apache-2.0 声明。

TerminalSession.java 的接入补丁：把本地进程接入替换为异步 TerminalTransport，使用有界 ByteQueue，并在主线程操作 emulator。原始差异见 patches/0001-transport-session.patch。新增 TerminalTransport、TerminalSize、ResizeCoordinator、TransportSessionTest 以及两个 Gradle 模块配置。原始测试保持原样；OSC 8 解析扩展见下文。

TerminalView.java 仅移除类声明的 `final`（patches/0002-extensible-terminal-view.patch），允许 App 子类统一中键/IME 粘贴策略，以及可见时按帧合并刷新。其方法体、选区、滚动和解析行为不修改。

TerminalRenderer 的可选选区前景/背景补丁见 patches/0003-selection-colors.patch；未配置时保持上游反色行为，不修改协议颜色和 cell 样式。

upstream-files.json 记录导入时的 SHA256。`python3 scripts/check_termux.py` 验证未修改文件与来源清单一致。升级时重新下载固定 commit，对照补丁更新会话接口，并运行所有上游和接入测试；不得直接覆盖定制会话。

尺寸协调器保存最新需求，仅允许一个尚未确认的 resize；通道就绪后补发最新尺寸。同 rows/cols 不重复发送。像素窗口尺寸由 cell pixels × grid 计算。没有定时器或固定 debounce。

OSC 8 链接补丁见 `patches/0004-osc8-hyperlinks.patch`，对应文件哈希在 `patches/osc8-patched-files.json`。新增 `TerminalHyperlinks` 和 `HyperlinkTest` 为本项目代码。链接 ID 懒分配于行，随字符复制、宽字符覆盖、擦除、滚动和 reflow 更新；不改变字符样式或渲染器。目标表最多 256 个 URI、合计 65,536 UTF-16 字符，单个 URI 最多 4,096 字符，重复 URI 复用记录。淘汰后旧 ID 不会指向其他目标，reset 清空目标表但不重用 ID。无 hover 分组，因此忽略 OSC 8 的 `id` 参数；支持现有解析器的 BEL / ESC-backslash 结束方式。

普通 URL 由 App 在触摸时读取逻辑行识别；不增加逐帧解析、不扫描整个历史、不自动访问网络。App 负责打开策略与确认弹窗。
