# Meridian Telemetry Pipeline — Mimari Kılavuz

> Bu belge, projenin teknik mimarisini, alınan kararları, reddedilen alternatifleri ve her seçimin **neden** yapıldığını ayrıntılı biçimde açıklar. Bir referans dokümanı olarak değil, bir **mimari ustalık sınıfı** (masterclass) olarak yazılmıştır. Amacı, 10 kişilik geliştirme ekibimizin her bireyinin bu sistemi bütünüyle kavraması ve gelecekte benzer ölçekteki projelerde bağımsız mimari kararlar alabilecek düzeye ulaşmasıdır.

---

## İçindekiler

1. [Proje Vizyonu ve Kapsamı](#1-proje-vizyonu-ve-kapsamı)
2. [Takım Yapısı ve Git İş Akışı](#2-takım-yapısı-ve-git-iş-akışı)
3. [Mimari Kararlar ve Reddedilen Alternatifler](#3-mimari-kararlar-ve-reddedilen-alternatifler)
4. [Clean Architecture ve DDD Açıklaması](#4-clean-architecture-ve-ddd-açıklaması)
5. [Eşzamanlılık ve Performans Stratejisi](#5-eşzamanlılık-ve-performans-stratejisi)
6. [Dayanıklılık ve Hata Toleransı](#6-dayanıklılık-ve-hata-toleransı)
7. [Zarif Kapanış (Graceful Shutdown)](#7-zarif-kapanış-graceful-shutdown)
8. [Tasarım Desenleri (Design Patterns)](#8-tasarım-desenleri-design-patterns)
9. [Yapılandırılmış Loglama Stratejisi](#9-yapılandırılmış-loglama-stratejisi)
10. [Gelecek Öğrenme ve Ufuk Genişletme](#10-gelecek-öğrenme-ve-ufuk-genişletme)

---

## 1. Proje Vizyonu ve Kapsamı

### 1.1 Problem Tanımı

Dünya genelinde yüzlerce fabrikada konuşlandırılmış on binlerce endüstriyel IoT sensörü (titreşim, sıcaklık, basınç, nem) saniyede binlerce veri noktası üretmektedir. Bu verilerin:

- **Gerçek zamana yakın** toplanması,
- **CPU-yoğun sinyal işleme** algoritmalarıyla analiz edilmesi (FFT, Z-skoru, istatistiksel anomali tespiti),
- **Kalıcı olarak** bir ilişkisel veritabanında saklanması,
- Anomali tespit edildiğinde **anında uyarı** üretilmesi,
- Tüm operasyonel verilerin **merkezi bir loglama sisteminde** izlenebilmesi

gerekmektedir.

### 1.2 Teknik Zorluklar

Bu problem alanı, kasıtlı olarak iki farklı performans darboğazını doğal şekilde içerir:

| Zorluk Tipi | Açıklama | Çözüm Stratejisi |
|---|---|---|
| **CPU-bound** (İşlemci sınırlı) | FFT (Hızlı Fourier Dönüşümü), Z-skoru hesaplama, istatistiksel analiz | `multiprocessing` ile GIL'i aşma |
| **I/O-bound** (Giriş/Çıkış sınırlı) | RabbitMQ tüketimi, Oracle yazma, Redis okuma/yazma, ELK loglama | `asyncio` ile asenkron I/O |

### 1.3 Teknoloji Yığını

| Bileşen | Teknoloji | Neden Bu? |
|---|---|---|
| Dil | Python 3.11+ | Ekip yetkinliği, ekosistem genişliği, tip güvenliği (strict mypy) |
| Veritabanı | Oracle Database | Kurumsal gereksinim, ACID garantileri, ölçeklenebilirlik |
| Mesaj Kuyruğu | RabbitMQ | Olgun AMQP protokolü, gelişmiş yönlendirme, yüksek güvenilirlik |
| Önbellek | Redis | Atomik operasyonlar (SET NX), düşük gecikme, paylaşımlı durum |
| Loglama | ELK Stack | Endüstri standardı merkezi loglama, güçlü sorgu ve görselleştirme |
| Yapılandırma | pydantic-settings | Tip güvenli yapılandırma, fail-fast doğrulama |
| Dayanıklılık | tenacity + özel Circuit Breaker | Geçici hata yeniden deneme + sürdürülen kesinti koruması |

---

## 2. Takım Yapısı ve Git İş Akışı

### 2.1 Takım Rolleri (10 Kişi)

Bir kurumsal projede roller net tanımlanmalıdır. Önerilen dağılım:

| Rol | Kişi Sayısı | Sorumluluk |
|---|---|---|
| Tech Lead | 1 | Mimari kararlar, kod inceleme onayı, teknik borç yönetimi |
| Kıdemli Geliştirici | 2 | Çekirdek altyapı (veritabanı, mesajlaşma, dayanıklılık) |
| Orta Düzey Geliştirici | 3 | Domain katmanı, uygulama servisleri, iş mantığı |
| Junior Geliştirici | 2 | Test yazımı, dokümantasyon, yardımcı araçlar |
| DevOps Mühendisi | 1 | CI/CD, Docker, izleme (monitoring), altyapı |
| QA Mühendisi | 1 | Test stratejisi, entegrasyon testleri, yük testleri |

### 2.2 Git Dallanma Stratejisi — Git Flow (Basitleştirilmiş)

Aşağıdaki stratejiyi uyguluyoruz:

```
main (production-ready)
  └── develop (entegrasyon dalı)
        ├── feature/MERID-123-fft-optimization
        ├── feature/MERID-456-redis-cache-ttl
        └── feature/MERID-789-oracle-batch-insert
```

**Kurallar:**

1. **`main`**: Her zaman üretime alınabilir (deployable) durumda. Sadece `develop`'tan merge alır. Etiketlerle (tag) sürümlenir: `v1.0.0`, `v1.1.0`.
2. **`develop`**: Entegrasyon dalı. Tüm feature dalları buraya merge edilir. CI pipeline burada çalışır.
3. **`feature/*`**: Her iş birimi (ticket) için ayrı dal. İsimlendirme: `feature/MERID-{ticket-no}-{kısa-açıklama}`.
4. **`hotfix/*`**: Üretimde acil düzeltme gerektiğinde `main`'den dallanır, hem `main`'e hem `develop`'a merge edilir.

### 2.3 Kod İnceleme (Code Review) Süreci

Her Pull Request (PR) için:

1. **En az 2 onay** gereklidir (biri Tech Lead veya Kıdemli Geliştirici olmalı).
2. **CI pipeline tamamen yeşil** olmalıdır (lint, type check, testler, SonarScanner).
3. **PR açıklaması** şunları içermelidir:
   - Değişikliğin amacı ve bağlamı.
   - Test edilme stratejisi.
   - Mimari kararlar (varsa).
4. **İnceleme kontrol listesi:**
   - [ ] Hard-coded değer yok mu?
   - [ ] Domain katmanında altyapı bağımlılığı yok mu?
   - [ ] Hata durumları ele alınmış mı?
   - [ ] Circuit breaker koruması eklenmiş mi?
   - [ ] Birim testleri yazılmış mı?

### 2.4 Commit Mesajı Standardı

[Conventional Commits](https://www.conventionalcommits.org/) standardını kullanıyoruz:

```
feat(processing): add FFT window overlap support
fix(oracle): handle connection timeout during batch insert
refactor(consumer): extract message validation to DTO layer
test(domain): add edge case tests for stale reading detection
docs(arch): update circuit breaker state diagram
```

**Neden bu standart?** Otomatik sürüm notları (changelog) üretilebilir, semantic versioning otomasyonu yapılabilir, ve geçmişteki değişiklikler anlamlı şekilde aranabilir.

---

## 3. Mimari Kararlar ve Reddedilen Alternatifler

Bu bölüm, projenin en kritik kısmıdır. Her mimar, her kararın arkasındaki **"neden"** sorusuna yanıt verebilmelidir. Bir seçim yapmak, aynı zamanda alternatifleri **bilinçli olarak reddetmek** demektir.

### 3.1 Neden Oracle? (PostgreSQL Neden Reddedildi?)

| Kriter | Oracle | PostgreSQL |
|---|---|---|
| Kurumsal destek | 7/24 kurumsal destek sözleşmeleri | Topluluk + ticari dağıtımlar |
| Ölçeklenebilirlik | RAC (Real Application Clusters) ile yatay ölçekleme | Replikasyon var ama native clustering yok |
| Partitioning | Gelişmiş otomatik tablo bölümleme | Var ama daha sınırlı |
| Python async desteği | `oracledb` 2.0+ ile native async | `asyncpg` ile mükemmel async destek |
| Maliyet | Lisans maliyeti yüksek | Açık kaynak, ücretsiz |

**Kararımız:** Oracle tercih edildi çünkü kurumsal gereksinim olarak belirlenmiştir. Mimari olarak **Repository Pattern** kullanarak Oracle'a olan bağımlılığı tek bir dosyaya (`oracle_telemetry_repository.py`) izole ettik. Gelecekte PostgreSQL'e geçiş gerekirse, domain ve application katmanlarında **tek satır bile değişmez** — sadece yeni bir `PostgresTelemetryRepository` yazarsınız.

**Önemli ders:** Veritabanı seçimi bir **altyapı kararıdır**, mimari karar değil. İyi bir mimari, veritabanını **takılabilir** (pluggable) kılar.

### 3.2 Neden Saf Multiprocessing? (Celery Neden Reddedildi?)

| Kriter | multiprocessing + asyncio | Celery |
|---|---|---|
| Karmaşıklık | Düşük — stdlib, ek bağımlılık yok | Yüksek — broker, backend, beat, flower |
| Kontrol | Tam kontrol: havuz boyutu, kapanış, paylaşımlı bellek | Soyutlanmış — iç mekanizmalar gizli |
| Performans | Doğrudan süreç yönetimi, minimum seri hale getirme yükü | Mesaj seri hale getirme + broker gecikmesi |
| Öğrenme eğrisi | Python stdlib bilgisi yeterli | Celery'nin kendi konfigürasyonu, "magic" davranışları |
| Asenkron entegrasyon | `run_in_executor()` ile doğal asyncio entegrasyonu | Celery 5.x'te async desteği hâlâ olgunlaşmamış |

**Kararımız:** Saf `multiprocessing` + `asyncio` tercih edildi çünkü:
1. **Eğitimsel amaç:** Ekibimiz Python'un eşzamanlılık mekanizmalarını derinden öğrenecek.
2. **Kontrol:** Graceful shutdown, shared memory, ve süreç yaşam döngüsü üzerinde tam kontrolümüz var.
3. **Basitlik:** Ek bir altyapı bileşeni (Celery worker, Celery beat, Redis backend) yönetmemize gerek yok.

**Celery ne zaman daha iyi olurdu?** Onlarca farklı asenkron görev tipi, zamanlama (scheduling), ve görev zincirleme (chaining) gerektiğinde. Bizim kullanım senaryomuz tek bir CPU-yoğun görev tipi (sinyal işleme) içeriyor — Celery burada overkill.

### 3.3 Neden RabbitMQ? (Kafka Neden Reddedildi?)

| Kriter | RabbitMQ | Apache Kafka |
|---|---|---|
| Mesaj modeli | Akıllı broker, basit tüketici | Aptal broker, akıllı tüketici |
| Yönlendirme | Gelişmiş exchange/routing key desteği | Topic-based, daha basit |
| Mesaj onayı | Per-message ACK/NACK | Offset-based commit |
| Sıralama garantisi | Kuyruk başına garanti | Partition başına garanti |
| Yeniden işleme | Dead letter exchange ile | Offset geri sarma ile (doğal) |
| Ölçek | Dikey ölçekleme, kümeleme | Yatay ölçekleme, partitioning |
| Karmaşıklık | Orta — tanıdık AMQP | Yüksek — ZooKeeper/KRaft, partition yönetimi |

**Kararımız:** RabbitMQ tercih edildi çünkü:
1. Bizim modelimiz klasik **producer-consumer** — bir mesaj **bir kez** işlenir ve onaylanır. RabbitMQ tam olarak bu iş için tasarlanmıştır.
2. **Gelişmiş yönlendirme** gerekiyor: Anomali alertlerini `alert.vibration.DEV-001` gibi routing key'lerle yönlendiriyoruz. RabbitMQ'nun topic exchange'i bunu doğal olarak destekler.
3. **Operasyonel basitlik:** RabbitMQ Management UI ile kuyruk durumu, mesaj sayısı, tüketici bağlantıları anlık izlenebilir.

**Kafka ne zaman daha iyi olurdu?** Event sourcing, olay tekrarı (event replay), çok yüksek throughput (saniyede milyonlarca mesaj), ve birden fazla bağımsız tüketicinin aynı veri akışını okuması gerektiğinde. Bu konuyu [Bölüm 10'da](#10-gelecek-öğrenme-ve-ufuk-genişletme) detaylı inceliyoruz.

### 3.4 Neden pydantic-settings? (python-dotenv + dataclass Neden Reddedildi?)

| Kriter | pydantic-settings | python-dotenv + dataclass |
|---|---|---|
| Tip doğrulama | Otomatik — port numarası int olmalı, yoksa hata | Manuel — os.getenv() her zaman string döner |
| Fail-fast | Uygulama başlangıcında, eksik yapılandırma anında hata verir | Eksik yapılandırma, kod çalıştığında patlayabilir (3 saat sonra!) |
| İç içe yapılandırma | OracleSettings, RedisSettings gibi nested modeller | Düz sözlük veya karmaşık manuel yapılar |
| Varsayılan değerler | Field(default=...) ile temiz tanım | getenv("KEY", "default") — dağınık |
| Belgeleme | Her ayar model tanımından okunabilir | Dağınık os.getenv() çağrıları |

**Kararımız:** pydantic-settings ile **sıfır hard-coded değer** ilkesini zorluyoruz. Bir geliştirici yanlışlıkla `host = "localhost"` yazarsa, bu değer `.env` veya ortam değişkeninden geliyor olmalı — settings modeli bunu garanti eder.

---

## 4. Clean Architecture ve DDD Açıklaması

### 4.1 Clean Architecture Nedir?

Robert C. Martin (Uncle Bob) tarafından ortaya konan Clean Architecture, yazılımı **eş merkezli halkalar** (concentric circles) şeklinde organize eder. Temel kural: **Bağımlılık Kuralı** (Dependency Rule).

```
                  ┌───────────────────────────────────────┐
                  │         Presentation Layer            │
                  │    (Health Check HTTP, CLI, API)       │
                  │                                       │
                  │    ┌───────────────────────────────┐   │
                  │    │     Infrastructure Layer      │   │
                  │    │  (Oracle, RabbitMQ, Redis,    │   │
                  │    │   Logging, WorkerPool)        │   │
                  │    │                               │   │
                  │    │   ┌───────────────────────┐   │   │
                  │    │   │  Application Layer    │   │   │
                  │    │   │  (Use Cases:          │   │   │
                  │    │   │   IngestionService,   │   │   │
                  │    │   │   ProcessingService)  │   │   │
                  │    │   │                       │   │   │
                  │    │   │   ┌───────────────┐   │   │   │
                  │    │   │   │ Domain Layer  │   │   │   │
                  │    │   │   │ (Entities,    │   │   │   │
                  │    │   │   │  Value Objs,  │   │   │   │
                  │    │   │   │  Events)      │   │   │   │
                  │    │   │   └───────────────┘   │   │   │
                  │    │   └───────────────────────┘   │   │
                  │    └───────────────────────────────┘   │
                  └───────────────────────────────────────┘
```

**Bağımlılık Kuralı:** Oklar **HER ZAMAN içe doğru** gösterir. Dış katmanlar iç katmanlara bağımlı olabilir, ancak iç katmanlar dış katmanlardan **asla** haberdar olamaz.

Pratikte bu şu anlama gelir:
- **Domain katmanı** hiçbir şeyi import etmez (Oracle yok, RabbitMQ yok, Redis yok).
- **Application katmanı** domain'i import eder, ama altyapıyı doğrudan değil — sadece **arayüzleri** (interface/port) import eder.
- **Infrastructure katmanı** hem domain'i hem application'ı import eder ve arayüzleri **somut sınıflarla** uygular.
- **Presentation katmanı** her şeyi import edebilir (ama genellikle sadece application'ı kullanır).

### 4.2 Katman Detayları

#### Domain Katmanı (`src/meridian_telemetry/domain/`)

Bu katman, sistemin **kalbidir**. Teknolojiden bağımsız, saf iş kurallarını içerir.

- **Entities** (Varlıklar): `TelemetryReading`, `Device` — kimliğe sahip, yaşam döngüsü olan nesneler.
- **Value Objects** (Değer Nesneleri): `SensorType`, `ReadingStatus` — kimlikleri olmayan, değerleriyle tanımlanan nesneler.
- **Domain Events** (Alan Olayları): `AnomalyDetectedEvent`, `ReadingProcessedEvent` — geçmiş zamanda isimlendirilen, olan biteni temsil eden olgular.
- **Exceptions** (İstisnalar): `InvalidReadingError`, `StaleReadingError` — iş kuralı ihlallerini temsil eder.
- **Repository Interfaces** (Depo Arayüzleri): `TelemetryRepository` — kalıcılık işlemlerinin soyut tanımı.

**Neden bu kadar katı izolasyon?** Çünkü domain katmanı, sisteminizin **en uzun ömürlü** parçasıdır. Veritabanınız değişebilir, mesaj kuyruğunuz değişebilir, ama iş kurallarınız (bir okumanın geçerliliğini nasıl belirlediğiniz, anomaliyi nasıl tanımladığınız) nadiren değişir. Bu katmanı saf tutarak, tüm teknoloji değişikliklerinden korumuş olursunuz.

#### Application Katmanı (`src/meridian_telemetry/application/`)

Bu katman, **kullanım senaryolarını** (use cases) orkestre eder. Bir orkestra şefi gibidir: enstrüman çalmaz (iş mantığı), enstrüman yapmaz (altyapı), ama her müzisyenin **ne zaman** çalacağını yönetir.

- **Services**: `IngestionService`, `ProcessingService` — akış kontrolü.
- **DTOs**: `TelemetryReadingIngestDTO` — katmanlar arası veri taşıma nesneleri.
- **Interfaces** (Portlar): `MessageBroker`, `CacheService` — altyapı bağımlılıklarının soyut tanımları.

#### Infrastructure Katmanı (`src/meridian_telemetry/infrastructure/`)

Bu katman, **adaptörleri** (adapters) içerir. Port'ları somut teknolojilerle uygular.

- `OracleConnectionManager` → `TelemetryRepository` port'unu uygular
- `RabbitMQConsumer` → `MessageConsumer` port'unu uygular
- `RedisCacheService` → `CacheService` port'unu uygular

#### Presentation Katmanı (`src/meridian_telemetry/presentation/`)

Dış dünyayla temas noktası. Bizim durumumuzda: `/health` HTTP endpoint'i.

### 4.3 Bağımlılık Enjeksiyonu ve Bileşim Kökü (Composition Root)

`bootstrap.py` dosyası, tüm bağımlılıkların oluşturulduğu ve birbirine bağlandığı **tek noktadır**. Bu, Dependency Injection'ın en saf halidir:

```python
# bootstrap.py'den — somut sınıf, soyut arayüze atanır
repository: TelemetryRepository = OracleTelemetryRepository(oracle_manager)
```

**Neden DI framework kullanmadık?** Açıklık (explicit > implicit). 15 nesne için bir DI container overkill'dir. Ekibimizin DI'ın **ne yaptığını** anlaması, bir framework'ün **nasıl yaptığını** öğrenmesinden daha önemlidir.

---

## 5. Eşzamanlılık ve Performans Stratejisi

### 5.1 GIL (Global Interpreter Lock) Problemi

Python'un GIL'i, aynı anda yalnızca **bir thread'in** Python bytecode çalıştırmasına izin verir. Bu, CPU-bound iş yüklerinde threading'i etkisiz kılar:

```
Threading ile (CPU-bound):
  Thread 1: ████░░░░████░░░░████  (GIL alınır, bırakılır, alınır...)
  Thread 2: ░░░░████░░░░████░░░░
  Toplam süre: ~T (paralellik YOK, sadece dönüşümlü çalışma)

Multiprocessing ile (CPU-bound):
  Process 1: ████████████████████  (kendi GIL'i)
  Process 2: ████████████████████  (kendi GIL'i)
  Toplam süre: ~T/2 (gerçek paralellik)
```

### 5.2 Bizim Çözümümüz: İki Dünyayı Birleştirmek

```
┌─────────────────────────────────────────────────────┐
│              Ana Süreç (Main Process)                │
│                                                     │
│  ┌────────────────────────────────────────────────┐  │
│  │          asyncio Event Loop                    │  │
│  │                                                │  │
│  │  RabbitMQ Consumer ──► IngestionService        │  │
│  │  Oracle Connection  ◄── ProcessingService      │  │
│  │  Redis Cache        ◄── ProcessingService      │  │
│  │  RabbitMQ Publisher ◄── ProcessingService      │  │
│  │  Health Check HTTP Server                      │  │
│  │                                                │  │
│  │  ┌──────────────────────────────────────────┐  │  │
│  │  │  loop.run_in_executor(ProcessPool, fn)   │  │  │
│  │  │        ▲                                 │  │  │
│  │  │        │ await (non-blocking)            │  │  │
│  │  └────────┼─────────────────────────────────┘  │  │
│  └───────────┼────────────────────────────────────┘  │
│              │ pickle serialization                   │
│  ┌───────────▼────────────────────────────────────┐  │
│  │       ProcessPoolExecutor (4 workers)          │  │
│  │                                                │  │
│  │  Worker 1: FFT + Anomali Tespiti  (kendi GIL)  │  │
│  │  Worker 2: FFT + Anomali Tespiti  (kendi GIL)  │  │
│  │  Worker 3: İstatistiksel Analiz   (kendi GIL)  │  │
│  │  Worker 4: İstatistiksel Analiz   (kendi GIL)  │  │
│  └────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

**Kilit satır:**
```python
result = await loop.run_in_executor(self._executor, process_sensor_reading, ...)
```

Bu satır şunu yapar:
1. `process_sensor_reading` fonksiyonunu ve argümanlarını pickle (seri hale getirme) ile bir worker sürecine gönderir.
2. Worker süreci, **kendi Python yorumlayıcısında, kendi GIL'iyle**, hesaplamayı yapar.
3. Sonuç pickle ile geri döner.
4. `await` sayesinde ana sürecin event loop'u bu süre boyunca **bloklanmaz** — diğer I/O işlemlerini (Oracle yazma, Redis okuma) işlemeye devam eder.

### 5.3 Neden `spawn` Metodu?

```python
mp_context = multiprocessing.get_context("spawn")
```

| Metod | Nasıl çalışır? | Riskler |
|---|---|---|
| `fork` | Üst sürecin belleğini kopyalar | asyncio event loop, DB bağlantıları, dosya tanıtıcıları kopyalanır → kaos |
| `spawn` | Yeni, temiz Python yorumlayıcı başlatır | Daha yavaş başlangıç, ama güvenli ve öngörülebilir |
| `forkserver` | fork + spawn hibrit | Linux-only, karmaşık |

**Kararımız:** `spawn` kullanıyoruz çünkü ana sürecimizde asyncio event loop, Oracle bağlantı havuzu, RabbitMQ kanalları gibi paylaşılmaması gereken kaynaklar var. `fork` bunları kopyalayarak çocuk süreçlerde tanımsız davranışa yol açardı.

---

## 6. Dayanıklılık ve Hata Toleransı

### 6.1 Temel İlke: Dış Servisler ÇÖKEBİLİR

Bir kurumsal sistemde şu gerçeği kabullenmelisiniz: Oracle çökecek, RabbitMQ yeniden başlayacak, Redis belleği dolacak. Sisteminiz bunu **beklenen bir durum** olarak ele almalı, **beklenmeyen bir felaket** olarak değil.

### 6.2 İki Katmanlı Savunma

```
İstek → [Retry (tenacity)] → [Circuit Breaker] → Dış Servis
         Geçici hatalar için    Sürdürülen kesintiler için
```

**Katman 1 — Retry (tenacity):**
- **Ne zaman?** Geçici hatalar (ağ kesintisi, bağlantı havuzu doluluk).
- **Nasıl?** Exponential backoff + jitter: 1s → 2s → 4s → 8s (rastgele kaydırma ile).
- **Neden jitter?** Jitter olmadan, 1000 worker aynı anda yeniden deneme yapar → "thundering herd" problemi. Rastgele kaydırma, istekleri zamana yayar.

**Katman 2 — Circuit Breaker:**
- **Ne zaman?** Hata eşiği aşılınca (5 ardışık hata).
- **Nasıl?** CLOSED → OPEN → HALF_OPEN → CLOSED döngüsü.

```
          ┌──── Hata eşiği aşıldı ────┐
          ▼                            │
     ┌─────────┐   recovery_timeout  ┌─────────┐
     │  OPEN   │ ──────────────────► │HALF_OPEN│
     │(reddeder│                     │(test     │
     │ anında) │ ◄────────────────── │ istekler)│
     └─────────┘   test başarısız    └────┬─────┘
                                          │ test başarılı
                                          ▼
                                     ┌─────────┐
                                     │ CLOSED  │
                                     │(normal) │
                                     └─────────┘
```

**Neden sadece retry yetmez?**
Retry'lar, zaten çökmüş bir servise binlerce istek göndermeye devam eder. Bu, toparlanmaya çalışan servisi daha da zorlar. Circuit Breaker **geri basınç** (backpressure) sağlar: "Bu servis çöktü, denemekten vazgeç, belirli süre bekle, sonra dikkatli bir şekilde tekrar dene."

### 6.3 Servis Bazında Farklı Politikalar

| Servis | Retry Stratejisi | Neden? |
|---|---|---|
| Oracle | Sonsuz retry, max 60s backoff | Kritik — veri kaybedilemez |
| RabbitMQ | Sonsuz retry, max 30s backoff | Kritik — mesaj akışı durmamalı |
| Redis | 30s timeout sonra vazgeç | Kritik değil — cache miss Oracle'a düşer |

Redis'in "vazgeç" stratejisi **kasıtlıdır**. Redis bir önbellektir, birincil veri deposu değil. Düşerse pipeline yavaşlar ama durmaz. Bu, **zarif bozulma** (graceful degradation) prensibinin somut uygulamasıdır.

---

## 7. Zarif Kapanış (Graceful Shutdown)

### 7.1 Problem

Ctrl+C (SIGINT) veya `docker stop` (SIGTERM) geldiğinde:

| Yanlış Yaklaşım | Doğru Yaklaşım |
|---|---|
| `sys.exit()` veya `kill -9` | Sinyal yakalanır, kapanış sırası başlatılır |
| Mesajlar yarıda kalır → kayıp | In-flight mesajlar tamamlanır → sıfır kayıp |
| Oracle bağlantıları zombie olur | Bağlantı havuzu temiz kapatılır |
| RabbitMQ mesajları yeniden teslim edilir | Consumer iptal edilir, ACK'lar tamamlanır |
| Log buffer'ı flush edilmez → kayıp | Loglar flush edilir |

### 7.2 Kapanış Sırası (main.py)

```
SIGINT/SIGTERM alındı
    │
    ▼
1. Consumer.stop_consuming()
   └─ Yeni mesaj kabul etme
   └─ In-flight mesajların tamamlanmasını bekle (30s timeout)
    │
    ▼
2. HealthCheck.stop()
   └─ HTTP sunucusunu kapat
    │
    ▼
3. WorkerPool.shutdown(timeout=30s)
   └─ Yeni görev kabul etme
   └─ Çalışan görevlerin tamamlanmasını bekle
   └─ Worker süreçlerini temiz sonlandır
    │
    ▼
4. Publisher.close()
   └─ RabbitMQ yayıncı bağlantısını kapat
    │
    ▼
5. Cache.close()
   └─ Redis bağlantı havuzunu kapat
    │
    ▼
6. OracleManager.close()
   └─ Oracle bağlantı havuzunu kapat (force=False)
   └─ Aktif sorguların tamamlanmasını bekle
    │
    ▼
7. Log flush + çıkış (exit code 0)
```

**Neden bu sıra?** Kapanış sırası, başlangıç sırasının **tersidir**. Önce veri kaynaklarını (consumer) kapatırsınız, sonra veri işleyicilerini (worker pool), en son veri depolarını (Oracle, Redis). Bu sıra, kapanış sırasında hiçbir bileşenin kapalı bir bağımlılığa erişmeye çalışmamasını garanti eder.

### 7.3 Windows Uyumluluğu

Unix'te `loop.add_signal_handler()` kullanılır. Windows'ta bu API mevcut olmadığından, `signal.signal()` ile senkron sinyal yakalayıcı kullanırız. Bu, `main.py`'deki platform kontrolüyle ele alınmıştır.

---

## 8. Tasarım Desenleri (Design Patterns)

### 8.1 Repository Pattern

**Ne:** Veri erişim mantığını soyutlayan bir arayüz deseni.

**Neden:** Domain katmanı, verinin **nerede** saklandığını bilmemeli. `TelemetryRepository` arayüzü domain'de, `OracleTelemetryRepository` uygulaması infrastructure'da yaşar.

**Fayda:** Oracle → PostgreSQL geçişinde domain ve application katmanlarında **sıfır değişiklik**. Birim testlerde gerçek veritabanı yerine mock repository kullanılır → testler milisaniyelerde çalışır.

### 8.2 Factory Method Pattern

**Ne:** Nesne oluşturmayı kontrol eden bir sınıf metodu.

**Neden:** `TelemetryReading.create()` fabrika metodu, bir okumanın **geçerli** bir şekilde oluşturulmasını garanti eder. Doğrudan `__init__` kullanmak, doğrulama kurallarının atlanmasına izin verir.

**Fayda:** Sistemde geçersiz bir `TelemetryReading` nesnesi **var olamaz**. Tutarsız durumların önüne geçilir.

### 8.3 Circuit Breaker Pattern

**Ne:** Dış servis çağrılarını koruyan bir durum makinesi.

**Neden:** [Bölüm 6.2'de](#62-iki-katmanlı-savunma) detaylı açıklandı. Cascading failure'ı (zincirleme çöküş) önler.

**Fayda:** Bir bileşenin çökmesi tüm sistemi çökertmez.

### 8.4 Domain Event Pattern

**Ne:** Domain'de olan biteni temsil eden değişmez (immutable) kayıtlar.

**Neden:** `AnomalyDetectedEvent` yayınlandığında, anomali alert yayıncısı bunu dinler ve RabbitMQ'ya alert gönderir. Domain, kimin dinlediğini **bilmez**. Bu, modüller arası gevşek bağlantı (loose coupling) sağlar.

**Fayda:** Yeni bir dinleyici eklemek (örn. SMS gönderme) için domain kodunda **sıfır değişiklik** gerekir.

### 8.5 Ports & Adapters (Hexagonal Architecture)

**Ne:** Uygulamanın çekirdeğini (domain + application) dış dünyadan port'lar ve adaptörler aracılığıyla izole etmek.

**Neden:** `CacheService` bir port'tur (application katmanında soyut arayüz), `RedisCacheService` bir adaptördür (infrastructure katmanında somut uygulama).

**Fayda:** Teknoloji bağımsızlığı. Bir adaptörü değiştirmek, çekirdeği etkilemez.

### 8.6 Composition Root Pattern

**Ne:** Tüm bağımlılıkların oluşturulduğu ve bağlandığı tek nokta.

**Neden:** `bootstrap.py` dosyası, hangi somut sınıfın hangi soyut arayüze atandığını **tek bir yerde** tanımlar.

**Fayda:** Bağımlılık grafiği bir bakışta anlaşılır. Test yapılandırması `bootstrap.py`'nin test versiyonunu oluşturarak yapılır.

---

## 9. Yapılandırılmış Loglama Stratejisi

### 9.1 Üç Çıktı Kanalı

| Kanal | Amaç | Format |
|---|---|---|
| **Konsol** | Yerel geliştirme sırasında anlık izleme | JSON (veya okunabilir metin) |
| **Dosya** | ELK çöktüğünde yedek, denetim izi | JSON, 5 günlük rotasyon |
| **ELK (Logstash TCP)** | Merkezi loglama, izleme, uyarı | JSON (json_lines codec) |

### 9.2 Neden JSON Formatında Yapılandırılmış Loglama?

Geleneksel düz metin logları:
```
2024-01-15 14:23:45 WARNING Device DEV-001 anomaly detected score 5.7
```

Yapılandırılmış JSON logları:
```json
{
  "timestamp": "2024-01-15T14:23:45.123Z",
  "level": "WARNING",
  "message": "Anomaly detected",
  "device_id": "DEV-001",
  "anomaly_score": 5.7,
  "reading_id": "RD-ABC123",
  "sensor_type": "vibration",
  "app": "meridian-telemetry",
  "hostname": "worker-03"
}
```

**Fark:** JSON logları, Elasticsearch'te **alan bazında** aranabilir. "Son 1 saatte DEV-001 cihazından gelen tüm anomaliler" gibi sorgular milisaniyelerde yanıtlanır. Düz metin loglarında bu, regex ile satır satır taramak demektir.

### 9.3 5 Günlük Rotasyon Zorunluluğu

Üretim sunucularında disk alanı sınırlıdır. 5 günlük rotasyon şu anlama gelir:
- `meridian.log` → bugünkü log
- `meridian.log.1` → dünkü log
- ...5 günden eski loglar otomatik silinir.

Bu, `TimedRotatingFileHandler` ile sağlanır — sıfır manuel müdahale gerektirir.

---

## 10. Gelecek Öğrenme ve Ufuk Genişletme

Bu bölüm, projemizde kullanmadığımız ama bir Tech Lead'in mutlaka bilmesi gereken teknoloji ve desenleri kapsar. Bunlar, kariyerinizin gelecek adımları için bir yol haritasıdır.

### 10.1 Apache Kafka

**Ne:** Dağıtılmış olay akışı platformu.

**Neden öğrenmeli?** RabbitMQ iletişim senaryosu "bir mesaj, bir tüketici" iken, Kafka "bir olay, sınırsız tüketici" modelini destekler. Event sourcing, gerçek zamanlı analitik, ve olay tekrarı (replay) gerektiren sistemlerde Kafka standart haline gelmiştir. Özellikle mikro servis mimarilerinde servisler arası olay yayınlama için vazgeçilmezdir.

**Ne zaman gerekir?** Saniyede milyonlarca olay, birden fazla bağımsız tüketici grubu, ve olay geçmişine erişim ihtiyacı olduğunda.

### 10.2 Kubernetes (K8s)

**Ne:** Konteyner orkestrasyon platformu.

**Neden öğrenmeli?** Docker Compose yerel geliştirme içindir. Üretimde, yüzlerce konteyner'ın otomatik ölçeklenmesi, hata kurtarma, ve dağıtımı (rolling deployment) için Kubernetes endüstri standardıdır. Bizim health check endpoint'imiz (`/health`, `/health/live`) doğrudan K8s'in liveness ve readiness probe'larıyla entegre olacak şekilde tasarlanmıştır.

**Ne zaman gerekir?** Üretim ortamında yatay ölçekleme, sıfır kesintili dağıtım, ve altyapı otomasyonu gerektiğinde.

### 10.3 Event Sourcing ve CQRS

**Ne:**
- **Event Sourcing:** Durumu doğrudan saklamak yerine, duruma yol açan **olayları** saklamak.
- **CQRS:** Okuma (query) ve yazma (command) modellerini ayırmak.

**Neden öğrenmeli?** Bizim `DomainEvent`'lerimiz event sourcing'in temelini oluşturur. Şu anda olayları sadece yayınlıyoruz, ama saklarsak tam bir olay kaynağı (event store) elde ederiz. Bu, zaman yolculuğu (time travel debugging), denetim izi (audit trail), ve sistem durumunun herhangi bir andaki halini yeniden oluşturma imkanı verir.

**Ne zaman gerekir?** Finansal sistemler, uyumluluk gereksinimleri olan domainler, ve karmaşık iş süreçlerinin tam geçmişinin tutulması gerektiğinde.

### 10.4 gRPC

**Ne:** Google'ın yüksek performanslı, Protocol Buffers tabanlı RPC framework'ü.

**Neden öğrenmeli?** REST API'ler insan okunabilirdir ama JSON serileştirme/seri hale getirme maliyetlidir. gRPC, ikili (binary) Protocol Buffers kullanarak 5-10x daha hızlıdır ve şema zorlama (schema enforcement) sağlar. Mikro servisler arası iletişimde REST'e güçlü bir alternatiftir.

**Ne zaman gerekir?** Yüksek throughput mikro servis iletişimi, çoklu dil desteği, ve sıkı şema kontrolü gerektiğinde.

### 10.5 Apache Airflow

**Ne:** İş akışı orkestrasyon platformu (DAG tabanlı).

**Neden öğrenmeli?** Bizim pipeline'ımız sürekli akan bir veri akışıdır. Ama batch ETL işleri (günlük raporlar, geçmiş veri yeniden işleme, model eğitimi) gerektiğinde Airflow standart araçtır. Zamanlama, bağımlılık yönetimi, ve hata kurtarma mekanizmaları içerir.

**Ne zaman gerekir?** Batch veri işleme, ML model eğitimi pipeline'ları, ve karmaşık bağımlılık zincirleri olan ETL süreçlerinde.

### 10.6 Observability (İzlenebilirlik) — OpenTelemetry

**Ne:** Dağıtılmış izleme (tracing), metrikler, ve logları birleştiren standart.

**Neden öğrenmeli?** Bizim yapılandırılmış loglarımız iyi bir başlangıçtır, ama bir istek birden fazla servisi geçtiğinde (mikro servisler), istekleri servisler arası **izlemek** (distributed tracing) gerekir. OpenTelemetry, trace ID'leri otomatik olarak yayarak her isteğin tam yolculuğunu görselleştirmenizi sağlar.

**Ne zaman gerekir?** Mikro servis mimarisine geçişte, performans darboğazı tespitinde, ve üretim sorunlarının hızlı teşhisinde.

### 10.7 Terraform / Infrastructure as Code (IaC)

**Ne:** Altyapıyı kod olarak tanımlama ve yönetme.

**Neden öğrenmeli?** Docker Compose dosyamız yerel geliştirme içindir. Üretim altyapısını (sunucular, ağ, güvenlik grupları, veritabanı kümeleri) elle kurmak tekrar edilemez ve hata eğilimlidir. Terraform ile altyapınızı versiyon kontrolünde tutarsınız — bir Pull Request'le sunucu eklersiniz.

**Ne zaman gerekir?** Bulut altyapısı yönetiminde, tekrarlanabilir ortam oluşturmada, ve altyapı değişikliklerinin denetlenebilirliğinde.

### 10.8 Öğrenme Öncelik Sıralaması

Bir Tech Lead olarak önerilen öğrenme sırası:

| Öncelik | Teknoloji | Neden Önce? |
|---|---|---|
| 1 | Kubernetes | Üretim dağıtımının temel taşı |
| 2 | OpenTelemetry | Üretim sorunlarını çözmenin anahtarı |
| 3 | Apache Kafka | Olay güdümlü mimari için gerekli |
| 4 | Terraform | Altyapı otomasyonu |
| 5 | gRPC | Mikro servis iletişiminde performans |
| 6 | Event Sourcing / CQRS | Gelişmiş mimari desenler |
| 7 | Apache Airflow | Batch işleme gerektiğinde |

---

## Son Söz

Bu mimari, **değişime direnmek** için değil, **değişimi kucaklamak** için tasarlanmıştır. Clean Architecture'ın bağımlılık kuralı sayesinde:

- Veritabanını değiştirmek → 1 dosya.
- Mesaj kuyruğunu değiştirmek → 2 dosya.
- Önbellek teknolojisini değiştirmek → 1 dosya.
- Domain iş kuralları → **hiç değişmez**.

Bu, 40 yıllık tecrübenin en önemli dersidir: **İyi mimari, kodunuzun hangi kısımlarının değişeceğini tahmin etmek ve bu kısımları izole etmekle ilgilidir.** Teknolojiler gelir geçer, iş kuralları kalır. Mimarinizi buna göre tasarlayın.

---

*Bu belge, Meridian Telemetry Pipeline projesinin canlı bir belgesidir. Mimari kararlar değiştikçe güncellenmelidir. Son güncelleme: 2026-02.*
