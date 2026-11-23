#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Generate app/src/main/assets/mcp/catalog.json from a curated service list plus shared translations.

The catalog is declarative and secret-free: presets only describe connection shape (transport, auth,
endpoint/command template, fields). Tool directories and schemas are discovered from the live server,
never written here. Descriptions are our own words; service names stay in their original language.

Usage:
  build_catalog.py            write app/src/main/assets/mcp/catalog.json
  build_catalog.py --check    write to a temp file and compare with the committed asset (exit 1 on mismatch)
"""

import argparse
import json
import os
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "mcp", "catalog.json")

LOCALES = ["en", "zh", "zh-Hant", "ar", "fa", "ja", "ko", "es", "pt", "ru", "id", "tr"]

# --- capability summaries (one line each), translated ---------------------------
SUMMARIES = {
    "files": {"en": "Read, write and search files", "zh": "读写和搜索文件", "zh-Hant": "讀寫和搜尋檔案",
              "ar": "قراءة الملفات وكتابتها والبحث فيها", "fa": "خواندن، نوشتن و جست‌وجوی فایل‌ها",
              "ja": "ファイルの読み書きと検索", "ko": "파일 읽기, 쓰기 및 검색", "es": "Leer, escribir y buscar archivos",
              "pt": "Ler, escrever e buscar arquivos", "ru": "Чтение, запись и поиск файлов",
              "id": "Membaca, menulis, dan mencari berkas", "tr": "Dosyaları okuma, yazma ve arama"},
    "git": {"en": "Git repositories and version control", "zh": "Git 仓库与版本控制", "zh-Hant": "Git 儲存庫與版本控制",
            "ar": "مستودعات Git والتحكم بالإصدارات", "fa": "مخازن Git و کنترل نسخه",
            "ja": "Git リポジトリとバージョン管理", "ko": "Git 저장소 및 버전 관리", "es": "Repositorios Git y control de versiones",
            "pt": "Repositórios Git e controle de versão", "ru": "Репозитории Git и контроль версий",
            "id": "Repositori Git dan kontrol versi", "tr": "Git depoları ve sürüm kontrolü"},
    "code": {"en": "Code review and repository analysis", "zh": "代码审查与仓库分析", "zh-Hant": "程式碼審查與儲存庫分析",
             "ar": "مراجعة الكود وتحليل المستودعات", "fa": "بازبینی کد و تحلیل مخازن",
             "ja": "コードレビューとリポジトリ解析", "ko": "코드 리뷰 및 저장소 분석", "es": "Revisión de código y análisis de repositorios",
             "pt": "Revisão de código e análise de repositórios", "ru": "Ревью кода и анализ репозиториев",
             "id": "Tinjauan kode dan analisis repositori", "tr": "Kod incelemesi ve depo analizi"},
    "ci": {"en": "Builds, deployments and CI/CD", "zh": "构建、部署与 CI/CD", "zh-Hant": "建置、部署與 CI/CD",
           "ar": "البناء والنشر و CI/CD", "fa": "ساخت، استقرار و CI/CD",
           "ja": "ビルド・デプロイ・CI/CD", "ko": "빌드, 배포 및 CI/CD", "es": "Compilaciones, despliegues y CI/CD",
           "pt": "Builds, deploys e CI/CD", "ru": "Сборки, развертывание и CI/CD",
           "id": "Build, deployment, dan CI/CD", "tr": "Derleme, dağıtım ve CI/CD"},
    "database": {"en": "Query and manage a database", "zh": "查询和管理数据库", "zh-Hant": "查詢和管理資料庫",
                 "ar": "الاستعلام عن قاعدة البيانات وإدارتها", "fa": "پرس‌وجو و مدیریت پایگاه داده",
                 "ja": "データベースのクエリと管理", "ko": "데이터베이스 쿼리 및 관리", "es": "Consultar y administrar una base de datos",
                 "pt": "Consultar e gerenciar um banco de dados", "ru": "Запросы к базе данных и её управление",
                 "id": "Mengkueri dan mengelola basis data", "tr": "Veritabanını sorgulama ve yönetme"},
    "web": {"en": "Fetch and read web pages", "zh": "抓取和阅读网页", "zh-Hant": "擷取和閱讀網頁",
            "ar": "جلب صفحات الويب وقراءتها", "fa": "واکشی و خواندن صفحات وب",
            "ja": "Web ページの取得と読み取り", "ko": "웹 페이지 가져오기 및 읽기", "es": "Obtener y leer páginas web",
            "pt": "Buscar e ler páginas da web", "ru": "Получение и чтение веб-страниц",
            "id": "Mengambil dan membaca halaman web", "tr": "Web sayfalarını getirme ve okuma"},
    "search": {"en": "Search the web", "zh": "搜索网页", "zh-Hant": "搜尋網頁",
               "ar": "البحث في الويب", "fa": "جست‌وجو در وب",
               "ja": "Web を検索", "ko": "웹 검색", "es": "Buscar en la web",
               "pt": "Pesquisar na web", "ru": "Поиск в интернете",
               "id": "Mencari di web", "tr": "Web'de arama"},
    "browser": {"en": "Automate a web browser", "zh": "自动化网页浏览器", "zh-Hant": "自動化網頁瀏覽器",
                "ar": "أتمتة متصفح الويب", "fa": "خودکارسازی مرورگر وب",
                "ja": "Web ブラウザの自動化", "ko": "웹 브라우저 자동화", "es": "Automatizar un navegador web",
                "pt": "Automatizar um navegador web", "ru": "Автоматизация веб-браузера",
                "id": "Mengotomatiskan peramban web", "tr": "Web tarayıcısını otomatikleştirme"},
    "chat": {"en": "Send and read messages", "zh": "发送和阅读消息", "zh-Hant": "傳送和閱讀訊息",
             "ar": "إرسال الرسائل وقراءتها", "fa": "ارسال و خواندن پیام‌ها",
             "ja": "メッセージの送受信", "ko": "메시지 보내기 및 읽기", "es": "Enviar y leer mensajes",
             "pt": "Enviar e ler mensagens", "ru": "Отправка и чтение сообщений",
             "id": "Mengirim dan membaca pesan", "tr": "Mesaj gönderme ve okuma"},
    "email": {"en": "Send and read email", "zh": "发送和阅读邮件", "zh-Hant": "傳送和閱讀電子郵件",
              "ar": "إرسال البريد الإلكتروني وقراءته", "fa": "ارسال و خواندن ایمیل",
              "ja": "メールの送受信", "ko": "이메일 보내기 및 읽기", "es": "Enviar y leer correo electrónico",
              "pt": "Enviar e ler e-mail", "ru": "Отправка и чтение электронной почты",
              "id": "Mengirim dan membaca surel", "tr": "E-posta gönderme ve okuma"},
    "docs": {"en": "Notes, documents and wikis", "zh": "笔记、文档与维基", "zh-Hant": "筆記、文件與維基",
             "ar": "الملاحظات والمستندات والويكي", "fa": "یادداشت‌ها، اسناد و ویکی‌ها",
             "ja": "ノート・文書・Wiki", "ko": "메모, 문서 및 위키", "es": "Notas, documentos y wikis",
             "pt": "Notas, documentos e wikis", "ru": "Заметки, документы и вики",
             "id": "Catatan, dokumen, dan wiki", "tr": "Notlar, belgeler ve wikiler"},
    "tasks": {"en": "Tasks, projects and issues", "zh": "任务、项目与问题跟踪", "zh-Hant": "任務、專案與問題追蹤",
              "ar": "المهام والمشاريع وتتبع المشكلات", "fa": "وظایف، پروژه‌ها و پیگیری مشکلات",
              "ja": "タスク・プロジェクト・課題", "ko": "작업, 프로젝트 및 이슈", "es": "Tareas, proyectos e incidencias",
              "pt": "Tarefas, projetos e problemas", "ru": "Задачи, проекты и тикеты",
              "id": "Tugas, proyek, dan isu", "tr": "Görevler, projeler ve sorunlar"},
    "calendar": {"en": "Calendar and scheduling", "zh": "日历与日程安排", "zh-Hant": "行事曆與排程",
                 "ar": "التقويم والجدولة", "fa": "تقویم و زمان‌بندی",
                 "ja": "カレンダーとスケジュール", "ko": "캘린더 및 일정", "es": "Calendario y programación",
                 "pt": "Calendário e agendamento", "ru": "Календарь и расписание",
                 "id": "Kalender dan penjadwalan", "tr": "Takvim ve planlama"},
    "design": {"en": "Design and collaborate", "zh": "设计与协作", "zh-Hant": "設計與協作",
               "ar": "التصميم والتعاون", "fa": "طراحی و همکاری",
               "ja": "デザインとコラボレーション", "ko": "디자인 및 협업", "es": "Diseñar y colaborar",
               "pt": "Projetar e colaborar", "ru": "Дизайн и совместная работа",
               "id": "Desain dan kolaborasi", "tr": "Tasarım ve iş birliği"},
    "cloud": {"en": "Manage cloud resources", "zh": "管理云资源", "zh-Hant": "管理雲端資源",
              "ar": "إدارة موارد السحابة", "fa": "مدیریت منابع ابری",
              "ja": "クラウドリソースの管理", "ko": "클라우드 리소스 관리", "es": "Administrar recursos en la nube",
              "pt": "Gerenciar recursos de nuvem", "ru": "Управление облачными ресурсами",
              "id": "Mengelola sumber daya cloud", "tr": "Bulut kaynaklarını yönetme"},
    "llm": {"en": "Large language models", "zh": "大语言模型", "zh-Hant": "大型語言模型",
            "ar": "نماذج اللغة الكبيرة", "fa": "مدل‌های زبانی بزرگ",
            "ja": "大規模言語モデル", "ko": "대규모 언어 모델", "es": "Modelos de lenguaje grandes",
            "pt": "Modelos de linguagem grandes", "ru": "Большие языковые модели",
            "id": "Model bahasa besar", "tr": "Büyük dil modelleri"},
    "vector": {"en": "Vector search and embeddings", "zh": "向量搜索与嵌入", "zh-Hant": "向量搜尋與嵌入",
               "ar": "البحث المتجهي والتضمينات", "fa": "جست‌وجوی برداری و تعبیه‌ها",
               "ja": "ベクトル検索と埋め込み", "ko": "벡터 검색 및 임베딩", "es": "Búsqueda vectorial e incrustaciones",
               "pt": "Busca vetorial e embeddings", "ru": "Векторный поиск и эмбеддинги",
               "id": "Pencarian vektor dan embedding", "tr": "Vektör arama ve gömme"},
    "analytics": {"en": "Query and visualize data", "zh": "查询和可视化数据", "zh-Hant": "查詢和視覺化資料",
                  "ar": "الاستعلام عن البيانات وتصويرها", "fa": "پرس‌وجو و تصویرسازی داده‌ها",
                  "ja": "データのクエリと可視化", "ko": "데이터 쿼리 및 시각화", "es": "Consultar y visualizar datos",
                  "pt": "Consultar e visualizar dados", "ru": "Запросы к данным и визуализация",
                  "id": "Mengkueri dan memvisualisasikan data", "tr": "Verileri sorgulama ve görselleştirme"},
    "payments": {"en": "Payments and billing", "zh": "支付与账单", "zh-Hant": "支付與帳單",
                 "ar": "المدفوعات والفوترة", "fa": "پرداخت‌ها و صورتحساب",
                 "ja": "決済と請求", "ko": "결제 및 청구", "es": "Pagos y facturación",
                 "pt": "Pagamentos e cobrança", "ru": "Платежи и выставление счетов",
                 "id": "Pembayaran dan penagihan", "tr": "Ödemeler ve faturalandırma"},
    "security": {"en": "Security scanning and analysis", "zh": "安全扫描与分析", "zh-Hant": "安全掃描與分析",
                 "ar": "فحص الأمان وتحليله", "fa": "اسکن و تحلیل امنیتی",
                 "ja": "セキュリティスキャンと解析", "ko": "보안 스캔 및 분석", "es": "Análisis y escaneo de seguridad",
                 "pt": "Varredura e análise de segurança", "ru": "Сканирование и анализ безопасности",
                 "id": "Pemindaian dan analisis keamanan", "tr": "Güvenlik taraması ve analizi"},
    "media": {"en": "Generate and edit images", "zh": "生成和编辑图像", "zh-Hant": "生成和編輯影像",
              "ar": "إنشاء الصور وتحريرها", "fa": "تولید و ویرایش تصاویر",
              "ja": "画像の生成と編集", "ko": "이미지 생성 및 편집", "es": "Generar y editar imágenes",
              "pt": "Gerar e editar imagens", "ru": "Генерация и редактирование изображений",
              "id": "Membuat dan mengedit gambar", "tr": "Görüntü oluşturma ve düzenleme"},
    "maps": {"en": "Maps and geolocation", "zh": "地图与地理定位", "zh-Hant": "地圖與地理定位",
             "ar": "الخرائط وتحديد الموقع", "fa": "نقشه و موقعیت‌یابی",
             "ja": "地図と位置情報", "ko": "지도 및 위치", "es": "Mapas y geolocalización",
             "pt": "Mapas e geolocalização", "ru": "Карты и геолокация",
             "id": "Peta dan geolokasi", "tr": "Haritalar ve konum"},
    "time": {"en": "Time and scheduling helpers", "zh": "时间与日程辅助", "zh-Hant": "時間與排程輔助",
             "ar": "أدوات الوقت والجدولة", "fa": "ابزارهای زمان و زمان‌بندی",
             "ja": "時刻とスケジュールの補助", "ko": "시간 및 일정 도우미", "es": "Ayudas de tiempo y programación",
             "pt": "Auxiliares de hora e agendamento", "ru": "Вспомогательные инструменты времени",
             "id": "Pembantu waktu dan jadwal", "tr": "Zaman ve planlama yardımcıları"},
    "memory": {"en": "Persistent knowledge and memory", "zh": "持久化知识与记忆", "zh-Hant": "持久化知識與記憶",
               "ar": "المعرفة الدائمة والذاكرة", "fa": "دانش پایدار و حافظه",
               "ja": "永続的な知識と記憶", "ko": "영구 지식 및 메모리", "es": "Conocimiento persistente y memoria",
               "pt": "Conhecimento persistente e memória", "ru": "Постоянное знание и память",
               "id": "Pengetahuan persisten dan memori", "tr": "Kalıcı bilgi ve hafıza"},
    "crm": {"en": "Customer relationship management", "zh": "客户关系管理", "zh-Hant": "客戶關係管理",
            "ar": "إدارة علاقات العملاء", "fa": "مدیریت ارتباط با مشتری",
            "ja": "顧客関係管理", "ko": "고객 관계 관리", "es": "Gestión de relaciones con clientes",
            "pt": "Gestão de relacionamento com clientes", "ru": "Управление взаимоотношениями с клиентами",
            "id": "Manajemen hubungan pelanggan", "tr": "Müşteri ilişkileri yönetimi"},
}

# --- field labels, translated ---------------------------------------------------
LABELS = {
    "api_key": {"en": "API Key", "zh": "API 密钥", "zh-Hant": "API 金鑰", "ar": "مفتاح API", "fa": "کلید API",
                "ja": "API キー", "ko": "API 키", "es": "Clave de API", "pt": "Chave de API", "ru": "Ключ API",
                "id": "Kunci API", "tr": "API anahtarı"},
    "token": {"en": "Token", "zh": "令牌", "zh-Hant": "權杖", "ar": "رمز", "fa": "توکن",
              "ja": "トークン", "ko": "토큰", "es": "Token", "pt": "Token", "ru": "Токен",
              "id": "Token", "tr": "Belirteç"},
    "secret": {"en": "Secret", "zh": "密钥", "zh-Hant": "密鑰", "ar": "سر", "fa": "کلید مخفی",
               "ja": "シークレット", "ko": "시크릿", "es": "Secreto", "pt": "Segredo", "ru": "Секрет",
               "id": "Rahasia", "tr": "Gizli anahtar"},
    "access_token": {"en": "Access token", "zh": "访问令牌", "zh-Hant": "存取權杖", "ar": "رمز الوصول", "fa": "نشانه دسترسی",
                     "ja": "アクセストークン", "ko": "액세스 토큰", "es": "Token de acceso", "pt": "Token de acesso",
                     "ru": "Токен доступа", "id": "Token akses", "tr": "Erişim belirteci"},
    "tenant": {"en": "Tenant", "zh": "租户", "zh-Hant": "租用戶", "ar": "المستأجر", "fa": "مستأجر",
               "ja": "テナント", "ko": "테넌트", "es": "Inquilino", "pt": "Tenant", "ru": "Тенант",
               "id": "Penyewa", "tr": "Kiracı"},
    "workspace": {"en": "Workspace", "zh": "工作区", "zh-Hant": "工作區", "ar": "مساحة العمل", "fa": "فضای کاری",
                  "ja": "ワークスペース", "ko": "작업 공간", "es": "Espacio de trabajo", "pt": "Workspace",
                  "ru": "Рабочая область", "id": "Ruang kerja", "tr": "Çalışma alanı"},
    "region": {"en": "Region", "zh": "区域", "zh-Hant": "區域", "ar": "المنطقة", "fa": "منطقه",
               "ja": "リージョン", "ko": "리전", "es": "Región", "pt": "Região", "ru": "Регион",
               "id": "Wilayah", "tr": "Bölge"},
    "base_url": {"en": "Server URL", "zh": "服务器地址", "zh-Hant": "伺服器位址", "ar": "عنوان الخادم", "fa": "نشانی سرور",
                 "ja": "サーバー URL", "ko": "서버 URL", "es": "URL del servidor", "pt": "URL do servidor",
                 "ru": "URL сервера", "id": "URL server", "tr": "Sunucu URL'si"},
    "project": {"en": "Project", "zh": "项目", "zh-Hant": "專案", "ar": "المشروع", "fa": "پروژه",
                "ja": "プロジェクト", "ko": "프로젝트", "es": "Proyecto", "pt": "Projeto", "ru": "Проект",
                "id": "Proyek", "tr": "Proje"},
    "repository": {"en": "Repository", "zh": "仓库", "zh-Hant": "儲存庫", "ar": "المستودع", "fa": "مخزن",
                   "ja": "リポジトリ", "ko": "저장소", "es": "Repositorio", "pt": "Repositório", "ru": "Репозиторий",
                   "id": "Repositori", "tr": "Depo"},
    "channel": {"en": "Channel", "zh": "频道", "zh-Hant": "頻道", "ar": "القناة", "fa": "کانال",
                "ja": "チャンネル", "ko": "채널", "es": "Canal", "pt": "Canal", "ru": "Канал",
                "id": "Kanal", "tr": "Kanal"},
}


def label(key):
    return LABELS[key]


def summary(key):
    return SUMMARIES[key]


# --- service builders -----------------------------------------------------------
# Remote presets use remote(); there is no "REST API" shape — an entry exists only when the vendor
# hosts a real MCP server. A service tuple is (id, name, category, transport, auth, endpoint|command, fields, official_source,
# summary_key, package_spec). fields are (id, secret, required, target, name, label_key); the name
# holds the header / env-var name, empty for bearer/url/argv. package_spec is (ecosystem, name,
# version, source) and only for stdio.

def remote(sid, name, category, endpoint, source, sumkey, auth="oauth", transport="streamable-http", fields=()):
    return (sid, name, category, transport, auth, endpoint, list(fields), source, sumkey, None)


def stdio(sid, name, category, binname, source, sumkey, eco, pkg, ver, auth="none", fields=(), args=()):
    return (sid, name, category, "stdio", auth, [binname, *args], list(fields), source, sumkey, (eco, pkg, ver, source))


# Auth / field shorthands
BEARER_KEY = [("api_key", True, True, "bearer", "", "api_key")]

SERVICES = [
    # ===== stdio — official / verifiable packages, versions pinned from the live registry =====
    stdio("filesystem", "Filesystem", "files", "mcp-server-filesystem",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-filesystem", "files",
          "npm", "@modelcontextprotocol/server-filesystem", "2.4.0", args=["/workspace"]),
    stdio("memory", "Memory", "memory", "mcp-server-memory",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-memory", "memory",
          "npm", "@modelcontextprotocol/server-memory", "2.2.1"),
    stdio("sequential-thinking", "Sequential Thinking", "llm", "mcp-server-sequential-thinking",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-sequential-thinking", "llm",
          "npm", "@modelcontextprotocol/server-sequential-thinking", "2.2.0"),
    stdio("everything", "Everything", "files", "mcp-server-everything",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-everything", "files",
          "npm", "@modelcontextprotocol/server-everything", "2.3.1", args=["/workspace"]),
    stdio("postgres", "PostgreSQL", "database", "mcp-server-postgres",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-postgres", "database",
          "npm", "@modelcontextprotocol/server-postgres", "0.6.2", auth="fields",
          fields=[("connection_string", True, True, "env", "DATABASE_URL", "secret")]),
    stdio("redis", "Redis", "database", "mcp-server-redis",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-redis", "database",
          "npm", "@modelcontextprotocol/server-redis", "2025.4.25"),
    stdio("puppeteer", "Puppeteer", "browser", "mcp-server-puppeteer",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-puppeteer", "browser",
          "npm", "@modelcontextprotocol/server-puppeteer", "2025.5.12"),
    stdio("brave-search", "Brave Search", "search", "mcp-server-brave-search",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-brave-search", "search",
          "npm", "@modelcontextprotocol/server-brave-search", "0.6.2", auth="fields",
          fields=[("api_key", True, True, "env", "BRAVE_API_KEY", "api_key")]),
    stdio("google-maps", "Google Maps", "maps", "mcp-server-google-maps",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-google-maps", "maps",
          "npm", "@modelcontextprotocol/server-google-maps", "0.6.2", auth="fields",
          fields=[("api_key", True, True, "env", "GOOGLE_MAPS_API_KEY", "api_key")]),
    stdio("slack", "Slack", "chat", "mcp-server-slack",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-slack", "chat",
          "npm", "@modelcontextprotocol/server-slack", "2025.4.25", auth="fields",
          fields=[("token", True, True, "env", "SLACK_BOT_TOKEN", "token")]),
    stdio("playwright", "Playwright", "browser", "playwright-mcp-server",
          "https://www.npmjs.com/package/@executeautomation/playwright-mcp-server", "browser",
          "npm", "@executeautomation/playwright-mcp-server", "1.0.12"),
    stdio("fetch", "Fetch", "web", "mcp-server-fetch",
          "https://pypi.org/project/mcp-server-fetch/", "web",
          "pypi", "mcp-server-fetch", "2026.11.18"),
    stdio("time", "Time", "time", "mcp-server-time",
          "https://pypi.org/project/mcp-server-time/", "time",
          "pypi", "mcp-server-time", "2026.11.18"),
    stdio("git", "Git", "git", "mcp-server-git",
          "https://pypi.org/project/mcp-server-git/", "git",
          "pypi", "mcp-server-git", "2026.11.18"),
    stdio("sqlite", "SQLite", "database", "mcp-server-sqlite",
          "https://pypi.org/project/mcp-server-sqlite/", "database",
          "pypi", "mcp-server-sqlite", "2025.4.25"),

    stdio("mariadb", "MariaDB", "database", "mcp-server-mariadb", "https://pypi.org/project/mcp-server-mariadb/", "database", "pypi", "mcp-server-mariadb", "0.1.2"),
    stdio("mysql", "MySQL", "database", "mcp-server-mysql", "https://pypi.org/project/mcp-server-mysql/", "database", "pypi", "mcp-server-mysql", "0.1.4"),
    stdio("docker", "Docker", "ci", "mcp-server-docker", "https://pypi.org/project/mcp-server-docker/", "ci", "pypi", "mcp-server-docker", "0.3.0"),
    stdio("kubernetes", "Kubernetes", "cloud", "mcp-server-kubernetes", "https://pypi.org/project/mcp-server-kubernetes/", "cloud", "pypi", "mcp-server-kubernetes", "0.1.6"),

    # ===== remote — vendor-hosted MCP servers only =====
    # Every endpoint below is the vendor's own MCP server, probed with check_catalog.py --online:
    # either the handshake succeeds, or it answers 401 with OAuth resource metadata. "oauth" presets
    # additionally need dynamic client registration, since the app signs in as a registered public
    # client. A plain REST API root is never a preset; services without a hosted MCP server are left out.

    # AI / large language models
    remote("huggingface", "Hugging Face", "llm", "https://huggingface.co/mcp", "https://huggingface.co/settings/mcp", "llm",
           auth="fields", fields=[("token", True, False, "bearer", "", "token")]),
    remote("replicate", "Replicate", "llm", "https://mcp.replicate.com/mcp", "https://replicate.com/docs/reference/mcp", "llm"),

    # web search
    remote("exa", "Exa", "search", "https://mcp.exa.ai/mcp", "https://exa.ai", "search", auth="none"),
    remote("tavily", "Tavily", "search", "https://mcp.tavily.com/mcp", "https://docs.tavily.com/documentation/mcp", "search"),
    remote("you", "You.com", "search", "https://api.you.com/mcp", "https://documentation.you.com/developer-resources/mcp-server", "search"),
    remote("serpapi", "SerpAPI", "search", "https://mcp.serpapi.com/mcp", "https://github.com/serpapi/serpapi-mcp", "search",
           auth="fields", fields=BEARER_KEY),

    # databases & data stores
    remote("neon", "Neon", "database", "https://mcp.neon.tech/mcp", "https://neon.com/docs/ai/neon-mcp-server", "database"),
    remote("supabase", "Supabase", "database", "https://mcp.supabase.com/mcp", "https://supabase.com/docs/guides/getting-started/mcp", "database"),
    remote("planetscale", "PlanetScale", "database", "https://mcp.pscale.dev/mcp/planetscale", "https://planetscale.com/docs/connect/mcp", "database"),
    remote("clickhouse", "ClickHouse", "database", "https://mcp.clickhouse.cloud/mcp", "https://clickhouse.com/docs/use-cases/AI/MCP/remote_mcp", "database"),
    remote("cockroachdb", "CockroachDB", "database", "https://cockroachlabs.cloud/mcp", "https://www.cockroachlabs.com/docs/cockroachcloud/connect-to-the-cockroachdb-cloud-mcp-server", "database"),

    # developer tools, git hosting, CI/CD, observability
    # GitHub's sign-in server has no dynamic client registration, so its hosted server takes a personal access token.
    remote("github", "GitHub", "git", "https://api.githubcopilot.com/mcp/", "https://github.com/github/github-mcp-server", "git",
           auth="fields", fields=[("token", True, True, "bearer", "", "token")]),
    remote("gitlab", "GitLab", "git", "https://gitlab.com/api/v4/mcp", "https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server/", "git"),
    remote("gitee", "Gitee", "git", "https://api.gitee.com/mcp", "https://gitee.com/oschina/mcp-gitee", "git",
           auth="fields", fields=[("token", True, True, "bearer", "", "access_token")]),
    remote("buildkite", "Buildkite", "ci", "https://mcp.buildkite.com/mcp", "https://buildkite.com/docs/apis/mcp-server", "ci"),
    remote("datadog", "Datadog", "analytics", "https://mcp.datadoghq.com/api/unstable/mcp-server/mcp", "https://docs.datadoghq.com/bits_ai/mcp_server/", "analytics"),
    remote("grafana", "Grafana", "analytics", "https://mcp.grafana.com/mcp", "https://grafana.com/docs/grafana-cloud/", "analytics"),
    remote("sentry", "Sentry", "analytics", "https://mcp.sentry.dev/mcp", "https://docs.sentry.io/product/sentry-mcp/", "analytics"),
    remote("newrelic", "New Relic", "analytics", "https://mcp.newrelic.com/mcp/", "https://docs.newrelic.com/docs/agentic-ai/mcp/overview/", "analytics"),

    # cloud & hosting
    remote("vercel", "Vercel", "cloud", "https://mcp.vercel.com", "https://vercel.com/docs/mcp/vercel-mcp", "cloud"),
    remote("netlify", "Netlify", "cloud", "https://netlify-mcp.netlify.app/mcp", "https://docs.netlify.com/build/build-with-ai/netlify-mcp-server/", "cloud"),
    remote("cloudflare", "Cloudflare", "cloud", "https://mcp.cloudflare.com/mcp", "https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/", "cloud"),
    remote("heroku", "Heroku", "cloud", "https://mcp.heroku.com/mcp", "https://devcenter.heroku.com/articles/heroku-mcp-server", "cloud"),
    remote("render", "Render", "cloud", "https://mcp.render.com/mcp", "https://render.com/docs/mcp-server", "cloud",
           auth="fields", fields=BEARER_KEY),
    remote("railway", "Railway", "cloud", "https://mcp.railway.com/mcp", "https://railway.com", "cloud"),
    remote("digitalocean", "DigitalOcean", "cloud", "https://apps.mcp.digitalocean.com/mcp", "https://docs.digitalocean.com/reference/mcp/", "cloud"),

    # documents & productivity
    remote("notion", "Notion", "docs", "https://mcp.notion.com/mcp", "https://developers.notion.com/docs/mcp", "docs"),
    remote("confluence", "Confluence", "docs", "https://mcp.atlassian.com/v1/mcp", "https://www.atlassian.com/platform/remote-mcp-server", "docs"),
    remote("dropbox", "Dropbox", "docs", "https://mcp.dropbox.com/mcp", "https://www.dropbox.com/developers", "docs"),
    remote("airtable", "Airtable", "docs", "https://mcp.airtable.com/mcp", "https://support.airtable.com/docs/using-the-airtable-mcp-server", "docs"),
    remote("coda", "Coda", "docs", "https://coda.io/apis/mcp", "https://coda.io/developers", "docs"),
    remote("readwise", "Readwise", "docs", "https://mcp2.readwise.io/mcp", "https://readwise.io/mcp", "docs"),
    remote("todoist", "Todoist", "tasks", "https://ai.todoist.net/mcp", "https://developer.todoist.com", "tasks"),

    # tasks & project tracking
    remote("linear", "Linear", "tasks", "https://mcp.linear.app/mcp", "https://linear.app/docs/mcp", "tasks"),
    remote("clickup", "ClickUp", "tasks", "https://mcp.clickup.com/mcp", "https://clickup.com", "tasks"),
    remote("monday", "monday.com", "tasks", "https://mcp.monday.com/mcp", "https://developer.monday.com/apps/docs/mondaycom-mcp-integration", "tasks"),
    remote("shortcut", "Shortcut", "tasks", "https://mcp.shortcut.com/mcp", "https://developer.shortcut.com", "tasks"),
    remote("basecamp", "Basecamp", "tasks", "https://mcp.basecamp.com/mcp", "https://basecamp.com", "tasks"),

    # communication & email
    remote("intercom", "Intercom", "chat", "https://mcp.intercom.com/mcp", "https://developers.intercom.com/docs/guides/mcp", "chat"),
    remote("resend", "Resend", "email", "https://mcp.resend.com/mcp", "https://resend.com/docs/knowledge-base/mcp-server", "email"),
    remote("mailchimp", "Mailchimp", "email", "https://mcp.mailchimp.com/mcp", "https://mailchimp.com/developer/", "email"),
    remote("klaviyo", "Klaviyo", "email", "https://mcp.klaviyo.com/mcp", "https://developers.klaviyo.com/en/docs/klaviyo_mcp_server", "email"),
    remote("customerio", "Customer.io", "email", "https://mcp.customer.io/mcp", "https://docs.customer.io/ai/mcp-server/", "email"),

    # design & collaboration
    remote("figma", "Figma", "design", "https://mcp.figma.com/mcp", "https://developers.figma.com/docs/figma-mcp-server/", "design"),
    remote("canva", "Canva", "design", "https://mcp.canva.com/mcp", "https://www.canva.com", "design"),
    remote("miro", "Miro", "design", "https://mcp.miro.com/", "https://developers.miro.com/docs/miro-mcp", "design"),
    remote("lucid", "Lucid", "design", "https://mcp.lucid.app/mcp", "https://help.lucid.co", "design"),

    # CRM
    remote("salesforce", "Salesforce", "crm", "https://api.salesforce.com/platform/mcp/v1-beta.2/platform/sobject-all",
           "https://developer.salesforce.com", "crm"),
    remote("close", "Close", "crm", "https://mcp.close.com/mcp", "https://www.close.com", "crm"),

    # payments
    remote("stripe", "Stripe", "payments", "https://mcp.stripe.com", "https://docs.stripe.com/mcp", "payments"),
    remote("square", "Square", "payments", "https://mcp.squareup.com/mcp", "https://developer.squareup.com/docs/mcp", "payments"),
    remote("paypal", "PayPal", "payments", "https://mcp.paypal.com/mcp", "https://developer.paypal.com/tools/mcp-server/", "payments"),

    # media & maps
    remote("cloudinary", "Cloudinary", "media", "https://asset-management.mcp.cloudinary.com/mcp", "https://cloudinary.com/documentation/cloudinary_llm_mcp", "media"),
    remote("mux", "Mux", "media", "https://mcp.mux.com/mcp", "https://www.mux.com/docs/integrations/mcp-server", "media"),
    remote("mapbox", "Mapbox", "maps", "https://mcp.mapbox.com/mcp", "https://docs.mapbox.com/api/guides/mcp-server/", "maps"),
]


def build(entry):
    (sid, name, category, transport, auth, ep, fields, source, sumkey, pkg) = entry
    p = {
        "id": sid,
        "name": name,
        "summary": summary(sumkey),
        "category": category,
        "transport": transport,
        "auth": auth,
        "officialSource": source,
    }
    if transport == "stdio":
        p["command"] = list(ep)
        if pkg is not None:
            eco, pname, ver, psrc = pkg
            p["packageSpec"] = {"ecosystem": eco, "name": pname, "version": ver, "source": psrc}
    else:
        p["endpoint"] = ep
    if fields:
        p["fields"] = [
            {"id": fid, "label": label(lkey), "secret": sec, "required": req, "target": tgt, "name": nm}
            for (fid, sec, req, tgt, nm, lkey) in fields
        ]
    return p


def generate():
    presets = [build(e) for e in SERVICES]
    ids = [p["id"] for p in presets]
    assert len(ids) == len(set(ids)), "duplicate preset ids"
    data = {"formatVersion": 1, "presets": presets}
    return json.dumps(data, ensure_ascii=False, indent=1) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    text = generate()
    if a.check:
        if not os.path.isfile(ASSET) or open(ASSET, encoding="utf-8").read() != text:
            print("catalog.json differs from the generator; run tools/mcp/build_catalog.py", file=sys.stderr)
            sys.exit(1)
        print(f"ok {len(SERVICES)} presets")
        return
    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    open(ASSET, "w", encoding="utf-8").write(text)
    print(f"wrote {len(SERVICES)} presets to {os.path.relpath(ASSET, ROOT)}")


if __name__ == "__main__":
    main()
