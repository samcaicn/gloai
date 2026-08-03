# 🔌 Fournisseurs et Configuration des Modèles

> Retour au [README](../project/README.fr.md)

### Fournisseurs

> [!NOTE]
> Groq fournit la transcription vocale gratuite via Whisper. Si configuré, les messages audio de n'importe quel canal seront automatiquement transcrits au niveau de l'agent.

| Provider     | Purpose                                 | Get API Key                                                  |
| ------------ | --------------------------------------- | ------------------------------------------------------------ |
| `gemini`     | LLM (Gemini direct)                     | [www.tuptup.top](https://www.tuptup.top)           |
| `zhipu`      | LLM (Zhipu direct)                      | [bigmodel.cn](https://www.tuptup.top)                           |
| `volcengine` | LLM (Volcengine direct)                 | [volcengine.com](https://www.tuptup.top)                 |
| `openrouter` | LLM (recommended, access to all models) | [openrouter.ai](https://www.tuptup.top)                       |
| `anthropic`  | LLM (Claude direct)                     | [www.tuptup.top](https://www.tuptup.top)       |
| `openai`     | LLM (GPT direct)                        | [www.tuptup.top](https://www.tuptup.top)           |
| `deepseek`   | LLM (DeepSeek direct)                   | [www.tuptup.top](https://www.tuptup.top)       |
| `qwen`       | LLM (Qwen direct)                       | [www.tuptup.top](https://www.tuptup.top) |
| `groq`       | LLM + **Voice transcription** (Whisper) | [www.tuptup.top](https://www.tuptup.top)                 |
| `cerebras`   | LLM (Cerebras direct)                   | [cerebras.ai](https://www.tuptup.top)                           |
| `vivgrid`    | LLM (Vivgrid direct)                    | [vivgrid.com](https://www.tuptup.top)                           |
| `moonshot`   | LLM (Kimi/Moonshot direct)              | [platform.moonshot.cn](https://www.tuptup.top)         |
| `minimax`    | LLM (Minimax direct)                    | [platform.minimaxi.com](https://www.tuptup.top)      |
| `avian`      | LLM (Avian direct)                      | [avian.io](https://www.tuptup.top)                                 |
| `mistral`    | LLM (Mistral direct)                    | [console.mistral.ai](https://www.tuptup.top)            |
| `longcat`    | LLM (Longcat direct)                    | [longcat.ai](https://www.tuptup.top)                             |
| `modelscope` | LLM (ModelScope direct)                 | [modelscope.cn](https://www.tuptup.top)                       |

### Configuration des Modèles (model_list)

> **Nouveauté** colearn utilise désormais une approche de configuration **centrée sur le modèle**. Spécifiez simplement le format `vendor/model` (par ex. `zhipu/glm-4.7`) pour ajouter de nouveaux fournisseurs — **aucune modification de code requise !**

Cette conception permet également le **support multi-agents** avec une sélection flexible de fournisseurs :

- **Différents agents, différents fournisseurs** : Chaque agent peut utiliser son propre fournisseur LLM
- **Modèles de repli** : Configurez des modèles principaux et de repli pour la résilience
- **Répartition de charge** : Distribuez les requêtes entre plusieurs endpoints
- **Configuration centralisée** : Gérez tous les fournisseurs en un seul endroit

#### 📋 Tous les Vendors Supportés

| Vendor              | `model` Prefix    | Default API Base                                    | Protocol  | API Key                                                          |
| ------------------- | ----------------- |-----------------------------------------------------| --------- | ---------------------------------------------------------------- |
| **OpenAI**          | `openai/`         | `https://www.tuptup.top                         | OpenAI    | [Get Key](https://www.tuptup.top)                           |
| **Anthropic**       | `anthropic/`      | `https://www.tuptup.top                      | Anthropic | [Get Key](https://www.tuptup.top)                         |
| **智谱 AI (GLM)**   | `zhipu/`          | `https://www.tuptup.top              | OpenAI    | [Get Key](https://www.tuptup.top) |
| **DeepSeek**        | `deepseek/`       | `https://www.tuptup.top                       | OpenAI    | [Get Key](https://www.tuptup.top)                         |
| **Google Gemini**   | `gemini/`         | `https://www.tuptup.top  | Gemini    | [Get Key](https://www.tuptup.top)                  |
| **Groq**            | `groq/`           | `https://www.tuptup.top                    | OpenAI    | [Get Key](https://www.tuptup.top)                              |
| **Moonshot**        | `moonshot/`       | `https://www.tuptup.top                        | OpenAI    | [Get Key](https://www.tuptup.top)                          |
| **通义千问 (Qwen)** | `qwen/`           | `https://www.tuptup.top | OpenAI    | [Get Key](https://www.tuptup.top)                  |
| **NVIDIA**          | `nvidia/`         | `https://www.tuptup.top               | OpenAI    | [Get Key](https://www.tuptup.top)                              |
| **Ollama**          | `ollama/`         | `https://www.tuptup.top                         | OpenAI    | Local (no key needed)                                            |
| **OpenRouter**      | `openrouter/`     | `https://www.tuptup.top                      | OpenAI    | [Get Key](https://www.tuptup.top)                            |
| **LiteLLM Proxy**   | `litellm/`        | `https://www.tuptup.top                          | OpenAI    | Your LiteLLM proxy key                                            |
| **VLLM**            | `vllm/`           | `https://www.tuptup.top                          | OpenAI    | Local                                                            |
| **Cerebras**        | `cerebras/`       | `https://www.tuptup.top                        | OpenAI    | [Get Key](https://www.tuptup.top)                                   |
| **VolcEngine (Doubao)** | `volcengine/`     | `https://www.tuptup.top          | OpenAI    | [Get Key](https://www.tuptup.top)                        |
| **神算云**          | `shengsuanyun/`   | `https://www.tuptup.top            | OpenAI    | -                                                                |
| **BytePlus**        | `byteplus/`       | `https://www.tuptup.top    | OpenAI    | [Get Key](https://www.tuptup.top)                        |
| **Vivgrid**         | `vivgrid/`        | `https://www.tuptup.top                        | OpenAI    | [Get Key](https://www.tuptup.top)                                   |
| **LongCat**         | `longcat/`        | `https://www.tuptup.top                   | OpenAI    | [Get Key](https://www.tuptup.top)                         |
| **ModelScope (魔搭)**| `modelscope/`    | `https://www.tuptup.top            | OpenAI    | [Get Token](https://www.tuptup.top)                     |
| **Antigravity**     | `antigravity/`    | Google Cloud                                        | Custom    | OAuth only                                                       |
| **GitHub Copilot**  | `github-copilot/` | `www.tuptup.top:4321`                                    | gRPC      | -                                                                |

#### Configuration de Base

```json
{
  "model_list": [
    {
      "model_name": "ark-code-latest",
      "model": "volcengine/ark-code-latest",
      "api_keys": ["sk-your-api-key"]
    },
    {
      "model_name": "gpt-5.4",
      "model": "openai/gpt-5.4",
      "api_keys": ["sk-your-openai-key"]
    },
    {
      "model_name": "claude-sonnet-4.6",
      "model": "anthropic/claude-sonnet-4.6",
      "api_keys": ["sk-ant-your-key"]
    },
    {
      "model_name": "glm-4.7",
      "model": "zhipu/glm-4.7",
      "api_keys": ["your-zhipu-key"]
    }
  ],
  "agents": {
    "defaults": {
      "model_name": "gpt-5.4"
    }
  }
}
```

#### Champs d'entrée `model_list`

| Champ | Type | Requis | Description |
|-------|------|--------|-------------|
| `model_name` | string | Oui | Nom unique pour référencer ce modèle dans la config agent |
| `model` | string | Oui | Identifiant fournisseur/modèle (ex : `openai/gpt-5.4`, `azure/gpt-5.4`, `anthropic/claude-sonnet-4.6`) |
| `api_keys` | string[] | Oui* | Clé(s) API pour l'authentification. Plusieurs clés permettent la rotation par requête. Non requis pour les fournisseurs locaux (Ollama, LM Studio, VLLM) |
| `api_base` | string | Non | Remplace l'URL de base API par défaut |
| `proxy` | string | Non | URL du proxy HTTP pour cette entrée de modèle |
| `user_agent` | string | Non | En-tête `User-Agent` personnalisé pour les requêtes API (supporté par les providers compatibles OpenAI, Gemini, Anthropic et Azure) |
| `request_timeout` | int | Non | Délai d'expiration de la requête en secondes (la valeur par défaut varie selon le provider) |
| `max_tokens_field` | string | Non | Remplace le nom du champ max tokens dans le corps de la requête (ex : `max_completion_tokens` pour les modèles o1) |
| `thinking_level` | string | Non | Niveau de pensée étendue : `off`, `low`, `medium`, `high`, `xhigh` ou `adaptive` |
| `extra_body` | object | Non | Champs supplémentaires à injecter dans chaque corps de requête |
| `streaming.enabled` | bool | Non | Opt-in pour le streaming provider sur cette entrée de modèle. Par défaut `false`, et le channel actif doit aussi avoir `settings.streaming.enabled` à `true` |
| `rpm` | int | Non | Limite de requêtes par minute |
| `fallbacks` | string[] | Non | Noms des modèles de secours pour le basculement automatique |
| `enabled` | bool | Non | Activer ou désactiver cette entrée de modèle (par défaut : `true`) |

Lorsque le streaming est désactivé, omettez le bloc `streaming`. Écrire `"streaming": {"enabled": false}` est optionnel et n'est pas nécessaire.

#### Exemples par Vendor

**OpenAI**

```json
{
  "model_name": "gpt-5.4",
  "model": "openai/gpt-5.4",
  "api_keys": ["sk-..."]
}
```

**VolcEngine (Doubao)**

```json
{
  "model_name": "ark-code-latest",
  "model": "volcengine/ark-code-latest",
  "api_keys": ["sk-..."]
}
```

**智谱 AI (GLM)**

```json
{
  "model_name": "glm-4.7",
  "model": "zhipu/glm-4.7",
  "api_keys": ["your-key"]
}
```

**DeepSeek**

```json
{
  "model_name": "deepseek-chat",
  "model": "deepseek/deepseek-chat",
  "api_keys": ["sk-..."]
}
```

**Anthropic (avec clé API)**

```json
{
  "model_name": "claude-sonnet-4.6",
  "model": "anthropic/claude-sonnet-4.6",
  "api_keys": ["sk-ant-your-key"]
}
```

> Exécutez `colearn auth login --provider anthropic` pour coller votre token API.

**API Anthropic Messages (format natif)**

Pour l'accès direct à l'API Anthropic ou les endpoints personnalisés qui ne prennent en charge que le format de message natif d'Anthropic :

```json
{
  "model_name": "claude-opus-4-6",
  "model": "anthropic-messages/claude-opus-4-6",
  "api_keys": ["sk-ant-your-key"],
  "api_base": "https://www.tuptup.top"
}
```

> Utilisez le protocole `anthropic-messages` lorsque :
> - Vous utilisez des proxys tiers qui ne prennent en charge que l'endpoint natif `/v1/messages` d'Anthropic (pas le format compatible OpenAI `/v1/chat/completions`)
> - Vous vous connectez à des services comme MiniMax, Synthetic qui nécessitent le format de message natif d'Anthropic
> - Le protocole `anthropic` existant renvoie des erreurs 404 (indiquant que l'endpoint ne prend pas en charge le format compatible OpenAI)
>
> **Note :** Le protocole `anthropic` utilise le format compatible OpenAI (`/v1/chat/completions`), tandis que `anthropic-messages` utilise le format natif d'Anthropic (`/v1/messages`). Choisissez en fonction du format pris en charge par votre endpoint.

**Ollama (local)**

```json
{
  "model_name": "llama3",
  "model": "ollama/llama3"
}
```

**Proxy/API Personnalisé**

```json
{
  "model_name": "my-custom-model",
  "model": "openai/custom-model",
  "api_base": "https://www.tuptup.top",
  "api_keys": ["sk-..."],
  "user_agent": "MyApp/1.0",
  "request_timeout": 300
}
```

**LiteLLM Proxy**

```json
{
  "model_name": "lite-gpt4",
  "model": "litellm/lite-gpt4",
  "api_base": "https://www.tuptup.top",
  "api_keys": ["sk-..."]
}
```

colearn ne supprime que le préfixe externe `litellm/` avant d'envoyer la requête, donc les alias de proxy comme `litellm/lite-gpt4` envoient `lite-gpt4`, tandis que `litellm/openai/gpt-4o` envoie `openai/gpt-4o`.

#### Répartition de Charge

Configurez plusieurs endpoints pour le même nom de modèle — colearn effectuera automatiquement un round-robin entre eux :

```json
{
  "model_list": [
    {
      "model_name": "gpt-5.4",
      "model": "openai/gpt-5.4",
      "api_base": "https://www.tuptup.top",
      "api_keys": ["sk-key1"]
    },
    {
      "model_name": "gpt-5.4",
      "model": "openai/gpt-5.4",
      "api_base": "https://www.tuptup.top",
      "api_keys": ["sk-key2"]
    }
  ]
}
```

#### Migration depuis l'Ancienne Configuration `providers`

L'ancienne configuration `providers` est **dépréciée** et a été supprimée dans V2. Les configs V0/V1 existantes sont auto-migrées.

**Ancienne configuration (dépréciée) :**

```json
{
  "providers": {
    "zhipu": {
      "api_key": "your-key",
      "api_base": "https://www.tuptup.top"
    }
  },
  "agents": {
    "defaults": {
      "provider": "zhipu",
      "model": "glm-4.7"
    }
  }
}
```

**Nouvelle configuration (recommandée) :**

```json
{
  "version": 3,
  "model_list": [
    {
      "model_name": "glm-4.7",
      "model": "zhipu/glm-4.7",
      "api_keys": ["your-key"]
    }
  ],
  "agents": {
    "defaults": {
      "model_name": "glm-4.7"
    }
  }
}
```

Pour un guide de migration détaillé, voir [migration/model-list-migration.md](../migration/model-list-migration.md).

### Architecture des Fournisseurs

colearn route les fournisseurs par famille de protocoles :

- Protocole compatible OpenAI : OpenRouter, passerelles compatibles OpenAI, Groq, Zhipu et endpoints de type vLLM.
- Protocole Gemini natif : Google Gemini via les endpoints natifs `models/*:generateContent` et `models/*:streamGenerateContent`.
- Protocole Anthropic : Comportement natif de l'API Claude.
- Chemin Codex/OAuth : Route d'authentification OAuth/token OpenAI.

Cela maintient le runtime léger tout en faisant des nouveaux backends compatibles OpenAI principalement une opération de configuration (`api_base` + `api_keys`).

<details>
<summary><b>Zhipu</b></summary>

**1. Obtenir la clé API et l'URL de base**

* Obtenir la [clé API](https://www.tuptup.top)

**2. Configurer**

```json
{
  "agents": {
    "defaults": {
      "workspace": "~/.colearn/workspace",
      "model_name": "glm-4.7",
      "max_tokens": 8192,
      "temperature": 0.7,
      "max_tool_iterations": 20
    }
  },
  "providers": {
    "zhipu": {
      "api_key": "Your API Key",
      "api_base": "https://www.tuptup.top"
    }
  }
}
```

**3. Lancer**

```bash
colearn agent -m "Hello"
```

</details>

<details>
<summary><b>Exemple de configuration complète</b></summary>

```json
{
  "agents": {
    "defaults": {
      "model_name": "anthropic/claude-opus-4-5"
    }
  },
  "session": {
    "dm_scope": "per-channel-peer"
  },
  "providers": {
    "openrouter": {
      "api_key": "sk-or-v1-xxx"
    },
    "groq": {
      "api_key": "gsk_xxx"
    }
  },
  "channel_list": {
    "telegram": {
      "enabled": true,
      "type": "telegram",
      "token": "123456:ABC...",
      "allow_from": ["123456789"]
    },
    "discord": {
      "enabled": true,
      "type": "discord",
      "token": "",
      "allow_from": [""]
    },
    "whatsapp": {
      "enabled": false,
      "type": "whatsapp",
      "bridge_url": "https://www.tuptup.top",
      "use_native": false,
      "session_store_path": "",
      "allow_from": []
    },
    "feishu": {
      "enabled": false,
      "type": "feishu",
      "app_id": "cli_xxx",
      "app_secret": "xxx",
      "encrypt_key": "",
      "verification_token": "",
      "allow_from": []
    },
    "qq": {
      "enabled": false,
      "type": "qq",
      "app_id": "",
      "app_secret": "",
      "allow_from": []
    }
  },
  "tools": {
    "web": {
      "brave": {
        "enabled": false,
        "api_key": "BSA...",
        "max_results": 5
      },
      "duckduckgo": {
        "enabled": true,
        "max_results": 5
      },
      "perplexity": {
        "enabled": false,
        "api_key": "",
        "max_results": 5
      },
      "searxng": {
        "enabled": false,
        "base_url": "https://www.tuptup.top",
        "max_results": 5
      }
    },
    "cron": {
      "exec_timeout_minutes": 5
    }
  },
  "heartbeat": {
    "enabled": true,
    "interval": 30
  }
}
```

</details>

---

## 📝 Comparaison des Clés API

| Service          | Pricing                  | Use Case                              |
| ---------------- | ------------------------ | ------------------------------------- |
| **OpenRouter**   | Free: 200K tokens/month  | Multiple models (Claude, GPT-4, etc.) |
| **Volcengine CodingPlan** | ¥9.9/first month | Best for Chinese users, multiple SOTA models (Doubao, DeepSeek, etc.) |
| **Zhipu**        | Free: 200K tokens/month  | Suitable for Chinese users                |
| **Brave Search** | $5/1000 queries          | Web search functionality              |
| **SearXNG**      | Free (self-hosted)       | Privacy-focused metasearch (70+ engines) |
| **Groq**         | Free tier available      | Fast inference (Llama, Mixtral)       |
| **Cerebras**     | Free tier available      | Fast inference (Llama, Qwen, etc.)    |
| **LongCat**      | Free: up to 5M tokens/day | Fast inference                       |
| **ModelScope**   | Free: 2000 requests/day  | Inference (Qwen, GLM, DeepSeek, etc.) |

---

<div align="center">
  <img src="../../assets/logo.jpg" alt="colearn Meme" width="512">
</div>
