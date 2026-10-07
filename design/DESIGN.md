# Síťový deník – návrh UI (v1)

Podklad pro převod testovacího GUI na skutečné obrazovky v Jetpack Compose (Material 3).
Datová vrstva už je hotová z prototypu – tady jde jen o UI.

Složka `obrazovky/` obsahuje 8 obrazovek jako HTML. Ber je jako přesnou předlohu
(barvy, velikosti, rozestupy, texty), ne jako kód k převzetí. Odkazy mezi soubory
odpovídají navigaci v appce.

**Čísla v návrhu jsou ukázková** (2,5 h skutečných dat + zbytek domyšlený). V appce
se vše počítá z databáze.

## Obrazovky a navigace

| Soubor | Obrazovka | Odkud se na ni jde |
|---|---|---|
| `Main.html` | Přehled · 24 h (výchozí) | spodní lišta „Přehled“ |
| `Prehled7d.html` | Přehled · 7 dní | přepínač 24 h / 7 dní |
| `Historie.html` | Historie změn | spodní lišta, „Celá historie změn“ |
| `Ping.html` | Ping | spodní lišta, karta Ping na přehledu |
| `Rychlost.html` | Rychlost | spodní lišta, karta Rychlost na přehledu |
| `DetailWifi.html` | Detail úseku Wi‑Fi | klepnutí na událost v historii |
| `DetailMobil.html` | Detail úseku mobilní data | klepnutí na událost v historii |
| `Nastaveni.html` | Nastavení | ozubené kolečko v hlavičce přehledu |

- Spodní navigace (Material 3 `NavigationBar`): Přehled, Historie, Ping, Rychlost.
- Detaily a Nastavení jsou podstránky se šipkou zpět, bez spodní lišty u Nastavení.
- Přepínač 24 h / 7 dní je i na Historii, Pingu a Rychlosti.

## Barvy (tmavý režim)

Pozadí a text:

| Token | Hex | Použití |
|---|---|---|
| background | `#1e1e2e` | pozadí obrazovky |
| surface | `#27273a` | karty |
| surfaceDim | `#181825` | spodní lišta, pozadí přepínačů |
| surfaceHigh | `#34344a` | aktivní segment, aktivní položka lišty |
| outline | `#313147` / `#45475a` | oddělovače / obrysy |
| onSurface | `#cdd6f4` | hlavní text |
| onSurfaceVariant | `#a6adc8` | vedlejší text |
| muted | `#9399b2` | popisky, osy grafů |
| primary | `#89b4fa` | akcent, odkazy, přepínače |
| success | `#a6e3a1` | „měření běží“, oprávnění OK |
| error | `#f38ba8` | smazání dat |

Stavy připojení (všude stejné, i v grafech):

| Stav | Barva | Poznámka |
|---|---|---|
| Wi‑Fi | `#89b4fa` | |
| Mobilní data | `#fab387` | |
| Hledání signálu | `#f9e2af` | |
| Offline | `#585b70` | |
| Telefon vypnutý | `#45475a` | s obrysem `#6c7086` |
| Neznámo | šrafování 45° `#7f849c` | výpadek měření, nikdy plnou barvou |

Světlý režim zatím navržený není (volba je v Nastavení, návrh přijde později).

## Typografie

- **Geist** – UI text. **JetBrains Mono** – všechna čísla, časy, IP, BSSID, ID buněk.
  Obě z Google Fonts (v Compose přes downloadable fonts nebo přibalené).
- Velikosti: nadpis obrazovky 22 sp/600 (podstránky 18–20), nadpis karty 15 sp/600,
  text řádku 13–15 sp, popisek 12 sp, osa grafu 11 sp, velké číslo 26–34 sp/500 mono.

## Komponenty

- **Karta:** surface, zaoblení 20 dp, padding 16 dp, rozestup mezi kartami 12 dp, okraj obrazovky 16 dp.
- **Dlaždice metriky:** background uvnitř karty, zaoblení 14 dp, velké mono číslo + popisek.
- **Segmentový přepínač:** surfaceDim, zaoblení 14 dp, segment 44 dp vysoký, aktivní surfaceHigh.
- **Řádek nastavení / detailu:** min. 52–60 dp, název vlevo, hodnota vpravo (mono), oddělovač outline.
- **Přepínač:** Material 3 `Switch` v barvě primary.
- **Koláč (donut):** 136 dp, prstenec 20 dp, uprostřed % Wi‑Fi.
- **Časová osa dne:** vodorovný pruh úseků podle délky; na Historii navíc tenké značky změn AP.
- **Heatmapa 7 dní:** 7 řádků × 24 hodin, buňka = převažující stav, vpravo % Wi‑Fi za den.
- **Sloupcové grafy:** ping po 15 min, rychlost po hodinách; barva sloupce = stav; bez měření jen tenká čárka.
- Grafy kreslit v Compose `Canvas` – knihovna na grafy není nutná.
- Ikony: obrysové, tloušťka 1,75–2 (Material Symbols Outlined sedí).
- Dotykové plochy min. 44–48 dp.

## Pravidla obsahu

- Ping a rychlost **vždy zvlášť pro Wi‑Fi a mobilní data**, nikdy společný průměr.
- Souhrny používají **medián**, rozsah min–max / p95 jako doplněk.
- Čísla česky: desetinná čárka (`9,5`), mezera před jednotkou (`12 ms`, `2,84 GB`).
- U objemu dat zvlášť uvést, kolik spotřebovaly vlastní speedtesty.
- Výpadek měření (stav Neznámo) poctivě ukázat v historii i grafech.

## Nastavení – co je navíc oproti plánu prototypu

Tyto volby v plánu nebyly, návrh je přidává (implementovat podle uvážení):

- interval pingu a speedtestů nastavitelný (+ upozornění na odhad denní spotřeby testů)
- denní limit dat pro testy na mobilu (výchozí 50 MB)
- netestovat na měřené Wi‑Fi (hotspot)
- pojmenování přístupových bodů podle BSSID (jména se ukazují v historii)
- uchovávání dat (např. 90 dní) – promazávání starých dat
- motiv: tmavý / světlý / podle systému
- u oprávnění tlačítko na vypnutí optimalizace baterie a návod na automatické spouštění (realme)
