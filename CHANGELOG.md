# Changelog

Plume shows the section for its own version in **Plume settings → Updates** after an update.
After an update, a card on the home screen shows the first 6 entries. Put the 6 most interesting changes first. Put small fixes last, as plain lines.
The build stops if `versionName` has no section here.

Write every entry in ASD-STE100 Simplified Technical English:

- Write one idea in each sentence. Use 20 words or fewer in each sentence.
- Use the active voice and the present tense.
- Write instructions as commands: "Select **Updates**", not "**Updates** can be selected".
- Use one word for one thing. Use the names that Plume shows on the screen.
- Start each entry with "- ". Plume shows each entry as one item. It removes `**` marks.

Put changes that are not released under `## Unreleased`. Rename that heading to the version number when you release.

## 1.14.2

- **闹钟延迟有数字。** 上报的闹钟事件记录原定时间与实际延迟毫秒数。降级闹钟晚多久，一眼可见。
- **答题页异常退出有记录。** 正常答完记「完成」，中途消失记「中断」。系统强杀与断电不再无迹可寻。
- **朗读失败有记录。** 引擎就绪但念不出声的情况（含合成阶段错误）记为「朗读失败」。
- **管理台失联有记录。** 管理台无法监听时上报「管理台故障」，设备信息里显示其端口。
- **上报附答题排期。** 设备信息记录答题开关、下次触发时间、间隔与时区，服务器可比对「该何时响」与「实际何时响」。
- **上报失败分原因。** 连不上与读写失败分开记录。

## 1.14.1

- **未授「闹钟和提醒」权限有提示。** Android 12 及以上未授权时，桌面提示一次，管理台总览显示红字。授权后答题时间不再被系统推迟。
- **管理台新增两项自检。** 「答题时间」显示闹钟权限；「主屏幕键服务」区分未开启与未生效，并给出开启路径。
- **自检给出客户可用的初始化命令。** 权限未授或服务未开启时，显示对应的 adb 命令（含 TCL/雷鸟的绑定注意事项），用电脑连电视照做即可完成初始化。
- **语音故障分成两类上报。** 电视没装语音引擎时上报「缺少引擎」。装了引擎但启动不了时上报「启动失败」。此前两种情况都记作启动失败。首批 22 台设备里有 19 台是没装引擎。
- **Android 11 及以上能查到已装引擎。** 清单加了语音引擎查询声明。系统不再隐藏引擎，上报里的引擎列表不再总是空的。
- **上报增加桌面候选列表。** 系统没设默认桌面时，上报会列出所有能当桌面的应用包名，便于适配视频电视的私有桌面。
- **精确闹钟被拒时记下系统版本。** 该事件附带 Android 版本与降级方式，便于区分系统默认拒绝与厂商故障。
- **闹钟触发时也上报报告。** 不常打开桌面的电视也能按期上传诊断数据。

## 1.14.0

- **异常自动记录并每日上报。** 崩溃、开机不启动、答题不弹出、答题被压到后台、无障碍服务假死、语音缺失、更新失败都会记在本机，每天匿名上报一次，用于适配更多电视品牌。
- **品牌专属修复只在本品牌运行。** 只在 TCL 上验证过的修复代码（无障碍服务假死重绑）只在 TCL 设备上执行，其他品牌只上报问题、不受改动影响。
- **上报不含隐私。** 设备以随机编号标识，不采集硬件标识；不上报答题内容、成绩与观看数据。

## 1.13.2

- **断电重启后自动回到宝贝成长桌面。** 电视开机先显示厂商桌面时，无障碍服务绑定后会主动接管，不再等一次窗口变化。
- **答题中途断电，开机接着答。** 答题页打开时记下状态，开机后自动回到答题页。
- **强制休息不怕断电。** 休息倒计时存在本机，开机后自动回到休息页，剩余时间照算。
- **开机后答题闹钟不再丢。** 部分电视不投递开机广播，闹钟改由无障碍服务在绑定后补上。

## 1.13.1

- **只有 10 岁及以下朗读题目。** 11 岁起不再朗读，孩子自己看屏幕答题。管理台的语音开关照旧可用。
- **进答题页立刻开口。** 语音引擎在桌面启动时就加载好，不再每题等模型加载。题干先读，选项随后。
- **答错换题不再等。** 换题时不再先停止引擎，第二题不会出现一段冷却时间才开口。
- **符号基本都能读。** `(x - 3)(x - 4)` 读作「括号艾克斯减三括号艾克斯减四括号」。`6/8` 读作「八分之六」。`18:27` 读作「十八比二十七」。`k/x` 读作「开除以艾克斯」。`□` 读作「方块」。`3×10^5 米/秒` 读作「三乘十的五次方米每秒」。
- **短选项不朗读。** 只有一个字、一个数字、一个字母或一串英文的选项不读出来。「3年」「3.5米」这类数字加一个单位的选项也不读。这类选项一读就等于给出答案。题干照常朗读。
- **答题时视频暂停。** 答题页接管声音焦点，正在播放的视频暂停。答对后视频继续播放。
- **切走后能找回答题页。** 新增「显示在其他应用上层」权限。Android 10 及以上不再拦截答题页回到前台。
- **开机提醒只弹一次。** 点「我知道了」后，本次开机不再弹出后台管理提醒。下次开机再弹。
- **题干数字按中文读法朗读。** `24 - 13` 读作「二十四减十三」。`4.6` 读作「四点六」。数字原样送进引擎时会被跳过。
- **长选项带字母朗读。** 要朗读的选项，其字母读作「选项诶」。中文模型不会读单独的拉丁字母。英语单词里的字母保持不变。
- **朗读语速放慢。** 语速调到 0.85 倍，四个选项不再连成一句。
- **切走的那一秒就回来。** 答题页失去前台时立刻把自己拉回，不再等每秒一次的检查。
- **「切走弹回」可以在手机控制台开关。** 默认开启。关掉后，答题期间切到别的应用不会被拉回。
- **控制台能证明它有没有在工作。** 总览页显示四种状态：正常工作、已关闭、缺权限、还没拦截过。正常工作时显示已拦住的次数和最近一次时间。
- **装完不再丢主屏幕键。** 覆盖安装后系统不肯重新绑定无障碍服务，应用会自己把它重新启用一次。
- **安装脚本自动授权。** 用 `tools/growquiz-install.sh` 部署时自动授予该权限，无需在电视上手动设置。

## 1.13.0

- **题库年龄扩展到 5–15 岁。** 新增 15 岁题库 3000 题，全库达 33000 题。
- **15 岁题对齐初三水平。** 数学新增一元二次方程、二次函数、三角函数、圆等题型。语文与常识新增中考层素材。
- **答题页适配小屏幕。** 全部控件按屏幕比例缩放，小屏设备无需滚动即可看到完整页面。
- **其他页面同步适配小屏。** 首页内容超屏时可滚动。管理密码弹窗、长按菜单限高可滚动。设置面板宽度不超过屏幕 92%。

## 1.12.0

- **控制台改用底部分页。** 分为总览、设置、题库、报告四个分页。手机端不用长滚动找按钮。
- **总览页聚合常用操作。** 显示运行状态和今日概览。「立即开始答题」一屏可达。强制休息警告卡仅在休息中出现。
- **统计报告并入「报告」分页。** 内容与独立统计页一致。独立页 `/stats` 保留可用。
- **设置页改为分组列表。** 答题参数、抽题分类、功能开关分组显示。修改密码和语音测试收进「工具与账号」。
- **题库编辑改为底部弹层。** 「一键禁用/开启全部题目」默认折叠，防止误触。

## 1.11.0

- **新增批量导入题库。** 控制台新增独立导入页。上传 AI 生成的题库文件即可批量加题。
- **导入页附 AI 出题教程。** 教程说明文件格式、字段要求和生成步骤。附可复制的提示词模板。
- **导入前逐题校验。** 任一题格式错误则整批拒绝。页面列出每道错题的位置和原因。重复题自动跳过。
- **支持追加或替换。** 追加模式保留已有自定义题。替换模式清空后重建。
- **移除手动录题入口。** 控制台的单题录入表单已删除。编辑和删除功能保留。

## 1.10.1

- 修复题库源数据的重复条目。重新生成全部 30000 题。题目内容更丰富。

## 1.10.0

- **新增宝贝成长等级。** 答题积分分为 7 个等级，共 28 个阶位。从塑料到钻石，积分满 4 万达到钻石 4 阶。
- **等级积分规则明确。** 答对一题加 10 分。答错一题扣 5 分。同一题重复答错多扣 8 分。答对该题后，该题错题记录清零。
- **答题页顶部显示等级徽章。** 徽章按等级变色。同时显示积分和距离下一阶的分数。
- **升级有庆祝动画。** 升阶时全屏显示新等级。星光迸发，语音播报祝贺。答对返回前多停留一会。
- **新增学习统计报告页。** 控制台新增独立统计页。支持整体、年度、半年、季度、月度、周度查看。
- **统计页显示知识点掌握分布。** 按分类显示答题量、答对答错数和正确率。
- **统计页显示学习活跃度。** 图表显示每日或每周答题数量与正确率变化。错题单独标红。
- **统计页显示高频错题。** 列出错得最多的 5 道题和次数。
- **记录看电视时长。** 开启「主屏幕键」服务后，统计常见电视播放器和视频应用的观看时间。统计页按时段显示观看分钟数。
- **离线题库扩充到 30000 题。** 每个年龄 3000 题。孩子更难遇到重复题。
- **新增语文题型。** 新增拼音、近义词、反义词、成语、古诗文、部首、量词、归类等题型。
- **新增常识题型。** 新增节气、生肖、省会、朝代、首都等题目。
- **数学题增加新题型。** 应用题有不同的人物和物品组合。大年龄新增平方、公式等题型。
- **题库按年龄分片加载。** 答题只读取当前年龄的题库。出题更快，占用内存更少。
- **已有答题记录不受影响。** 原有 8000 题的编号不变。答对次数与禁用状态继续有效。

## 1.8.2

- **小米电视重启不再还原桌面。** MIUI 每次开机都把默认桌面还给自带桌面。现在自带桌面一出现，本应用就自动回到前台。
- **答对后回到正在看的内容。** 答题页改为独立任务。看电视时弹出答题，答对后直接回到刚才的应用。之前会先回桌面，应用重新加载。
- **识别更多国产电视桌面。** 开启「主屏幕键」服务后，TCL、海信、创维、乐视、腾讯极光盒子等的自带桌面出现时，同样自动切回本应用。
- **小米电视设置向导更直接。** 检测到小米电视时跳过「设为默认桌面」，直接引导开启「主屏幕键」服务。
- **「知道了」按钮遥控器可达。** 首次设置后，管理提醒的按钮之前只能用鼠标点。现在遥控器可直接选中。
- **TCL 应用内升级更顺。** 安装包按 TCL 的校验要求声明应用类型。应用内更新可通过安装校验。

## 1.8.1

- **安装包兼容老电视。** 签名改用 v1 加 v2 加 v3 方案。老电视的安装器也能识别安装包。

## 1.8.0

- **答题前必须思考。** 出题后数秒内选项无效。屏幕显示剩余秒数。默认 5 秒，控制台可调 0–60 秒。
- **连续答错强制休息。** 连错 3 题休息 10 分钟。之后每多错 1 题，多休息 10 分钟。
- **蒙题会被验证。** 快速连错后又快速答对，系统追加一道验证题。验证失败按连错 3 题处理。
- **答题期间每秒检查前台。** 若答题或休息被切到后台，自动弹回。防止用其他应用跳过。
- **密码框闪退修复。** 管理密码改为系统圆点显示。输入密码时不再崩溃。
- **弹窗适配小屏。** 更新记录等弹窗内容过高时可滚动。按钮保持在屏幕内。
- **分类抽题更准确。** 所选分类在当前年龄无题时，不再悄悄改用全部分类。控制台按年龄显示分类题量；无题的分类不可勾选。
- **休息页显示管理地址。** 强制休息页面显示控制台地址和二维码。家长扫码即可解除休息。
- **管理后台可解除休息。** 控制台显示休息状态，可一键解除。也可关闭强制休息功能。
- **开机答题触发更可靠。** 开机后弹出管理端提醒。按「确定」立即答一题。
- **手机扫码输入密码。** 密码框新增二维码；手机扫码后在手机上输入密码，电视自动解锁，小孩看不到。
- **管理端地址可扫码直达。** 启动提醒弹窗新增二维码，手机扫码直接打开管理控制台。

## 1.7.1

- **密码输入改为星号。** 设置页输入管理密码时显示 *，键盘预览也不再泄露内容。
- **键盘“完成”直接确认。** 输完密码按输入法的完成键即可确认。

## 1.7.0

- **管理后台可关闭语音朗读。** 控制台新增「语音朗读题目」开关；关闭后答题只显示文字。
- **QQ 群二维码修复。** 二维码改由应用直接绘制，内容与群卡片一致，扫描更稳。
- **密码框按钮更清楚。** 确认按钮在深色背景下清晰可见，取消按钮改为中文。
- **新增遥控器专用键说明。** 说明拦截 Netflix 等一键直达键的方法。

## 1.6.1

- **启动提示自适应屏幕。** 提示框按屏幕分辨率调整大小，内容过长时可滚动，小屏电视也能看全。
- **管理密码框重做。** 密码输入框改为与应用一致的外观；遥控器可直接操作，输错时提示更清楚。
- **关于页新增 QQ 群。** 加入用户交流 QQ 群的按钮与群二维码；电视上让手机扫码即可加群。
- **GitHub 链接更明确。** 关于页的项目链接改为 GitHub 仓库，并配 GitHub 图标。

## 1.6.0

- **正式签名。** 应用改用正式密钥签名。此后所有新版本都可直接覆盖安装。
- **换版须知。** 从旧版本换到本版本时，必须先卸载旧版本。卸载会清除学习进度和设置。
- **GitHub 正式发布。** 新版本发布到 GitHub Releases，更新服务器定时同步。

## 1.5.0

- **题库扩展到 14 岁。** 年龄段从 5–10 岁扩展到 5–14 岁。新增 11–12 岁和 13–14 岁两个学段。
- **题量提升到 8000。** 每个年龄 800 题，是原来的两倍多。
- **新增四个学科。** 新增英语、历史、地理和初中理科题。语文题也大幅扩充。
- **数学难度升级。** 高年龄段加入方程、负数、几何、概率等题型。

## 1.4.1

- **方形图标。** 桌面图标改为铺满的方形。四角用徽章的蓝色填满，不再露出黑边。

## 1.4.0

- **应用改名。** 应用显示名改为“宝贝成长桌面”。设置标题、无障碍服务名和控制台页头一并修改。
- **全新图标。** 桌面图标、Android TV 横幅和控制台页头换成新的圆形徽章。
- **可直接升级。** 包名不变，旧版本可直接覆盖安装，布局和设置保留。

## 1.3.0

- **题库分类与多选。** 题库按年龄和分类两个维度整理，答题可按分类多选抽题，不选即为全部。
- **家长自定义出题。** 管理控制台新增自定义出题，题目需选择年龄和分类，与内置题一起随机出现。
- **题库维护。** 管理控制台可按年龄、分类、状态筛选题目，勾选后批量禁用或启用，也可一键开启或禁用全部题目。
- **答对自动退休。** 同一道题答对满 5 次后自动禁用，避免已掌握的题目反复出现。
- **设置密码保护。** 设置管理密码后，电视上的 Wi-Fi、系统设置和桌面设置入口也需输入密码。
- **修改管理密码。** 控制端支持修改管理密码，修改前需先验证原密码。
- **关于页面。** 设置菜单新增“关于”：查看当前版本与内部版本号、扫描二维码打开项目主页，并支持手动检查更新。
- **自动更新增强。** 更新来源服务器上的最新发版每 3 小时自动从项目仓库同步。

## 1.2.0

- **自动更新。** 每天自动检查一次新版本，发现后自动下载并安装；更新来源改为自有服务器，不再指向上游 Plume。

## 1.0.0

- **Grow Quiz launcher.** Add a Chinese children quiz to the open-source Plume TV home screen.
- **Offline bank.** Include 300 questions for each age from 5 through 10.
- **Device-owner mode.** Lock the full-screen quiz until the child selects a correct answer.
- **Parent settings.** Choose a child age, a quiz interval and the next default home screen.

## 1.7.1

- **Photo of the day fix.** Plume no longer closes when you select **Photo of the day** before the photo downloads.

## 1.7

- **A new Plume settings.** Clear sections with new icons. Select **Plume settings → Style → Theme → Wallpaper**, and Plume takes its colours from your wallpaper.
- **Clear glass.** See more of your wallpaper through tiles and menus. Select **Plume settings → Style → Glass → Clear**. The edges catch the light when you select a tile.
- **Aerial Views.** Apple, NASA and Amazon scenes play as your wallpaper or your screensaver. Select **Plume settings → Wallpaper → Live wallpapers**. You can also upload your own MP4.
- **Bigger apps.** Select **Plume settings → Apps row → Size**, then **Large** or **Largest**. Banners and icon tiles both grow.
- **New clock.** Date and weather show on one line, with a weather icon. Select **Plume settings → Clock & weather → Clock style** or **Clock size**.
- **New 4K wallpapers.** Select **Plume settings → Wallpaper → Built-in → More photos** to download ten 4K photos. New photos appear without an app update.
- **Aerial Views screensaver.** Select **Plume settings → Screensaver → Screensaver → Aerial Views**. It uses the scenes that you downloaded for the wallpaper, so each scene downloads only once.
- **Shows wallpaper.** The pictures of the shows in your apps become your wallpaper. Select **Plume settings → Wallpaper → Shows from** to choose the apps. Plume fetches nothing until you select it.
- **Edit the top bar.** Hold **OK** on an icon at the top of the home screen. Select **Move**, **Hide** or **Add icon**. **Plume settings** stays on the bar.
- **Power menu icon.** Hold **OK** on a top-bar icon and select **Add icon**, then **Power menu**. The icon opens the Android power menu.
- **More apps.** Plume shows installed apps that do not have a TV launcher entry. Some apps need touch or a mouse.
- **Italian.** Plume is now available in Italian.
- **The selected tile rises.** The selected tile or card moves up out of its row, so you can find it from across the room.
- **Wallpapers show as they are.** Plume no longer adds dark fades over a picture or a video. The text and the glass change to suit the picture.
- **Home returns to your app.** Press Home in an app that you opened from a tile or a card. That tile or card is selected.
- **Open from tile.** An app opens out of the tile that you press, and the row settles when you come back. Select **Plume settings → Style → Open from tile** to turn it off.
- **Remote keys.** Plume can tell apart two buttons that send the same key. To separate them, remove the old key and learn the button again.
- The top icons, the text and the progress bars change colour to match the wallpaper.
- The Glass setting changes tiles, cards and the settings panel without a restart.
- A tile is selected when Home first opens.
- A video wallpaper shows its own first frame while it starts.
- With **Large** or **Largest** apps, the apps row no longer covers the Continue watching title.
- Glass rows no longer show a grey edge after the last tile.
- Continue watching shows the show you watched last first in apps such as Nuvio.
- On Fire TV, Plume comes back when you press Home on the Plume home screen or in the Plume settings.
- On Google TV, a notice or a sign-in screen no longer brings Plume to the front.
- On some CVTE TVs, the Home button returns to Plume from HDMI or Live TV.
- On Xiaomi TVs, **Plume settings → Updates → Install** opens the TV's installer. Before, Plume closed.
- On debloated Google TVs, the **Finish setup** card does not come back after a restart. Folder wallpapers load after a restart.
- Plume no longer closes when the theme changes while a picture loads, or when the screensaver ends.
- Old wallpapers no longer ship in the app. An old selection shows a plain colour until you select another wallpaper.
- Plume is now available in Brazilian Portuguese.
- Continue watching no longer shows a season or an episode that is not a number.

## 1.6

### Design
- **Glass.** A frosted-glass blur behind the tiles and menus. Plume blurs the wallpaper behind the tiles, menus, cards and the settings panel. Glass is on after the update. To turn it off, select **Plume settings → Appearance → Glass**.
- **A new Plume settings.** Each section has an icon and a live preview. Under its name, each section shows its settings. While you change a setting, the part of the home screen it changes stays bright.
- **Banner icons.** Each app shows its own picture, as on Apple TV. An app with no banner gets its own colour behind a white mark. Select **Plume settings → Apps row → Icon style**.
- **Hide the Shows row.** Show only your apps and wallpaper, and press Down for your shows. Press Up to hide the row again. Select **Plume settings → Shows row → Show row**.
- **Auto follows the sun.** With **Theme** set to **Auto**, Plume changes to dark at sunset and to light at sunrise.
- **Text that fits the wallpaper.** The clock, the weather and the Continue watching text change to white or dark to match your photo.
- **Set how strong the fades are.** Select **Plume settings → Appearance → Top fade** or **Bottom fade**. Choose from 0% to 100%.
- **Colour icons on the Shows row.** Each card can show the app's logo in its own colour. Select **Plume settings → Shows row → Icons**.
- **Monochrome banner.** A fourth icon style. The pictures are grey until you select one.

### New
- **Sources.** A new icon in the top bar lists your TV inputs. A device such as a PS5 shows with its logo.
- **Remote keys.** Choose what a button on your remote does when you press it or hold it. Select **Plume settings → Remote & Home button → Remote keys**.
- **Uninstall an app.** Hold **OK** on an app in the row and select **Uninstall**.

### Easier to use
- **Setup fits your TV.** The Home button setup starts with the step that works on your TV.
- **Settings in home screen order.** The sections follow the home screen from top to bottom. Your settings do not change.
- A long app name scrolls slowly while the app is selected. It no longer runs past its tile.
- Plume shows in the app list on Xiaomi TVs.

### Fixes
- **Less memory.** Plume no longer runs twice after an update. It also keeps each picture only once.
- On a Fire TV, an HDMI tile switches to its input. Before, it opened Live TV.

## 1.5

### New
- **Pick your bottom row.** Show any channel from your apps, such as a Stremio library. Select **Plume settings → Bottom row → Channels**. The row takes the channel's name, or a name you set in **Row name**.
- **Bing wallpaper.** Use the Bing picture of the day as your wallpaper. Select **Plume settings → Appearance → Light mode wallpaper** or **Dark mode wallpaper**, then select **Bing picture of the day**.
- **New languages.** Plume is now available in English, French, Turkish and Arabic. It uses the language of your TV.
- **PIN for Wi-Fi and Android settings.** If you set a PIN, Plume asks for it before it opens Wi-Fi or Android settings from the top bar.

### Design
- **See-through tiles.** The wallpaper shows through the tiles. Menus and dialogs stay solid.
- **Smooth gradients.** The dark tiles and the wallpaper fade no longer show bands.
- **Smoother scrolling.** The app row moves more smoothly.

### Easier to use
- **Continue Watching order.** What you are watching comes before the next-episode suggestions. Plume also reads the time correctly from apps that give it in seconds.
- **App names.** When you move quickly along the app row, Plume shows an app's name only when you reach that app.

### Fixes
- Plume opens on Android 8.0 and 8.1. Before, it closed immediately after it opened.
- If your wallpaper is too big for the memory of your TV, Plume shows the default wallpaper. Before, Plume closed.
- In **All apps**, the tile in the first or last column is no longer cut off when you select it.

## 1.4

### Smaller
- **12% smaller.** Plume is now 365 KB, down from 413 KB.

### New
- **Consoles on your home screen.** Your PS5, Xbox or Nintendo Switch gets a tile with its logo. Select it to switch to that input.
- **More Continue Watching.** The limit of 3 shows for each app is gone. Each app keeps 3 places at the front.
- **Every HDMI input as a tile.** Other HDMI devices get a tile while they are on. All of them are in **All apps**.
- **What's new card.** After an update, a card shows the highlights. Select **See all changes** to read everything.

### Design
- **New dark mode.** True black, with lit edges on the selected item.
- **Plume settings, organised.** Settings are in sections. Each section shows its current settings.
- **App row position.** Put a short app row on the left, in the centre or on the right. Select **Plume settings → App row → App row position**.

### Easier to use
- **Faster settings.** Push Left or Right to change a value. Settings opens where you left it. At the top of a list, push Up to go to the bottom.
- **Weather location.** Set it by hand in **Plume settings → Weather → Weather location**.
- **Home button setup.** A guided setup makes the Home button open Plume. Select **Plume settings → Home button → Set up Home button**. **Home button** is now an on/off switch.
- **Update dot.** A red dot on the Plume settings icon tells you about an update. **Plume settings → Updates** shows what is new.

### Fixes
- After the TV starts, the first app is selected, not the network icon.
- The hold OK menu is only as wide as its items.

## 1.3

- You can use a folder of photos as your wallpaper. The folder can be on the TV or on a USB drive. Select **Plume settings → Folder photo changes** to set how often the photo changes.
- **Photo fade** removes the dark area at the bottom of your photos.
- You can use your photos as the screensaver. Select **Plume settings → Screensaver → Your photos**.
- **Lock layout** keeps the home screen safe from changes. Set a 4-digit PIN in **Plume settings → Lock layout**. Apps still open without the PIN.
- Hold OK on an app to show **Move**, **Remove from row** and **App info**.
- Hold OK on a Continue Watching card to show **Remove** and **Open app**.
- Continue Watching shows a maximum of three shows from each app.
- **Launcher settings** is now **Plume settings**. Back goes up one page.
- **Remove a photo…** deletes wallpapers that you uploaded.
- The Home button switch opens the correct settings screen on more TVs.

## 1.2.1

- Fix: with **Icon style → Colour**, some app icons stayed grey until Plume started again.

## 1.2

- Plume updates itself. It looks for a new version one time each day. Select **Plume settings → Updates** to install it.
- You can hide apps from the home row. Select **Plume settings → Apps in row**. Hidden apps stay in All apps.
- **App names** shows app names below the tiles: on focus, always or never.
- **Icon style → Colour** shows each logo in the colour of its brand.
- You can add wallpapers from your phone. Select **Add from phone…** in a wallpaper list, then scan the QR code.
- Fix: green areas no longer show in the sky of wallpapers.

## 1.1

- The APK is now 0.4 MB. It was 5.1 MB.
- The Gallery screensaver paintings are now a 4.7 MB download. Select **Plume settings → Screensaver → Gallery** to get them.
- Plume deletes the paintings when you select a different screensaver.
- Fix: Plume did not always find its Home button service.

## 1.0

- First release.
