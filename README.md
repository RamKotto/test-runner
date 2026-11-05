# test-runner

Сервис исполнения тест-кейсов. Он получает тест-кейсы в формате Markdown (их создаёт `md-to-test`) либо по ссылке, либо по `sourceRunId` (кейсы читаются из БД), строит план для каждого шага с помощью LLM через OpenRouter, выполняет UI-шаги в браузере (Playwright, Chromium) и API-шаги (RestAssured) только против разрешённых хостов, записывает результаты в PostgreSQL и отдаёт обогащённые Markdown-файлы с подробностями каждого шага. Файлы и сессии уточнений хранятся в БД; на диск сервис ничего не пишет.

Сервис построен на Spring Boot 3.2.5, использует Java 21 и Maven, пакет приложения — `application`, порт по умолчанию — `8083`.

## Возможности

- Загрузка набора тест-кейсов по ссылке на файл `*.md` или на каталог с файлом `_index.md`, либо по `sourceRunId` (кейсы и индекс генерации из `md-to-test` читаются из БД).
- Разбор кейсов: идентификатор, тип (`UI`, `API`, `E2E`, `NEGATIVE`, `SECURITY`), приоритет, ссылка на требование, предусловия, шаги, ожидаемый результат.
- Планирование каждого шага через LLM: текст шага переводится в одну или несколько операций исполнения (ответ модели — JSON).
- UI-проверки в браузере: переход, клик, ввод, проверка текста, снимок текста элемента и его сравнение с предыдущим снимком.
- API-проверки: вызовы `GET`, `HEAD`, `OPTIONS`, `POST`, `PUT`, `DELETE`, `PATCH`; проверки статуса, тела и заголовков; проверка JSON-значения по пути; снимки значений и их сравнение между шагами.
- Уточнения: если данных не хватает, прогон останавливается и возвращает вопросы; ответы на них продолжают прогон. Сессия уточнения хранится в БД и переживает перезапуск сервиса.
- Обогащённые результаты: для каждого шага сохраняются выполненное действие, найденные элементы, эквивалентный `curl`, тело запроса и фрагмент ответа. Для каждого кейса создаётся отдельный файл, для прогона — индексный файл. Файлы хранятся в таблице `qa.artifacts` и отдаются по UUID или ZIP-архивом прогона.
- Запись прогонов в PostgreSQL (схема `qa`).
- Защита от обращений к хостам вне allowlist (см. раздел «Безопасность»).

## Архитектура и конвейер

| Компонент | Назначение |
|---|---|
| `controller.RunController` | `POST /api/run`, `POST /api/run/{taskId}/answers`, `GET /api/run/{taskId}`, `PUT /api/run/{taskId}/draft`: оркестрация прогона и сессии уточнений |
| `controller.FileController` | `GET /api/files/{id}`: выдача артефакта по UUID; `GET /api/files/runs/{taskId}/archive`: ZIP-архив обогащённых кейсов прогона из БД |
| `service.TestCaseFetcher`, `service.TestCaseParser` | скачивание и разбор тест-кейсов по ссылке |
| `service.TestCaseRunnerService`, `service.HybridCaseRunner` | выполнение одного кейса шаг за шагом |
| `service.StepPlanner`, `service.OpenRouterClient` | построение плана шага через LLM |
| `service.UiExecutor` | браузер Playwright для UI-операций |
| `service.ApiExecutor` | HTTP-вызовы RestAssured и проверки ответов |
| `service.TargetUrlGuard` | allowlist хостов для исполнения |
| `service.RunSessionService` | сессии прогонов в БД: чтение состояния, дедупликация ответов, фиксация ответа одной транзакцией с проверкой версии |
| `repository.ClarificationSessionRepository` | `qa.clarification_sessions` и `qa.clarification_answers` (сессии и история ответов) |
| `repository.ArtifactRepository` | `qa.artifacts`: обогащённые кейсы, индексы и исходные файлы прогонов |
| `service.RetentionService` | очистка записей с истёкшим сроком (`expires_at`) при старте и каждые 6 часов |
| `service.TestCaseMarkdownRenderer` | Markdown обогащённого кейса и индекса прогона |
| `service.EnrichedStorageService` | ссылки на артефакты (`/api/files/{id}`, архив прогона) и сборка ZIP из БД |
| `repository.RunRepository` | запись результатов в базу данных |

Конвейер одного запроса `POST /api/run`:

1. **Получение кейсов.** Источник задаётся ровно одним из полей запроса:
   - `sourceRunId` (taskId генерации в `md-to-test`): индекс читается из `qa.artifacts` (`kind = 'index'`, `run_id = sourceRunId`), кейсы — по именам из индекса (`kind = 'test_case'`, тот же `run_id`). HTTP-запросов к `md-to-test` нет. Если индекса нет или он пуст, либо какого-то файла кейса нет или он не разобран, прогон не запускается (502).
   - `sourceUrl` (ручной адрес): если `sourceUrl` оканчивается на `.md`, скачивается этот файл. Если он является индексом (начинается с заголовка `# Тест-кейсы — индекс`), по ссылкам из него скачиваются все кейсы; иначе файл разбирается как один кейс. Если `sourceUrl` — каталог, скачивается `<каталог>/_index.md`. Если файл по имени отсутствует, сервис перебирает версии `имя_2.md`, `имя_3.md` и так далее (не более 50) и берёт последнюю подряд найденную. Скачанные файлы сохраняются артефактами с `run_id` нового прогона: индекс — `kind = 'index'`, кейсы — `kind = 'test_case'`. Диск не используется.
2. **Разбор.** Кейс читается по разметке: заголовок `# TC-… — название` (его положение относительно строк `- **ID:**` не важно; заголовок задаёт название, а поле `**ID:**` перекрывает id), поля `- **ID:**`, `- **Тип:**`, `- **Приоритет:**`, `- **Требование:**`, разделы `## Предусловия`, `## Шаги` (нумерованный список) и `## Ожидаемый результат`. Неизвестный или отсутствующий тип получает значение `INVALID`. Если кейсы из индекса не удалось скачать или разобрать, весь прогон завершается ошибкой 502: неполный набор не выполняется. Кейс без заголовка `# TC-…` не разбирается и даёт понятную ошибку разбора (`IllegalArgumentException`, код ответа см. в «API»).
3. **Исполнение по порядку.** Кейсы выполняются один за другим. Кейс с типом `INVALID` или без типа получает статус `SKIPPED`.
4. **Планирование шага.** `StepPlanner` отправляет в модель системный промпт (`app.prompts.plan-step`) и пользовательское сообщение: тип и текст кейса, все шаги кейса, ответы пользователя, уже выполненные шаги, снимки значений, DOM-снимок страницы (для UI), базовый URL API и последний ответ API (статус, заголовки, тело не длиннее 12000 символов). Запрос к OpenRouter выполняется с `response_format: json_object`, `temperature: 0`, `top_p: 1` и `seed: 42`. Ответ модели — объект плана или массив планов. Операции проверяются на соответствие типу кейса.
5. **Выполнение операций.** UI-операции выполняет `UiExecutor`, API-операции — `ApiExecutor`. Операции проверки ответа (`assertStatus`, `assertBody` и другие) работают с последним ответом API внутри кейса. Если API-вызов в кейсе был пропущен, последующие проверки этого кейса также пропускаются (каскадный пропуск).
6. **Итог кейса.** `FAILED`, если есть ошибки шагов (ошибка проверки, ошибка планирования, запрещённая операция); `NEEDS_CLARIFICATION`, если нужны данные от пользователя; `SKIPPED`, если все записи шагов — пропуски (`skip`); иначе `PASSED`. `FAILED` и `SKIPPED` не прерывают прогон.
7. **Обогащённый файл кейса.** Результаты шагов записываются в Markdown и сохраняются артефактом `kind = 'enriched_case'` с `run_id` прогона; в `filename` указывается исходное имя кейса. На диск ничего не пишется.
8. **Индекс и БД.** Формируется индекс `_enriched_index_<taskId>.md` (артефакт `kind = 'enriched_index'`). Файлы прогона, состояние сессии уточнений, история ответов и запись прогона в `qa.execution_runs` фиксируются одной транзакцией; клиент получает ответ `RunResponse`.

Прогон, остановленный на уточнении, сохраняется в БД (`qa.clarification_sessions`) под `taskId` и переживает перезапуск сервиса. Ответ на уточнение через `POST /api/run/{taskId}/answers` перезапускает этот кейс с первого шага, сохраняя результаты кейсов, которые завершились до него.

### Причины уточнения

Уточнение возникает, если:

- планировщик вернул `needClarification` (например, не хватает значения, которого нет ни в шаге, ни в ответах, ни в DOM-снимке);
- для UI-кейса не задан стартовый URL (`startUrl` в запросе и ответы с темой `start_url`);
- локатор UI-элемента не находит ни одного элемента (любая операция с элементом) либо находит несколько там, где нужен один (`click`, `fill`, `snapshotUiText`, `assertUiTextEqualsSnapshot`);
- для API-вызова не задан или недоступен базовый URL (ошибки вида `connection refused`, `unknown host`, таймаут; тема `api_base_url`).

Ответ пользователя с той же темой передаётся планировщику. Если планировщик снова запрашивает уточнение по этой теме, шаг пропускается.

Пропуски без уточнения (`SKIP`, в отчёте `⏭ SKIP (каскад)`): API-проверки ответа (`assertStatus`, `assertBody`, `assertBodyAbsent`, `assertHeader`, `assertHeaderAbsent`, `parseJson`, сравнение со снимком) пропускаются каскадно, если предыдущий `apiCall` не прошёл (не совпал статус или сетевая ошибка). Причина: «нет данных от предыдущего apiCall». Такие шаги не превращаются в уточнения. Кейс получает `SKIPPED` только если все его записи — пропуски.

### UI-исполнение

- Chromium запускается один раз при старте приложения (в конструкторе `UiExecutor`), поэтому браузер должен быть установлен до запуска сервиса.
- Для каждого кейса создаётся отдельный контекст браузера, который закрывается по завершении кейса. Стартовый URL открывается при первом UI-шаге.
- Поддерживаемые операции: `navigate`, `click`, `fill`, `assertText`, `waitForText`, `assertPageText`, `snapshotUiText`, `assertUiTextEqualsSnapshot`. Ожидание `waitForText` ограничено 10 секундами.
- Перед планированием UI-шага собирается текстовый DOM-снимок интерактивных элементов страницы (атрибуты `id`, `data-testid`, `role`, `aria-label`, `name`, `type`, `href`, `placeholder`, `value` и другие).

### API-исполнение

- HTTP-вызовы выполняет RestAssured. Поддерживаются методы `GET`, `HEAD`, `OPTIONS`, `POST`, `PUT`, `DELETE`, `PATCH`; другой метод даёт ошибку шага без отправки запроса. Если тело задано, а заголовка `Content-Type` нет, добавляется `application/json`.
- Формат тела: если `bodyJson` или другое поле плана пришло из модели объектом или массивом, оно сериализуется в JSON, поэтому тело запроса остаётся валидным. Строки и числа передаются как есть. `requestHeaders` в виде объекта превращаются в строки `Имя: значение`.
- Относительный путь склеивается с `apiBaseUrl`; абсолютный URL из плана используется как есть, но его хост проверяется по allowlist.
- Редиректы не выполняются.
- Повторные попытки делаются только для `GET`: до 3 раз при ошибке сети или ответе 5xx, с паузой 500 мс × номер попытки. `HEAD`, `OPTIONS`, `POST`, `PUT`, `DELETE` и `PATCH` выполняются один раз.
- Поддерживаемые проверки: `assertStatus` (точный код, например `200`, или класс, например `4xx`), `assertBody` и `assertBodyAbsent` (подстрока тела), `assertHeader` и `assertHeaderAbsent`, `parseJson`, `snapshotApiValue` и `assertApiValueEqualsSnapshot` (значение по JSON Pointer или пути вида `$.a.b`).
- Путь `/`, `$` или пустая строка означает весь ответ: снимок содержит всё тело ответа в компактном JSON. Путь `/` не трактуется как ключ с пустым именем.
- Если ответ API не совпал с ожидаемым статусом `expectedStatus` плана, шаг получает ошибку. Неуспешный `apiCall` сбрасывает состояние последнего ответа, поэтому следующие проверки ответа не видят устаревшее тело.
- Автоматический снимок: если в будущих шагах есть слова сравнения (например, «сравни», «идентичны», «совпадают», «не изменился», «равны», «оба ответа»), после успешного `apiCall` делается снимок с ключом `auto_stepN` (N — номер шага). Путь — первый идентификатор вида `a.b` из будущих шагов, который находит значение в ответе. Если такого пути нет, снимок не делается, а шаг сравнения получит ошибку «нет снимка».

## Требования

- Java 21 (JDK).
- Maven 3.8 или новее (в Docker-сборке используется Maven 3.9).
- Chromium для Playwright. Устанавливается командой `mvn exec:java` (см. быстрый старт) или при сборке Docker-образа.
- Ключ OpenRouter (`OPENROUTER_API_KEY`) и доступ в интернет к `openrouter.ai`.
- PostgreSQL со схемой `qa` из `db/init/01-schema.sql` и ролью приложения с правами `SELECT`, `INSERT`, `UPDATE`, `DELETE` на таблицы этой схемы (см. `db/init/02-roles.sh`). Для уже работающей БД, созданной до перехода на хранение в БД, примените `db/migrations/0002_artifacts_sessions_journal.sql` (таблицы `qa.artifacts`, `qa.clarification_sessions`, `qa.clarification_answers`). Подключение к БД обязательно: запись прогона и артефактов выполняется при каждом запросе, отключить её нельзя.

## Быстрый старт

Перед запуском создайте базу `qa`, примените `db/init/01-schema.sql` и создайте роль `qa_app` (либо используйте Docker, см. раздел «Docker»). Для существующей БД дополнительно примените миграцию `db/migrations/0002_artifacts_sessions_journal.sql`.

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
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/qa` | JDBC-адрес PostgreSQL (артефакты, сессии уточнений, результаты прогонов) |
| `spring.datasource.username` | `DB_USER` | `qa_app` | Пользователь БД (права `SELECT`, `INSERT`, `UPDATE`, `DELETE` на схему `qa`) |
| `spring.datasource.password` | `DB_PASSWORD` | пусто | Пароль пользователя БД |
| `app.storage.base-url` | `APP_STORAGE_BASE_URL` | `http://localhost:8083` | Базовый URL для ссылок `enrichedIndexUrl` и `archiveUrl` (`<baseUrl>/api/files/...`) |
| `app.target.allowed-hosts` | `APP_TARGET_ALLOWED_HOSTS` | `localhost` | Хосты, разрешённые для исполнения (через запятую) |
| `app.download.allowed-hosts` | `APP_DOWNLOAD_ALLOWED_HOSTS` | `localhost` | Хосты, с которых разрешено скачивать кейсы (через запятую) |
| `app.download.connect-timeout-seconds` | — | `10` | Таймаут соединения при скачивании, с |
| `app.download.read-timeout-seconds` | — | `300` | Таймаут чтения при скачивании, с |
| `app.playwright.headless` | — | `true` | Запуск Chromium без окна |
| `app.playwright.navigation-timeout-ms` | — | `30000` | Таймаут навигации, мс |
| `app.playwright.action-timeout-ms` | — | `10000` | Таймаут действий и поиска элементов, мс |
| `app.enrichment-body-limit` | — | `0` | Ограничение длины `responseSample` в обогащении, символов (`0` — без ограничения) |
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

Переменные `DB_URL`, `DB_USER` и `DB_PASSWORD` задают подключение к PostgreSQL (строки `spring.datasource.*` выше). Без доступной БД прогоны, сессии уточнений и файлы не работают.

Переменные окружения, которые читает модуль: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OPENROUTER_API_KEY`, `APP_STORAGE_BASE_URL`, `APP_TARGET_ALLOWED_HOSTS`, `APP_DOWNLOAD_ALLOWED_HOSTS`.

Срок хранения (30 дней) задаётся в БД через `expires_at` и не настраивается переменной окружения; отдельных настроек TTL и лимита количества сессий больше нет.

## API

Все endpoints без аутентификации. Ошибки возвращаются в формате `ErrorResponse`:

```json
{"timestamp":"2026-11-04T10:00:00Z","status":400,"message":"Укажите ровно одно из полей: sourceRunId или sourceUrl"}
```

### POST /api/run

Запускает прогон. Запрос выполняется синхронно: ответ придёт после исполнения всех кейсов или остановки на уточнении.

Тело запроса (`application/json`):

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `sourceRunId` | строка (UUID) | ровно одно из двух | taskId генерации в `md-to-test`: индекс и кейсы читаются из `qa.artifacts` |
| `sourceUrl` | строка | ровно одно из двух | URL файла `*.md`, индекса или каталога с `_index.md`; пустая строка считается отсутствием поля |
| `startUrl` | строка | нет | Стартовый URL веб-приложения для UI-шагов |
| `apiBaseUrl` | строка | нет | Базовый URL API для относительных путей |

Нужен ровно один из `sourceRunId` и `sourceUrl`: если заданы оба или ни одного, сервис отвечает 400.

Ответ `200` — `RunResponse`:

| Поле | Тип | Описание |
|---|---|---|
| `taskId` | строка (UUID) | Идентификатор прогона; он же используется в URL ответов на уточнения |
| `status` | строка | `DONE` или `NEEDS_CLARIFICATION` |
| `version` | число | Версия сессии после этого ответа; передаётся как `expectedVersion` при ответах на уточнения |
| `outcomes` | массив | Итоги кейсов: `testCaseId`, `filename`, `status` (`PASSED`, `FAILED`, `SKIPPED`, `NEEDS_CLARIFICATION`), `failureMessage`, `enrichments` |
| `clarifications` | массив | Уточнения: `testCaseId`, `stepIndex` (с 0), `question`, `reason`, `category` (`DATA_MISSING` или `LOCATOR_NOT_FOUND`), `topicKey` |
| `enrichedIndexUrl` | строка | Адрес индекса обогащённых кейсов прогона: `<baseUrl>/api/files/<uuid>` |
| `archiveUrl` | строка | Адрес ZIP со всеми обогащёнными файлами прогона: `<baseUrl>/api/files/runs/<taskId>/archive` |

Элементы `enrichments` — объекты `stepIndex`, `action`, `detail`, `elements`, `requestCurl`, `requestBody`, `responseSample`, `subEnrichments`.

Коды ошибок:

- `400` — не задан ровно один источник (ни `sourceRunId`, ни `sourceUrl`, либо оба): `Укажите ровно одно из полей: sourceRunId или sourceUrl`; `sourceRunId` не является UUID; прямая ссылка на `.md` указывает на недопустимый или запрещённый хост либо на некорректный URL; одиночный файл по прямой ссылке не удалось разобрать;
- `502` — не удалось скачать или разобрать кейсы из индекса либо из каталога (в том числе запрещённый хост для `_index.md` каталога), либо при `sourceRunId` не найден индекс, пуст индекс, не найден или не разобран кейс. Ошибка LLM при планировании шага ответ 502 не даёт: шаг получает `FAILED` (`planning-error`), прогон продолжается;
- `500` — ошибка записи в БД либо непредвиденная ошибка.

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
  "version": 1,
  "enrichedIndexUrl": "http://localhost:8083/api/files/<uuid>",
  "archiveUrl": "http://localhost:8083/api/files/runs/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/archive"
}
```

curl (bash), запуск по ручному адресу:

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

Запуск по кейсам генерации (`sourceRunId` вместо `sourceUrl`):

```bash
curl -X POST http://localhost:8083/api/run \
  -H "Content-Type: application/json" \
  -d '{"sourceRunId":"<taskId генерации из md-to-test>","startUrl":"http://localhost:3000","apiBaseUrl":"http://localhost:3000"}'
```

### POST /api/run/{taskId}/answers

Передаёт ответы на уточнения и продолжает прогон с кейса, на котором он остановился. Кейсы, завершённые до этого, не перезапускаются. Кейс с уточнением выполняется заново с первого шага, поэтому его `POST`, `PUT` и `DELETE` вызовы повторятся.

Параметр `taskId` — идентификатор из ответа `POST /api/run`.

Тело запроса:

| Поле | Тип | Описание |
|---|---|---|
| `answers` | массив | Ответы; элемент содержит `question` (текст вопроса), `answer` (текст ответа), `topicKey` (тема из `clarifications`) и необязательный `testCaseId`. Ответ, в котором `answer` пуст или из пробелов, отбрасывается |
| `expectedVersion` | число | Необязательно. Версия, которую видел пользователь (`version` из `GET /api/run/{taskId}` или `POST /api/run`). Если задана и не совпадает с текущей версией, сервис отвечает 409 |

Ответ `200` — `RunResponse` с полным набором итогов прогона (включая кейсы до остановки) и новым `version`. Версия увеличивается на 1 при каждом успешном ответе, раунд ответов — на 1. Непустые ответы добавляются в историю сессии (`qa.clarification_answers`), а в выполнение передаётся накопленный набор ответов с дедупликацией по паре «тема + вопрос» (побеждает последний ответ).

Статус `NEEDS_CLARIFICATION` означает, что потребовалось ещё одно уточнение, и сессия сохраняется под тем же `taskId`. Статус `DONE` означает, что прогон завершён: сессия остаётся в БД со статусом `DONE` до истечения срока, но на новые ответы отвечает 400.

Коды ошибок:

- `400` — сессия с таким `taskId` не найдена или истекла (`Сессия не найдена или истекла`); прогон уже завершён (`DONE`): `Прогон уже завершён, уточнения не требуются`;
- `409` — `expectedVersion` не совпадает с текущей версией, либо сессия изменена параллельным запросом: `Сессия уже изменена в другом окне. Обновите страницу.`;
- `500` — ошибка записи в БД.

curl (bash):

```bash
curl -X POST http://localhost:8083/api/run/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/answers \
  -H "Content-Type: application/json" \
  -d '{"answers":[{"question":"укажите стартовый URL","answer":"http://localhost:3000","topicKey":"start_url"}],"expectedVersion":1}'
```

PowerShell:

```powershell
$body = @{ answers = @(@{ question = 'укажите стартовый URL'; answer = 'http://localhost:3000'; topicKey = 'start_url' }); expectedVersion = 1 } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri http://localhost:8083/api/run/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/answers -ContentType 'application/json' -Body $body
```

### GET /api/run/{taskId}

Возвращает состояние сессии прогона (например, чтобы восстановить экран после перезагрузки страницы): статус, версию, раунд, уточнения и итоги последнего ответа, историю ответов, черновики.

| Поле | Описание |
|---|---|
| `taskId`, `status` | Идентификатор и статус: `DONE` или `NEEDS_CLARIFICATION` |
| `version`, `round` | Версия сессии и номер последнего раунда ответов |
| `outcomes`, `clarifications` | Итоги кейсов и уточнения из последнего ответа |
| `answers` | История ответов: `round`, `testCaseId`, `question`, `topicKey`, `answer`, `answeredAt` |
| `draft` | Черновики ответов по индексу уточнения, например `{"0": "текст"}` |
| `expiresAt` | Срок истечения сессии (30 дней, продлевается при каждом изменении) |
| `enrichedIndexUrl`, `archiveUrl` | Ссылки на индекс и архив прогона из последнего ответа |

Коды ошибок:

- `400` — сессия не найдена или истекла (`Сессия не найдена или истекла`).

curl (bash):

```bash
curl http://localhost:8083/api/run/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c
```

### PUT /api/run/{taskId}/draft

Перезаписывает черновики ответов целиком. Тело: `{ "draft": { "0": "текст ответа на уточнение с индексом 0" } }`. Ответ `204 No Content`. Версия сессии при этом не меняется.

Коды ошибок:

- `400` — сессия не найдена или истекла.

curl (bash):

```bash
curl -X PUT http://localhost:8083/api/run/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/draft \
  -H "Content-Type: application/json" \
  -d '{"draft":{"0":"http://localhost:3000"}}'
```

### GET /api/files/{id}

Отдаёт артефакт из БД по UUID как вложение: `Content-Type` берётся из артефакта, `Content-Disposition: attachment; filename="<filename>"`. UUID приходит в `enrichedIndexUrl` и других ссылках ответов сервиса.

Коды ошибок:

- `400` — UUID некорректен, артефакт не найден или срок его хранения истёк; тело ответа: `{"message": "Файл не найден"}`.

curl (bash):

```bash
curl -OJ http://localhost:8083/api/files/<uuid>
```

PowerShell:

```powershell
Invoke-WebRequest -Uri http://localhost:8083/api/files/<uuid> -OutFile _enriched_index.md
```

### GET /api/files/runs/{taskId}/archive

Отдаёт ZIP-архив обогащённых кейсов и индекса одного прогона (`Content-Type: application/zip`, `Content-Disposition: attachment; filename="enriched-<taskId>.zip"`). В архив входят артефакты прогона видов `enriched_case` и `enriched_index`, собранные из БД. Ссылки индекса на кейсы относительные, поэтому внутри архива они работают.

Архив собирается из строк этого прогона (`run_id = taskId`) и не зависит от последующих прогонов с теми же кейсами: повторный прогон создаёт свой набор артефактов и не меняет архив прежнего.

Коды ошибок:

- `400` — `taskId` не является UUID, либо для прогона нет обогащённых файлов (неизвестный прогон; в том числе прогон, выполненный до перехода на хранение в БД): `Обогащённые кейсы для прогона не найдены`.

curl (bash):

```bash
curl -OJ http://localhost:8083/api/files/runs/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/archive
```

PowerShell:

```powershell
Invoke-WebRequest -Uri http://localhost:8083/api/files/runs/3f2c9a4e-1b7d-4c8e-9a2f-5d6e7f8a9b0c/archive -OutFile enriched.zip
```

### GET /actuator/health и GET /actuator/info

Служебные endpoints Spring Boot Actuator. Открыты только `health` и `info`.

## Безопасность

Сервис обращается к сети по адресам, которые задают пользователь и LLM. Поэтому обращения ограничены allowlist-ами.

- **Исполнение (`app.target.allowed-hosts`, `APP_TARGET_ALLOWED_HOSTS`).** Проверяется `startUrl` и все переходы UI-шагов (`navigate`): хост должен входить в список. Браузер перехватывает все навигационные запросы, включая редиректы, и отменяет переходы на запрещённые хосты. Запросы ресурсов страницы (скрипты, изображения, `fetch` из JavaScript) этим правилом не блокируются. Для API проверяется итоговый URL каждого вызова, включая абсолютные URL из плана LLM; редиректы не выполняются.
- **Нарушение allowlist.** Шаг получает статус `FAILED` (исключение `TargetNotAllowedException`), а не уточнение: ответом пользователя запрет хоста не снимается. Если запрещён `startUrl`, `FAILED` получает весь кейс, и записи шагов в отчёте отсутствуют.
- **Скачивание (`app.download.allowed-hosts`, `APP_DOWNLOAD_ALLOWED_HOSTS`).** Разрешены только схемы `http` и `https` и хосты из списка. Проверка применяется к исходному URL и к каждому адресу из `Location`. Автоматическое следование редиректам отключено: каждый переход проверяется схемой и allowlist, переходов не больше 5. Нарушение прерывает скачивание ошибкой (`502`).
- **Сравнение хостов.** Сравнение точное, без учёта регистра; порт и путь не проверяются. Для URL вида `http://localhost@evil.com/` учитывается реальный хост (`evil.com`). Проверяется только имя хоста: IP-адреса и результаты DNS отдельно не сверяются.
- **Значения по умолчанию.** Оба списка по умолчанию содержат только `localhost`. Для Docker `docker-compose.yml` задаёт список исполнения `localhost,host.docker.internal`, его можно переопределить переменной `APP_TARGET_ALLOWED_HOSTS`. Пустой список скачивания трактуется как `localhost`, а пустой список исполнения, наоборот, запрещает все хосты.
- **TLS.** Для API-вызовов отключена проверка сертификатов (`relaxedHTTPSValidation`). Используйте сервис только против стендов, которым вы доверяете.
- **Идентификаторы файлов.** `GET /api/files/{id}` принимает только канонический UUID и отдаёт только артефакты из БД. Путей к файлам на диске нет, поэтому обхода каталогов быть не может.
- **Ответ LLM.** Модель может вернуть только операции из фиксированного набора; неизвестная операция даёт ошибку шага. Адреса, которые модель укажет вне allowlist, блокируются кодом, а не промптом.
- **Доступ к endpoints.** Аутентификация отсутствует. В `docker-compose.yml` порт публикуется только на `127.0.0.1`; при другом развёртывании ограничивайте сетевой доступ самостоятельно.
- **Секреты.** Ключ OpenRouter и пароль БД задаются через переменные окружения. Сохраняемые обогащения и артефакты содержат тела запросов и ответов API и хранятся в БД (`qa.artifacts`, `qa.case_executions`) без шифрования на уровне приложения. Ограничивайте доступ к БД и следите за сроком хранения.

## Данные

Сервис работает со схемой `qa` PostgreSQL. Схема создаётся скриптом `db/init/01-schema.sql`; для уже существующей БД применяется миграция `db/migrations/0002_artifacts_sessions_journal.sql`. Артефакты, сессии уточнений и результаты прогонов хранятся только в БД. Диск для них не используется.

Таблицы, которые пишет сервис:

| Таблица | Что хранится |
|---|---|
| `qa.execution_runs` | Один прогон: `id` (taskId), `status` (`DONE` или `NEEDS_CLARIFICATION`), `source_url` (URL либо метка `md-to-test:<sourceRunId>`), `start_url`, `api_base_url`, `created_at`, `updated_at` |
| `qa.case_executions` | По строке на кейс прогона: `run_id`, `test_case_id`, `filename`, `status`, `failure_message`, `enrichments` (JSON-массив обогащений шагов) |
| `qa.execution_clarifications` | По строке на уточнение: `run_id`, `test_case_id`, `step_index`, `question`, `reason`, `category`, `topic_key` |
| `qa.artifacts` | Файлы прогона: обогащённые кейсы (`kind = 'enriched_case'`), индекс обогащений (`'enriched_index'`), а при `sourceUrl` — скачанные кейсы (`'test_case'`) и индекс источника (`'index'`). `run_id` — taskId прогона, `filename`, `content_type`, `content` (bytea), `sha256`, `expires_at` |
| `qa.clarification_sessions` | Сессия прогона (`service = 'run'`): `status`, `state` (внутреннее состояние, в нём и последний ответ), `clarifications`, `draft`, `version`, `round`, `expires_at` |
| `qa.clarification_answers` | История ответов сессии: `task_id`, `round`, `test_case_id`, `question`, `topic_key`, `answer`, `answered_at`. Только добавление строк |

Чтение: при `sourceRunId` сервис читает из `qa.artifacts` файлы индекса (`kind = 'index'`) и кейсов (`kind = 'test_case'`) генерации с этим `run_id`. Архив и файлы выдаются тоже из `qa.artifacts`.

Таблицы `qa.generation_runs`, `qa.test_cases` и `qa.clarifications` относятся к генерации (`md-to-test`) и сервисом не записываются.

Правила хранения:

- Внутри одного прогона файл с тем же `filename` и `kind` заменяется: старая строка удаляется, новая вставляется в одной транзакции. Файлы разных прогонов с одинаковым именем не конфликтуют.
- Все файлы одного прогона и состояние сессии фиксируются одной транзакцией. При ошибке записи частичного набора не остаётся.
- Каждая запись получает `expires_at = now() + 30 дней`. Сессия продлевает срок при каждом изменении (ответ, черновик).
- Очистка (`RetentionService`) удаляет истёкшие артефакты этого сервиса (`owner_service = 'test-runner'`) и истёкшие сессии прогонов при старте приложения и затем каждые 6 часов. Истёкшие записи не отдаются: `GET /api/files/{id}` возвращает 400, сессия — 400 `Сессия не найдена или истекла`.

## Docker

Сервис собирается и запускается вместе с остальными сервисами через Docker Compose. Инструкция: [DOCKER.md](../DOCKER.md).

Dockerfile (`Dockerfile`) выполняет многоэтапную сборку: на первом этапе собирается JAR и копируются зависимости, на втором — образ `eclipse-temurin:21-jre-jammy`, в который при сборке устанавливается Chromium для Playwright. Приложение запускается от пользователя `app`, сервис слушает порт `8083`. Постоянные данные хранятся в PostgreSQL, файлы на диск не записываются. Параметр `-XX:MaxRAMPercentage=75` ограничивает память JVM.

В `docker-compose.yml` для `test-runner` заданы `DB_URL=jdbc:postgresql://db:5432/qa`, `DB_USER=qa_app`, `DB_PASSWORD` из `QA_APP_PASSWORD`, `OPENROUTER_API_KEY`, `APP_STORAGE_BASE_URL=http://test-runner:8083`, `APP_DOWNLOAD_ALLOWED_HOSTS=md-to-test,localhost`, `APP_TARGET_ALLOWED_HOSTS` (по умолчанию `localhost,host.docker.internal`), а также `shm_size: 1gb` (Chromium не работает с 64 МБ `/dev/shm`) и `host.docker.internal:host-gateway`.

## Разработка

Сборка и тесты (из каталога `test-runner`):

```bash
mvn -B -q test-compile   # компиляция main и test
mvn -B test              # тесты (JUnit 5, AssertJ)
mvn -B package -DskipTests
mvn spring-boot:run      # запуск с локальными переменными окружения
```

Тесты находятся в `src/test/java`: `application.service.TargetUrlGuardTest`, `application.service.TestCaseFetcherTest`, `application.service.EnrichedStorageServiceTest`, `application.service.RunSessionServiceTest`.

## Ограничения

- **Сессии уточнений в БД.** Состояние прогона, остановленного на уточнении, хранится в `qa.clarification_sessions` и переживает перезапуск сервиса. Срок — 30 дней после последнего изменения (`expires_at`); истёкшие сессии удаляются при старте и каждые 6 часов. Ограничения по количеству сессий нет.
- **Повтор кейса с уточнением.** Кейс, на котором остановились, выполняется заново целиком, поэтому его побочные API-вызовы (`POST`, `PUT`, `DELETE`) повторятся. Это важно для стендов с необратимыми действиями.
- **Синхронный запрос.** `POST /api/run` удерживает соединение до конца прогона. Таймаут ответа модели по умолчанию в `application.yml` — 2400 секунд, поэтому прогон может длиться долго.
- **Лимит входа модели не применяется.** Параметр `app.planner.max-input-chars` задан, но планировщик не проверяет длину промпта и не возвращает уточнение `context_overflow`.
- **Обогащения по прогонам.** Каждый прогон хранит свой набор файлов (`run_id`), поэтому повторный прогон не перезаписывает файлы предыдущего. Внутри одного прогона файл с тем же именем заменяется, например при ответе на уточнение, после которого кейс выполняется заново.
- **Обязательный браузер.** Chromium запускается при старте; без установленного браузера сервис не стартует.
- **Обязательная БД.** Запись в БД выполняется при каждом запросе. При её недоступности ответ — `500`. Фиксация транзакционная: частичных записей прогона или сессии при ошибке не остаётся, но результаты уже выполненных кейсов этого запроса теряются, и прогон нужно запустить заново.
- **Ошибки Spring MVC.** Неизвестный путь даёт `404`, неверный метод — `405`, неверный `Content-Type` — `415`, нечитаемое тело, отсутствующий параметр и неверный тип параметра — `400`. Прочие необработанные исключения возвращаются с кодом `500`.
- **Редиректы при скачивании.** Каждый переход проверяется по allowlist, но DNS-адрес хоста не проверяется, поэтому возможен DNS rebinding (см. «Безопасность»).
- **Фиксированный таймаут `waitForText`.** Ожидание текста на странице составляет 10 секунд и не настраивается.
- **Недетерминированность LLM.** Параметры `temperature: 0` и `seed: 42` уменьшают разброс ответов, но не гарантируют одинаковый план для одного и того же шага.
- **Тема `api_base_url` без `apiBaseUrl`.** Если `apiBaseUrl` не задан, а модель вернула относительный путь без `needClarification`, тема уточнения выводится из текста ошибки, а не равна `api_base_url`. Ответ с `topicKey: "api_base_url"` тогда не сопоставится; передавайте `apiBaseUrl` в запросе.
- **XPath в отчёте.** Поле `xpath` элемента вычисляется в браузере (`UiExecutor.computeXPath`). Литерал выбирается корректно для XPath 1.0: `'…'`, если нет апострофа, `"…"`, если нет двойных кавычек, иначе `concat(...)`. Выполнение использует локаторы Playwright, а XPath нужен только для отчёта.
