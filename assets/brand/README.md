# 中南坦克 — 品牌资产

## 文件

| 文件 | 用途 |
|---|---|
| `logo-avatar.svg` | **GitHub 仓库头像**。自带浅色圆底，浅色/深色主题下都能正常显示 |
| `avatar-460.png` | 上面那份的 PNG，**上传 GitHub 头像用这个**（GitHub 建议 ≥460×460） |
| `avatar-128.png` / `avatar-64.png` / `avatar-32.png` | 常用小尺寸，供文档、徽章、站点使用 |
| `logo-mark.svg` | 纯标记，**无底色**。用于深色/彩色底、印刷、需要自己配底色的场合 |
| `logo-mark-512.png` | 上面那份的 PNG（透明底） |
| `logo-banner.svg` | **README 顶部横幅**，1280×320 |
| `banner-1280.png` | 上面那份的 PNG |
| `preview-sizes.png` | 浅底/深底下的多尺寸实测，改图后用来复查 |

SVG 是源文件，改颜色和比例请改 SVG 再重新导出 PNG，别直接修图。

## 配色

| 名称 | 值 | 用在哪 |
|---|---|---|
| 墨黑 | `#1A1D1F` | 履带、炮管、描边 |
| 冷白 | `#F2F5F6` | 车体、炮塔 |
| 青 | `#4DD0E1` | 履带板、高亮条、强调色 |
| 黄 | `#FDD835` | 车身高亮条 |
| 珊瑚红 | `#FF6B5B` | 天线帽（唯一的暖色点缀） |
| 头像圆底 | `#EAF4F7` | 仅头像 |
| 横幅底 | `#F7FAFB` | 仅横幅 |
| 深字 | `#12232B` | 横幅标题 |

配色取自游戏内机甲美术（`src/main/resources/art/mecha-*.png`）——白车身、黑履带、青色发光、黄色高亮条，红天线帽也是从那上面搬的。改配色时请和机甲美术一起改，别让两边分家。

## 为什么头像要自带底色

坦克是**白车身 + 黑履带**：透明底放深色主题上，黑履带会消失；放纯白底上，白车身会消失。给它一个自己的浅色圆底，就跟页面主题无关了。

同理，**不要**把 `logo-mark.svg`（透明底）直接用作头像。

## 重新导出 PNG

用 Edge 无头模式渲染 SVG，再用 PIL 缩放（比让浏览器直接渲染小尺寸更清晰）：

```bash
# 1. 先在某尺寸视口里铺满渲染成高清 PNG
msedge --headless=new --disable-gpu --hide-scrollbars \
       --default-background-color=00000000 \
       --screenshot=_hi/avatar.png --window-size=1024,1024 \
       "file:///<路径>/wrap.html?s=file:///<路径>/logo-avatar.svg"

# 2. 再用 PIL 缩到目标尺寸（LANCZOS）
```

注意：**不要**直接把 SVG 文件当截图目标——浏览器会按 SVG 的固有尺寸（200×200）渲染，截 32×32 就只截到左上角一小块。
