# SellMacro (Fabric 1.21.11, client-side)

Build:  ./gradlew build     (Windows: gradlew.bat build)  -> build/libs/sellmacro-1.0.0.jar
Needs JDK 21 and Fabric API in your mods folder.

Commands:
  /sellmacro                toggle on/off (default item: sweet_berries)
  /sellmacro sweet_berries  sell that item (any item id); switches item if already running
  /sellmacro delay 20       ticks between cycles
  /sellmacro command sell   command that opens the server sell GUI (e.g. sellgui)
