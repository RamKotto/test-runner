# test-runner

Сервис исполнения тест-кейсов. Он получает тест-кейсы в формате Markdown (их создаёт `md-to-test`), строит план для каждого шага с помощью LLM через OpenRouter, выполняет UI-шаги в браузере (Playwright, Chromium) и API-шаги (RestAssured) только против разрешённых хостов, записывает результаты в PostgreSQL и отдаёт обогащённые Markdown-файлы с подробностями каждого шага.

Сервис построен на Spring Boot 3.2.5, использует Java 21 и Maven, пакет приложения — `application`, порт по умолчанию — `8083`.

## Возможности

- Загрузка набора тест-кейсов по ссылке на файл `*.md` или на каталог с файлом `_index.md`.
- Разбор кейсов: идентификатор, тип (`UI`, `API`, `E2E`, `NEGATIVE`, `SECURITY`), приоритет, ссылка на требование, предусловия, шаги, ожидаемый результат.
- Планирование каждого шага через LLM: текст шага переводится в одну или несколько операций исполнения (ответ модели — JSON).
- UI-проверки в браузере: переход, клик, ввод, проверка текста, снимок текста элемента и его сравнение с предыдущим снимком.
- API-проверки: вызовы `GET`, `POST`, `PUT`, `DELETE`, `PATCH`; проверки статуса, тела и заголовков; проверка JSON-значения по пути; снимки значений и их сравнение между шагами.
- Уточнения: если данных не хватает, прогон останавливается и возвращает вопросы; ответы на них продолжают прогон.
- Обогащённые результаты: для каждого шага сохраняются выполненное действие, найденные элементы, эквивалентный `curl`, тело запроса и фрагмент ответа. Для каждого кейса создаётся отдельный файл, для прогона — индексный файл.
- Запись прогонов в PostgreSQL (схема `qa`).
- Защита от обращений к хостам вне allowlist (см. раздел «Безопасность»).

## Архитектура и конвейер

| Компонент | Назначение |
|---|---|
| `controller.RunController` | `POST /api/run` и `POST /api/run/{taskId}/answers`: оркестрация прогона |
| `controller.ResultFileController` | `GET /api/results/{filename}`: выдача обогащённых файлов |
| `service.TestCaseFetcher`, `service.TestCaseParser` | скачивание и разбор тест-кейсов |
| `service.TestCaseRunnerService`, `service.HybridCaseRunner` | выполнение одного кейса шаг за шагом |
| `service.StepPlanner`, `service.OpenRouterClient` | построение плана шага через LLM |
| `service.UiExecutor` | браузер Playwright для UI-операций |
| `service.ApiExecutor` | HTTP-вызовы RestAssured и проверки ответов |
| `service.TargetUrlGuard` | allowlist хостов для исполнения |
| `service.SessionStore` | состояние прогонов, остановленных на уточнении (в памяти процесса) |
| `service.TestCaseMarkdownRenderer`, `service.EnrichedStorageService` | обогащённые файлы кейсов и индекс |
| `repository.RunRepository` | запись результатов в базу данных |

Конвейер одного запроса `POST /api/run`:

1. **Получение кейсов.** Если `sourceUrl` оканчивается на `.md`, скачивается этот файл. Если он является индексом (начинается с заголовка `# Тест-кейсы — индекс`), по ссылкам из него скачиваются все кейсы; иначе файл разбирается как один кейс. Если `sourceUrl` — каталог, скачивается `<каталог>/_index.md`. Если файл по имени отсутствует, сервис перебирает версии `имя_2.md`, `имя_3.md` и так далее (не более 50) и берёт последнюю подряд найденную. Копии скачанных файлов сохраняются в каталог `app.download.directory`.
2. **Разбор.** Кейс читается по разметке: заголовок `# TC-… — название`, поля `- **ID:**`, `- **Тип:**`, `- **Приоритет:**`, `- **Требование:**`, разделы `## Предусловия`, `## Шаги` (нумерованный список) и `## Ожидаемый результат`. Неизвестный тип получает значение `INVALID`. Если кейсы из индекса не удалось скачать или разобрать, весь прогон завершается ошибкой 502: неполный набор не выполняется.
3. **Исполнение по порядку.** Кейсы выполняются один за другим. Кейс с типом `INVALID` или без типа получает статус `SKIPPED`.
4. **Планирование шага.** `StepPlanner` отправляет в модель системный промпт (`app.prompts.plan-step`) и пользовательское сообщение: тип и текст кейса, все шаги кейса, ответы пользователя, уже выполненные шаги, снимки значений, DOM-снимок страницы (для UI), базовый URL API и последний ответ API (статус, заголовки, тело не длиннее 12000 символов). Запрос к OpenRouter выполняется с `response_format: json_object`, `temperature: 0`, `top_p: 1` и `seed: 42`. Ответ модели — объект плана или массив планов. Операции проверяются на соответствие типу кейса.
5. **Выполнение операций.** UI-операции выполняет `UiExecutor`, API-операции — `ApiExecutor`. Операции проверки ответа (`assertStatus`, `assertBody` и другие) работают с последним ответом API внутри кейса. Если API-вызов в кейсе был пропущен, последующие проверки этого кейса также пропускаются (каскадный пропуск).
6. **Итог кейса.** `FAILED`, если есть ошибки шагов (ошибка проверки, ошибка планирования, запрещённая операция); `NEEDS_CLARIFICATION`, если нужны данные от пользователя; `SKIPPED`, если все шаги пропущены по ответам пользователя; иначе `PASSED`. `FAILED` и `SKIPPED` не прерывают прогон.
7. **Обогащённый файл кейса.** Результаты шагов записываются в Markdown и сохраняются в `app.storage.directory` под исходным именем кейса.
8. **Индекс и БД.** Формируется индекс `_enriched_index_<taskId>.md`, прогон записывается в PostgreSQL, клиент получает ответ `RunResponse`.

Прогон, остановленный на уточнении, сохраняется в памяти процесса под `taskId`. Ответ на уточнение через `POST /api/run/{taskId}/answers` перезапускает этот кейс с первого шага, сохраняя результаты кейсов, которые завершились до него.

### Причины уточнения

Уточнение возникает, если:

- планировщик вернул `needClarification` (например, не хватает значения, которого нет ни в шаге, ни в ответах, ни в DOM-снимке);
- для UI-кейса не задан стартовый URL (`startUrl` в запросе и ответы с темой `start_url`);
- локатор UI-элемента не найден или совпадает с несколькими элементами для операций `click` и `fill`;
- для API-вызова не задан или недоступен базовый URL (ошибки вида `connection refused`, `unknown host`, таймаут; тема `api_base_url`).

Ответ пользователя с той же темой передаётся планировщику. Если планировщик снова запрашивает уточнение по этой теме, шаг пропускается.

### UI-исполнение

- Chromium запускается один раз при старте приложения (в конструкторе `UiExecutor`), поэтому браузер должен быть установлен до запуска сервиса.
- Для каждого кейса создаётся отдельный контекст браузера, который закрывается по завершении кейса. Стартовый URL открывается при первом UI-шаге.
- Поддерживаемые операции: `navigate`, `click`, `fill`, `assertText`, `waitForText`, `assertPageText`, `snapshotUiText`, `assertUiTextEqualsSnapshot`. Ожидание `waitForText` ограничено 10 секундами.
- Перед планированием UI-шага собирается текстовый DOM-снимок интерактивных элементов страницы (атрибуты `id`, `data-testid`, `role`, `aria-label`, `name`, `type`, `href`, `placeholder`, `value` и другие).

### API-исполнение

- HTTP-вызовы выполняет RestAssured. Поддерживаются методы `GET`, `POST`, `PUT`, `DELETE`, `PATCH`. Если тело задано, а заголовка `Content-Type` нет, добавляется `application/json`.
- Относительный путь склеивается с `apiBaseUrl`; абсолютный URL из плана используется как есть, но его хост проверяется по allowlist.
- Редиректы не выполняются.
- Повторные попытки делаются только для `GET`: до 3 раз при ошибке сети или ответе 5xx, с паузой 500 мс × номер попытки.
- Поддерживаемые проверки: `assertStatus` (точный код, например `200`, или класс, например `4xx`), `assertBody` и `assertBodyAbsent` (подстрока тела), `assertHeader` и `assertHeaderAbsent`, `parseJson`, `snapshotApiValue` и `assertApiValueEqualsSnapshot` (значение по JSON Pointer или пути вида `$.a.b`).
- Если ответ API не совпал с ожидаемым статусом `expectedStatus` плана, шаг получает ошибку.

## Требования

- Java 21 (JDK).
- Maven 3.8 или новее (в Docker-сборке используется Maven 3.9).
- Chromium для Playwright. Устанавливается командой `mvn exec:java` (см. быстрый старт) или при сборке Docker-образа.
- Ключ OpenRouter (`OPENROUTER_API_KEY`) и доступ в интернет к `openrouter.ai`.
- PostgreSQL со схемой `qa` из `db/init/01-schema.sql` и ролью приложения с правами `SELECT`, `INSERT`, `UPDATE`, `DELETE` на таблицы этой схемы (см. `db/init/02-roles.sh`). Подключение к БД обязательно: запись прогона выполняется при каждом запросе, отключить её нельзя.

## Быстрый старт

Перед запуском создайте базу `qa`, примените `db/init/01-schema.sql` и создайте роль `qa_app` (либо используйте Docker, см. раздел «Docker»).

### PowerShell

```powershell
cd D:\AI-programs\ai-qa-helper\test-runner

$env:OPENROUTER_API_KEY = "sk-or-v1-ваш-ключ"
$env:DB_URL = "jdbc:postgresql://localhost:5432/qa"
$env:DB_USER = "qa_app"
$env:DB_PASSWORD = "пароль-qa_app"
$env:APP_TARGET_ALLOWED_HOSTS = "localhost"
$env:APP_DOWNLOAD_ALLOWED_HOSTS = "localhost"

mvn exec:java
mvn spring-boot:run
```

### bash

```bash
cd test-runner

export OPENROUTER_API_KEY="sk-or-v1-ваш-ключ"
export DB_URL="jdbc:postgresql://localhost:5432/qa"
export DB_USER="qa_app"
export DB_PASSWORD="пароль-qa_app"
export APP_TARGET_ALLOWED_HOSTS="localhost"
export APP_DOWNLOAD_ALLOWED_HOSTS="localhost"

mvn exec:java
mvn spring-boot:run
```

`mvn exec:java` запускает `com.microsoft.playwright.CLI install chromium` (плагин настроен в `pom.xml`) и нужен один раз. `mvn spring-boot:run` запускает сервис на `http://localhost:8083`. Проверка готовности: `curl http://localhost:8083/actuator/health`.

Для сборки исполняемого JAR выполните `mvn -B package -DskipTests` и запускайте `java -jar target/test-runner-1.0.0.jar` с теми же переменными окружения.

## Конфигурация

Значения задаются в `src/main/resources/application.yml`. Переменные окружения, перечисленные в колонке «Переменная», переопределяют значения по умолчанию. Любое свойство Spring Boot можно переопределить переменной окружения по стандартным правилам.

| Ключ | Переменная окружения | Значение по умолчанию | Описание |
|---|---|---|---|
| `server.port` | — | `8083` | HTTP-порт сервиса |
| `spring.application.name` | — | `test-runner` | Имя приложения |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/qa` | JDBC-адрес PostgreSQL |
| `spring.datasource.username` | `DB_USER` | `qa_app` | Пользователь БД |
| `spring.datasource.password` | `DB_PASSWORD` | пусто | Пароль пользователя БД |
| `app.storage.directory` | — | `${user.dir}/data/enriched` | Каталог обогащённых файлов и индексов |
| `app.storage.base-url` | `APP_STORAGE_BASE_URL` | `http://localhost:8083` | Базовый URL для `downloadUrl` в ответах |
| `app.target.allowed-hosts` | `APP_TARGET_ALLOWED_HOSTS` | `localhost` | Хосты, разрешённые для исполнения (через запятую) |
| `app.download.directory` | — | `${user.dir}/data/downloaded` | Каталог копий скачанных кейсов и HTML-дампов |
| `app.download.allowed-hosts` | `APP_DOWNLOAD_ALLOWED_HOSTS` | `localhost` | Хосты, с которых разрешено скачивать кейсы (через запятую) |
| `app.download.connect-timeout-seconds` | — | `10` | Таймаут соединения при скачивании, с |
| `app.download.read-timeout-seconds` | — | `300` | Таймаут чтения при скачивании, с |
| `app.playwright.headless` | — | `true` | Запуск Chromium без окна |
| `app.playwright.navigation-timeout-ms` | — | `30000` | Таймаут навигации, мс |
| `app.playwright.action-timeout-ms` | — | `10000` | Таймаут действий и поиска элементов, мс |
| `app.sessions.ttl-minutes` | — | `180` | Время жизни сессии уточнения, минут |
| `app.sessions.max-count` | — | `500` | Максимум одновременно хранимых сессий уточнений |
| `app.enrichment-body-limit` | — | `0` | Ограничение длины `responseSample` в обогащении, символов (`0` — без ограничения) |
| `app.debug.dump-html` | `APP_DEBUG_DUMP_HTML` | `false` | Сохранять HTML страницы после каждого UI-шага в каталог скачивания |
| `app.planner.max-input-chars` | — | `300000` | Лимит размера входа модели; в текущей версии не применяется |
| `app.prompts.plan-step` | — | текст в `application.yml` | Системный промпт планировщика; обязателен |
| `openrouter.base-url` | — | `https://openrouter.ai/api/v1` | Базовый URL OpenRouter API |
| `openrouter.api-key` | `OPENROUTER_API_KEY` | нет, обязателен | Ключ OpenRouter |
| `openrouter.model` | — | `anthropic/claude-haiku-5.5` | Модель (в классе по умолчанию `openrouter/free`) |
| `openrouter.max-output-tokens` | — | `64000` | Лимит токенов ответа модели (в классе по умолчанию `4000`) |
| `openrouter.timeout-seconds` | — | `2400` | Таймаут чтения ответа модели, с (в классе по умолчанию `120`) |
| `openrouter.connect-timeout-seconds` | — | `30` | Таймаут соединения с OpenRouter, с |
| `openrouter.referer` | — | `http://localhost:8083` | Заголовок `HTTP-Referer` |
| `openrouter.title` | — | `test-runner` | Заголовок `X-Title` |
| `management.endpoints.web.exposure.include` | — | `health,info` | Открытые endpoints Actuator |
| `logging.level.application` | — | `DEBUG` | Уровень логирования пакета `application` |

Переменные окружения, которые читает модуль: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OPENROUTER_API_KEY`, `APP_STORAGE_BASE_URL`, `APP_TARGET_ALLOWED_HOSTS`, `APP_DOWNLOAD_ALLOWED_HOSTS`, `APP_DEBUG_DUMP_HTML`.

## API

Все endpoints без аутентификации. Ошибки возвращаются в формате `ErrorResponse`:

```json
{"timestamp":"2026-11-04T10:00:00Z","status":400,"message":"sourceUrl обязателен"}
```

### POST /api/run

Запускает прогон. Запрос выполняется синхронно: ответ придёт после исполнения всех кейсов или остановки на уточнении.

Тело запроса (`application/json`):

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `sourceUrl` | строка | да | URL файла `*.md`, индекса или каталога с `_index.md` |
| `startUrl` | строка | нет | Стартовый URL веб-приложения для UI-шагов |
| `apiBaseUrl` | строка | нет | Базовый URL API для относительных путей |

Ответ `200` — `RunResponse`:

| Поле | Тип | Описание |
|---|---|---|
| `taskId` | строка (UUID) | Идентификатор прогона |
| `status` | строка | `DONE` или `NEEDS_CLARIFICATION` |
| `outcomes` | массив | Итоги кейсов: `testCaseId`, `filename`, `status` (`PASSED`, `FAILED`, `SKIPPED`, `NEEDS_CLARIFICATION`), `failureMessage`, `enrichments` |
| `clarifications` | массив | Уточнения: `testCaseId`, `stepIndex` (с 0), `question`, `reason`, `category` (`DATA_MISSING` или `LOCATOR_NOT_FOUND`), `topicKey` |
| `enrichedIndexUrl` | строка | URL индекса обогащённых результатов прогона |

Элементы `enrichments` — объекты `stepIndex`, `action`, `detail`, `elements`, `requestCurl`, `requestBody`, `responseSample`, `subEnrichments`.

Коды ошибок:

- `400` — `sourceUrl` пуст; прямая ссылка на `.md` указывает на недопустимый или запрещённый хост либо на некорректный URL;
- `502` — не удалось скачать или разобрать кейсы (в том числе для каталога и `_index.md`, где запрещённый хост тоже даёт 502); ошибка планирования LLM;
- `500` — ошибка записи файлов или БД либо непредвиденная ошибка.

Пример ответа с уточнением:

```json
{
  "taskId": "3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c",
  "status": "NEEDS_CLARIFICATION",
  "outcomes": [
    {
      "testCaseId": "TC-UI-001",
      "filename": "TC-UI-001.md",
      "status": "NEEDS_CLARIFICATION",
      "failureMessage": "Прервано на шаге 1",
      "enrichments": []
    }
  ],
  "clarifications": [
    {
      "testCaseId": "TC-UI-001",
      "stepIndex": 0,
      "question": "[start_url] Шаг 1 («Открыть страницу входа»): укажите стартовый URL (http(s)://...) для UI-кейса",
      "reason": "startUrl не передан в RunRequest и не дан в ответах",
      "category": "DATA_MISSING",
      "topicKey": "start_url"
    }
  ],
  "enrichedIndexUrl": "http://localhost:8083/api/results/_enriched_index_3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c.md"
}
```

curl (bash):

```bash
curl -X POST http://localhost:8083/api/run \
  -H "Content-Type: application/json" \
  -d '{"sourceUrl":"http://localhost:8082/api/files/_index.md","startUrl":"http://localhost:3000","apiBaseUrl":"http://localhost:3000"}'
```

PowerShell:

```powershell
$body = @{ sourceUrl = 'http://localhost:8082/api/files/_index.md'; startUrl = 'http://localhost:3000'; apiBaseUrl = 'http://localhost:3000' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8083/api/run -ContentType 'application/json' -Body $body
```

### POST /api/run/{taskId}/answers

Передаёт ответы на уточнения и продолжает прогон с кейса, на котором он остановился. Кейсы, завершённые до этого, не перезапускаются. Кейс с уточнением выполняется заново с первого шага, поэтому его `POST`, `PUT` и `DELETE` вызовы повторятся.

Параметр `taskId` — идентификатор из ответа `POST /api/run`.

Тело запроса:

| Поле | Тип | Описание |
|---|---|---|
| `answers` | массив | Ответы; элемент содержит `question` (текст вопроса), `answer` (текст ответа) и `topicKey` (тема из `clarifications`) |

Ответ `200` — `RunResponse` с полным набором итогов прогона (включая кейсы до остановки). Статус `NEEDS_CLARIFICATION` означает, что потребовалось ещё одно уточнение, и сессия сохраняется под тем же `taskId`. Статус `DONE` означает, что сессия удалена.

Коды ошибок:

- `400` — сессия с таким `taskId` не найдена или истекла (`Сессия не найдена или истекла`);
- `500` — ошибка записи в хранилище или БД.

curl (bash):

```bash
curl -X POST http://localhost:8083/api/run/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/answers \
  -H "Content-Type: application/json" \
  -d '{"answers":[{"question":"укажите стартовый URL","answer":"http://localhost:3000","topicKey":"start_url"}]}'
```

PowerShell:

```powershell
$body = @{ answers = @(@{ question = 'укажите стартовый URL'; answer = 'http://localhost:3000'; topicKey = 'start_url' }) } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri http://localhost:8083/api/run/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/answers -ContentType 'application/json' -Body $body
```

### GET /api/results/{filename}

Отдаёт обогащённый файл кейса или индекс прогона как вложение (`Content-Type: text/markdown; charset=UTF-8`, `Content-Disposition: attachment`).

Имя файла должно состоять из латинских букв, цифр, `_` и `-` и иметь расширение `.md`. Файлы: `<имя кейса>.md` и `_enriched_index_<taskId>.md`.

Коды ошибок:

- `400` — имя файла не соответствует шаблону или файл не найден (сервис возвращает `400`, а не `404`).

curl (bash):

```bash
curl -OJ http://localhost:8083/api/results/_enriched_index_3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c.md
```

PowerShell:

```powershell
Invoke-WebRequest -Uri http://localhost:8083/api/results/_enriched_index_3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c.md -OutFile _enriched_index.md
```

### GET /actuator/health и GET /actuator/info

Служебные endpoints Spring Boot Actuator. Открыты только `health` и `info`.

## Безопасность

Сервис обращается к сети по адресам, которые задают пользователь и LLM. Поэтому обращения ограничены allowlist-ами.

- **Исполнение (`app.target.allowed-hosts`, `APP_TARGET_ALLOWED_HOSTS`).** Проверяется `startUrl` и все переходы UI-шагов (`navigate`): хост должен входить в список. Браузер перехватывает все навигационные запросы, включая редиректы, и отменяет переходы на запрещённые хосты. Запросы ресурсов страницы (скрипты, изображения, `fetch` из JavaScript) этим правилом не блокируются. Для API проверяется итоговый URL каждого вызова, включая абсолютные URL из плана LLM; редиректы не выполняются.
- **Нарушение allowlist.** Шаг получает статус `FAILED` (исключение `TargetNotAllowedException`), а не уточнение: ответом пользователя запрет хоста не снимается.
- **Скачивание (`app.download.allowed-hosts`, `APP_DOWNLOAD_ALLOWED_HOSTS`).** Разрешены только схемы `http` и `https` и хосты из списка. Проверка применяется к исходному URL каждого файла. Редиректы при скачивании следуют стандартной обработке HTTP-клиента, поэтому редирект на другой хост не блокируется.
- **Сравнение хостов.** Сравнение точное, без учёта регистра; порт и путь не проверяются. Для URL вида `http://localhost@evil.com/` учитывается реальный хост (`evil.com`). Проверяется только имя хоста: IP-адреса и результаты DNS отдельно не сверяются.
- **Значения по умолчанию.** Оба списка по умолчанию содержат только `localhost`. Для Docker `docker-compose.yml` добавляет `host.docker.internal` в список исполнения. Пустой список скачивания трактуется как `localhost`.
- **TLS.** Для API-вызовов отключена проверка сертификатов (`relaxedHTTPSValidation`). Используйте сервис только против стендов, которым вы доверяете.
- **Имена файлов результатов.** Имя проходит проверку по шаблону, а путь нормализуется и должен оставаться внутри каталога хранилища. Это защищает от обхода каталогов.
- **Ответ LLM.** Модель может вернуть только операции из фиксированного набора; неизвестная операция даёт ошибку шага. Адреса, которые модель укажет вне allowlist, блокируются кодом, а не промптом.
- **Доступ к endpoints.** Аутентификация отсутствует. В `docker-compose.yml` порт публикуется только на `127.0.0.1`; при другом развёртывании ограничивайте сетевой доступ самостоятельно.
- **Секреты.** Ключ OpenRouter и пароль БД задаются через переменные окружения. Сохраняемые обогащения содержат тела запросов и ответов API, а файлы записываются без шифрования.

## Данные

Сервис пишет результаты в схему `qa` PostgreSQL (схема и таблицы создаются скриптом `db/init/01-schema.sql`). Чтения из БД сервис не выполняет. Каждый прогон записывается полным снимком в одной транзакции: строка прогона создаётся или обновляется (`INSERT ... ON CONFLICT (id) DO UPDATE`), затем удаляются и заново вставляются строки кейсов и уточнений этого прогона.

| Таблица | Что пишет сервис |
|---|---|
| `qa.execution_runs` | Один прогон: `id` (taskId), `status` (`DONE` или `NEEDS_CLARIFICATION`), `source_url`, `start_url`, `api_base_url`, `created_at`, `updated_at` |
| `qa.case_executions` | По строке на кейс прогона: `run_id`, `test_case_id`, `filename`, `status`, `failure_message`, `enrichments` (JSON-массив обогащений шагов) |
| `qa.execution_clarifications` | По строке на уточнение: `run_id`, `test_case_id`, `step_index`, `question`, `reason`, `category`, `topic_key` |

Таблицы `qa.generation_runs`, `qa.test_cases` и `qa.clarifications` относятся к генерации (`md-to-test`) и сервисом не используются.

Файлы на диске:

- `app.storage.directory` (`./data/enriched` или `${user.dir}/data/enriched`) — обогащённые файлы кейсов и индексы прогонов;
- `app.download.directory` (`./data/downloaded` или `${user.dir}/data/downloaded`) — копии скачанных кейсов и, при `app.debug.dump-html=true`, HTML-дампы шагов вида `<идентификатор>.step<N>.html`.

Файл кейса называется по исходному имени кейса, поэтому его перезаписывают последующие прогоны. Индексный файл уникален для каждого прогона.

## Docker

Сервис собирается и запускается вместе с остальными сервисами через Docker Compose. Инструкция: [DOCKER.md](../DOCKER.md).

Dockerfile (`Dockerfile`) выполняет многоэтапную сборку: на первом этапе собирается JAR и копируются зависимости, на втором — образ `eclipse-temurin:21-jre-jammy`, в который при сборке устанавливается Chromium для Playwright. Приложение запускается от пользователя `app`, сервис слушает порт `8083`, данные хранятся в `/app/data`. Параметр `-XX:MaxRAMPercentage=75` ограничивает память JVM.

В `docker-compose.yml` для `test-runner` заданы `DB_URL=jdbc:postgresql://db:5432/qa`, `DB_USER=qa_app`, `DB_PASSWORD` из `QA_APP_PASSWORD`, `OPENROUTER_API_KEY`, `APP_STORAGE_BASE_URL=http://test-runner:8083`, `APP_DOWNLOAD_ALLOWED_HOSTS=md-to-test,localhost`, `APP_TARGET_ALLOWED_HOSTS=localhost,host.docker.internal`, а также `shm_size: 1gb` (Chromium не работает с 64 МБ `/dev/shm`) и `host.docker.internal:host-gateway`.

## Разработка

Сборка и тесты (из каталога `test-runner`):

```bash
mvn -B -q test-compile   # компиляция main и test
mvn -B test              # тесты (JUnit 5, AssertJ)
mvn -B package -DskipTests
mvn spring-boot:run      # запуск с локальными переменными окружения
```

Тесты находятся в `src/test/java`; сейчас это `application.service.TargetUrlGuardTest`.

## Ограничения

- **Сессии уточнений в памяти.** Состояние прогона, остановленного на уточнении, хранится в `SessionStore` и теряется при перезапуске. Сессия живёт `app.sessions.ttl-minutes` минут после последнего изменения; при превышении `app.sessions.max-count` удаляется самая старая. Результаты прогонов при этом сохраняются в БД.
- **Повтор кейса с уточнением.** Кейс, на котором остановились, выполняется заново целиком, поэтому его побочные API-вызовы (`POST`, `PUT`, `DELETE`) повторятся. Это важно для стендов с необратимыми действиями.
- **Синхронный запрос.** `POST /api/run` удерживает соединение до конца прогона. Таймаут ответа модели по умолчанию в `application.yml` — 2400 секунд, поэтому прогон может длиться долго.
- **Лимит входа модели не применяется.** Параметр `app.planner.max-input-chars` задан, но планировщик не проверяет длину промпта и не возвращает уточнение `context_overflow`.
- **Перезапись обогащений.** Файлы кейсов перезаписываются при каждом прогоне, в том числе при повторном запросе с ответами на уточнения.
- **Обязательный браузер.** Chromium запускается при старте; без установленного браузера сервис не стартует.
- **Обязательная БД.** Запись в БД выполняется при каждом запросе. При её недоступности ответ — `500`, хотя файлы кейсов и индекса к этому моменту уже записаны.
- **Ошибки Spring MVC.** Ошибки разбора JSON, неизвестный путь и неподдерживаемый метод возвращаются с кодом `500`, так как обработчик перехватывает `Exception`.
- **Скачивание и редиректы.** Редиректы при скачивании кейсов не ограничиваются allowlist-ом (см. «Безопасность»).
- **Фиксированный таймаут `waitForText`.** Ожидание текста на странице составляет 10 секунд и не настраивается.
- **Недетерминированность LLM.** Параметры `temperature: 0` и `seed: 42` уменьшают разброс ответов, но не гарантируют одинаковый план для одного и того же шага.
