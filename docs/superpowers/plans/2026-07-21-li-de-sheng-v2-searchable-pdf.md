# 李德胜 V2 可检索 PDF 与重新蒸馏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用 Apple Vision 为 14 份共 5,151 页的扫描书稿增加坐标化中文 OCR 文本层，再以 51 份最终资料构建、验证并签名发布“李德胜”schema v2 `balanced` 智能体。

**Architecture:** Swift 原生程序只负责按 300/400 DPI 渲染 PDF 页面并调用 Vision，输出带 PDF 坐标的逐页 JSONL；Python 管线负责 checkpoint、Unicode 不可见文本层、PDF 合并、质量验证、批处理和原子发布。PDF 全部通过后才进入现有 `prepare-v2 -> source catalog -> 九类资产 -> validate -> recommend -> pack` 流程。

**Tech Stack:** Swift 6、Vision、CoreGraphics、Foundation、Python 3、ReportLab、pypdf、Pillow、Poppler、`unittest`、现有 Harness agent-builder。

## Global Constraints

- 平台固定为 Apple M4 macOS；OCR 和构建全部在本机进行。
- 名称为“李德胜”，`agent-id=li-de-sheng`，版本为整数 `2`。
- 原始资料集为 `/Volumes/game/books/agents/李德胜-v1-原始书稿`；V1 工作区、V1 成品和原始文件不得覆盖。
- 14 份既有 OCR 文本位于 `/Volumes/game/books/agent-build/li-de-sheng-ocr`，仅用于页数、回归和异常发现。
- V2 最终输入集固定为 `/Volumes/game/books/agents/李德胜-v2-可检索原稿`。
- V2 元数据发现工作区固定为 `/Volumes/game/books/agent-build/li-de-sheng-v2-discovery`。
- V2 正式工作区固定为 `/Volumes/game/books/agent-build/li-de-sheng-v2`。
- V2 发布目录固定为 `/Volumes/game/books/agents/li-de-sheng-v2-balanced-release`。
- 发布者私钥固定为 `/Users/tony/.config/harness-apk/keys/li-de-sheng.pem`，不得写入 Git、日志、包或聊天内容。
- Vision 首轮使用 300 DPI；已有 OCR 非空而 Vision 为空的页面使用 400 DPI 重试。
- OCR 同时请求 `zh-Hans`、`zh-Hant`、`en-US` 中运行时支持的语言，使用 `.accurate` 和语言纠正。
- 最多同时处理 2 页；不得把 5,151 页图像同时保存在内存或磁盘。
- 成品 PDF 不得栅格化、重新压缩或替换原页面图像。
- 全量页数、页面框、旋转和文本层必须验证；每份 PDF 的首页、中页、末页及固定种子选择的 2 个正文页进行视觉抽检。
- 嵌入后文本相对 Vision 结果的规范化字符覆盖率必须不低于 99.5%。
- `balanced` 是默认且唯一自动发布的 profile；不生成 `.hsource`，除非用户随后明确要求 `source`。
- 原文、OCR checkpoint、PDF 成品、workspace staging 和构建产物不进入 Git；Git 只提交工具、测试、规格和计划。
- 不推送 Git，除非用户明确要求。

---

## File Map

- Create: `tools/agent_builder/native/vision_pdf_ocr.swift` — Vision OCR、页面渲染、坐标反变换和 JSONL checkpoint。
- Create: `tools/agent_builder/searchable_pdf.py` — OCR 记录模型、不可见文本层、PDF 合并、验证、重试、批处理和原子发布。
- Create: `scripts/vision-searchable-pdf.sh` — 使用本机 Swift 和 Codex Python 的稳定入口。
- Create: `tools/agent_builder/tests/test_searchable_pdf.py` — 单元与本机 Vision 集成测试。
- Modify: `tools/agent_builder/requirements.txt` — 声明 ReportLab 与 Pillow 运行依赖。
- External only: `/Volumes/game/books/agent-build/li-de-sheng-v2-ocr-work` — checkpoint、临时叠层、候选 PDF 和报告。
- External only: `/Volumes/game/books/agents/李德胜-v2-可检索原稿` — 51 份最终 V2 输入。
- External only: `/Volumes/game/books/agent-build/li-de-sheng-v2-discovery` — 首轮 unknown catalog 工作区。
- External only: `/Volumes/game/books/agent-build/li-de-sheng-v2` — 正式 V2 工作区。
- External only: `/Volumes/game/books/agents/li-de-sheng-v2-balanced-release` — 最终本地发布目录。

---

### Task 1: 锁定 OCR JSONL 协议和 Swift 构建入口

**Files:**
- Create: `tools/agent_builder/native/vision_pdf_ocr.swift`
- Create: `scripts/vision-searchable-pdf.sh`
- Create: `tools/agent_builder/tests/test_searchable_pdf.py`

**Interfaces:**
- Consumes: 普通 PDF 路径、JSONL 输出路径、DPI、可选页码列表。
- Produces: `vision_pdf_ocr --input PDF --output JSONL --dpi N [--pages 1,2]`；每行一个 `OCRPageRecord`。
- `OCRPageRecord`: `pageNumber:int`、`pageWidth:double`、`pageHeight:double`、`rotation:int`、`dpi:int`、`lines:[OCRLine]`。
- `OCRLine`: `text:string`、`confidence:double`、`pdfRect:[x,y,width,height]`。

- [ ] **Step 1: 写缺少原生程序时必然失败的测试**

创建 `tools/agent_builder/tests/test_searchable_pdf.py`：

```python
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WRAPPER = ROOT / "scripts/vision-searchable-pdf.sh"

class NativeVisionOCRTest(unittest.TestCase):
    def test_native_cli_reports_supported_requested_languages(self):
        result = subprocess.run(
            [str(WRAPPER), "languages"], cwd=ROOT, check=True,
            text=True, capture_output=True,
        )
        payload = json.loads(result.stdout)
        self.assertEqual(1, payload["protocolVersion"])
        self.assertEqual("accurate", payload["recognitionLevel"])
        self.assertIn("zh-Hans", payload["requestedLanguages"])
        self.assertTrue(set(payload["activeLanguages"]).issubset(payload["supportedLanguages"]))
        self.assertTrue(payload["activeLanguages"])
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf.NativeVisionOCRTest.test_native_cli_reports_supported_requested_languages -v
```

Expected: FAIL because the wrapper or Swift source does not exist.

- [ ] **Step 3: 写最小 wrapper**

创建 `scripts/vision-searchable-pdf.sh`：

```sh
#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR="$ROOT/build/vision-pdf-ocr"
SOURCE="$ROOT/tools/agent_builder/native/vision_pdf_ocr.swift"
BINARY="$BUILD_DIR/vision_pdf_ocr"
PYTHON="${CODEX_PYTHON:-$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3}"
mkdir -p "$BUILD_DIR"
if [ ! -x "$BINARY" ] || [ "$SOURCE" -nt "$BINARY" ]; then
  xcrun swiftc -O -framework Foundation -framework Vision \
    -framework CoreGraphics -framework ImageIO "$SOURCE" -o "$BINARY"
fi
case "${1:-}" in
  languages|ocr) exec "$BINARY" "$@" ;;
  build|batch|verify) exec "$PYTHON" -m tools.agent_builder.searchable_pdf "$@" ;;
  *) echo "usage: $0 {languages|ocr|build|batch|verify} <command-options>" >&2; exit 2 ;;
esac
```

```bash
chmod +x scripts/vision-searchable-pdf.sh
```

- [ ] **Step 4: 写 Swift 语言探测骨架**

创建 `tools/agent_builder/native/vision_pdf_ocr.swift`：

```swift
import CoreGraphics
import Foundation
import ImageIO
import Vision

struct OCRLine: Codable {
    let text: String
    let confidence: Double
    let pdfRect: [Double]
}
struct OCRPageRecord: Codable {
    let pageNumber: Int
    let pageWidth: Double
    let pageHeight: Double
    let rotation: Int
    let dpi: Int
    let lines: [OCRLine]
}
enum ToolError: Error, CustomStringConvertible {
    case usage(String), runtime(String)
    var description: String {
        switch self { case .usage(let value), .runtime(let value): return value }
    }
}
let requestedLanguages = ["zh-Hans", "zh-Hant", "en-US"]
func supportedLanguages() throws -> [String] {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    return try VNRecognizeTextRequest.supportedRecognitionLanguages(
        for: request.recognitionLevel, revision: request.revision
    )
}
func printLanguages() throws {
    let supported = try supportedLanguages()
    let active = requestedLanguages.filter(supported.contains)
    guard !active.isEmpty else { throw ToolError.runtime("Vision 没有可用的请求语言") }
    let value: [String: Any] = [
        "protocolVersion": 1,
        "recognitionLevel": "accurate",
        "requestedLanguages": requestedLanguages,
        "supportedLanguages": supported.sorted(),
        "activeLanguages": active,
    ]
    let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
    FileHandle.standardOutput.write(data + Data([0x0a]))
}
do {
    guard CommandLine.arguments.count >= 2 else { throw ToolError.usage("缺少命令") }
    switch CommandLine.arguments[1] {
    case "languages": try printLanguages()
    default: throw ToolError.usage("未知命令：\(CommandLine.arguments[1])")
    }
} catch {
    FileHandle.standardError.write(Data("vision_pdf_ocr: \(error)\n".utf8))
    exit(1)
}
```

- [ ] **Step 5: 运行测试并确认 GREEN**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 6: 提交 Task 1**

```bash
git add scripts/vision-searchable-pdf.sh \
  tools/agent_builder/native/vision_pdf_ocr.swift \
  tools/agent_builder/tests/test_searchable_pdf.py
git commit -m "功能：建立 Vision PDF OCR 原生入口"
```

---

### Task 2: 实现真实页面识别和 PDF 坐标反变换

**Files:**
- Modify: `tools/agent_builder/native/vision_pdf_ocr.swift`
- Modify: `tools/agent_builder/tests/test_searchable_pdf.py`

**Interfaces:**
- Consumes: Task 1 数据模型和 wrapper。
- Produces: `ocr --input PATH --output PATH --dpi 300 [--pages CSV]`；完整 JSONL 行以追加、同步方式写入。
- Coordinate contract: `pdfRect` 相对 media box 左下角、单位 point；所有值有限并位于页面边界内。

- [ ] **Step 1: 写真实两页扫描 fixture 和失败测试**

测试用 Pillow 与 `/System/Library/Fonts/PingFang.ttc` 生成含“调查研究必须从事实出发”“实践是检验认识的标准”的两页位图 PDF，再用 pypdf 将第二页旋转 90 度。调用：

```python
subprocess.run([
    str(WRAPPER), "ocr", "--input", str(source), "--output", str(checkpoint),
    "--dpi", "300",
], cwd=ROOT, check=True)
rows = [json.loads(line) for line in checkpoint.read_text("utf-8").splitlines()]
self.assertEqual([1, 2], [row["pageNumber"] for row in rows])
self.assertIn("调查研究", "".join(item["text"] for item in rows[0]["lines"]))
self.assertIn("实践", "".join(item["text"] for item in rows[1]["lines"]))
for row in rows:
    for line in row["lines"]:
        x, y, width, height = line["pdfRect"]
        self.assertGreater(width, 0)
        self.assertGreater(height, 0)
        self.assertGreaterEqual(x, 0)
        self.assertGreaterEqual(y, 0)
        self.assertLessEqual(x + width, row["pageWidth"] + 0.5)
        self.assertLessEqual(y + height, row["pageHeight"] + 0.5)
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf.NativeVisionOCRTest.test_native_ocr_emits_bounded_pdf_coordinates_for_normal_and_rotated_pages -v
```

Expected: FAIL because `ocr` is not implemented.

- [ ] **Step 3: 实现参数、页面渲染、Vision 和 JSONL append**

在 Swift 文件增加以下精确接口：

```swift
struct OCRArguments {
    let input: URL
    let output: URL
    let dpi: Int
    let pages: Set<Int>?
}
func parseOCRArguments(_ values: ArraySlice<String>) throws -> OCRArguments
func render(_ page: CGPDFPage, dpi: Int) throws -> (CGImage, CGAffineTransform, CGRect)
func recognize(page: CGPDFPage, pageNumber: Int, dpi: Int, languages: [String]) throws -> OCRPageRecord
func append(_ record: OCRPageRecord, to output: URL) throws
func runOCR(arguments: ArraySlice<String>) throws
```

实现要求：

```swift
let transform = page.getDrawingTransform(
    .mediaBox, rect: pixelRect, rotate: 0, preserveAspectRatio: true
)
context.concatenate(transform)
context.drawPDFPage(page)
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = activeLanguages
request.usesLanguageCorrection = true
try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
let pdfRect = visionPixelRect.applying(transform.inverted()).standardized
```

输出前将 `pdfRect` 减去 media box origin 并裁剪到页面尺寸。观察结果按视觉阅读顺序稳定排序。每个 JSON 行使用 `JSONEncoder.outputFormatting=[.sortedKeys,.withoutEscapingSlashes]`，写入后 `FileHandle.synchronize()`。主 switch 增加：

```swift
case "ocr": try runOCR(arguments: CommandLine.arguments.dropFirst(2))
```

`runOCR` 使用 `OperationQueue`，`maxConcurrentOperationCount = 2`；每个 operation 独立重新打开 `CGPDFDocument`，确保同时最多保留两页渲染图。JSONL append 通过专用串行队列执行，一页完整编码、写入并同步后才开始下一条写入，避免并发交错和半行 checkpoint。

- [ ] **Step 4: 运行 Task 1-2 测试并确认 GREEN**

```bash
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf.NativeVisionOCRTest -v
```

Expected: both tests PASS; no box escapes page bounds.

- [ ] **Step 5: 提交 Task 2**

```bash
git add tools/agent_builder/native/vision_pdf_ocr.swift \
  tools/agent_builder/tests/test_searchable_pdf.py
git commit -m "功能：实现 Vision 逐页坐标 OCR"
```

---

### Task 3: 生成 Unicode 不可见文本层并保留原页面

**Files:**
- Create: `tools/agent_builder/searchable_pdf.py`
- Modify: `tools/agent_builder/requirements.txt`
- Modify: `tools/agent_builder/tests/test_searchable_pdf.py`

**Interfaces:**
- Consumes: Task 2 JSONL。
- Produces: `load_checkpoint(path) -> dict[int, OCRPageRecord]`、`build_searchable_pdf(source, records, target)`、`character_coverage(expected, actual) -> float`。
- Invisible font: `UnicodeCIDFont("STSong-Light")`；PDF text render mode fixed to `3`。

- [ ] **Step 1: 写叠层失败测试**

测试使用合成 OCR 记录，不依赖 Vision。断言页数、media/crop box、rotation 和中文提取结果；用 `pdftoppm -r 150` 渲染前后页面，把 `difference = ImageChops.difference(source_image, output_image)`，再断言 `difference.getbbox() is None`，即像素完全相同。

```python
records = {
    1: OCRPageRecord(1, 595.0, 842.0, 0, 300, (
        OCRLine("调查研究必须从事实出发", 0.99, (54.0, 650.0, 420.0, 30.0)),
    )),
    2: OCRPageRecord(2, 595.0, 842.0, 90, 300, (
        OCRLine("实践是检验认识的标准", 0.99, (54.0, 650.0, 420.0, 30.0)),
    )),
}
build_searchable_pdf(source, records, target)
self.assertGreaterEqual(
    character_coverage("调查研究必须从事实出发", PdfReader(target).pages[0].extract_text() or ""),
    0.995,
)
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf.SearchablePDFOverlayTest -v
```

Expected: FAIL because `searchable_pdf.py` does not exist.

- [ ] **Step 3: 声明依赖**

将 `tools/agent_builder/requirements.txt` 更新为：

```text
cryptography>=49.0.0,<50
pypdf>=6.10.0,<7
reportlab>=4.4.0,<5
pillow>=12.0.0,<13
```

- [ ] **Step 4: 实现模型、checkpoint、覆盖率和文本层**

`tools/agent_builder/searchable_pdf.py` 先写入以下完整基础实现：

```python
from __future__ import annotations

import json
import math
import os
import unicodedata
from collections import Counter
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

from pypdf import PdfReader, PdfWriter
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.pdfgen import canvas

CJK_FONT = "STSong-Light"
MIN_CHARACTER_COVERAGE = 0.995
pdfmetrics.registerFont(UnicodeCIDFont(CJK_FONT))

@dataclass(frozen=True)
class OCRLine:
    text: str
    confidence: float
    pdf_rect: tuple[float, float, float, float]

    @classmethod
    def from_dict(cls, value: dict[str, object]) -> "OCRLine":
        text, confidence, rect = value.get("text"), value.get("confidence"), value.get("pdfRect")
        if not isinstance(text, str) or not text.strip():
            raise ValueError("OCR line text 必须非空")
        if isinstance(confidence, bool) or not isinstance(confidence, (int, float)):
            raise ValueError("OCR line confidence 无效")
        if not isinstance(rect, list) or len(rect) != 4 or any(
            isinstance(item, bool) or not isinstance(item, (int, float)) or not math.isfinite(item)
            for item in rect
        ):
            raise ValueError("OCR line pdfRect 无效")
        normalized = tuple(float(item) for item in rect)
        if normalized[2] <= 0 or normalized[3] <= 0:
            raise ValueError("OCR line pdfRect 必须为正尺寸")
        return cls(text.strip(), float(confidence), normalized)

@dataclass(frozen=True)
class OCRPageRecord:
    page_number: int
    page_width: float
    page_height: float
    rotation: int
    dpi: int
    lines: tuple[OCRLine, ...]

    @classmethod
    def from_dict(cls, value: dict[str, object]) -> "OCRPageRecord":
        page, width, height = value.get("pageNumber"), value.get("pageWidth"), value.get("pageHeight")
        rotation, dpi, lines = value.get("rotation"), value.get("dpi"), value.get("lines")
        if type(page) is not int or page < 1 or type(rotation) is not int or type(dpi) is not int:
            raise ValueError("OCR page 标识无效")
        if not isinstance(width, (int, float)) or not isinstance(height, (int, float)) or width <= 0 or height <= 0:
            raise ValueError("OCR page 尺寸无效")
        if not isinstance(lines, list):
            raise ValueError("OCR page lines 必须是数组")
        return cls(page, float(width), float(height), rotation, dpi,
                   tuple(OCRLine.from_dict(item) for item in lines))

def load_checkpoint(path: Path) -> dict[int, OCRPageRecord]:
    if not path.exists():
        return {}
    payload = path.read_bytes()
    raw_lines = payload.splitlines()
    records: dict[int, OCRPageRecord] = {}
    for index, raw in enumerate(raw_lines, start=1):
        if not raw.strip():
            continue
        try:
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError("不是对象")
            record = OCRPageRecord.from_dict(value)
        except (UnicodeError, json.JSONDecodeError, ValueError) as error:
            if index == len(raw_lines) and not payload.endswith(b"\n"):
                break
            raise ValueError(f"OCR checkpoint 第 {index} 行无效：{error}") from error
        records[record.page_number] = record
    return records

def _normalized_characters(value: str) -> Counter[str]:
    return Counter(
        character
        for character in unicodedata.normalize("NFKC", value)
        if not character.isspace()
    )

def character_coverage(expected: str, actual: str) -> float:
    wanted = _normalized_characters(expected)
    if not wanted:
        return 1.0
    found = _normalized_characters(actual)
    return sum(min(count, found[character]) for character, count in wanted.items()) / sum(wanted.values())

def _overlay(records: dict[int, OCRPageRecord]) -> bytes:
    output = BytesIO()
    document = canvas.Canvas(output, pagesize=(1, 1), pageCompression=1, invariant=1)
    for page_number in sorted(records):
        page = records[page_number]
        document.setPageSize((page.page_width, page.page_height))
        for line in page.lines:
            x, y, width, height = line.pdf_rect
            font_size = max(1.0, height * 0.82)
            natural_width = pdfmetrics.stringWidth(line.text, CJK_FONT, font_size)
            text = document.beginText()
            text.setTextRenderMode(3)
            text.setFont(CJK_FONT, font_size)
            text.setTextOrigin(x, y + max(0.0, (height - font_size) / 2))
            if natural_width > 0:
                text.setHorizScale(max(1.0, min(1000.0, width / natural_width * 100.0)))
            text.textOut(line.text)
            document.drawText(text)
        document.showPage()
    document.save()
    return output.getvalue()

def build_searchable_pdf(source: Path, records: dict[int, OCRPageRecord], target: Path) -> Path:
    reader = PdfReader(source, strict=False)
    if sorted(records) != list(range(1, len(reader.pages) + 1)):
        raise ValueError("OCR 页码必须完整覆盖 PDF")
    overlay = PdfReader(BytesIO(_overlay(records)), strict=False)
    if len(overlay.pages) != len(reader.pages):
        raise ValueError("叠层页数不一致")
    writer = PdfWriter()
    writer.clone_document_from_reader(reader)
    for index, overlay_page in enumerate(overlay.pages):
        writer.pages[index].merge_page(overlay_page, over=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(f".{target.name}.tmp-{os.getpid()}")
    try:
        with temporary.open("xb") as stream:
            writer.write(stream)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target
```

使用 `PdfWriter.clone_document_from_reader` 保留原文档，并对 writer pages 逐页合并叠层。checkpoint 只允许末行因进程中断而截断，其余无效行必须失败。

- [ ] **Step 5: 运行叠层测试和全部回归**

```bash
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf.SearchablePDFOverlayTest -v
./scripts/agent-builder.sh -m unittest discover -s tools/agent_builder/tests -v
```

Expected: all tests PASS.

- [ ] **Step 6: 提交 Task 3**

```bash
git add tools/agent_builder/searchable_pdf.py \
  tools/agent_builder/requirements.txt \
  tools/agent_builder/tests/test_searchable_pdf.py
git commit -m "功能：生成保真可检索 PDF 文本层"
```

---

### Task 4: 实现重试、验证、checkpoint 和原子批处理

**Files:**
- Modify: `tools/agent_builder/searchable_pdf.py`
- Modify: `tools/agent_builder/tests/test_searchable_pdf.py`

**Interfaces:**
- Consumes: Task 3 `build_searchable_pdf` and Task 2 native CLI。
- Produces: `process_document(source: Path, reference_ocr: Path, work_dir: Path, output: Path, native_command: list[str]) -> DocumentReport`、`verify_document(source: Path, output: Path, records: dict[int, OCRPageRecord], retry_pages: tuple[int, ...]) -> DocumentReport`、`publish_input_set(source_root: Path, reference_root: Path, work_root: Path, output_root: Path, native_command: list[str]) -> BatchReport`。
- `DocumentReport`: source/output/page counts、OCR line/character counts、minimum character coverage、retry pages、sample pages、input/output bytes、SHA-256。

- [ ] **Step 1: 写中断恢复、400 DPI 重试和非空输出拒绝测试**

在测试中创建临时 fake native executable：第一次只写第一页后返回非零；第二次从 checkpoint 恢复剩余页。另一个 case 让 300 DPI 结果为空、400 DPI 非空。测试精确调用：

```python
arguments = dict(
    source=source, reference_ocr=reference, work_dir=root / "work",
    output=root / "published.pdf", native_command=[str(fake_native)],
)
with self.assertRaises(subprocess.CalledProcessError):
    process_document(**arguments)
self.assertFalse((root / "published.pdf").exists())
report = process_document(**arguments)
self.assertEqual(2, report.page_count)
self.assertEqual((2,), report.retry_pages)
self.assertTrue(report.output.is_file())
```

另写测试确认候选验证失败时 `published.pdf` 不存在，且 `publish_input_set` 拒绝已有非空最终目录。

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf.SearchablePDFPipelineTest -v
```

Expected: FAIL because pipeline interfaces do not exist.

- [ ] **Step 3: 实现 reference OCR 与 native 恢复逻辑**

在 `searchable_pdf.py` 追加 `re`、`subprocess` 和 `Iterable` import，并实现：

```python
@dataclass(frozen=True)
class DocumentReport:
    source: Path
    output: Path
    page_count: int
    ocr_line_count: int
    ocr_character_count: int
    minimum_character_coverage: float
    retry_pages: tuple[int, ...]
    sample_pages: tuple[int, ...]
    input_bytes: int
    output_bytes: int
    source_sha256: str
    output_sha256: str

PAGE_MARKER = re.compile(r"^# page-(\d+)\s*$")

def read_reference_pages(path: Path) -> dict[int, str]:
    pages: dict[int, str] = {}
    current: int | None = None
    content: list[str] = []
    for raw_line in path.read_text("utf-8").splitlines():
        marker = PAGE_MARKER.fullmatch(raw_line)
        if marker:
            if current is not None:
                pages[current] = "\n".join(content).strip()
            page_number = int(marker.group(1))
            if page_number != len(pages) + 1:
                raise ValueError(f"reference OCR 页码不连续：{page_number}")
            current, content = page_number, []
        elif current is None:
            if raw_line.strip():
                raise ValueError("reference OCR 在首页标记前含有文字")
        else:
            content.append(raw_line)
    if current is None:
        raise ValueError("reference OCR 没有 # page-N 标记")
    pages[current] = "\n".join(content).strip()
    return pages

def run_native(command: list[str], source: Path, checkpoint: Path,
               dpi: int, pages: Iterable[int]) -> None:
    selected = tuple(sorted(set(pages)))
    if not selected:
        return
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        *command, "ocr", "--input", str(source), "--output", str(checkpoint),
        "--dpi", str(dpi), "--pages", ",".join(map(str, selected)),
    ], check=True)

def vision_text(record: OCRPageRecord) -> str:
    return "\n".join(line.text for line in record.lines)

def complete_records(source: Path, reference: Path, checkpoint: Path,
                     native_command: list[str]) -> tuple[dict[int, OCRPageRecord], tuple[int, ...]]:
    page_count = len(PdfReader(source, strict=False).pages)
    expected_pages = set(range(1, page_count + 1))
    reference_pages = read_reference_pages(reference)
    if set(reference_pages) != expected_pages:
        raise ValueError("reference OCR 页数与 PDF 不一致")

    records = load_checkpoint(checkpoint)
    if not set(records).issubset(expected_pages):
        raise ValueError("checkpoint 含有 PDF 范围外页码")
    run_native(native_command, source, checkpoint, 300, expected_pages - set(records))
    records = load_checkpoint(checkpoint)
    if set(records) != expected_pages:
        raise ValueError("300 DPI OCR 未完整覆盖 PDF")

    retry_pages = tuple(
        page_number for page_number in sorted(expected_pages)
        if reference_pages[page_number] and not vision_text(records[page_number]).strip()
    )
    run_native(native_command, source, checkpoint, 400, retry_pages)
    records = load_checkpoint(checkpoint)
    failed_pages = [
        page_number for page_number in retry_pages
        if not vision_text(records[page_number]).strip()
    ]
    if failed_pages:
        raise ValueError("400 DPI 后仍无识别文字：" + ",".join(map(str, failed_pages)))
    return records, retry_pages
```

`load_checkpoint` 对重复页采用最后一条完整记录，所以 400 DPI 的完整行自然覆盖该页 300 DPI 结果。`process_document` 还要在工作目录写 `input-manifest.json`，记录 source/reference 的路径、字节数、SHA-256 和 `ocrProtocolVersion=1`；已有 manifest 不一致时必须停止并保留旧 checkpoint，不得混用。

- [ ] **Step 4: 实现全量结构、文本、图像哈希和固定抽样验证**

再追加 `hashlib`、`random` import，并实现以下验证核心；`image_xobject_hashes` 递归遍历页面及 Form XObject 的 `/Resources`，按解压后的 image stream 字节计数，因此不能依赖对象编号：

```python
def sample_pages(page_count: int, seed_material: str) -> tuple[int, ...]:
    if page_count < 1:
        raise ValueError("PDF 必须至少有一页")
    selected = {1, (page_count + 1) // 2, page_count}
    candidates = [page for page in range(2, page_count) if page not in selected]
    if candidates:
        seed = int.from_bytes(
            hashlib.sha256(seed_material.encode("utf-8")).digest()[:8], "big"
        )
        selected.update(random.Random(seed).sample(candidates, min(2, len(candidates))))
    return tuple(sorted(selected))

def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def box_values(box: object) -> tuple[float, float, float, float]:
    return tuple(float(value) for value in box)

def image_xobject_hashes(page: object) -> Counter[str]:
    hashes: Counter[str] = Counter()
    visited: set[int] = set()

    def resolve(value: object) -> object:
        return value.get_object() if hasattr(value, "get_object") else value

    def walk(resources_value: object) -> None:
        resources = resolve(resources_value)
        marker = id(resources)
        if marker in visited:
            return
        visited.add(marker)
        xobjects_value = resources.get("/XObject")
        if xobjects_value is None:
            return
        xobjects = resolve(xobjects_value)
        for reference in xobjects.values():
            item = resolve(reference)
            subtype = str(item.get("/Subtype", ""))
            if subtype == "/Image":
                hashes[hashlib.sha256(item.get_data()).hexdigest()] += 1
            elif subtype == "/Form":
                nested = item.get("/Resources")
                if nested is not None:
                    walk(nested)

    resources = page.get("/Resources")
    if resources is not None:
        walk(resources)
    return hashes

def verify_document(source: Path, output: Path,
                    records: dict[int, OCRPageRecord],
                    retry_pages: tuple[int, ...]) -> DocumentReport:
    source_reader = PdfReader(source, strict=False)
    output_reader = PdfReader(output, strict=False)
    page_count = len(source_reader.pages)
    if len(output_reader.pages) != page_count or set(records) != set(range(1, page_count + 1)):
        raise ValueError("原稿、成品和 OCR 页数不一致")

    coverages: list[float] = []
    for page_number, (source_page, output_page) in enumerate(
        zip(source_reader.pages, output_reader.pages), start=1
    ):
        if box_values(source_page.mediabox) != box_values(output_page.mediabox):
            raise ValueError(f"第 {page_number} 页 media box 改变")
        if box_values(source_page.cropbox) != box_values(output_page.cropbox):
            raise ValueError(f"第 {page_number} 页 crop box 改变")
        if source_page.rotation != output_page.rotation:
            raise ValueError(f"第 {page_number} 页 rotation 改变")
        if image_xobject_hashes(source_page) != image_xobject_hashes(output_page):
            raise ValueError(f"第 {page_number} 页图像对象改变")
        expected = vision_text(records[page_number])
        actual = output_page.extract_text() or ""
        coverage = character_coverage(expected, actual)
        coverages.append(coverage)
        if expected.strip() and coverage < MIN_CHARACTER_COVERAGE:
            raise ValueError(f"第 {page_number} 页字符覆盖率不足：{coverage:.6f}")

    return DocumentReport(
        source=source,
        output=output,
        page_count=page_count,
        ocr_line_count=sum(len(record.lines) for record in records.values()),
        ocr_character_count=sum(len(vision_text(record)) for record in records.values()),
        minimum_character_coverage=min(coverages, default=1.0),
        retry_pages=retry_pages,
        sample_pages=sample_pages(page_count, str(source)),
        input_bytes=source.stat().st_size,
        output_bytes=output.stat().st_size,
        source_sha256=file_sha256(source),
        output_sha256=file_sha256(output),
    )
```

除上述实现外，`verify_document` 必须先确认每个 `OCRPageRecord` 的 page width/height/rotation 与对应原页一致。测试覆盖嵌套 Form XObject、缺页、box 改变、rotation 改变、图像 stream 改变和 99.5% 临界值。

逐页门槛为：

- page count、media box、crop box、rotation 完全一致；
- 原页递归 `/Image` XObject 解压数据 SHA-256 全部出现在成品对应页，不依赖对象编号；
- `character_coverage(Vision text, extracted text) >= 0.995`；
- 输入/结果 SHA-256、字节数、行数、字符数和抽样页进入 `DocumentReport`。

- [ ] **Step 5: 实现单文档事务、batch 和 CLI**

定义批报告：

```python
@dataclass(frozen=True)
class BatchReport:
    document_count: int
    page_count: int
    copied_file_count: int
    total_file_count: int
    retry_page_count: int
    minimum_character_coverage: float
    failures: tuple[str, ...]
    documents: tuple[DocumentReport, ...]
```

增加精确 CLI：

```text
build --input PDF --reference TXT --work DIR --output PDF --native-command PATH
verify --source-root DIR --reference-root DIR --work DIR --output DIR
batch --source-root DIR --reference-root DIR --work DIR --output DIR --native-command PATH
```

`process_document` 使用 `work/<stable-id>/input-manifest.json`、`ocr.jsonl`、`candidate.pdf`、`report.json`。stable id 是 source 相对路径的 SHA-256 前 16 位；单文档顺序固定为 fingerprint guard → `complete_records` → `build_searchable_pdf` → `verify_document` → 原子写 report → 发布到 batch staging。任何一步失败都不得创建该文档的发布路径。

`publish_input_set` 必须执行以下事务：

1. 拒绝已存在的 `output_root`，并在同一父目录创建唯一的隐藏 staging。
2. 枚举 source root 中全部 PDF/TXT，拒绝符号链接、重复相对路径和 PDF 重名 stem；枚举 reference root 中 14 个 TXT，并按 stem 唯一匹配 14 个 PDF。
3. 对匹配 PDF 调用 `process_document`；其余 37 个文件以 `shutil.copyfile` 按原相对路径复制，并逐个比较源/目标 SHA-256。
4. 确认 staging 共有 51 个 PDF/TXT、14 个文档报告、5,151 页、`failures=()`；在汇总前把每个 `DocumentReport.output` 从 staging 路径替换为对应的最终 output 路径，再将 `BatchReport` 原子写到 `work_root/report.json`。
5. 用同卷 `Path.replace` 将完整 staging 一次性发布为 `output_root`。失败时保留 checkpoint/candidate/report 供诊断，只删除本次唯一 staging；绝不触碰 source root 或已有输出。

`verify` 从 work manifest 找到 14 个 checkpoint，对已发布目录重新执行 `verify_document` 和 37 个副本哈希检查。异常打印一行错误并返回 1，不输出 traceback，不发布半目录。

- [ ] **Step 6: 运行 pipeline 测试并确认 GREEN**

```bash
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf.SearchablePDFPipelineTest -v
./scripts/agent-builder.sh -m unittest discover -s tools/agent_builder/tests -v
```

Expected: all tests PASS; interrupted or invalid candidates never appear at final path.

- [ ] **Step 7: 提交 Task 4**

```bash
git add tools/agent_builder/searchable_pdf.py \
  tools/agent_builder/tests/test_searchable_pdf.py
git commit -m "功能：增加 OCR 恢复与原子 PDF 批处理"
```

---

### Task 5: 用真实 Vision fixture 完成红绿回归与视觉验收

**Files:**
- Modify if a failing test proves a defect: `tools/agent_builder/native/vision_pdf_ocr.swift`
- Modify if a failing test proves a defect: `tools/agent_builder/searchable_pdf.py`
- Modify if a failing test proves a defect: `tools/agent_builder/tests/test_searchable_pdf.py`
- Temporary only: `tmp/pdfs/vision-ocr-fixture/`

**Interfaces:**
- Consumes: Tasks 1-4 CLI。
- Produces: 两页中文 fixture 的可检索 PDF、抽检 PNG 和测试报告。

- [ ] **Step 1: 运行真实端到端测试**

```bash
mkdir -p tmp/pdfs/vision-ocr-fixture
./scripts/agent-builder.sh -m unittest \
  tools.agent_builder.tests.test_searchable_pdf -v
```

Expected: all searchable PDF tests PASS.

- [ ] **Step 2: 渲染原稿和成品**

```bash
pdftoppm -f 1 -l 2 -r 150 -png \
  tmp/pdfs/vision-ocr-fixture/source.pdf \
  tmp/pdfs/vision-ocr-fixture/source
pdftoppm -f 1 -l 2 -r 150 -png \
  tmp/pdfs/vision-ocr-fixture/searchable.pdf \
  tmp/pdfs/vision-ocr-fixture/searchable
```

Expected: automated pixel comparison reports zero differing pixels.

- [ ] **Step 3: 用 `view_image` 检查两页成品 PNG**

Expected: normal and rotated pages have no visible overlay text, clipping, shifts, black boxes, or changed imagery.

- [ ] **Step 4: 检查提取文本**

```bash
pdftotext tmp/pdfs/vision-ocr-fixture/searchable.pdf -
```

Expected: both Chinese fixture sentences appear on the correct pages.

- [ ] **Step 5: 若真实测试暴露问题，严格做一次 RED-GREEN 修复**

先增加最小失败测试并观察预期失败，再修改实现，重新运行 `test_searchable_pdf` 和完整 agent-builder tests。然后：

```bash
git add tools/agent_builder/native/vision_pdf_ocr.swift \
  tools/agent_builder/searchable_pdf.py \
  tools/agent_builder/tests/test_searchable_pdf.py
git commit -m "修复：通过 Vision PDF 真实页面验收"
```

若无需修复，不创建空提交。

---

### Task 6: 批量生成并验收 14 份可检索 PDF

**Files:**
- External write: `/Volumes/game/books/agent-build/li-de-sheng-v2-ocr-work`
- External write: `/Volumes/game/books/agents/李德胜-v2-可检索原稿`
- No Git artifacts.

**Interfaces:**
- Consumes: 原始 51 文件、14 个同 stem OCR TXT、Tasks 1-5 CLI。
- Produces: 51 文件最终输入集；14 份 PDF 为新 Vision 文本层版本，37 份为字节相同副本。

- [ ] **Step 1: 运行只读 preflight**

```bash
test -d /Volumes/game/books/agents/李德胜-v1-原始书稿
test -d /Volumes/game/books/agent-build/li-de-sheng-ocr
test ! -e /Volumes/game/books/agents/李德胜-v2-可检索原稿
test -f /Users/tony/.config/harness-apk/keys/li-de-sheng.pem
test "$(find /Volumes/game/books/agent-build/li-de-sheng-ocr -maxdepth 1 -name '*.txt' | wc -l | tr -d ' ')" = 14
df -h /Volumes/game
```

Expected: 14 OCR TXT, final output absent, key present, sufficient free space.

- [ ] **Step 2: 启动可恢复批处理**

```bash
./scripts/vision-searchable-pdf.sh batch \
  --source-root /Volumes/game/books/agents/李德胜-v1-原始书稿 \
  --reference-root /Volumes/game/books/agent-build/li-de-sheng-ocr \
  --work /Volumes/game/books/agent-build/li-de-sheng-v2-ocr-work \
  --output /Volumes/game/books/agents/李德胜-v2-可检索原稿 \
  --native-command /Users/tony/Documents/harness-apk/scripts/vision-searchable-pdf.sh
```

Expected: progress reports document/page counters at least once per minute; rerunning the same command resumes checkpoints.

- [ ] **Step 3: 验证全量报告与输入数量**

```bash
jq '{documents, pages, retries, minimumCharacterCoverage, failures}' \
  /Volumes/game/books/agent-build/li-de-sheng-v2-ocr-work/report.json
find /Volumes/game/books/agents/李德胜-v2-可检索原稿 \
  -type f \( -name '*.pdf' -o -name '*.txt' \) | wc -l
```

Expected: `documents=14`, `pages=5151`, `failures=[]`, minimum coverage >= `0.995`, final files `51`.

- [ ] **Step 4: 验证 37 份非 OCR 文件字节相同**

根据 14 个 OCR stem 排除对应 PDF；对其余每个相对路径比较源/目标 SHA-256。Expected: 37/37 equal.

- [ ] **Step 5: 渲染并检查 70 个固定样本**

对每份文档报告中的 5 个 `samplePages`，分别渲染原稿和成品 150 DPI PNG；自动像素测试必须 70/70 相同。制作每份文档一张前后并排 contact sheet，使用 `view_image` 逐张检查。

- [ ] **Step 6: 全量再次运行文本与结构验证**

```bash
./scripts/vision-searchable-pdf.sh verify \
  --source-root /Volumes/game/books/agents/李德胜-v1-原始书稿 \
  --reference-root /Volumes/game/books/agent-build/li-de-sheng-ocr \
  --work /Volumes/game/books/agent-build/li-de-sheng-v2-ocr-work \
  --output /Volumes/game/books/agents/李德胜-v2-可检索原稿
```

Expected: 14/14 pass, 5,151/5,151 pages present, no image hash loss, character coverage gate passes.

---

### Task 7: 建立 V2 元数据发现工作区并执行唯一一次合并确认

**Files:**
- External write: `/Volumes/game/books/agent-build/li-de-sheng-v2-discovery`
- External read: `source-catalog.json` inside that workspace.

**Interfaces:**
- Consumes: Task 6 的 51 份输入。
- Produces: schema v2 unknown catalog and one consolidated user question.

- [ ] **Step 1: 确认 discovery 路径不存在**

```bash
test ! -e /Volumes/game/books/agent-build/li-de-sheng-v2-discovery
```

若存在失败或过期工作区，不删除；改名为同级带 ISO 时间戳的保留目录，并向用户报告。

- [ ] **Step 2: 稳定排序输入并首次 prepare-v2**

```bash
set -euo pipefail
setopt null_glob
inputs=(/Volumes/game/books/agents/李德胜-v2-可检索原稿/**/*.pdf(N) \
        /Volumes/game/books/agents/李德胜-v2-可检索原稿/**/*.txt(N))
inputs=("${(@on)inputs}")
test "${#inputs[@]}" = 51
./scripts/agent-builder.sh prepare-v2 "${inputs[@]}" \
  --agent-id li-de-sheng \
  --name 李德胜 \
  --version 2 \
  --output /Volumes/game/books/agent-build/li-de-sheng-v2-discovery
```

Expected: workspace created; catalog has 51 rows, all initial `genre/authorship/period` values are `unknown`.

- [ ] **Step 3: 生成稳定排序的合并元数据表**

按 `title,fileName` 排序，列出全部 51 行：序号、标题、文件名、建议 genre、建议 authorship、建议 period、建议理由。系列可以共用建议规则，但表中不得省略文件；每个值必须显式标为“建议”。

- [ ] **Step 4: 向用户提出一个合并确认问题并暂停**

只问：“是否接受整表建议；若不接受，请仅列出要修改的序号和值。”不得逐本拆问，也不得把建议静默写成事实。

Expected: user accepts or supplies corrections before Task 8.

---

### Task 8: 用确认 catalog 重建正式 V2 并完成九类资产

**Files:**
- External create: `/Volumes/game/books/agent-build/li-de-sheng-v2-source-catalog.json`
- External write: `/Volumes/game/books/agent-build/li-de-sheng-v2`
- External write: nine files under `/Volumes/game/books/agent-build/li-de-sheng-v2/agent/`.

**Interfaces:**
- Consumes: Task 7 用户确认值、51 份最终输入和 V2 chunk/node/duplicate index。
- Produces: 无 unknown 元数据、九类人物资产、至少 100 条 eval，全部 evidence 为真实 chunk ID。

- [ ] **Step 1: 生成确认后的 catalog**

先确认 `/Volumes/game/books/agent-build/li-de-sheng-v2-source-catalog.json` 不存在；若存在，只能改名保留为带 ISO 时间戳的同级文件，不得覆盖。复制 discovery catalog 到该路径，只修改用户确认的 `genre/authorship/period`；不得修改 prepare-v2 生成的 `sourceId/title/fileName/storedName/sourceHash`。验证：

```bash
jq -e '
  .schemaVersion == 2 and
  (.sources | length == 51) and
  all(.sources[];
    (.genre | IN("essay","speech","conversation","letter","interview","memoir","secondary")) and
    (.authorship | IN("direct","edited_direct","secondary")) and
    (.period != "unknown" and (.period | length > 0)))
' /Volumes/game/books/agent-build/li-de-sheng-v2-source-catalog.json
```

Expected: exit 0.

- [ ] **Step 2: 在新空目录重新 prepare-v2**

先确认 `/Volumes/game/books/agent-build/li-de-sheng-v2` 不存在；若存在，只能按 Task 7 的同样规则改名保留，不得覆盖。然后使用 Task 7 相同的排序输入数组：

```bash
./scripts/agent-builder.sh prepare-v2 "${inputs[@]}" \
  --agent-id li-de-sheng \
  --name 李德胜 \
  --version 2 \
  --output /Volumes/game/books/agent-build/li-de-sheng-v2 \
  --source-catalog /Volumes/game/books/agent-build/li-de-sheng-v2-source-catalog.json
```

Expected: schemaVersion 2, 51 sources, metadataCoverage 1.0, extractionFailures empty.

- [ ] **Step 3: 分批读取索引并建立外部证据账本**

只从以下文件提取事实：

```text
corpora/index/nodes.jsonl
corpora/index/chunks.jsonl
corpora/index/duplicates.jsonl
corpora/index/report.json
```

证据账本记录 chunk ID、sourceId、period、genre、authorship、top-level route、主题和目标资产。不得使用模型通用知识补人物立场。

- [ ] **Step 4: 写 `persona.md`、`identity.json`、`voice.json`**

`persona.md` 必须明确“李德胜是基于资料的模拟，不是真人或现实组织”，资料不足时回答未知。`identity.relationships[].evidence` 使用真实 chunk；`voice.evidence` 只用 `direct|edited_direct`，优先 `speech|conversation|letter|interview`。

- [ ] **Step 5: 写 `worldview.jsonl`、`episodes.jsonl`、`concepts.json`**

worldview 每条包含稳定 id、topic、statement、conditions、period、aliases、confidence、evidence；冲突观点按时期分开。episode 每条包含 location、participants、summary、meaning、period、evidence，且至少一条 direct/edited_direct 证据。concepts 使用稳定 id、name、aliases、keywords 和真实 evidence。

- [ ] **Step 6: 写 `examples.jsonl`、`openers.json`**

examples 每条包含 id、intent、user、assistant、styleTags、`generationType="synthesized"`、evidence；不得伪装成原话。openers 只含一个 default 和最多两个 alternatives。

- [ ] **Step 7: 写至少 100 条 `eval.jsonl`**

最低数量固定为：

```text
grounding 20
stance 30
voice 20
temporal 12
diversity 10
global 8
```

所有 `expectedEvidence` 为真实 chunk ID；voice 只引用 direct/edited_direct；stance/temporal period 与 evidence 一致；diversity 至少跨 2 sourceId 和 2 duplicateGroup；global 至少跨 2 sourceId 和 2 top-level route。

- [ ] **Step 8: 规划 corpus 并绑定真实归属题**

运行 `plan_corpus_shards` 与 `choose_install_profiles` 获取实际 install class。为每个 required/recommended corpus 至少分配两道 `corpusId` 与其 chunk 选择完全一致的题；重新规划直到推荐集合和题目归属稳定。不得把跨 shard evidence 强行归属给单一 corpus。

---

### Task 9: Validate、精确 Recommend、Balanced Pack 和独立复验

**Files:**
- External read/write: `/Volumes/game/books/agent-build/li-de-sheng-v2`
- External create: `/Volumes/game/books/agents/li-de-sheng-v2-balanced-release`

**Interfaces:**
- Consumes: Task 8 publishable workspace and existing publisher key。
- Produces: `li-de-sheng-v2-balanced.hbundle`、`.hagent`、独立 `.hcorpus`、build report；不产生 `.hsource`。

- [ ] **Step 1: 运行正式 validate**

```bash
./scripts/agent-builder.sh validate /Volumes/game/books/agent-build/li-de-sheng-v2 \
  | tee /Volumes/game/books/agent-build/li-de-sheng-v2/validate-report.json
```

Expected: exit 0, `publishable=true`, `errors=[]`, schemaVersion 2, all six categories meet count/rate gates.

- [ ] **Step 2: 使用最终同一 key 运行精确 recommend**

```bash
./scripts/agent-builder.sh recommend \
  /Volumes/game/books/agent-build/li-de-sheng-v2 \
  --key /Users/tony/.config/harness-apk/keys/li-de-sheng.pem \
  --json \
  > /Volumes/game/books/agent-build/li-de-sheng-v2/recommendation.json
```

Expected: schemaVersion 2, recommendedProfileId `balanced`; four profiles report exact signed bytes; preflight reports elapsedMilliseconds、sourceInputBytes、temporaryArtifactBytes.

- [ ] **Step 3: 确认发布目录不存在并打 balanced 包**

```bash
test ! -e /Volumes/game/books/agents/li-de-sheng-v2-balanced-release
./scripts/agent-builder.sh pack \
  /Volumes/game/books/agent-build/li-de-sheng-v2 \
  --output /Volumes/game/books/agents/li-de-sheng-v2-balanced-release \
  --key /Users/tony/.config/harness-apk/keys/li-de-sheng.pem \
  --profile balanced
```

Expected: output ends with `li-de-sheng-v2-balanced.hbundle`; no `.hsource` exists.

- [ ] **Step 4: 用独立 verifier 复验**

独立 Python verifier 必须检查：

- ZIP 条目唯一且路径安全；
- `checksums.json` 中每一项 SHA-256 与实际内容相同；
- `signature.json` 为 Ed25519，且可由现有私钥对应公钥验证；
- bundle agent id/name/version/profile 为 `li-de-sheng`/`李德胜`/`2`/`balanced`；
- bundle 不含 `.hsource` 或 `sources/` 原文；
- selected child IDs 与 signed install plan 的 balanced profile 完全一致；
- build report 的 selectedPackageIds、bundleSizeBytes、sourcesEmitted 与实际一致。

- [ ] **Step 5: 比较 recommend 与正式发布字节**

正式 `.hagent` 和每个 balanced child 的文件名、SHA-256、字节数必须与 recommendation 的同一签名快照一致；安装字节按 `hagent + selected signed children` 逐项求和复核。

---

### Task 10: 最终回归、清理和交付

**Files:**
- Delete temporary only: `tmp/pdfs/vision-ocr-fixture/`
- Preserve external: OCR work/checkpoints、V2 searchable source set、discovery/final workspaces、release directory。

**Interfaces:**
- Consumes: all prior tasks。
- Produces: clean repo, committed tool/tests, local artifacts and concise verification report.

- [ ] **Step 1: 运行完整 agent-builder 测试**

```bash
./scripts/agent-builder.sh -m unittest discover -s tools/agent_builder/tests -v
```

Expected: all tests PASS, zero failures/errors.

- [ ] **Step 2: 删除 repo 临时 fixture**

只删除 `tmp/pdfs/vision-ocr-fixture` 中由本计划生成的文件；不得删除外置 OCR checkpoint、可检索原稿、V2 工作区或用户原稿。

- [ ] **Step 3: 检查并提交剩余代码变更**

```bash
git status --short
git diff --check
```

仅 stage 本计划对应工具、测试和依赖；使用中文 commit message。若此前已逐步提交且无剩余变更，不创建空提交。

- [ ] **Step 4: 确认不推送并报告 Git 状态**

```bash
git status --short --branch
git rev-list --left-right --count @{upstream}...HEAD
```

Expected: worktree clean; local commits may ahead; no push.

- [ ] **Step 5: 向用户交付**

链接：

- `/Volumes/game/books/agents/李德胜-v2-可检索原稿`
- `/Volumes/game/books/agents/li-de-sheng-v2-balanced-release/li-de-sheng-v2-balanced.hbundle`
- `/Volumes/game/books/agent-build/li-de-sheng-v2/recommendation.json`
- `/Volumes/game/books/agent-build/li-de-sheng-v2/validate-report.json`

报告 14 份 PDF、5,151 页、51 份 V2 输入、OCR 重试页数、最低字符覆盖率、六类评测结果、四档精确安装字节、bundle SHA-256、是否含原文（应为否）和 publisher key 权限模式（不得展示私钥内容）。
