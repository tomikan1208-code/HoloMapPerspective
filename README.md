# HoloMapPerspective v0.7.0 — 内向き地図・GPU描画・最大60fps送信

アップロードされた **v0.4.0** を基礎として変更した、Minecraft Java **26.3 / Paper 26.3 / Java 25** 用プラグインのソースです。クライアントMODは不要。正面1面の表示と、6面すべてにプレイヤー別視差を反映した立方体・直方体表示に対応します。

GPU版はPaperと同じPCのPython 3・ModernGL・Pillowを使います。OpenGLで描いた画像をJavaがMinecraftの地図色へ変換します。Paper 26.3では描画と地図パケット送信を20TPSの処理から分離し、最大60回/秒を目標に更新します。実際の速度はモデル、地図枚数、同時表示数、視聴人数、通信帯域に依存します。CPU方式とネイティブ送信が使えない場合のBukkit方式は最大20回/秒です。

## 導入（Windows）

1. **JDK 25** と Python 3 をインストール。NVIDIA/AMD/Intel のGPUドライバを最新版に更新。
2. PowerShellで `py -3 -m pip install -r gpu-requirements.txt` を実行。`python -m pip ...` でも可。
3. `JAVA_HOME` にJDK 25のインストール先を設定し、ソースのフォルダで `.\gradlew.bat build` を実行。
4. `build/libs/HoloMapPerspective-0.7.0.jar` をPaper 26.3サーバーの `plugins` フォルダへ配置し、旧版のJARをplugins外へ退避。サーバーを完全再起動。
5. 起動時に `plugins/HoloMapPerspective/gpu/renderer.py` が同梱版へ更新される。`config.yml`の`gpu-python-command`が利用可能なPythonコマンド（例:`python`、`py -3`）であることを確認。自分で変更したGPUスクリプトは事前に保存する。
6. `/holomap cube 1 1 1 demo` を実行。`/holomap status` でモデルの準備状況と実際のOpenGL実行環境を確認する。

正式なPaper APIとJava 25でビルドし、RTX 3050の実GPUと独立したPaperテストサーバーで検証しています。[0.6.0の検証記録](docs/gpu-0.6.0-verification.md)

0.7.0では、新しい透明な直方体の地図を内向きに置きます。プレイヤーの視点から見て奥の面に投影すると、近づいたときもモデルに使える画素が増えます。近くで見るなら2×4×2が目安です。発光額縁で暗い場所でもモデルの色を保ちます。

GPU方式では移動イベントで視点を更新し、描画時間と通信の遅れを短い予測で補います。急な切り返しやテレポートで古くなった描画は破棄します。大きい直方体で移動中の負荷が高い場合は描画を軽くし、静止時に細部を描き直します。

## 主なコマンド

```text
/holomap cube 2 2 2 demo
/holomap cube 2 3 4 home
/holomap place 2 2 home
/holomap create world 0 70 0 2 3 4 home
/holomap list
/holomap models
/holomap model 1 home
/holomap status
/holomap reload
/holomap remove 1
```

`cube <X> <Y> <Z> [model]`の3軸は幅(+X)、高さ(+Y)、奥行(+Z)。設置したい地面のブロック上面を向いて実行します。`create`は座標指定。必要地図数は `2*(X*Y+Y*Z+Z*X)` 。上限は `max-prism-axis` / `max-prism-maps` を参照してください。

## モデルの入れ方

```text
plugins/
  HoloMapPerspective/
    config.yml
    models/
      home/
        model.obj         ← 静止モデル。ファイル名固定
        materials.mtl     ← 任意。OBJ内の mtllib 指定名に合わせる
      miku_dance/
        frames/
          0000.obj
          0001.obj
          0002.obj
    gpu-cache/             ← 自動生成。消しても再計算される
    gpu/
      renderer.py          ← 自動生成。GPUの描画処理
    gpu-renderer.log       ← GPU起動/描画失敗時のログ
```

モデル名はフォルダ名です。通常は `.obj` と `.mtl` の `Kd` 色を読み込みます。GPU方式では `model.hmesh` があれば同じフォルダの `model.obj` より優先します。`HMAPTEX1` と `HMAPTEX2` には、`model.hmesh.materials.json` と相対パスの `textures/` を一緒に置きます。`tools/prepare_pmx.py` で作る `HMAPTEX2` は、PMXの法線、陰影用・反射用画像、輪郭線と半透明の材質を使います。アニメーションは `frames/*.obj` の連番に対応します。

今回のSparxieは `tools/prepare_pmx.py` で元PMXの拡散テクスチャ7枚を含む静止モデルに変換しました。骨・VMD・トゥーン・スフィアマップは変換しません。家は `tools/prepare_house_lod.py` をBlenderのバックグラウンド処理で使い、原本を保存せずメッシュのコピーを軽量化しました。変換済みの個人用モデルはこのプロジェクトの `prepared-models/`、サーバー用コピーは `plugins/HoloMapPerspective/models/` に保存します。

巨大OBJはバックグラウンドで1行ずつ読み、GPU転送向けのhmeshへ変換します。更新日時と容量に基づくキャッシュで次回から変換を省略します。GPU転送は8MB単位、同時保持は最大8メッシュです。初回変換には時間と空き容量が必要で、最終的な頂点データと画像テクスチャを保持するVRAMも必要です。

## config.yml の主な設定

```yaml
renderer-backend: gpu
cube-inward: true
cube-glow: true
render-fps: 60
animation-fps: 60
gpu-python-command: python
max-obj-bytes: 1500000000
max-triangles: 3000000
max-animation-frames: 120
max-prism-axis: 8
max-prism-maps: 96
```

`cube-inward` と `cube-glow` は新しく作る直方体に適用します。内向き投影には `cube-material: AIR` または `BARRIER` を使ってください。外向きの地図で作るには `cube-inward: false` を設定します。

`max-obj-bytes` は**OBJ原本の容量**、`max-triangles` は**三角形換算ポリゴン数**の上限です。設定を増やせば、読み込み可能なモデルは増えますが、VRAM消費やレンダリング時間も増加します。600MBのOBJも容量上限には収まりますが、ポリゴン数上限は別に確認が必要です。`/holomap status` で `FAILED` の理由を確認できます。

アップグレード時、旧v0.4.0 configの既定値 `render-fps:5`、`animation-fps:10`、`max-triangles:6000` は初回起動時に新しい値に移行します。既定値と異なる数値は保持します。既存の `displays.yml` と `models/` は残し、事前にワールドと設定ファイルをバックアップしてください。

## トラブルシューティング

- `/holomap status` にGPU名、待機ジョブ数、完了シーン数、直近の描画時間を表示します。GPUプロセスはサーバー起動時に準備します。
- `GPU ERROR: ...` → `plugins/HoloMapPerspective/gpu-renderer.log` を確認。まず `py -3 -m pip show moderngl`、`python --version` でPythonを確認。Windowsは `gpu-python-command: py -3` に変更可。
- `ModuleNotFoundError: moderngl` → サーバーのPython環境へModernGLをインストール。
- `Hardware GPU unavailable` → GPUドライバ、Windowsログオンセッション、VPSのGPU搭載有無を確認。ソフトウェアOpenGLではCPUによる描画になるため初期設定では拒否します。
- `OBJ exceeds max-triangles` → `max-triangles`を増やすかBlenderでDecimate。`max-obj-bytes`のみ変えても解決しません。
- GPUの起動ができない場合、`renderer-backend: cpu` で旧CPU方式を選択できますが、旧CPU用OBJ読み込みには10MB制限が残り、巨大OBJには利用できません。

## 検証内容と制約

- 正式なPaper APIでビルド。JUnitで描画・インポート・モデルのフィット・地図差分・視点予測を検証しています。
- 実Paper＋GPUで76視点・タイルの一貫性を確認。60fps方式では実際のネイティブ地図パケットをメモリ上の受信先へ渡し、モデル別と複数同時表示の速度を測定します。結果は検証記録を参照してください。Minecraftクライアントの実表示fpsや通信込みの数値ではありません。
- 地図は平面に投影する表示です。実際の3Dメッシュと同じ奥行き情報をクライアントへ渡していないため、雨や他の物との前後関係は完全には一致しません。展示空間の外から見ることを想定し、移動中には視点の取得・描画・通信による遅れが残ります。
- ゲーム画面での目視確認は未実施です。画素比較と実GPU・Paper処理の検証範囲を検証記録に記載しています。
- OpenGLとMinecraft間の色変換、地図データ配信はCPU側に残ります。GPU化は**3D三角形のラスタライズ**部分です。視聴者と6面の数に比例して負荷が増える場合があります。
