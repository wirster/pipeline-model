## Pipeline Model (管线/管点三维建模与三维瓦片发布)

### 概述
- **目标**: 从管线/管点 SHP 数据生成 3D 模型（OBJ/MTL），转换为 3D Tiles（B3DM），支持颜色/贴图两种渲染方案、网格化分片与可选的 Draco 压缩。
- **技术栈**: Spring Boot 2.7.14（编译目标 Java 1.8）+ 原生前端静态页（`src/main/resources/static`）+ `obj23dtiles`（外部 Node.js 工具）+ `gltf-pipeline`（可选，Draco 压缩）。
- **核心能力**:
  - 管线（圆柱体）与管点（默认长方体/圆柱体，或按附属物/特征放置 OBJ 模型）
  - 颜色/贴图材质，贴图自动复制并在 MTL 中写入 `map_Kd`
  - 按网格大小分片，仅对非空分片生成瓦片
  - BatchTable 仅包含原始属性字段（不含系统字段）
  - OBJ/MTL 导出与 B3DM 转换，生成每个分片及管线/管点根级 `tileset.json`
  - 可选 Draco 后处理（对 b3dm 内嵌 glTF 调用 `gltf-pipeline` 压缩后回包）
  - 管线/管点双面几何（避免背面剔除造成“镂空”）

---

### 目录结构
```text
src/main/java/com/bdtdgis/
  PipeModelApplication.java             # Spring Boot 启动类
  config/FileConfig.java                # 输出/上传目录解析与创建（可配置）
  controller/ModelController.java       # 上传、建模、下载、状态接口
  model/                                # Mesh/Face/Vertex/PipeLine/PipePoint 等模型
  service/ModelService.java             # 任务编排：读SHP→分片→导出→转换→（可选）Draco
  util/
    PipeLineMeshBuilder.java            # 管线圆柱体与管点默认圆柱体
    PipePointMeshBuilder.java           # 管点默认几何/附属物/特征模型放置
    ObjExporter.java                    # OBJ/MTL 写出（含 map_Kd 纹理）
    BatchTableUtil.java                 # <objBase>_batchTable.json 生成
    CustomSpatialTilesUtil.java         # 网格分片与区域分配
    TilesetUtil.java                    # 分片 tileset.json 与根 tileset.json
    B3dmDracoCompressor.java            # b3dm 拆包/gltf-pipeline 压缩/回包
    ShpReader.java                      # SHP 读取与字段映射
    NodeUtil.java                       # 外部命令执行与工具可用性探测
src/main/resources/static/              # 前端静态页面
  index.html                            # UI：数据源/字段映射/建模参数
  js/app.js                             # 前端交互逻辑
```

---

### 构建与运行
- 先决条件: JDK 8+、Maven、Node.js（供 obj23dtiles 使用）。
- 开发运行:
```bash
mvn spring-boot:run
```
- 生产构建与启动（artifactId 为 `pipeline-model`）:
```bash
mvn clean package
java -jar target/pipeline-model-1.0.0.jar
```
- 访问: `http://localhost:8080/`

---

### 配置项（`src/main/resources/application.yml`）
| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `obj23dtilesPath` | `D:\nodejs\obj23dtiles.cmd` | `obj23dtiles` 可执行文件路径或命令名。为路径时会先检查文件是否存在；为命令名时通过 `命令 --version` 探测。建模前会前置校验，不可用则直接返回失败，不生成半成品。 |
| `dracoToolPath` | `gltf-pipeline` | Draco 压缩工具路径或命令名，支持 npm 全局安装后的 `gltf-pipeline` 命令或 `gltf-pipeline.cmd` 绝对路径。仅在 `enableDraco=true` 时使用。 |
| `app.file.output-dir` | `./output` | 3D Tiles 输出目录。相对路径按进程工作目录解析，绝对路径直接使用。目录无法创建时启动阶段直接报错。 |
| `app.file.upload-dir` | `./target/classes/uploads` | 上传的 SHP/贴图/模型包存放目录，解析规则同上。 |
| `app.file.use-relative-path` | `true` | 保留的兼容配置项，当前目录解析以上述路径配置为准。 |

> 默认 `./output` 会在工作目录下自动创建，不依赖 E 盘等特定盘符；如需固定位置，改成绝对路径即可（例如 `E:/3dtiles/tiles`）。

---

### 使用流程
1. 数据源设置
   - 上传管线/管点 SHP 压缩包（zip），在“管线图层/管点图层”下拉框中选择 .shp。
2. 字段映射
   - 管线必填：唯一标识码、起点编号、终点编号、起点高程、终点高程、管径。
   - 管线可选：起点埋深、终点埋深（仅读取保存，**不参与几何计算**）。
   - 管点必填：唯一标识码、管点编号、附属物、特征、井盖规格、井盖材质、地面高程、井深。
   - 管点可选：附属物类型、特征类型、方向。
   - 井盖类型无需单独映射：所有原始字段都会原样写入 BatchTable。
3. 建模参数
   - 方案：颜色 / 贴图
   - 管线材质（颜色或贴图文件）
   - 网格化管理（0.25 / 0.5 / 0.75 / 1 km，默认 0.25km）
   - 埋深基础（管顶 / 管心 / 管底，界面默认管顶）
   - 是否启用 Draco 压缩
4. 附属物/特征设置（可选）
   - 按“属性值（+类型）”上传 OBJ/MTL/贴图 ZIP 包，作为对应管点的替换模型。
5. 提交建模任务
   - 后端完成 SHP 读取、分片、OBJ/MTL 导出、B3DM 转换，按需执行 Draco 压缩。

#### 图层选择规则（真实行为）
上传接口会把压缩包内所有 `.shp` 以**相对上传目录的路径**返回（统一 `/` 分隔，按路径不区分大小写排序），并给出 `defaultLayer`（排序后的第一个）。
建模时 `lineLayer` / `pointLayer` 按以下顺序定位：
1. 未传或空白 → 使用排序后的第一个 `.shp`（没有文件则报错“请先上传…”）。
2. 传入相对路径 → 先按相对路径不区分大小写精确匹配。
3. 精确匹配失败 → 仅按文件名匹配；唯一同名文件时使用它。
4. 同名文件多于一个或完全不存在 → 返回错误并列出所有可选图层的相对路径。

---

### 几何算法（与代码一致）

#### 管线
- 几何为沿起终点轴线的双面圆柱体，32 段；半径 = 管径 / 2。
- 管径字段读取后按毫米处理（代码中 `/1000` 转换为米）。
- 起点/终点 Z 取“起点高程/终点高程”字段，再按埋深基础整体平移：
  - `top`（管顶）：`z = 高程 - radius`，最终管顶与高程对齐；
  - `center`（管心）：`z = 高程`，最终中心轴与高程对齐；
  - `bottom`（管底）：`z = 高程 + radius`，最终管底与高程对齐。
- 调整后空间长度小于 0.1m 的管线会被跳过（记日志，不生成该要素）。

#### 管点
模型匹配优先级：附属物模型 > 特征模型 > 默认几何。

未匹配到任何模型时生成默认几何：
- 管径：取与该管点关联（管点编号 = 管线起点编号/终点编号）的所有管线的最大管径；无关联管线时使用 0.3m。
- 有井深（`depth > 0.001`）：长方体井体，底面为正方形，边长 = 管径，高度 = 井深。
- 无井深：竖直圆柱体接头，半径 = 管径/2，高度 = 管径；高度最小 0.1m。
- 默认几何均为双面；圆柱为 32 段。默认颜色 `#7C95AA`（RGB 0.486/0.584/0.667），材质名 `mat_point`。

落位规则（`applyPointPlacement`）：
- 参考高程 `refZ`：优先取关联管线端部高程（同编号的起点/终点高程）中的最大值；无关联管线时取管点坐标 Z（即映射的地面高程）。
- `top`：几何包围盒顶部 = `refZ`；
- `center`：几何包围盒中心 = `refZ`；
- `bottom`（后端缺省值）：几何包围盒底部 = `refZ`。
- X/Y 以管点坐标为中心放置。
- 界面默认提交 `top`；仅当 `modelSettings.depthBase` 缺失时后端按 `bottom` 处理。

附属物/特征模型：OBJ 解析为 mesh 后，自动把 Y 轴“上”旋转为 Z 轴“上”，按方向字段/关联管线计算朝向，并按井深或关联管线高程差/管径调整高度，再按上述落位规则与管线对齐。

---

### 贴图与材质（真实行为）
- 颜色模式：OBJ 使用 `mat_line`，MTL 写入该材质的 `Kd`（取自 `pipeColor`，支持 `#RRGGBB`/`#RRGGBBAA`/`rgba(...)`）。
- 贴图模式（管线）：`/api/uploadTexture` 返回贴图绝对路径并作为 `pipeTexture` 提交；导出时：
  - 有可用贴图时，OBJ 中 `usemtl` 使用材质名（优先 `material` → `originalMaterial` → `materialMap`，否则 `mat_line_tex`）；
  - MTL 中对应写入 `newmtl <材质名>` 与 `map_Kd <贴图文件名>`；
  - 贴图文件会复制到该分片的输出目录（与 OBJ/MTL 同目录），`map_Kd` 使用文件名相对引用。
- 若贴图路径不可用（文件不存在），按无贴图处理，避免 `usemtl` 指向 MTL 中不存在的材质。
- 管点默认几何使用 `mat_point`；附属物/特征模型优先复用源 MTL 的材质与 `map_Kd`，缺失材质名时按组名/单一材质回退，并按需生成 1×1 颜色贴图。

---

### 批表（Batch Table）
- 仅使用 `Mesh.originalAttributes`（用户原始字段），不附加 `batchId`、`name` 等系统字段。
- 输出文件名为 `<objBase>_batchTable.json`，例如 `grid_0_0_batchTable.json`（不是 `batch_table.json`）。
- JSON 结构为“字段名 → 按要素顺序的数组”；某要素缺少该字段时填 `""`。

---

### 空间分片与坐标
- `CustomSpatialTilesUtil` 按 `gridSize`（km）在投影坐标上划分规则网格，分片索引从 1 开始，仅对包含要素的分片生成瓦片（不是按数据分布动态增减分片数量）。
- 每个分片导出时以分片中心做去中心化，OBJ/B3DM 使用局部坐标；坐标不做整体重投影。
- 输入 SHP 使用原始投影坐标；投影坐标仅在生成 `tileset.json` 的 `region` 和 `transform` 时转换为 WGS84（经纬度/ECEF）。
- 输出结构：
```text
<output-dir>/
├── line/
│   ├── tileset.json                         # 管线根瓦片集（children 指向分片 tileset.json）
│   └── <tileIndex>/3dtiles_output/
│       ├── grid_0_0.obj
│       ├── grid_0_0.mtl
│       ├── grid_0_0.b3dm
│       ├── grid_0_0_batchTable.json
│       └── tileset.json                     # 单分片瓦片集
└── point/                                   # 结构同 line/
    ├── tileset.json
    └── <tileIndex>/3dtiles_output/...
```
- 输出根目录**不会**生成 `tileset.json`；根级入口是 `line/tileset.json` 与 `point/tileset.json`。
- 每次建模不会清空输出目录，历史分片可能残留并被一起打包；需要干净产物时请先清空该目录。

---

### Draco 压缩（`enableDraco`）
- `enableDraco=true` 时先前置校验 `dracoToolPath`：
  - 配置为文件路径 → 检查文件存在；
  - 配置为命令名 → 通过 `命令 --version` 探测。
  - 工具不可用 → 整次建模直接返回失败（`errorType=TOOL_MISSING`），不生成未压缩的“假已压缩”瓦片。
- 每个分片 b3dm 生成后执行真实后处理：
  1. 按 b3dm 28 字节小端头拆出 featureTable / batchTable（原样保留）与内嵌 GLB；
  2. 执行 `gltf-pipeline -i in.glb -o out.glb -d`；
  3. 校验输出 GLB 合法、包含 `KHR_draco_mesh_compression`、`_BATCHID` 未丢失；
  4. 移除 `_BATCHID` accessor 上不合规的 `min/max`；
  5. 保留原表段字节，替换 glTF 段并做 8 字节对齐，回包覆盖原 b3dm。
- 单个分片压缩失败不会中断其他分片：该分片记为失败（`stage=draco`，`errorType=DRACO_FAILED`，工具在压缩阶段消失时为 `DRACO_TOOL_MISSING`）。
- 返回的 `draco` 字段反映真实执行结果：`requested`、`applied`（请求且全部分片压缩成功才为 true）、`tool`、`toolPath`、`compressedTileCount`、`failedTileCount`、`reason`。
- `enableDraco=false` 时不执行任何压缩，`draco.requested=false`。

---

### 建模结果与接口返回
`POST /api/model/generate` 返回三种状态（接口路径与参数不变）：

| 状态 | 判定 | 关键字段 |
| --- | --- | --- |
| 成功 | 所有分片生成成功且至少有一个瓦片 | `success=true`、`message="建模完成"`、`lineTileCount`、`pointTileCount`、`failedTileCount=0`、`failures=[]`、`draco` |
| 部分失败 | 有分片成功、也有分片失败 | `success=false`、`errorType=TILE_EXPORT_FAILED`（若全部失败原因均为工具缺失则为 `TOOL_MISSING`）、`message` 含成功/失败分片数与首个失败原因、`failures` 非空 |
| 失败 | 没有任何瓦片产出 | `success=false`，`errorType` 为 `TOOL_MISSING`（obj23dtiles/Draco 工具不可用）、`NO_TILE`（无失败但零产出）或 `TILE_EXPORT_FAILED`（分片全部失败） |

失败明细 `failures` 为数组，每项包含：
- `dataType`: `line` / `point`
- `tileIndex`: 分片索引
- `stage`: 失败阶段 `obj` / `batchTable` / `b3dm` / `draco` / `tileset`
- `errorType`: `TILE_EXPORT_FAILED` / `TOOL_MISSING` / `DRACO_FAILED` / `DRACO_TOOL_MISSING`
- `reason`: 具体原因（含外部工具输出摘要）

前端按 `success` 与成功分片数展示“建模完成 / 建模部分失败 / 建模失败”，部分失败时仍允许下载已有产物。

---

### 下载与状态接口
- `GET /api/download/3dtiles/status`
  - 有产物：`success=true`、`hasFiles=true`、`foundFiles=["line","point"]`（按存在情况）、`message="OK"`。
  - 无产物：`success=false`、`hasFiles=false`、`errorType=NO_OUTPUT`、`message="未找到可下载的3D Tiles产物…"`。
  - `hasFiles` 通过递归查找 `tileset.json` 判定。
- `GET /api/download/3dtiles/zip`
  - 输出目录不存在或没有任何 `tileset.json`：HTTP 404，JSON `{success:false,errorType:"NO_OUTPUT",message:"…"}`。
  - 打包异常：HTTP 500，JSON `{success:false,errorType:"ZIP_FAILED",message:"…"}`。
  - 成功：`application/zip`，文件名 `3dtiles.zip`。
  - ZIP 内容为输出目录下的 `line/`、`point/` 等目录，**不含根级 `tileset.json`**；ZIP 自身不会被再次打包。

---

### 常见问题（FAQ）
- 模型看起来“镂空”或缺面？
  - 已启用双面几何；若仍出现，检查查看器的背面剔除设置。
- 贴图未显示？
  - 确认方案为“贴图”、贴图已上传成功，并检查分片目录中 MTL 的 `map_Kd` 与贴图文件是否同名同目录。
- 管线与管点在接口处错位？
  - 管点按埋深基础与关联管线端部参考高程对齐；请确认管线端点编号与管点编号一致，且高程字段有效。
- 建模返回“建模失败”且 `errorType=TOOL_MISSING`？
  - 检查 `obj23dtilesPath`（或启用 Draco 时的 `dracoToolPath`）是否指向本机真实可执行文件/命令。
- 启用 Draco 后部分分片失败？
  - 查看 `failures` 中 `stage=draco` 的 `reason`；常见原因是 gltf-pipeline 执行失败或输出未包含 Draco 扩展。
- 下载提示“未找到可下载的3D Tiles产物”？
  - 说明输出目录下没有任何 `tileset.json`；先确认建模是否成功，以及 `app.file.output-dir` 实际指向哪里。
- 下载包里出现旧数据？
  - 输出目录不会在建模前清空，请手动清理后重新建模。

---

### 许可证
- 内部项目，未设置公开许可证。如需开源请补充 LICENSE。
