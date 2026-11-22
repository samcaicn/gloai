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
# A service tuple is (id, name, category, transport, auth, endpoint|command, fields, official_source,
# summary_key, package_spec). fields are (id, secret, required, target, name, label_key); the name
# holds the header / env-var name, empty for bearer/url/argv. package_spec is (ecosystem, name,
# version, source) and only for stdio.

def remote(sid, name, category, endpoint, source, sumkey, auth="oauth", transport="streamable-http", fields=()):
    return (sid, name, category, transport, auth, endpoint, list(fields), source, sumkey, None)


def api(sid, name, category, endpoint, source, sumkey, fields=None, transport="streamable-http"):
    f = list(fields) if fields is not None else [("api_key", True, True, "bearer", "", "api_key")]
    return (sid, name, category, transport, "fields", endpoint, f, source, sumkey, None)


def stdio(sid, name, category, binname, source, sumkey, eco, pkg, ver, auth="none", fields=(), args=()):
    return (sid, name, category, "stdio", auth, [binname, *args], list(fields), source, sumkey, (eco, pkg, ver, source))


# Auth / field shorthands
BEARER_KEY = [("api_key", True, True, "bearer", "", "api_key")]
TENANT_URL = [("tenant", False, True, "url", "", "tenant")]

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
    stdio("github", "GitHub", "git", "mcp-server-github",
          "https://www.npmjs.com/package/@modelcontextprotocol/server-github", "git",
          "npm", "@modelcontextprotocol/server-github", "2025.4.8", auth="fields",
          fields=[("token", True, True, "env", "GITHUB_PERSONAL_ACCESS_TOKEN", "token")]),
    stdio("notion", "Notion", "docs", "notion-mcp-server",
          "https://www.npmjs.com/package/@notionhq/notion-mcp-server", "docs",
          "npm", "@notionhq/notion-mcp-server", "2.6.1", auth="fields",
          fields=[("token", True, True, "env", "NOTION_TOKEN", "token")]),
    stdio("sentry", "Sentry", "analytics", "mcp-server",
          "https://www.npmjs.com/package/@sentry/mcp-server", "analytics",
          "npm", "@sentry/mcp-server", "0.50.0", auth="fields",
          fields=[("token", True, True, "env", "SENTRY_TOKEN", "token")]),
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

    # ===== AI / large language models =====
    api("openai", "OpenAI", "llm", "https://api.openai.com/v1", "https://platform.openai.com", "llm"),
    api("anthropic", "Anthropic", "llm", "https://api.anthropic.com", "https://www.anthropic.com", "llm",
        fields=[("api_key", True, True, "header", "x-api-key", "api_key")]),
    api("google-gemini", "Google Gemini", "llm", "https://generativelanguage.googleapis.com", "https://ai.google.dev", "llm"),
    api("mistral", "Mistral AI", "llm", "https://api.mistral.ai", "https://mistral.ai", "llm"),
    api("cohere", "Cohere", "llm", "https://api.cohere.com", "https://cohere.com", "llm"),
    api("groq", "Groq", "llm", "https://api.groq.com", "https://groq.com", "llm"),
    api("deepseek", "DeepSeek", "llm", "https://api.deepseek.com", "https://platform.deepseek.com", "llm"),
    api("xai", "xAI", "llm", "https://api.x.ai", "https://x.ai", "llm"),
    api("together", "Together AI", "llm", "https://api.together.xyz", "https://www.together.ai", "llm"),
    api("perplexity", "Perplexity", "llm", "https://api.perplexity.ai", "https://www.perplexity.ai", "llm"),
    api("fireworks", "Fireworks AI", "llm", "https://api.fireworks.ai", "https://fireworks.ai", "llm"),
    api("replicate", "Replicate", "llm", "https://api.replicate.com", "https://replicate.com", "llm"),
    api("huggingface", "Hugging Face", "llm", "https://api-inference.huggingface.co", "https://huggingface.co", "llm"),
    api("cerebras", "Cerebras", "llm", "https://api.cerebras.ai", "https://cerebras.ai", "llm"),
    api("moonshot", "Moonshot AI", "llm", "https://api.moonshot.ai", "https://platform.moonshot.cn", "llm"),
    api("zhipu", "Zhipu AI", "llm", "https://open.bigmodel.cn", "https://www.zhipuai.cn", "llm"),
    api("qwen", "Qwen", "llm", "https://dashscope.aliyuncs.com", "https://www.alibabacloud.com", "llm"),
    api("baidu-ernie", "Baidu ERNIE", "llm", "https://qianfan.baidubce.com", "https://cloud.baidu.com", "llm"),
    api("minimax", "MiniMax", "llm", "https://api.minimax.chat", "https://www.minimaxi.com", "llm"),
    remote("vertex-ai", "Google Vertex AI", "llm", "https://aiplatform.googleapis.com", "https://cloud.google.com/vertex-ai", "llm"),
    remote("bedrock", "Amazon Bedrock", "llm", "https://bedrock.amazonaws.com", "https://aws.amazon.com/bedrock", "llm"),

    # ===== web search =====
    api("brave", "Brave Search API", "search", "https://api.search.brave.com", "https://brave.com/search/api", "search",
        fields=[("api_key", True, True, "header", "X-Subscription-Token", "api_key")]),
    api("tavily", "Tavily", "search", "https://api.tavily.com", "https://tavily.com", "search"),
    api("exa", "Exa", "search", "https://api.exa.ai", "https://exa.ai", "search"),
    api("serper", "Serper", "search", "https://google.serper.dev", "https://serper.dev", "search"),
    api("serpapi", "SerpAPI", "search", "https://serpapi.com", "https://serpapi.com", "search"),
    api("google-custom-search", "Google Custom Search", "search", "https://customsearch.googleapis.com", "https://developers.google.com/custom-search", "search"),
    api("bing", "Bing Search", "search", "https://api.bing.microsoft.com", "https://www.microsoft.com/bing/apis", "search",
        fields=[("api_key", True, True, "header", "Ocp-Apim-Subscription-Key", "api_key")]),
    api("you", "You.com", "search", "https://api.you.com", "https://you.com", "search"),
    api("kagi", "Kagi", "search", "https://kagi.com/api", "https://kagi.com", "search"),
    remote("searxng", "SearXNG", "search", "https://{{base_url}}", "https://docs.searxng.org", "search", auth="fields",
           fields=[("base_url", False, True, "url", "", "base_url")]),

    # ===== databases & data stores =====
    remote("mongodb-atlas", "MongoDB Atlas", "database", "https://cloud.mongodb.com", "https://www.mongodb.com/atlas", "database"),
    api("neon", "Neon", "database", "https://console.neon.tech", "https://neon.tech", "database"),
    api("supabase", "Supabase", "database", "https://api.supabase.com", "https://supabase.com", "database",
        fields=[("api_key", True, True, "bearer", "", "api_key"), ("project", False, True, "header", "x-supabase-project", "project")]),
    api("planetscale", "PlanetScale", "database", "https://api.planetscale.com", "https://planetscale.com", "database"),
    api("upstash", "Upstash", "database", "https://api.upstash.com", "https://upstash.com", "database"),
    api("clickhouse", "ClickHouse", "database", "https://api.clickhouse.cloud", "https://clickhouse.com", "database"),
    api("snowflake", "Snowflake", "database", "https://api.snowflake.com", "https://www.snowflake.com", "database",
        fields=[("api_key", True, True, "bearer", "", "api_key"), ("workspace", False, True, "header", "x-snowflake-account", "workspace")]),
    remote("bigquery", "Google BigQuery", "database", "https://bigquery.googleapis.com", "https://cloud.google.com/bigquery", "database"),
    remote("databricks", "Databricks", "database", "https://api.databricks.com", "https://www.databricks.com", "database"),
    api("pinecone", "Pinecone", "vector", "https://api.pinecone.io", "https://www.pinecone.io", "vector"),
    api("weaviate", "Weaviate", "vector", "https://api.weaviate.io", "https://weaviate.io", "vector"),
    api("qdrant", "Qdrant", "vector", "https://api.qdrant.tech", "https://qdrant.tech", "vector"),
    api("chroma", "Chroma", "vector", "https://api.trychroma.com", "https://www.trychroma.com", "vector"),
    api("zilliz", "Zilliz", "vector", "https://api.zilliz.com", "https://zilliz.com", "vector"),
    api("singlestore", "SingleStore", "database", "https://api.singlestore.com", "https://www.singlestore.com", "database"),
    api("timescale", "Timescale", "database", "https://api.timescale.com", "https://www.timescale.com", "database"),
    api("cockroachdb", "CockroachDB", "database", "https://api.cockroachlabs.cloud", "https://www.cockroachlabs.com", "database"),
    api("elasticsearch", "Elasticsearch", "database", "https://api.elastic-cloud.com", "https://www.elastic.co/elasticsearch", "database"),
    stdio("mariadb", "MariaDB", "database", "mcp-server-mariadb", "https://pypi.org/project/mcp-server-mariadb/", "database", "pypi", "mcp-server-mariadb", "0.1.2"),
    stdio("mysql", "MySQL", "database", "mcp-server-mysql", "https://pypi.org/project/mcp-server-mysql/", "database", "pypi", "mcp-server-mysql", "0.1.4"),

    # ===== developer tools, git hosting, CI/CD =====
    remote("gitlab", "GitLab", "git", "https://gitlab.com", "https://gitlab.com", "git"),
    remote("bitbucket", "Bitbucket", "git", "https://api.bitbucket.org", "https://bitbucket.org", "git"),
    remote("azure-devops", "Azure DevOps", "git", "https://dev.azure.com", "https://azure.microsoft.com/products/devops", "git"),
    remote("gitee", "Gitee", "git", "https://gitee.com", "https://gitee.com", "git"),
    stdio("docker", "Docker", "ci", "mcp-server-docker", "https://pypi.org/project/mcp-server-docker/", "ci", "pypi", "mcp-server-docker", "0.3.0"),
    stdio("kubernetes", "Kubernetes", "cloud", "mcp-server-kubernetes", "https://pypi.org/project/mcp-server-kubernetes/", "cloud", "pypi", "mcp-server-kubernetes", "0.1.6"),
    api("datadog", "Datadog", "analytics", "https://api.datadoghq.com", "https://www.datadoghq.com", "analytics",
        fields=[("api_key", True, True, "header", "DD-API-KEY", "api_key")]),
    remote("grafana", "Grafana", "analytics", "https://grafana.com/api", "https://grafana.com", "analytics"),
    api("newrelic", "New Relic", "analytics", "https://api.newrelic.com", "https://newrelic.com", "analytics",
        fields=[("api_key", True, True, "header", "Api-Key", "api_key")]),
    api("circleci", "CircleCI", "ci", "https://circleci.com/api", "https://circleci.com", "ci"),
    api("buildkite", "Buildkite", "ci", "https://api.buildkite.com", "https://buildkite.com", "ci"),
    api("sonarqube", "SonarQube", "code", "https://sonarcloud.io", "https://www.sonarsource.com", "code"),
    api("codeclimate", "Code Climate", "code", "https://api.codeclimate.com", "https://codeclimate.com", "code"),
    api("coveralls", "Coveralls", "code", "https://coveralls.io", "https://coveralls.io", "code"),
    remote("npm-registry", "npm Registry", "ci", "https://registry.npmjs.org", "https://www.npmjs.com", "ci"),
    remote("pypi", "PyPI", "ci", "https://pypi.org", "https://pypi.org", "ci"),
    remote("vercel", "Vercel", "cloud", "https://api.vercel.com", "https://vercel.com", "cloud"),
    remote("netlify", "Netlify", "cloud", "https://api.netlify.com", "https://www.netlify.com", "cloud"),
    api("cloudflare", "Cloudflare", "cloud", "https://api.cloudflare.com", "https://www.cloudflare.com", "cloud",
        fields=[("api_key", True, True, "bearer", "", "token")]),
    remote("heroku", "Heroku", "cloud", "https://api.heroku.com", "https://www.heroku.com", "cloud"),
    api("render", "Render", "cloud", "https://api.render.com", "https://render.com", "cloud"),
    api("flyio", "Fly.io", "cloud", "https://api.machines.dev", "https://fly.io", "cloud"),
    api("railway", "Railway", "cloud", "https://backboard.railway.app", "https://railway.app", "cloud"),
    api("snyk", "Snyk", "security", "https://api.snyk.io", "https://snyk.io", "security"),

    # ===== documents & productivity =====
    remote("google-drive", "Google Drive", "docs", "https://www.googleapis.com/drive", "https://developers.google.com/drive", "docs"),
    remote("google-docs", "Google Docs", "docs", "https://docs.googleapis.com", "https://developers.google.com/docs", "docs"),
    remote("google-sheets", "Google Sheets", "docs", "https://sheets.googleapis.com", "https://developers.google.com/sheets", "docs"),
    remote("google-calendar", "Google Calendar", "calendar", "https://www.googleapis.com/calendar", "https://developers.google.com/calendar", "calendar"),
    remote("gmail", "Gmail", "email", "https://gmail.googleapis.com", "https://developers.google.com/gmail", "email"),
    remote("confluence", "Confluence", "docs", "https://{{tenant}}.atlassian.net", "https://www.atlassian.com/software/confluence", "docs", fields=TENANT_URL),
    remote("dropbox", "Dropbox", "docs", "https://api.dropboxapi.com", "https://www.dropbox.com", "docs"),
    remote("box", "Box", "docs", "https://api.box.com", "https://www.box.com", "docs"),
    remote("onedrive", "OneDrive", "docs", "https://graph.microsoft.com", "https://www.microsoft.com/microsoft-365/onedrive", "docs"),
    api("airtable", "Airtable", "docs", "https://api.airtable.com", "https://airtable.com", "docs"),
    api("coda", "Coda", "docs", "https://coda.io/apis", "https://coda.io", "docs"),
    remote("readwise", "Readwise", "docs", "https://readwise.io/api", "https://readwise.io", "docs", auth="fields",
           fields=BEARER_KEY),
    remote("pocket", "Pocket", "docs", "https://getpocket.com", "https://getpocket.com", "docs"),
    remote("todoist", "Todoist", "tasks", "https://api.todoist.com", "https://todoist.com", "tasks"),

    # ===== tasks & project tracking =====
    remote("linear", "Linear", "tasks", "https://api.linear.app", "https://linear.app", "tasks"),
    remote("asana", "Asana", "tasks", "https://app.asana.com/api", "https://asana.com", "tasks"),
    remote("trello", "Trello", "tasks", "https://api.trello.com", "https://trello.com", "tasks"),
    api("clickup", "ClickUp", "tasks", "https://api.clickup.com", "https://clickup.com", "tasks"),
    api("monday", "monday.com", "tasks", "https://api.monday.com", "https://monday.com", "tasks"),
    api("shortcut", "Shortcut", "tasks", "https://api.app.shortcut.com", "https://www.shortcut.com", "tasks"),
    remote("basecamp", "Basecamp", "tasks", "https://3.basecamp.com", "https://basecamp.com", "tasks"),
    api("height", "Height", "tasks", "https://api.height.app", "https://height.app", "tasks"),
    remote("wrike", "Wrike", "tasks", "https://www.wrike.com/api/v4", "https://developers.wrike.com", "tasks"),
    remote("youtrack", "YouTrack", "tasks", "https://{{tenant}}.myjetbrains.com", "https://www.jetbrains.com/youtrack", "tasks", auth="fields",
           fields=[("tenant", False, True, "url", "", "tenant"), ("token", True, True, "bearer", "", "token")]),
    remote("ticktick", "TickTick", "tasks", "https://api.ticktick.com", "https://developer.ticktick.com", "tasks"),

    # ===== communication, chat & email =====
    remote("discord", "Discord", "chat", "https://discord.com", "https://discord.com", "chat"),
    api("telegram", "Telegram", "chat", "https://api.telegram.org", "https://core.telegram.org", "chat"),
    api("whatsapp", "WhatsApp", "chat", "https://graph.facebook.com", "https://developers.facebook.com/docs/whatsapp", "chat"),
    remote("msteams", "Microsoft Teams", "chat", "https://graph.microsoft.com", "https://www.microsoft.com/microsoft-teams", "chat"),
    remote("zoom", "Zoom", "chat", "https://api.zoom.us", "https://zoom.us", "chat"),
    remote("webex", "Webex", "chat", "https://webexapis.com", "https://www.webex.com", "chat"),
    api("intercom", "Intercom", "chat", "https://api.intercom.io", "https://www.intercom.com", "chat"),
    remote("zendesk", "Zendesk", "chat", "https://{{tenant}}.zendesk.com", "https://www.zendesk.com", "chat", fields=TENANT_URL),
    api("sendgrid", "SendGrid", "email", "https://api.sendgrid.com", "https://sendgrid.com", "email"),
    api("mailgun", "Mailgun", "email", "https://api.mailgun.net", "https://www.mailgun.com", "email"),
    api("postmark", "Postmark", "email", "https://api.postmarkapp.com", "https://postmarkapp.com", "email"),
    api("resend", "Resend", "email", "https://api.resend.com", "https://resend.com", "email"),
    remote("outlook", "Outlook", "email", "https://graph.microsoft.com", "https://www.microsoft.com/outlook", "email"),

    # ===== design & collaboration =====
    remote("figma", "Figma", "design", "https://api.figma.com", "https://www.figma.com", "design"),
    remote("canva", "Canva", "design", "https://api.canva.com", "https://www.canva.com", "design"),
    remote("miro", "Miro", "design", "https://api.miro.com", "https://miro.com", "design"),
    remote("lucid", "Lucid", "design", "https://api.lucid.co", "https://lucid.co", "design"),
    remote("zeplin", "Zeplin", "design", "https://api.zeplin.io", "https://zeplin.io", "design"),

    # ===== cloud platforms =====
    remote("google-cloud", "Google Cloud", "cloud", "https://cloudresourcemanager.googleapis.com", "https://cloud.google.com", "cloud"),
    remote("azure", "Microsoft Azure", "cloud", "https://management.azure.com", "https://azure.microsoft.com", "cloud"),
    api("digitalocean", "DigitalOcean", "cloud", "https://api.digitalocean.com", "https://www.digitalocean.com", "cloud"),
    api("linode", "Linode", "cloud", "https://api.linode.com", "https://www.linode.com", "cloud"),
    api("aliyun", "Alibaba Cloud", "cloud", "https://api.aliyun.com", "https://www.alibabacloud.com", "cloud"),
    api("tencentcloud", "Tencent Cloud", "cloud", "https://api.tencentcloudapi.com", "https://www.tencentcloud.com", "cloud"),
    remote("ibmcloud", "IBM Cloud", "cloud", "https://cloud.ibm.com", "https://www.ibm.com/cloud", "cloud"),
    remote("oraclecloud", "Oracle Cloud", "cloud", "https://api.oraclecloud.com", "https://www.oracle.com/cloud", "cloud"),
    api("scaleway", "Scaleway", "cloud", "https://api.scaleway.com", "https://www.scaleway.com", "cloud"),
    api("vultr", "Vultr", "cloud", "https://api.vultr.com", "https://www.vultr.com", "cloud"),
    api("hetzner", "Hetzner", "cloud", "https://api.hetzner.cloud", "https://www.hetzner.com/cloud", "cloud"),
    remote("ovh", "OVHcloud", "cloud", "https://api.ovh.com", "https://www.ovhcloud.com", "cloud"),
    api("bunny", "Bunny.net", "cloud", "https://api.bunny.net", "https://bunny.net", "cloud"),
    api("backblaze", "Backblaze B2", "cloud", "https://api.backblazeb2.com", "https://www.backblaze.com", "cloud"),
    api("wasabi", "Wasabi", "cloud", "https://s3.wasabisys.com", "https://wasabi.com", "cloud"),

    # ===== CRM & marketing =====
    remote("salesforce", "Salesforce", "crm", "https://{{tenant}}.my.salesforce.com", "https://www.salesforce.com", "crm", fields=TENANT_URL),
    remote("hubspot", "HubSpot", "crm", "https://api.hubapi.com", "https://www.hubspot.com", "crm"),
    remote("pipedrive", "Pipedrive", "crm", "https://api.pipedrive.com", "https://www.pipedrive.com", "crm"),
    remote("zoho", "Zoho CRM", "crm", "https://www.zohoapis.com", "https://www.zoho.com/crm", "crm"),
    api("close", "Close", "crm", "https://api.close.com", "https://www.close.com", "crm"),
    api("copper", "Copper", "crm", "https://api.copper.com", "https://www.copper.com", "crm"),
    remote("mailchimp", "Mailchimp", "email", "https://us1.api.mailchimp.com", "https://mailchimp.com", "email"),
    api("klaviyo", "Klaviyo", "email", "https://a.klaviyo.com", "https://www.klaviyo.com", "email"),
    remote("marketo", "Marketo", "email", "https://{{tenant}}.mktorest.com", "https://business.adobe.com/products/marketo", "email", fields=TENANT_URL),
    api("customerio", "Customer.io", "email", "https://api.customer.io", "https://customer.io", "email"),
    remote("activecampaign", "ActiveCampaign", "email", "https://{{tenant}}.api-us1.com", "https://www.activecampaign.com", "email", auth="fields",
           fields=[("tenant", False, True, "url", "", "tenant"), ("api_key", True, True, "header", "Api-Token", "api_key")]),
    api("drip", "Drip", "email", "https://api.getdrip.com", "https://www.drip.com", "email"),
    api("convertkit", "ConvertKit", "email", "https://api.convertkit.com", "https://convertkit.com", "email"),
    api("brevo", "Brevo", "email", "https://api.brevo.com", "https://www.brevo.com", "email"),

    # ===== finance & payments =====
    remote("stripe", "Stripe", "payments", "https://api.stripe.com", "https://stripe.com", "payments"),
    remote("square", "Square", "payments", "https://connect.squareup.com", "https://squareup.com", "payments"),
    remote("paypal", "PayPal", "payments", "https://api-m.paypal.com", "https://www.paypal.com", "payments"),
    api("plaid", "Plaid", "payments", "https://production.plaid.com", "https://plaid.com", "payments"),
    remote("wise", "Wise", "payments", "https://api.wise.com", "https://wise.com", "payments"),
    remote("braintree", "Braintree", "payments", "https://api.braintreegateway.com", "https://www.braintreepayments.com", "payments"),
    api("adyen", "Adyen", "payments", "https://api.adyen.com", "https://www.adyen.com", "payments",
        fields=[("api_key", True, True, "header", "x-api-key", "api_key")]),
    remote("shopify", "Shopify", "payments", "https://{{tenant}}.myshopify.com", "https://www.shopify.com", "payments", fields=TENANT_URL),
    remote("quickbooks", "QuickBooks", "payments", "https://quickbooks.api.intuit.com", "https://quickbooks.intuit.com", "payments"),
    remote("xero", "Xero", "payments", "https://api.xero.com", "https://www.xero.com", "payments"),
    remote("freshbooks", "FreshBooks", "payments", "https://api.freshbooks.com", "https://www.freshbooks.com", "payments"),
    remote("wave", "Wave", "payments", "https://www.waveapps.com", "https://www.waveapps.com", "payments"),
    remote("coinbase", "Coinbase", "payments", "https://api.coinbase.com", "https://www.coinbase.com", "payments"),
    api("binance", "Binance", "payments", "https://api.binance.com", "https://www.binance.com", "payments"),
    api("kraken", "Kraken", "payments", "https://api.kraken.com", "https://www.kraken.com", "payments"),

    # ===== security =====
    api("veracode", "Veracode", "security", "https://api.veracode.com", "https://www.veracode.com", "security"),
    remote("checkmarx", "Checkmarx", "security", "https://ast.checkmarx.net", "https://checkmarx.com", "security"),
    api("tenable", "Tenable", "security", "https://cloud.tenable.com", "https://www.tenable.com", "security",
        fields=[("api_key", True, True, "header", "x-apikeys", "api_key")]),
    remote("crowdstrike", "CrowdStrike", "security", "https://api.crowdstrike.com", "https://www.crowdstrike.com", "security"),
    api("wiz", "Wiz", "security", "https://api.wiz.io", "https://www.wiz.io", "security"),
    api("lacework", "Lacework", "security", "https://api.lacework.net", "https://www.lacework.com", "security"),
    api("orca", "Orca Security", "security", "https://api.orcasecurity.io", "https://orca.security", "security"),
    api("aqua", "Aqua Security", "security", "https://api.aquasec.com", "https://www.aquasec.com", "security"),
    api("detectify", "Detectify", "security", "https://api.detectify.com", "https://detectify.com", "security"),
    api("hackerone", "HackerOne", "security", "https://api.hackerone.com", "https://www.hackerone.com", "security"),
    api("intruder", "Intruder", "security", "https://api.intruder.io", "https://www.intruder.io", "security"),

    # ===== media & images =====
    api("cloudinary", "Cloudinary", "media", "https://api.cloudinary.com", "https://cloudinary.com", "media"),
    api("unsplash", "Unsplash", "media", "https://api.unsplash.com", "https://unsplash.com", "media"),
    remote("getty", "Getty Images", "media", "https://api.gettyimages.com", "https://www.gettyimages.com", "media"),
    remote("shutterstock", "Shutterstock", "media", "https://api.shutterstock.com", "https://www.shutterstock.com", "media"),
    api("elevenlabs", "ElevenLabs", "media", "https://api.elevenlabs.io", "https://elevenlabs.io", "media",
        fields=[("api_key", True, True, "header", "xi-api-key", "api_key")]),
    api("imgix", "imgix", "media", "https://api.imgix.com", "https://imgix.com", "media"),
    api("mux", "Mux", "media", "https://api.mux.com", "https://www.mux.com", "media"),
    api("pexels", "Pexels", "media", "https://api.pexels.com", "https://www.pexels.com", "media"),
    api("giphy", "GIPHY", "media", "https://api.giphy.com", "https://giphy.com", "media"),
    remote("youtube", "YouTube", "media", "https://www.googleapis.com/youtube", "https://developers.google.com/youtube", "media"),
    remote("spotify", "Spotify", "media", "https://api.spotify.com", "https://developer.spotify.com", "media"),
    remote("deezer", "Deezer", "media", "https://api.deezer.com", "https://developers.deezer.com", "media"),

    # ===== maps & location =====
    api("mapbox", "Mapbox", "maps", "https://api.mapbox.com", "https://www.mapbox.com", "maps"),
    api("here", "HERE", "maps", "https://api.here.com", "https://www.here.com", "maps"),
    api("tomtom", "TomTom", "maps", "https://api.tomtom.com", "https://developer.tomtom.com", "maps"),
    remote("openstreetmap", "OpenStreetMap", "maps", "https://api.openstreetmap.org", "https://www.openstreetmap.org", "maps"),
    api("maptiler", "MapTiler", "maps", "https://api.maptiler.com", "https://www.maptiler.com", "maps"),
    api("locationiq", "LocationIQ", "maps", "https://api.locationiq.com", "https://locationiq.com", "maps"),
    api("geoapify", "Geoapify", "maps", "https://api.geoapify.com", "https://www.geoapify.com", "maps"),
    remote("foursquare", "Foursquare", "maps", "https://api.foursquare.com", "https://foursquare.com", "maps"),
    api("radar", "Radar", "maps", "https://api.radar.io", "https://radar.com", "maps"),
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
