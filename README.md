# NetNote

Deník síťového připojení pro Android. Na pozadí zapisuje, přes co je telefon připojený (Wi‑Fi, mobilní data, hledání signálu, offline), a měří k tomu odezvu, rychlost a objem dat. Ukáže podíl Wi‑Fi a dat za posledních 24 hodin nebo 7 dní, historii přepnutí včetně přístupových bodů a buněk, a všechno umí vyexportovat do CSV.

Naměřená data zůstávají jen v telefonu. K měření odezvy a rychlosti se appka připojuje na servery Cloudflare; adresy i to, jak často se měří, jdou změnit v Nastavení. Testy rychlosti na Wi‑Fi přenesou ve výchozím nastavení až kolem 600 MB denně.

## Instalace

1. Stáhni soubor `.apk` z [Releases](../../releases/latest) a otevři ho v telefonu (Android 11 nebo novější). Android se zeptá na povolení instalace z neznámého zdroje.
2. Otevři aplikaci, klepni na **Spustit měření** a povol polohu, stav telefonu a notifikace. Bez polohy Android nevydá název Wi‑Fi sítě ani buňku.
3. V **Nastavení → Oprávnění** povol polohu na pozadí („Povolit vždy“), aby měření naběhlo i po restartu telefonu. Na telefonech jako realme tam najdeš i návod na automatické spouštění.
