# blbl-animate

一个第三方哔哩哔哩安卓 App，支持触摸、遥控，以及安卓5，适用于平板、TV、车机等设备。

> **非官方声明**
>
> - 本项目是**个人学习研究性质的非商业开源项目**，与哔哩哔哩（及其运营主体）**不存在任何隶属、
>   合作、授权或关联关系**；本项目不宣称、也不应被理解为官方客户端的替代品或官方衍生版本。
> - 本项目**未使用**哔哩哔哩的商标、品牌名称或官方 Logo 作为自身标识。`Blbl` 仅为本项目自称，
>   文中出现的「B站」「哔哩哔哩」仅用于指代其运营的视频网站。
> - 本项目**不具备**解除付费内容限制、解除番剧区域限制、规避会员体系、下载视频或去除平台广告的
>   能力，代码中也**未实现**此类功能。
> - 若权利人认为本项目侵犯其合法权益，请通过 GitHub Issue 联系本项目维护者，**收到通知后将立即
>   下架相关代码与全部发布物**（含 GitHub Releases 中的 APK）。

> 本仓库 fork 自 [cat3399/blbl](https://github.com/cat3399/blbl)。上游项目本身已经很完整，
> 这个 fork 只围绕「电视上的实际观感」做少量改动，并**移除了所有指向上游作者的网络功能**
> （日志上传、更新检查、QQ 群入口）。应用内名称仍然是 `Blbl`，包名也未改动。

## 这个 fork 改了什么

### 修复

- **弹幕在部分电视上「前后抖动」**（如 TCL Q10G，官方云视听小电视同样存在）
  根因不在弹幕算法：SurfaceView 的独立硬件层会让合成器把 UI 层的 vsync 对齐到视频层去「等帧」，
  实测节拍被拉成 57.7Hz 并出现 33~83ms 的等帧长尾。默认渲染视图改为 TextureView 后，
  节拍回到 59.96Hz、等帧长尾消失。设置 → 其他设置 → 渲染视图 仍可切回 SurfaceView。
- **评论回复数多算**：主评论的 `count` 包含待审核/折叠等条目，改用 `rcount`
  （严格等于楼接口返回的 `page.count`）。此前会出现「显示 1 条回复、点开却只有主评论」。
- **评论区动图不播放 / 完全透明**：CDN 缩略图后缀 `@480w_360h_1c.webp` 会把 GIF 压成静态首帧，
  现在动图请求原图；播放侧 GIF 改用 `android.graphics.Movie` 自己驱动，不再依赖
  `AnimatedImageDrawable`（后者必须先挂到 View 上再 `start()`，顺序错了就是「占位但全透明」）。

### 新增

- 评论区图片支持播放动图（GIF / 动画 WebP）
- 弹幕支持显示大表情 / 行内表情
- **多平台直播**（feature/multi-platform-live 分支）：侧边栏新增「斗鱼」「虎牙」两个入口，
  直播列表、分区浏览、清晰度/线路选择、弹幕全部复用原有电视交互；接口实现参考
  [PureLive](https://github.com/wzgrx/PureLive)（MIT）的斗鱼 / 虎牙站点适配移植。
  详见 [多平台直播说明](#多平台直播说明)。

### 已回退（弹幕时间轴与出帧链）

排查抖动期间做过的一批改动**已全部回退到上游原版**，只保留上面「渲染视图默认 TextureView」这一项：

- 第 4 代时间轴（`PresentationClock`：累加 vsync 时间戳之差）—— 已回退为上游原版时间轴；
- 主线程出帧链 —— 已回退为上游的 ActionThread 出帧；
- 全部诊断探针（`vsP`/`vsQ1`/`nm*`/`late*`/`idle*`/`cb`/`step`/`gap`、`IdleVsyncProbe`）—— 已移除。

原因：A/B 实测（同机、同为 TextureView 的干净 60Hz，只换那两处行为）丢帧率差异全在噪声内
（−0.02% vs +0.07%），实际观感也不优于原版；真正起作用的只有渲染视图这一项。

### 移除

- 上传日志到开发者服务器
- 自动检查更新 / 检查更新
- QQ 交流群入口
- 项目地址指向本 fork

## 界面预览

**推荐页**
![推荐页](./example-pic/推荐页.png)

**分类页**
![分类页](./example-pic/分类页.png)

**动态页**
![动态页](./example-pic/动态页.png)

**直播页**
![直播页](./example-pic/直播页.png)

**我的页**
![我的页](./example-pic/我的页.png)

**搜索页**
![搜索页](./example-pic/搜索页.png)

**追番**
![追番](./example-pic/追番.png)

**视频播放页**
![视频播放页](./example-pic/视频播放页.png)

## 功能概览

- 侧边栏导航：搜索 / 推荐 / 分类 / 动态 / 直播 / 斗鱼 / 虎牙 / 我的
- 扫码登录入口
- 视频播放：Media3(ExoPlayer)，支持分辨率/编码/倍速/字幕/弹幕等设置
- 设置页：播放与弹幕偏好等

## 多平台直播说明

> 本节描述 `feature/multi-platform-live` 分支新增的能力，合入前可先行 review。

### 功能范围

- 侧边栏新增 **斗鱼**、**虎牙** 两个入口（与 B 站直播并列，互不影响）。
- 每个平台都有：推荐直播流、一级/二级分区浏览、房间卡片列表、清晰度选择、线路选择、弹幕。
- 斗鱼额外支持醒目留言（SuperChat，`comm_chatmsg`）；虎牙的留言板与礼物消息暂不展示。
- 播放流为 FLV / HLS，由 Media3(ExoPlayer) 直接播放（B 站直播本身即 FLV，链路一致）。

### 架构

```
core/live/
├── LiveSite.kt        # 平台枚举（BILIBILI / DOUYU / HUYA）
├── MultiLiveApi.kt    # 统一入口：按平台分发，返回与 B 站一致的模型
├── DouyuApi.kt        # 斗鱼：分区/推荐/房间/播放地址（getEncryption + getH5PlayV1 签名）
├── HuyaApi.kt         # 虎牙：分区/推荐/房间/播放地址（antiCode 模板签名）
└── Tars.kt            # 虎牙弹幕/WUP 使用的 Tars 编解码
feature/live/
├── LiveDanmakuClient.kt   # 弹幕客户端统一接口
├── DouyuDanmakuClient.kt  # 斗鱼弹幕（wss://danmuproxy.douyu.com:8506，STT 协议）
├── HuyaDanmakuClient.kt   # 虎牙弹幕（wss://wsapi.huya.com，Tars 协议）
└── LiveFragment.kt 等     # 原有 UI 增加 site 参数，B 站路径行为不变
```

UI 层（`LiveFragment` / `LiveGridFragment` / `LiveAreaIndexFragment` / `LiveAreaDetailFragment` /
`LivePlayerActivity`）只依赖 `LiveSite` + `MultiLiveApi`，模型直接复用 `LiveRoomCard` /
`LiveAreaParent` / `BiliApi.LivePlayUrl`，因此电视端的 D-pad 焦点管理、刷新、加载更多等行为
与 B 站直播完全一致。清晰度 id 做了归一化：斗鱼 `rate 0`（原画）与虎牙 `iBitRate 0`（原画）
都映射为 `10000`，清晰度弹窗里「原画」固定排最前。

### 接口实现要点（均已实测验证）

- **斗鱼**
  - 分区 `m.douyu.com/api/cate/list`；推荐 `japi/weblist/apinc/allpage/6/<page>`；
    分区房间 `gapi/rkc/directory/mixList/2_<area>/<page>`；房间信息 `betard/<rid>`。
  - 播放地址：先取签名描述符 `wgapi/livenc/liveweb/websec/getEncryption?did=<did>`
    （`did` 为进程级随机 32 位十六进制，缓存 5 分钟），再 POST
    `lapi/live/getH5PlayV1/<rid>`，`auth = md5(md5^n(rand_str+key) + key + rid + tt)`。
  - 弹幕：`wss://danmuproxy.douyu.com:8506`，二进制报文 + `key@=value/` STT 文本。
- **虎牙**
  - 分区 `live.cdn.huya.com/liveconfig/game/bussLive?bussType=<1|2|8|3>`（四个固定一级分区）；
    推荐/分区房间 `www.huya.com/cache.php?m=LiveList&do=getLiveListByPage`；
    房间信息 `mp.huya.com/cache.php?m=Live&do=profileRoom`。
  - 播放地址：对 `sFlvAntiCode` / `sHlsAntiCode` 做模板签名
    （`wsSecret = md5(fm 模板替换 $0=u, $1=streamName, $2=md5(seqid|ctype|t), $3=wsTime)`），
    访客 uid 先尝试匿名登录，失败时用临时 uid（与 PureLive 的 `fallbackViewerUid` 一致）。
  - 弹幕：`wss://wsapi.huya.com`，Tars 编码（命令 16 注册分组 / 20 心跳 / 7、22 下发）。

### 已知限制

- 斗鱼 / 虎牙为**匿名访客**模式：不登录，因此没有「我的关注」；付费房、密码房无法播放。
- 虎牙未接入 WUP `getCdnTokenInfoEx` 原生令牌与 SuperChat 留言板（PureLive 中的增强路径），
  首版使用房间自带 antiCode 的 Web 签名路径。
- 播放地址有有效期（虎牙约 5 分钟、斗鱼视 URL `expire` 而定），长时间观看如遇断流，
  重新选择一次清晰度/线路即可（会自动拉取新地址）。
- 未在真机编译验证前请勿直接合入 main；建议在 Android Studio 打开后先构建 Debug 包实测
  （斗鱼房间 793400、虎牙房间 998 均可用于验证）。

## 技术栈

- Kotlin + AndroidX + ViewBinding
- Media3(ExoPlayer)/[Ijkplayer](https://github.com/cat3399/ijkplayer)
- OkHttp
- Protobuf-lite
- Material / RecyclerView / ViewPager2

## 构建

环境要求：JDK 17，Android SDK（compileSdk 36）。

调试包：

```
./gradlew assembleDebug
```

发布包（已开启 R8 混淆 + 资源压缩）：

```
./gradlew assembleRelease
```

可选版本参数（本地或 CI）：

```
./gradlew assembleRelease -PversionName=0.1.30 -PversionCode=30
```

## GitHub Actions

- **Feat Branch Build**：push 到 `feat/**`、`fix/**` 时自动编译 debug 包并上传 artifact。
- **Android Release**：push tag（`v*`，如 `git push origin v0.1.30`）或手动触发，
  编译 release 包并发布到 GitHub Releases。

签名默认使用 CI 临时生成的密钥（每次不同，安装前需先卸载旧包）。想固定签名，
在仓库 Secrets 里配置 `RELEASE_KEYSTORE_BASE64`（keystore 的 base64）、
`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 即可，
工作流会自动优先使用它们。

## 感谢

- https://github.com/SocialSisterYi/bilibili-API-collect B站API收集整理
- https://github.com/xiaye13579/BBLL 优秀的页面设计和操作逻辑，本项目绝大部分页面和操作逻辑都是抄袭BBLL🥰
- https://github.com/bggRGjQaUbCoE/PiliPlus 部分关键功能参考了Piliplus的逻辑
- https://github.com/debugly/ijkplayer 感谢debugly大佬移植的ijkplayer
- https://github.com/cat3399/blbl 上游项目
- 开源第三方B站客户端

## 免责声明

> 不得利用本项目进行任何非法活动。 不得干扰B站的正常运营。 不得传播恶意软件或病毒。 此外，为降低法律风险

1. 🚫禁止在官方平台（b站）及官方账号区域（如b站微博评论区）宣传本项目
2. 🚫禁止在微信公众号平台宣传本项目
3. 🚫禁止利用本项目牟利，本项目无任何盈利行为，第三方盈利与本项目无关
4. 🚫禁止将本项目用于解除付费内容限制、解除番剧区域限制、规避会员体系或下载视频 ——
   本项目本身也并未实现上述任何一项
5. ℹ️本项目不含、也不协助获取任何受版权保护内容的非法副本；播放与评论等数据均来自用户自己的
   账号在网站上的既有访问行为，与官方客户端的正常访问范围一致
6. ℹ️收到权利人通知后，本项目将立即移除相关代码、文档与发布物

**关于许可证**：上游项目 `cat3399/blbl` 未附带开源许可证，因此本 fork 同样未附加许可证 ——
代码版权仍归各自作者所有，本仓库的分发不构成对上游代码的再授权。如需转载或二次分发，
请保留原作者与本 fork 的来源署名。

代码都是codex写的，如有问题请联系https://openai.com/ 😤
