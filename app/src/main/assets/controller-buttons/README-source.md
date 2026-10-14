# 四键手柄 UI 图示

依据用户提供的手柄照片绘制的原创矢量图标。字母与位置按实物，未套用 Xbox、Nintendo 或 PlayStation 的标准排列。

```text
 A  X
 B  Y
```

保留外侧弧形轮廓和内侧较直的边界；为小尺寸 UI 做了正视化与简化，不保留照片透视、磨损、阴影或塑料纹理。字母全部使用矢量路径，无字体依赖。

## 优先使用

- 深色 MR 界面直接使用 `svg/white/button-a.svg` 等白色版本。
- 需要随文字颜色变化时，内联 `svg/button-a.svg` 等 `currentColor` 版本。
- 白色/深色透明 PNG 在 `png/white/` 和 `png/dark/` 中，按输出尺寸分目录。
- 实心为默认；`-outline` 为线框版本。它们是视觉样式，不自动代表“按下 / 松开”或“启用 / 禁用”。

## 文件结构

```text
svg/button-a.svg                 可变色实心
svg/button-a-outline.svg         可变色线框
svg/white/button-a.svg           固定白色实心
svg/white/button-a-outline.svg   固定白色线框
png/white/24/button-a.png        24px 白色透明 PNG
png/dark/32/button-a.png         32px 深色透明 PNG
sprite.svg                      8 个 symbol
manifest.json                   字母、位置、文件路径及尺寸
preview.png                     深色背景预览
preview-light.png               浅色背景预览
```

每个字母 A、X、B、Y 都有相同目录结构。SVG 统一 32×32 viewBox；默认线框描边在该网格下为 1.8。推荐 UI 显示为 24–32 逻辑 px；需要更小尺寸时先在实际设备上检查字母可读性。

## 前端嵌入

### 固定白色文件

```html
<span class="input-prompt">
  按 <img src="svg/white/button-a.svg" width="24" height="24" alt="A"> 键
</span>
```

### SVG sprite，跟随文字颜色

```html
<svg class="button-glyph" viewBox="0 0 32 32" role="img" aria-label="A 按键">
  <use href="sprite.svg#button-a"></use>
</svg>
```

```css
.input-prompt { display: inline-flex; align-items: center; gap: .25em; }
.button-glyph { width: 1.25em; height: 1.25em; flex: none; color: inherit; }
```

外部 `<img>` 中的 `currentColor` 不继承父页面文字颜色。因此直接 `<img>` 放到深色背景时使用 `svg/white/`，或选择白色 PNG。需要动态颜色时使用内联 SVG 或 sprite；某些本地文件浏览器会限制外部 sprite 引用，可改为将 symbols 内联。

实心版的字母采用镂空结构：字母区域透明，露出承载它的背景；A、B 的字腔保持实心。线框版的字母为实色。不要给整个图标额外套圆形底板，否则会丢失手柄按键形状的提示。

非 Web 平台可直接使用 PNG；若支持 SVG，则使用固定白色版本或将 currentColor 替换为引擎需要的颜色。按键功能映射未指定，本包不为 A/B/X/Y 绑定确认、返回、设置等操作。

## 可访问性

与文字并排且邻近文字已完整读出按键时，可以将图标设为装饰；仅使用图标传递操作时，为其提供字母名称。不要只凭颜色区分四个键。

