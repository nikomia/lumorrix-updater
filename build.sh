#!/bin/sh
# Сборка lumorrix-updater.jar. Нужен только JDK 21+ (javac/jar), Gradle не нужен.
# Библиотеки для компиляции берутся из папки сервера (Server/libraries, или укажите свою папку в LIB).
#   src/     — апдейтер и установщик, Java 17 (установщик запускается на любой Java 17+);
#   src21/   — то, что работает только внутри NeoForge (локатор мода), Java 21;
#   src8/    — Main, Java 8: на старой Java покажет понятное сообщение;
set -e
cd "$(dirname "$0")"
LIB="${LIB:-../../Server/libraries}"
CP="$LIB/cpw/mods/modlauncher/11.0.5/modlauncher-11.0.5.jar;$LIB/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar;$LIB/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar"
LOADER="$LIB/net/neoforged/fancymodloader/loader/4.0.45/loader-4.0.45.jar"
rm -rf build && mkdir -p build/classes dist
# Одна версия везде: апдейтер, мод в игре, список модов лаунчера.
VERSION=$(grep -o 'static final String VERSION = "[^"]*"' src/ru/lumorrix/updater/Updater.java | cut -d'"' -f2)

# Апдейтер
javac --release 17 -encoding UTF-8 -cp "$CP" -d build/classes src/ru/lumorrix/updater/*.java
javac --release 21 -encoding UTF-8 -cp "$CP;$LOADER;build/classes" -d build/classes src21/ru/lumorrix/updater/*.java
javac --release 8 -Xlint:-options -encoding UTF-8 -d build/classes src8/ru/lumorrix/updater/Main.java
cp -r res/META-INF res/lumorrix build/classes/
sed -i "s/@VERSION@/$VERSION/" build/classes/META-INF/neoforge.mods.toml
sed "s/^Implementation-Version: .*/Implementation-Version: $VERSION/" res/MANIFEST.MF > build/MANIFEST.MF
jar --create --file dist/lumorrix-updater.jar --manifest build/MANIFEST.MF -C build/classes .
rm -rf build
echo "OK: dist/lumorrix-updater.jar ($VERSION)"
