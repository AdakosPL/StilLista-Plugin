# StilLista — plugin nagród za głosy

Plugin Bukkit/Spigot/Paper/Purpur, który automatycznie nagradza graczy za głosy
oddane na [StilLista.pl](https://stillista.pl). Działa w modelu **PULL/API**:
serwer co kilkadziesiąt sekund pyta API StilLista o nowe (nieodebrane) głosy,
wykonuje za nie komendy-nagrody w grze i potwierdza ich odebranie.

- Gracz online → nagroda od razu.
- Gracz offline → nagroda trafia do kolejki (`pending.yml`) i zostaje wydana,
  gdy gracz następnym razem wejdzie na serwer.

## Kompatybilność

Skompilowany jako bajtkod **Java 8** względem **spigot-api 1.8.8**, używa wyłącznie
stabilnego API Bukkita (komendy, konsola, lista graczy online, scheduler,
zdarzenia) + `java.net`. Dzięki temu ładuje się na **Minecraft 1.8 – 26.x**.

> Uwaga: gwarantowane jest tylko podstawowe wykonywanie komend i wyszukiwanie
> graczy online w całym tym zakresie — celowo unikamy API zależnego od wersji.

## Instalacja

1. Pobierz `StilLista-1.0.0.jar` z panelu: **https://stillista.pl/dashboard/plugin**
   (sekcja „Pobierz plugin”) i wrzuć go do folderu `plugins/` na serwerze.
2. Uruchom/zrestartuj serwer raz, aby wygenerować `plugins/StilLista/config.yml`.
3. Skopiuj **klucz serwera** ze strony **Dashboard → Plugin → „Klucze Twoich
   serwerów”** i wklej go do `config.yml` jako `server-key`.
4. Wykonaj `/stillista reload` (lub zrestartuj serwer).

## Konfiguracja (`config.yml`)

```yaml
api-url: "https://stillista.pl"   # adres API (zwykle bez zmian)
server-key: ""                    # KLUCZ SERWERA z panelu — wymagany!
poll-interval-seconds: 60         # co ile sekund pytać API (min. 10)
reward-offline: true              # kolejkować nagrody dla offline?
claimed-message: true             # wysyłać graczowi wiadomość po nagrodzie?
claimed-message-text: "&aDziękujemy za głos na &dStilLista.pl&a!"
rewards:                          # komendy wykonywane jako KONSOLA, %player% = nick
  - "give %player% diamond 3"
  - "broadcast &d%player% &7zagłosował na &dStilLista.pl&7!"
```

### Dostosowanie nagród

W sekcji `rewards` wpisz dowolne komendy konsoli. `%player%` zostanie zastąpione
nickiem gracza, a kody kolorów z `&` są tłumaczone (np. dla `broadcast`).
Przykłady:

```yaml
rewards:
  - "eco give %player% 500"
  - "crate give %player% vote 1"
  - "lp user %player% permission settemp vip true 7d"
```

## Komendy i uprawnienia

| Komenda             | Opis                         | Uprawnienie       |
| ------------------- | ---------------------------- | ----------------- |
| `/stillista reload` | Przeładowanie konfiguracji   | `stillista.admin` |

Aliasy: `/sl`, `/stilista`.

## Budowanie ze źródeł

Wymaga JDK 8+ oraz Mavena (pobierze `spigot-api` z repozytorium Spigot):

```bash
cd /www/wwwroot/stillista-plugin
mvn -q -DskipTests package
# wynik: target/StilLista-1.0.0.jar
```
