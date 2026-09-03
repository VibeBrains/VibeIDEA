#!/usr/bin/env bash
# Канал обновлений: пишет updates/updates.xml по СОБРАННОМУ артефакту.
#
# Источник номера сборки и версии — product-info.json рядом с артефактом, а не пересчёт из версии:
# отображение «версия → номер сборки» живёт в одном месте (VibeBuildNumber в сборке), и второй
# экземпляр той же формулы в скрипте однажды разошёлся бы с первым. Файл читает IDE по адресу
# raw.githubusercontent.com/…/main/updates/updates.xml (VibeProductUrls) — значит, он должен лежать
# в main к моменту релиза: коммит этого файла идёт ДО штампа, штамп сверяет его с артефактом.
#
# Использование: ./vibe-plugins/tools/releaseUpdatesXml.sh vX.Y.Z
set -euo pipefail
cd "$(dirname "$0")/../.."
. vibe-plugins/tools/pythonBin.sh

VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "✖ укажите версию: releaseUpdatesXml.sh vX.Y.Z"; exit 1; }
ARTIFACTS=out/vibeidea/artifacts
INFO=$(ls -t "$ARTIFACTS"/*.product-info.json 2>/dev/null | head -1 || true)
[ -n "$INFO" ] || { echo "✖ нет product-info.json в $ARTIFACTS — сначала соберите инсталлятор"; exit 1; }
OUT=updates/updates.xml
"$PYTHON" - "$INFO" "$VERSION" "$OUT" <<'PY'
import io, json, sys, datetime, xml.etree.ElementTree as ET
info_path, tag, out = sys.argv[1], sys.argv[2], sys.argv[3]
info = json.load(io.open(info_path, encoding='utf-8'))
version, build, code = info['version'], info['buildNumber'], info['productCode']
if tag.lstrip('v') != version:
    sys.exit(f"✖ версия артефакта ({version}) не совпадает с выпускаемой ({tag})")
if 'SNAPSHOT' in build:
    # Платформа читает SNAPSHOT как Integer.MAX_VALUE: такая сборка считает себя новее любого числа,
    # и канал обновлений для её пользователей молчит навсегда. Публиковать её в канал — врать.
    sys.exit(f"✖ номер сборки {build} — SNAPSHOT: релиз обязан нести настоящий номер (VibeBuildNumber)")
release_url = f"https://github.com/VibeBrains/VibeIDEA/releases/tag/{tag}"
today = datetime.date.today().strftime('%Y%m%d')
try:
    tree = ET.parse(out); products = tree.getroot()
except (FileNotFoundError, ET.ParseError):
    products = ET.Element('products'); tree = ET.ElementTree(products)
product = products.find(f"./product[code='{code}']")
if product is None:
    product = ET.SubElement(products, 'product', name='VibeIDEA')
    ET.SubElement(product, 'code').text = code
channel = product.find("./channel[@id='vibeidea-release']")
if channel is None:
    channel = ET.SubElement(product, 'channel', id='vibeidea-release', status='release', licensing='release',
                            name='VibeIDEA', url='https://github.com/VibeBrains/VibeIDEA/releases')
for old in channel.findall('build'):
    if old.get('number') == build or old.get('version') == version:
        channel.remove(old)  # переиздание той же версии заменяет запись, а не дублирует её
b = ET.SubElement(channel, 'build', number=build, version=version, releaseDate=today)
ET.SubElement(b, 'message').text = f"Вышла VibeIDEA {version}. Патчей нет — новая версия ставится поверх, настройки сохраняются."
ET.SubElement(b, 'button', name='Скачать', url=release_url, download='true')
# Новые сборки первыми: платформа берёт максимум, но человеку удобнее читать сверху.
builds = sorted(channel.findall('build'), key=lambda e: [int(x) for x in e.get('number').split('.')], reverse=True)
for e in channel.findall('build'): channel.remove(e)
for e in builds: channel.append(e)
ET.indent(tree, space='  ')
tree.write(out, encoding='unicode', xml_declaration=True)
io.open(out, 'a', encoding='utf-8').write('\n')
print(f"  updates.xml: {code} {build} / {version} → {release_url}")
PY
echo "Закоммитьте $OUT: IDE читает его из ветки main, штамп сверит его с артефактом."
