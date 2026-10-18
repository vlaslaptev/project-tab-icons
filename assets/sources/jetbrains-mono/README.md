# Исходник символьных иконок

`JetBrainsMono-Bold.ttf` взят из [официального релиза JetBrains Mono 2.304](https://github.com/JetBrains/JetBrainsMono/tree/v2.304).
Полная лицензия OFL 1.1 сохранена в `licenses/JetBrains-Mono-OFL-1.1.txt`.
Хеш исходника, лицензии и всех полученных иконок — в `docs/icon-provenance.json`.

SVG создаёт `tools/icons/generate_symbols.py` с помощью Python и FontTools.
Генератор сохраняет прежние строки каталога, обновляет только символьные иконки
и их происхождение. Буквы и цифры преобразуются в залитые пути; плагин не загружает
шрифт во время работы. Две монограммы нарисованы отдельно и используют MIT проекта.
