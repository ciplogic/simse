Screens
====
The app's screens are XML, and minui's loader builds them into the arena the painter and the hit
test walk. They live here rather than in a string in `main.kt` so the XML gets a fence, syntax
highlighting and a file of its own, and so the code can name one. No prose line below carries a
colon, because a line with one is a resource entry like any other.

Menu:
```xml
<screen name="menu" width="360" height="348">
  <panel rect="12,16,336,84" palette="plain"/>
  <label rect="12,36,336,30" palette="emphasized" align="center">MINESWEEPER</label>
  <label rect="12,72,336,18" palette="dim" align="center">minui - SDL2 + Skia</label>
  <button name="beginner" rect="80,120,200,32">BEGINNER</button>
  <button name="medium" rect="80,160,200,32">MEDIUM</button>
  <button name="expert" rect="80,200,200,32">EXPERT</button>
  <button name="quit" rect="80,264,200,32">QUIT</button>
</screen>
```

Result:
```xml
<screen name="result" width="260" height="180">
  <custom name="scrim" rect="0,0,260,180"/>
  <panel name="dialog" rect="0,0,260,180" palette="plain">
    <label name="headline" rect="12,16,236,26" palette="emphasized" align="center">BOOM</label>
    <label name="detail" rect="12,50,236,18" palette="dim" align="center">the board had 10 mines</label>
    <button name="again" rect="12,116,112,30">PLAY AGAIN</button>
    <button name="to_menu" rect="136,116,112,30">MENU</button>
  </panel>
</screen>
```
