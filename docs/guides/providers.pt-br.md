# 🔌 Provedores e Configuração de Modelos

> Voltar ao [README](../project/README.pt-br.md)

### Provedores

> [!NOTE]
> O Groq fornece transcrição de voz gratuita via Whisper. Se configurado, mensagens de áudio de qualquer canal serão automaticamente transcritas no nível do agente.

| Provider     | Purpose                                 | Get API Key                                                  |
| ------------ | --------------------------------------- | ------------------------------------------------------------ |
| `gemini`     | LLM (Gemini direct)                     | [www.tuptup.top](https://www.tuptup.top)           |
| `zhipu`      | LLM (Zhipu direct)                      | [bigmodel.cn](https://www.tuptup.top)                           |
| `volcengine` | LLM(Volcengine direct)                  | [volcengine.com](https://www.tuptup.top)                 |
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

### Configuração de Modelos (model_list)

> **Novidade?** O colearn agora usa uma abordagem de configuração **centrada no modelo**. Basta especificar o formato `vendor/model` (ex.: `zhipu/glm-4.7`) para adicionar novos provedores — **sem necessidade de alteração de código!**

Este design também permite **suporte multi-agente** com seleção flexível de provedores:

- **Agentes diferentes, provedores diferentes**: Cada agente pode usar seu próprio provedor LLM
- **Fallback de modelos**: Configure modelos primários e de fallback para resiliência
- **Balanceamento de carga**: Distribua requisições entre múltiplos endpoints
- **Configuração centralizada**: Gerencie todos os provedores em um só lugar

#### 📋 Todos os Vendors Suportados

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

#### Configuração Básica

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

#### Campos de entrada `model_list`

| Campo | Tipo | Obrigatório | Descrição |
|-------|------|-------------|-----------|
| `model_name` | string | Sim | Nome único para referenciar este modelo na config do agent |
| `model` | string | Sim | Identificador fornecedor/modelo (ex: `openai/gpt-5.4`, `azure/gpt-5.4`, `anthropic/claude-sonnet-4.6`) |
| `api_keys` | string[] | Sim* | Chave(s) API para autenticação. Múltiplas chaves permitem rotação por requisição. Não necessário para providers locais (Ollama, LM Studio, VLLM) |
| `api_base` | string | Não | Substitui a URL base da API padrão |
| `proxy` | string | Não | URL do proxy HTTP para esta entrada de modelo |
| `user_agent` | string | Não | Cabeçalho `User-Agent` personalizado enviado com requisições API (suportado por providers OpenAI-compatible, Gemini, Anthropic e Azure) |
| `request_timeout` | int | Não | Timeout de requisição em segundos (o padrão varia por provider) |
| `max_tokens_field` | string | Não | Substitui o nome do campo max tokens no corpo da requisição (ex: `max_completion_tokens` para modelos o1) |
| `thinking_level` | string | Não | Nível de pensamento estendido: `off`, `low`, `medium`, `high`, `xhigh` ou `adaptive` |
| `extra_body` | object | Não | Campos adicionais para injetar em cada corpo de requisição |
| `streaming.enabled` | bool | Não | Opt-in para provider streaming nesta entrada de modelo. O padrão é `false` e o canal ativo também precisa de `settings.streaming.enabled` como `true` |
| `rpm` | int | Não | Limite de requisições por minuto |
| `fallbacks` | string[] | Não | Nomes dos modelos de fallback para failover automático |
| `enabled` | bool | Não | Ativar ou desativar esta entrada de modelo (padrão: `true`) |

Quando streaming estiver desativado, omita o bloco `streaming`. Escrever `"streaming": {"enabled": false}` é opcional e não é necessário.

#### Exemplos por Vendor

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

**Anthropic (com chave de API)**

```json
{
  "model_name": "claude-sonnet-4.6",
  "model": "anthropic/claude-sonnet-4.6",
  "api_keys": ["sk-ant-your-key"]
}
```

> Execute `colearn auth login --provider anthropic` para colar seu token de API.

**Anthropic Messages API (formato nativo)**

Para acesso direto à API Anthropic ou endpoints personalizados que suportam apenas o formato de mensagem nativo da Anthropic:

```json
{
  "model_name": "claude-opus-4-6",
  "model": "anthropic-messages/claude-opus-4-6",
  "api_keys": ["sk-ant-your-key"],
  "api_base": "https://www.tuptup.top"
}
```

> Use o protocolo `anthropic-messages` quando:
> - Usar proxies de terceiros que suportam apenas o endpoint nativo `/v1/messages` da Anthropic (não o compatível com OpenAI `/v1/chat/completions`)
> - Conectar a serviços como MiniMax, Synthetic que requerem o formato de mensagem nativo da Anthropic
> - O protocolo `anthropic` existente retorna erros 404 (indicando que o endpoint não suporta formato compatível com OpenAI)
>
> **Nota:** O protocolo `anthropic` usa formato compatível com OpenAI (`/v1/chat/completions`), enquanto `anthropic-messages` usa o formato nativo da Anthropic (`/v1/messages`). Escolha com base no formato suportado pelo seu endpoint.

**Ollama (local)**

```json
{
  "model_name": "llama3",
  "model": "ollama/llama3"
}
```

**Proxy/API Personalizado**

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

O colearn remove apenas o prefixo externo `litellm/` antes de enviar a requisição, então aliases de proxy como `litellm/lite-gpt4` enviam `lite-gpt4`, enquanto `litellm/openai/gpt-4o` envia `openai/gpt-4o`.

#### Balanceamento de Carga

Configure múltiplos endpoints para o mesmo nome de modelo — o colearn fará automaticamente round-robin entre eles:

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

#### Migração da Configuração Legacy `providers`

A configuração antiga `providers` está **descontinuada** e foi removida no V2. Configs V0/V1 existentes são auto-migradas.

**Configuração Antiga (descontinuada):**

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

**Configuração Nova (recomendada):**

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

Para guia de migração detalhado, veja [migration/model-list-migration.md](../migration/model-list-migration.md).

### Arquitetura de Provedores

O colearn roteia provedores por família de protocolo:

- Protocolo compatível com OpenAI: OpenRouter, gateways compatíveis com OpenAI, Groq, Zhipu e endpoints estilo vLLM.
- Protocolo Gemini nativo: Google Gemini via endpoints nativos `models/*:generateContent` e `models/*:streamGenerateContent`.
- Protocolo Anthropic: Comportamento nativo da API Claude.
- Caminho Codex/OAuth: Rota de autenticação OAuth/token da OpenAI.

Isso mantém o runtime leve enquanto torna novos backends compatíveis com OpenAI basicamente uma operação de configuração (`api_base` + `api_keys`).

<details>
<summary><b>Zhipu</b></summary>

**1. Obter chave de API e URL base**

* Obtenha a [chave de API](https://www.tuptup.top)

**2. Configurar**

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

**3. Executar**

```bash
colearn agent -m "Hello"
```

</details>

<details>
<summary><b>Exemplo de configuração completa</b></summary>

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

## 📝 Comparação de Chaves de API

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
