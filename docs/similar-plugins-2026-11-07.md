# 参考動画に近い公開プラグインの調査

調査日: 2026-11-07。参考動画: https://www.youtube.com/watch?v=ATQc0o-uOFw 。動画本文の直接取得はYouTube側の制限でできず、添付スクリーンショットの「自作プラグイン・MODとリソースパック不使用」という説明を参照しました。

今回確認した範囲では、動画と同じ「クライアントMOD・リソースパック不要、透過地図へMMDを視点別投影する」完成品の公開配布は確認できませんでした。同じ方式の公開物が存在しないと断定する調査ではありません。

| 公開物 | 方式と違い |
| --- | --- |
| [TheCymaera / Hologram](https://github.com/TheCymaera/minecraft-hologram) | Paper/Spigot向け。Text Displayで3Dメッシュを描く実験的プラグイン。[リリースJAR](https://github.com/TheCymaera/minecraft-hologram/releases)あり。作者READMEにマルチプレイ未検証と記載。透過地図方式やPMX/VMDプレイヤーではなく、26.3での動作は今回未検証。 |
| [3DEngine](https://hangar.papermc.io/ResourcePackAI/3DEngine) | 公式掲載はPaper/Spigot 26.3向けでGLB/GLTF/OBJ、テクスチャ、アニメーションに対応と説明。クライアントMOD不要だが、プラグインが作成・送信するリソースパックが必要。動画とは要件が異なる。今回未導入。 |
| [HoloFrames](https://github.com/JacksonHoggard/hologrammc) | アイテムフレームに独自3Dモデルを表示するFabric MOD。Paperプラグインの置換ではない。 |
| [Hologram Projector](https://github.com/Sylsatra/hologram) | Fabric 1.21.8用MOD。BlazeRodでPMX/PMDと外部VMDを扱う。作者READMEにサーバープレイ未検証と記載。 |

Paperの標準スケジューラは20tick/秒ですが、独立したJavaの時間スケジューラを使うことはできます。今回の60fps化ではワールドとプレイヤーの情報取得をメインスレッドに残し、GPU描画とネイティブ地図送信を分離しました。[Paper公式スケジューラ文書](https://docs.papermc.io/paper/dev/scheduler/)
