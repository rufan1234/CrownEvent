# CrownEvent (Kral Tacı) – Paper/Purpur 26.2, Java 25

## Derleme
Gereksinimler: JDK 25, Gradle 9.1+ (Java 25 için gerekli).

    gradle build
    # Paper API surum dizesi farkliysa:
    gradle build -PpaperVersion=<dogru.surum>

Cikti: `build/libs/CrownEvent-1.0.0.jar` -> sunucunun `plugins/` klasorune at, sunucuyu yeniden baslat.
`plugins/CrownEvent/config.yml` olusur; townlari, mesajlari ve `custom-event-message` degerini duzenle, sonra `/crownevent reload`.

## Komutlar (izin: crownevent.admin)
- /crownevent start               - etkinligi hemen baslatir (sandik + duyuru)
- /crownevent stop                - etkinligi durdurur, sandigi kaldirir
- /crownevent status              - mevcut durum
- /crownevent givecrown <oyuncu>  - 6 gunluk taze Tac (hunt mekanigi olmadan; etki/parcacik/sure dolumu var)
- /crownevent addtown <isim> <yaricap> - bulundugun konumu town olarak kaydeder (isimde bosluk yerine _ kullan)
- /crownevent reload

## Notlar
- `crownevent.bypass` izni kisitlamalari yok sayar (kimseye varsayilan verilmez).
- Etkinlik penceresi (varsayilan 120 dk) boyunca herkes icin tp/elytra yasak; Tac'i tasiyan teslim edene kadar her zaman yasak.
- Tac teslim edilince tum kisitlamalar kalkar; Tac 6 gun boyunca etki + parcacik vermeye devam eder.
- Sure dolumu: online envanter/enderchest, yuklu chunk'taki sandiklar, yerdeki esyalar, item frame, armor stand, chest minecart + chunk/entity yuklenme ve envanter acma anlarinda silinir.
- Bilinen sinir: Tac bir shulker box'in ICINDEYSE (ic ice envanter) taranmaz.
