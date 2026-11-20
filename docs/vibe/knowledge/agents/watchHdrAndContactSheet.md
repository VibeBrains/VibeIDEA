# [инцидент] HDR-кадры и лист кадров в /watch

**Контекст.** Хендофф VibeIDE 03.11.2026: ролик с айфона (HDR10, HLG, Dolby Vision) без перевода
цвета даёт бледные серые кадры — модель видит не то, что человек. Второй приём: 12 отдельных
вложений дороже одной картинки-сетки, а время на кадре модели приходится держать в голове.

**Суть.** HDR — это `color_transfer` `smpte2084` (PQ) или `arib-std-b67` (HLG); в баннере
`ffmpeg -i` видно как `yuv420p10le(tv, bt2020nc/bt2020/smpte2084)`. Перевод ставится ДО `scale`:
`zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,
zscale=t=bt709:m=bt709:r=tv,format=yuv420p`. `zscale` требует libzimg и есть не в каждой сборке:
проверяем `ffmpeg -filters`, нет — кадры без перевода и строка в ленте, а не падение. Лист кадров
строится сеткой под число кадров (для одного кадра — 1x1, а не 4x3 с пустыми ячейками), тайм-код
ставится на кадр до укладки. Собираем лист в Java (`ContactSheet`), а не фильтрами `tile`/`drawtext`:
в статической сборке ffmpeg их может не быть, а `drawtext` без fontconfig падает на шрифте.

**Применение.** `WatchProbe.isHdr`, `SceneFrames.TONEMAP/filter(tonemap = true)`, `ContactSheet.grid/
compose`; `WatchPipeline` отдаёт один файл для нескольких кадров, `frameSeconds` перечисляет времена
ячеек по порядку. Тесты `WatchProbeTest`, `ContactSheetTest`, `SceneFramesTest`. ⚠ живого HDR-ролика
не было: проверен разбор баннера, фильтр и сборка листа, но не реальный ffmpeg с libzimg.
